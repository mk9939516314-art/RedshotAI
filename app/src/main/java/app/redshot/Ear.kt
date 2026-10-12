package app.redshot

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.math.sqrt

/**
 * Redshot's own ears. Records the mic, ignores silence completely (so it stays light on battery and heat),
 * and only runs the offline speech model while someone is actually talking. No Google, no beep, no internet.
 */
class Ear(
    private val ctx: Context,
    private val onText: (String) -> Unit,
    private val onState: (String) -> Unit
) {
    companion object {
        private const val SR = 16000
        private const val FRAME = 320          // 20 ms
        private const val PREROLL_FRAMES = 15  // 300 ms kept from before speech was detected
        private const val START_FRAMES = 2     // loud frames in a row to start
        private const val END_FRAMES = 30      // 600 ms of quiet ends the sentence
        private const val MAX_FRAMES = 750     // 15 s hard cap per sentence
    }

    @Volatile private var alive = false
    @Volatile var paused = false
    private var thread: Thread? = null

    fun start() {
        if (alive) return
        alive = true
        thread = Thread { loop() }.also { it.name = "redshot-ear"; it.start() }
    }

    fun stop() {
        alive = false
        thread?.interrupt()
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        onState("model load ho raha hai")
        val dir = ModelStore.ensure(ctx)
        if (dir == null) { onState("speech model nahi mila"); return }
        val model = try { Model(dir) } catch (e: Throwable) { onState("speech model khula nahi"); return }
        val rec = Recognizer(model, SR.toFloat())

        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val audio = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SR,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, FRAME * 2 * 8)
        )
        if (audio.state != AudioRecord.STATE_INITIALIZED) { onState("mic nahi khula"); return }
        audio.startRecording()
        onState("sun raha hoon")

        val preroll = ArrayDeque<ShortArray>()
        var floor = 150.0
        var speaking = false
        var loudRun = 0
        var quietRun = 0
        var frames = 0

        try {
            while (alive) {
                val buf = ShortArray(FRAME)
                var got = 0
                while (got < FRAME && alive) {
                    val n = audio.read(buf, got, FRAME - got)
                    if (n <= 0) break
                    got += n
                }
                if (got < FRAME) { Thread.sleep(20); continue }

                if (paused) {
                    // Redshot is talking; do not listen to itself.
                    preroll.clear(); speaking = false; loudRun = 0; quietRun = 0; frames = 0
                    rec.reset()
                    continue
                }

                val rms = rms(buf)
                val threshold = maxOf(floor * 3.0, 420.0)
                val loud = rms > threshold

                if (!speaking) {
                    floor = (floor * 0.98 + rms * 0.02).coerceIn(60.0, 1500.0)
                    preroll.addLast(buf)
                    if (preroll.size > PREROLL_FRAMES) preroll.removeFirst()
                    loudRun = if (loud) loudRun + 1 else 0
                    if (loudRun >= START_FRAMES) {
                        speaking = true
                        quietRun = 0
                        frames = 0
                        for (f in preroll) rec.acceptWaveForm(f, f.size)
                        preroll.clear()
                    }
                } else {
                    frames++
                    val endpoint = rec.acceptWaveForm(buf, buf.size)
                    quietRun = if (rms > threshold * 0.6) 0 else quietRun + 1
                    if (endpoint) emit(rec.result)
                    if (quietRun >= END_FRAMES || frames >= MAX_FRAMES) {
                        emit(rec.finalResult)
                        rec.reset()
                        speaking = false; loudRun = 0; quietRun = 0; frames = 0
                    }
                }
            }
        } catch (e: InterruptedException) {
            // stopping
        } finally {
            try { audio.stop() } catch (e: Exception) {}
            audio.release()
            rec.close()
            model.close()
        }
    }

    private fun emit(json: String) {
        val text = try { JSONObject(json).optString("text", "").trim() } catch (e: Exception) { "" }
        if (text.length >= 3) onText(text)
    }

    private fun rms(b: ShortArray): Double {
        var s = 0.0
        for (v in b) s += v.toDouble() * v.toDouble()
        return sqrt(s / b.size)
    }
}
