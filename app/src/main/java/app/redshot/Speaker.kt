package app.redshot

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Redshot's voice. Premium cloud voice streamed from the brain (starts talking within a fraction of a second);
 * if that is not available (offline / no key yet) it falls back to the best voice installed on the phone.
 */
class Speaker(private val ctx: Context, private val onBusy: (Boolean) -> Unit) {

    @Volatile var engine: String = "-"
    @Volatile private var cloudOk = true
    @Volatile private var cloudOffAt = 0L
    @Volatile private var ready = false
    @Volatile private var latch: CountDownLatch? = null

    private var tts: TextToSpeech? = null
    private val pool = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger(0)

    init {
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setSpeechRate(1.05f)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { latch?.countDown() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { latch?.countDown() }
                })
                ready = true
            }
        }
    }

    fun say(text: String, lang: String) {
        if (text.isBlank()) return
        if (!cloudOk && System.currentTimeMillis() - cloudOffAt > 120_000) cloudOk = true
        pending.incrementAndGet()
        onBusy(true)
        pool.execute {
            try {
                val played = cloudOk && cloud(text, lang)
                if (!played) system(text, lang)
            } catch (e: Exception) {
                // never let a voice problem stop the assistant
            } finally {
                if (pending.decrementAndGet() == 0) onBusy(false)
            }
        }
    }

    fun shutdown() {
        tts?.shutdown()
        pool.shutdownNow()
    }

    // ---------- premium cloud voice ----------

    private fun cloud(text: String, lang: String): Boolean {
        var track: AudioTrack? = null
        var written = 0L
        try {
            val c = URL(Config.brainUrl(ctx).trimEnd('/') + "/_api/device/speak").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 6000
            c.readTimeout = 15000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("X-Device-Id", Config.deviceId(ctx))
            c.setRequestProperty("X-Device-Secret", Config.deviceSecret(ctx))
            c.setRequestProperty("X-Device-Name", Config.deviceName())
            c.outputStream.use { it.write(JSONObject().put("text", text).put("lang", lang).toString().toByteArray(Charsets.UTF_8)) }

            val code = c.responseCode
            if (code == 501) { cloudOk = false; cloudOffAt = System.currentTimeMillis(); return false }
            if (code !in 200..299) return false

            val rate = 24000
            val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, rate))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()
            engine = "premium"

            val input = c.inputStream
            val buf = ByteArray(4800)
            val out = ByteArray(4801)
            var leftover = -1
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                var len = 0
                if (leftover >= 0) { out[0] = leftover.toByte(); len = 1; leftover = -1 }
                System.arraycopy(buf, 0, out, len, n)
                len += n
                if (len % 2 == 1) { leftover = out[len - 1].toInt() and 0xFF; len -= 1 }
                if (len > 0) { track.write(out, 0, len); written += len / 2 }
            }
            track.stop()
            val deadline = System.currentTimeMillis() + 25_000
            while (track.playbackHeadPosition < written && System.currentTimeMillis() < deadline) Thread.sleep(15)
            return written > 0
        } catch (e: Exception) {
            return written > 0
        } finally {
            try { track?.release() } catch (e: Exception) {}
        }
    }

    // ---------- fallback: best voice installed on the phone ----------

    private fun system(text: String, lang: String) {
        val t = tts ?: return
        if (!ready) return
        pickVoice(t, lang)
        engine = "phone:" + (t.voice?.name ?: "default")
        val l = CountDownLatch(1)
        latch = l
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime())
        l.await(3000L + text.length * 120L, TimeUnit.MILLISECONDS)
    }

    private fun pickVoice(t: TextToSpeech, lang: String) {
        val loc = Locale.forLanguageTag(lang)
        val best = try {
            t.voices
                ?.filter { it.locale.language == loc.language && !it.features.contains("notInstalled") }
                ?.maxByOrNull { it.quality * 10 + (if (it.locale.country == loc.country) 5 else 0) }
        } catch (e: Exception) { null }
        if (best != null) t.voice = best else t.language = loc
    }
}
