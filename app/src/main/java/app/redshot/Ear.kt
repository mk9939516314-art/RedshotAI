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
 * Redshot's ears. Records the mic, ignores silence completely (light on battery and heat), and while someone
 * talks it keeps the raw audio (for the cloud brain) and runs the small offline model (for instant on-phone commands).
 * One call to onUtterance per spoken sentence: (offline text, raw 16 kHz audio).
 */
class Ear(
    private val ctx: Context,
    private val onUtterance: (String, ShortArray, Int) -> Unit,
    private val onState: (String) -> Unit,
    private val onHint: () -> Unit = {},
    private val onVoice: () -> Unit = {}
) {
    companion object {
        private const val SR = 16000
        private const val FRAME = 320          // 20 ms
        private const val PREROLL_FRAMES = 15  // 300 ms kept from before speech was detected
        private const val START_FRAMES = 2     // loud frames in a row to start
        private const val END_FRAMES = 17      // 340 ms of quiet ends the sentence
        private const val MIN_FRAMES = 14      // shorter than ~280 ms is a click, not speech
        private const val MAX_FRAMES = 750     // 15 s hard cap per sentence
    }

    @Volatile private var alive = false
    @Volatile private var paused = false
    @Volatile private var resumeAt = 0L
    /** When set, the ear is in a live conversation: raw 20 ms frames go straight to the sink. */
    @Volatile private var liveSink: ((ShortArray) -> Unit)? = null
    private var thread: Thread? = null

    val running: Boolean get() = thread?.isAlive == true

    fun start() {
        if (running) return
        alive = true
        thread = Thread { loop() }.also { it.name = "redshot-ear"; it.start() }
    }

    fun stop() {
        alive = false
        thread?.interrupt()
    }

    fun startLive(sink: (ShortArray) -> Unit) { liveSink = sink }
    fun stopLive() { liveSink = null }

    /** While Redshot itself is talking the ear stays shut (plus a short tail so it never hears its own echo). */
    fun setPaused(p: Boolean) {
        paused = p
        if (!p) resumeAt = System.currentTimeMillis() + 350
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        onState("model load ho raha hai")
        val dir = ModelStore.ensure(ctx)
        if (dir == null) { onState("speech model nahi mila"); return }
        val model = try { Model(dir) } catch (e: Throwable) { onState("speech model khula nahi"); return }
        val rec = Recognizer(model, SR.toFloat())

        // Wake-word spotters: tiny vocabulary => very accurate for just "Redshot" (English + Hindi spelling)
        var enModel: Model? = null
        var recEn: Recognizer? = null
        var recHiWake: Recognizer? = null
        try {
            recHiWake = Recognizer(model, SR.toFloat(), "[\"रेड शॉट\", \"रेडशॉट\", \"रेड\", \"शॉट\", \"[unk]\"]")
            val enDir = ModelStore.ensure(ctx, "model-en")
            if (enDir != null) {
                enModel = Model(enDir)
                recEn = Recognizer(enModel, SR.toFloat(), "[\"red shot\", \"redshot\", \"[unk]\"]")
            }
        } catch (e: Throwable) {
            // wake spotting stays partly off; free-text matching still works
        }

        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val audio = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, SR,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, FRAME * 2 * 8)
        )
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            onState("mic nahi khula (permission?)")
            audio.release()
            return
        }
        // phone-side echo cancellation + noise suppression so Redshot does not hear itself while talking
        try { if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) android.media.audiofx.AcousticEchoCanceler.create(audio.audioSessionId)?.enabled = true } catch (e: Throwable) {}
        try { if (android.media.audiofx.NoiseSuppressor.isAvailable()) android.media.audiofx.NoiseSuppressor.create(audio.audioSessionId)?.enabled = true } catch (e: Throwable) {}
        audio.startRecording()
        onState("sun raha hoon")

        val preroll = ArrayDeque<ShortArray>()
        val frames = ArrayList<ShortArray>()
        val text = StringBuilder()
        val wakeEn = StringBuilder()
        val wakeHi = StringBuilder()
        var floor = 150.0
        var speaking = false
        var loudRun = 0
        var quietRun = 0
        var hintTick = 0
        var hintAt = 0L
        var wasLive = false

        fun feed(f: ShortArray) {
            if (rec.acceptWaveForm(f, f.size)) text.append(' ').append(parse(rec.result))
            recEn?.let { if (it.acceptWaveForm(f, f.size)) wakeEn.append(' ').append(parse(it.result)) }
            recHiWake?.let { if (it.acceptWaveForm(f, f.size)) wakeHi.append(' ').append(parse(it.result)) }
        }

        fun resetAll() {
            rec.reset(); recEn?.reset(); recHiWake?.reset()
            wakeEn.setLength(0); wakeHi.setLength(0)
        }

        fun finish() {
            text.append(' ').append(parse(rec.finalResult))
            recEn?.let { wakeEn.append(' ').append(parse(it.finalResult)) }
            recHiWake?.let { wakeHi.append(' ').append(parse(it.finalResult)) }
            val en = wakeEn.toString().replace(" ", "").contains("redshot")
            val hi = wakeHi.toString().let { it.replace(" ", "").contains("रेडशॉट") }
            val level = (if (en) 1 else 0) + (if (hi) 1 else 0)
            resetAll()
            val count = frames.size
            if (count >= MIN_FRAMES) {
                val pcm = ShortArray(count * FRAME)
                for (i in 0 until count) System.arraycopy(frames[i], 0, pcm, i * FRAME, FRAME)
                onUtterance(text.toString().trim(), pcm, level)
            }
            frames.clear()
            text.setLength(0)
            speaking = false
            loudRun = 0
            quietRun = 0
        }

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

                val sink = liveSink
                if (sink != null) {
                    if (!wasLive) {
                        wasLive = true
                        resetAll(); frames.clear(); preroll.clear(); text.setLength(0)
                        speaking = false; loudRun = 0; quietRun = 0
                    }
                    val r = rms(buf)
                    RedshotService.level = r.toInt()
                    if (r > maxOf(floor * 3.0, 350.0)) onVoice() else floor = (floor * 0.98 + r * 0.02).coerceIn(60.0, 1500.0)
                    sink(buf)
                    continue
                }
                if (wasLive) { wasLive = false; resetAll() }

                if (paused || System.currentTimeMillis() < resumeAt) {
                    preroll.clear(); frames.clear(); text.setLength(0)
                    if (speaking) { resetAll(); speaking = false }
                    loudRun = 0; quietRun = 0
                    continue
                }

                val rms = rms(buf)
                val threshold = maxOf(floor * 2.5, 220.0)
                RedshotService.level = rms.toInt()
                RedshotService.threshold = threshold.toInt()
                val loud = rms > threshold

                if (!speaking) {
                    floor = (floor * 0.98 + rms * 0.02).coerceIn(60.0, 1500.0)
                    preroll.addLast(buf)
                    if (preroll.size > PREROLL_FRAMES) preroll.removeFirst()
                    loudRun = if (loud) loudRun + 1 else 0
                    if (loudRun >= START_FRAMES) {
                        speaking = true
                        quietRun = 0
                        for (f in preroll) { frames.add(f); feed(f) }
                        preroll.clear()
                    }
                } else {
                    frames.add(buf)
                    feed(buf)
                    // early hint: the spotter already sees "red"/"shot" -> let the service pre-open the live connection
                    if (++hintTick % 8 == 0 && recEn != null && System.currentTimeMillis() - hintAt > 4000) {
                        val part = try { JSONObject(recEn?.partialResult ?: "{}").optString("partial", "") } catch (e: Exception) { "" }
                        if (part.contains("red") || part.contains("shot")) { hintAt = System.currentTimeMillis(); onHint() }
                    }
                    quietRun = if (rms > threshold * 0.6) 0 else quietRun + 1
                    if (quietRun >= END_FRAMES || frames.size >= MAX_FRAMES) finish()
                }
            }
        } catch (e: InterruptedException) {
            // stopping
        } finally {
            try { audio.stop() } catch (e: Exception) {}
            audio.release()
            rec.close()
            recEn?.close(); recHiWake?.close()
            enModel?.close()
            model.close()
        }
    }

    private fun parse(json: String): String =
        try { JSONObject(json).optString("text", "").trim() } catch (e: Exception) { "" }

    private fun rms(b: ShortArray): Double {
        var s = 0.0
        for (v in b) s += v.toDouble() * v.toDouble()
        return sqrt(s / b.size)
    }
}
