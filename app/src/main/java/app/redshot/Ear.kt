package app.redshot

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
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
    private val onUtterance: (String, ShortArray, Int, Double) -> Unit,
    private val onState: (String) -> Unit,
    private val onHint: () -> Unit = {},
    private val onVoice: () -> Unit = {},
    private val onBarge: () -> Unit = {}
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
    fun stopLive() { liveSink = null; modelTalking = false; gateTailUntil = 0L }

    /** true while the model is talking: the mic is muted for the model unless the owner clearly talks over it. */
    @Volatile var modelTalking = false
    @Volatile var gateTailUntil = 0L
    @Volatile private var gateClosedSince = 0L
    private val liveGate: Boolean get() {
        val now = System.currentTimeMillis()
        val closed = modelTalking || now < gateTailUntil
        if (!closed) { gateClosedSince = 0L; return false }
        if (gateClosedSince == 0L) { gateClosedSince = now; barge.reset() }
        // safety: the mic can never stay shut for more than 30 s, whatever happens elsewhere
        if (now - gateClosedSince > 30_000L) { modelTalking = false; gateTailUntil = 0L; gateClosedSince = 0L; return false }
        return true
    }
    @Volatile private var bargeUntil = 0L
    private val barge = BargeDetector()
    /** how many enrollment sentences are still wanted (0 = not enrolling) */
    @Volatile var enrollWanted = 0
        set(v) { field = v; enrollFails = 0; enrollLastAt = System.currentTimeMillis() }
    @Volatile private var enrollFails = 0
    @Volatile private var enrollLastAt = 0L

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
        var spkModel: org.vosk.SpeakerModel? = null
        try {
            val sd = ModelStore.ensure(ctx, "model-spk")
            if (sd != null) spkModel = org.vosk.SpeakerModel(sd)
        } catch (e: Throwable) { spkModel = null }
        val rec = if (spkModel != null) Recognizer(model, SR.toFloat(), spkModel) else Recognizer(model, SR.toFloat())
        RedshotService.spkReady = spkModel != null

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
        var spkSum: DoubleArray? = null
        var spkFrames = 0
        val zero = ShortArray(FRAME)
        var lframes = 0
        var lquiet = 0
        var lchecked = false
        var lmute = false
        var hintTick = 0
        var hintAt = 0L
        var wasLive = false
        var callTick = 0
        var callPaused = false

        fun grab(json: String): String {
            try {
                val o = JSONObject(json)
                val v = Voiceprint.parseVec(o.optJSONArray("spk"))
                val fr = o.optInt("spk_frames", 0)
                if (v != null && fr > 0) {
                    var n = 0.0
                    for (x in v) n += x * x
                    n = sqrt(n)
                    if (n > 0) {
                        val sum = spkSum ?: DoubleArray(v.size).also { spkSum = it }
                        for (i in v.indices) sum[i] += v[i] / n * fr
                        spkFrames += fr
                    }
                }
                return o.optString("text", "").trim()
            } catch (e: Exception) { return "" }
        }

        fun feed(f: ShortArray) {
            if (rec.acceptWaveForm(f, f.size)) text.append(' ').append(grab(rec.result))
            recEn?.let { if (it.acceptWaveForm(f, f.size)) wakeEn.append(' ').append(parse(it.result)) }
            recHiWake?.let { if (it.acceptWaveForm(f, f.size)) wakeHi.append(' ').append(parse(it.result)) }
        }

        fun resetAll() {
            rec.reset(); recEn?.reset(); recHiWake?.reset()
            wakeEn.setLength(0); wakeHi.setLength(0)
            spkSum = null; spkFrames = 0
        }

        fun finish() {
            text.append(' ').append(grab(rec.finalResult))
            recEn?.let { wakeEn.append(' ').append(parse(it.finalResult)) }
            recHiWake?.let { wakeHi.append(' ').append(parse(it.finalResult)) }
            val en = wakeEn.toString().replace(" ", "").contains("redshot")
            val hi = wakeHi.toString().let { it.replace(" ", "").contains("रेडशॉट") }
            val level = (if (en) 1 else 0) + (if (hi) 1 else 0)
            // owner-voice distance for this sentence: -1 = nothing enrolled, -2 = too short to judge
            val emb: DoubleArray? = spkSum?.takeIf { spkFrames >= 60 }
            var dist = -1.0
            if (Voiceprint.enrolled) dist = if (emb != null) Voiceprint.dist(emb) else -2.0
            resetAll()
            val count = frames.size
            if (enrollWanted > 0) {
                RedshotService.spkFramesLast = spkFrames
                if (emb != null && spkFrames >= 60) {
                    Voiceprint.addEnroll(emb)
                    enrollWanted--
                    enrollFails = 0
                    enrollLastAt = System.currentTimeMillis()
                    RedshotService.enrollHave = Voiceprint.count
                    RedshotService.enrollMsg = ""
                } else if (count >= MIN_FRAMES) {
                    enrollFails++
                    RedshotService.enrollMsg = if (spkFrames == 0) "Awaaz ka fingerprint nahi ban raha (model se output nahi aaya)" else "Vakya chhota tha ya Hindi mein nahi bola, Hindi mein poora bol"
                    if (enrollFails >= 5) { enrollWanted = 0; RedshotService.enrollMsg = "Awaaz sikhna phone pe nahi chal paaya. Baaki sab normal chalega." }
                }
            } else if (count >= MIN_FRAMES) {
                val pcm = ShortArray(count * FRAME)
                for (i in 0 until count) System.arraycopy(frames[i], 0, pcm, i * FRAME, FRAME)
                RedshotService.spkDist = dist
                if (emb != null && dist >= 0) Voiceprint.adapt(emb, dist)
                onUtterance(text.toString().trim(), pcm, level, dist)
            }
            frames.clear()
            text.setLength(0)
            speaking = false
            loudRun = 0
            quietRun = 0
        }

        try {
            while (alive) {
              try {
                // a phone / WhatsApp / Meet call is running: let go of the mic completely so the other app owns it
                if (++callTick % 25 == 0) {
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val inCall = am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION || am.mode == AudioManager.MODE_CALL_SCREENING
                    if (inCall && !callPaused) { callPaused = true; try { audio.stop() } catch (e: Exception) {}; onState("call chal rahi hai, mic chhod diya") }
                    else if (!inCall && callPaused) { callPaused = false; try { audio.startRecording() } catch (e: Exception) {}; onState("sun raha hoon") }
                }
                if (callPaused) { Thread.sleep(150); callTick += 24; continue }
                val buf = ShortArray(FRAME)
                var got = 0
                while (got < FRAME && alive) {
                    val n = audio.read(buf, got, FRAME - got)
                    if (n <= 0) break
                    got += n
                }
                if (got < FRAME) { Thread.sleep(20); continue }

                if (enrollWanted > 0 && System.currentTimeMillis() - enrollLastAt > 75_000) {
                    enrollWanted = 0
                    RedshotService.enrollMsg = "Awaaz sikhna beech mein ruk gaya. Dobara shuru kar sakta hai."
                }
                val sink = liveSink
                if (sink != null) {
                    if (!wasLive) {
                        wasLive = true
                        resetAll(); frames.clear(); preroll.clear(); text.setLength(0)
                        speaking = false; loudRun = 0; quietRun = 0
                        lframes = 0; lquiet = 0; lchecked = false; lmute = false; barge.reset()
                    }
                    val r = rms(buf)
                    RedshotService.level = r.toInt()
                    val vthr = maxOf(floor * 3.0, 350.0)
                    val voiced = r > vthr
                    if (voiced) onVoice() else floor = (floor * 0.98 + r * 0.02).coerceIn(60.0, 1500.0)

                    // rolling check of whose voice it is (first ~1 s of every sentence)
                    if (Voiceprint.enrolled && !liveGate) {
                        if (voiced) { lframes++; lquiet = 0 } else lquiet++
                        if (lframes > 0) rec.acceptWaveForm(buf, buf.size)
                        if (!lchecked && lframes >= 50) {
                            lchecked = true
                            val fr = grab(rec.finalResult)
                            val emb = spkSum?.takeIf { spkFrames >= 60 }
                            if (emb != null) {
                                val d = Voiceprint.dist(emb)
                                RedshotService.spkDist = d
                                if (d >= 0 && !Voiceprint.accept(d, false)) lmute = true
                                else if (d >= 0) Voiceprint.adapt(emb, d)
                            }
                            spkSum = null; spkFrames = 0
                        }
                        if (lquiet > 35) { lframes = 0; lchecked = false; lmute = false; rec.reset(); spkSum = null; spkFrames = 0 }
                    }

                    var out = buf
                    if (liveGate && System.currentTimeMillis() > bargeUntil) {
                        // model is talking: mic stays closed unless the owner clearly talks over it
                        out = zero
                        if (barge.onFrame(r, System.currentTimeMillis() - gateClosedSince)) {
                            bargeUntil = System.currentTimeMillis() + 4000
                            onBarge()
                            out = buf
                        }
                    } else if (lmute) {
                        out = zero
                    }
                    sink(out)
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
                        if (part.replace(" ", "").contains("redshot")) { hintAt = System.currentTimeMillis(); onHint() }
                    }
                    quietRun = if (rms > threshold * 0.6) 0 else quietRun + 1
                    if (quietRun >= END_FRAMES || frames.size >= MAX_FRAMES) finish()
                }
              } catch (e: InterruptedException) {
                throw e
              } catch (t: Throwable) {
                // never let one bad frame kill the ears
                RedshotService.earError = t.javaClass.simpleName + ": " + (t.message ?: "")
                try { resetAll() } catch (x: Throwable) {}
                frames.clear(); preroll.clear(); text.setLength(0)
                speaking = false; loudRun = 0; quietRun = 0
                Thread.sleep(20)
              }
            }
        } catch (e: InterruptedException) {
            // stopping
        } finally {
            try { audio.stop() } catch (e: Exception) {}
            audio.release()
            rec.close()
            recEn?.close(); recHiWake?.close()
            try { spkModel?.close() } catch (e: Throwable) {}
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
