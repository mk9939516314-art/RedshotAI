package app.redshot

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One live voice session with Gemini Live (speech in -> speech out, streamed both ways, barge-in, tools).
 * Same architecture ChatGPT / Gemini voice use: no separate STT/TTS, audio flows continuously over a WebSocket.
 */
class LiveSession(private val host: Host) {

    interface Host {
        fun onReady()
        fun onModelAudio()
        fun onModelSpeaking(speaking: Boolean)
        fun onAudioStats(s: String) {}
        fun onTurnComplete()
        fun onUserText(t: String)
        fun onModelText(t: String)
        fun onClosed(reason: String, wasReady: Boolean)
        /** Runs a tool the model asked for; returns the text result (blocking is fine, runs on a worker). */
        fun onTool(name: String, args: JSONObject): String
    }

    companion object {
        // shared so TLS/DNS are reused: the next session connects faster
        val client: OkHttpClient = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(6, TimeUnit.SECONDS)
            .build()
        private const val CHUNK = 1600 // 100 ms of 16 kHz audio
        private const val URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key="
    }

    @Volatile var ready = false
    private val closed = AtomicBoolean(false)
    private var ws: WebSocket? = null
    private val pending = ConcurrentLinkedQueue<String>()
    private val acc = ShortArray(CHUNK)
    private var accPos = 0
    private val tools = Executors.newCachedThreadPool()
    private val player = PcmPlayer({ AndroidSink() }, { speaking -> host.onModelSpeaking(speaking) }, { host.onAudioStats(it) })

    val isClosed: Boolean get() = closed.get()
    fun echoLevel(): Double = player.echoLevel()
    fun echoResidual(mic: ShortArray): Double = player.echoResidual(mic)
    fun aecErleDb(): Double = player.aecErleDb

    fun open(key: String, setup: JSONObject) {
        val setupText = setup.toString()
        ws = client.newWebSocket(Request.Builder().url(URL + key).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(setupText) }
            override fun onMessage(webSocket: WebSocket, text: String) { handle(text) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { handle(bytes.utf8()) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { finish("closed $code $reason") }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                finish("fail " + (t.message ?: t.javaClass.simpleName))
            }
        })
    }

    /** Text from the phone itself (e.g. "tell the owner: class starts in 10 minutes"): the model says it out loud in its own voice. */
    fun sendText(t: String) {
        val msg = JSONObject().put("realtimeInput", JSONObject().put("text", t)).toString()
        if (ready) ws?.send(msg) else pending.add(msg)
    }

    /** One screenshot/camera frame so the model can actually SEE (games, canvas apps, images). */
    fun sendImage(jpeg: ByteArray) {
        val b64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
        val msg = JSONObject().put("realtimeInput", JSONObject().put("video", JSONObject().put("mimeType", "image/jpeg").put("data", b64))).toString()
        if (ready) ws?.send(msg) else pending.add(msg)
    }

    /** Owner talked over the model: cut its voice right now. */
    fun cutPlayback() { dropUntil = System.currentTimeMillis() + 10_000; player.flush() }
    /** after the owner cut in, the rest of that answer is thrown away until the server says it is done */
    @Volatile private var dropUntil = 0L

    fun close() {
        try { ws?.close(1000, "bye") } catch (e: Exception) {}
        finish("closed by phone")
    }

    private fun finish(reason: String) {
        if (closed.compareAndSet(false, true)) {
            player.release()
            tools.shutdown()
            host.onClosed(reason, ready)
        }
    }

    // ---------- microphone -> model ----------

    /** A burst of already recorded audio (e.g. the sentence that contained "Redshot"), plus trailing silence. */
    fun sendBurst(pcm: ShortArray, trailingSilenceMs: Int = 400) {
        var i = 0
        while (i < pcm.size) {
            val n = minOf(CHUNK, pcm.size - i)
            send(pcm, i, n)
            i += n
        }
        val sil = ShortArray(16 * trailingSilenceMs)
        i = 0
        while (i < sil.size) {
            val n = minOf(CHUNK, sil.size - i)
            send(sil, i, n)
            i += n
        }
    }

    /** Live microphone frames (20 ms each); batched into 100 ms messages. */
    fun feed(frame: ShortArray) {
        var o = 0
        while (o < frame.size) {
            val n = minOf(CHUNK - accPos, frame.size - o)
            System.arraycopy(frame, o, acc, accPos, n)
            accPos += n
            o += n
            if (accPos == CHUNK) { send(acc, 0, CHUNK); accPos = 0 }
        }
    }

    private fun send(pcm: ShortArray, off: Int, len: Int) {
        val bb = ByteBuffer.allocate(len * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (k in off until off + len) bb.putShort(pcm[k])
        val b64 = Base64.encodeToString(bb.array(), Base64.NO_WRAP)
        val msg = "{\"realtimeInput\":{\"audio\":{\"data\":\"$b64\",\"mimeType\":\"audio/pcm;rate=16000\"}}}"
        if (ready) ws?.send(msg) else if (pending.size < 600) pending.add(msg)
    }

    // ---------- model -> phone ----------

    private fun handle(text: String) {
        val m = try { JSONObject(text) } catch (e: Exception) { return }

        if (m.has("setupComplete")) {
            ready = true
            while (true) { val p = pending.poll() ?: break; ws?.send(p) }
            host.onReady()
        }

        m.optJSONObject("serverContent")?.let { sc ->
            sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    val data = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data") ?: continue
                    if (data.isEmpty()) continue
                    if (System.currentTimeMillis() < dropUntil) continue   // tail of an answer the owner already cut off
                    player.enqueue(Base64.decode(data, Base64.DEFAULT))
                    host.onModelAudio()
                }
            }
            if (sc.optBoolean("interrupted")) { dropUntil = 0L; player.flush() }
            if (sc.optBoolean("turnComplete") || sc.optBoolean("generationComplete")) dropUntil = 0L
            sc.optJSONObject("inputTranscription")?.optString("text")?.let { if (it.isNotEmpty()) host.onUserText(it) }
            sc.optJSONObject("outputTranscription")?.optString("text")?.let { if (it.isNotEmpty()) host.onModelText(it) }
            if (sc.optBoolean("turnComplete")) host.onTurnComplete()
        }

        if (m.has("goAway")) { try { ws?.close(1000, "goAway") } catch (e: Exception) {}; finish("server goAway") }

        m.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls -> runTools(calls) }
    }

    private fun runTools(calls: JSONArray) {
        try {
            tools.execute {
                val responses = JSONArray()
                for (i in 0 until calls.length()) {
                    val c = calls.optJSONObject(i) ?: continue
                    val name = c.optString("name")
                    val result = try {
                        host.onTool(name, c.optJSONObject("args") ?: JSONObject())
                    } catch (e: Exception) {
                        "error: " + (e.message ?: e.javaClass.simpleName)
                    }
                    responses.put(
                        JSONObject().put("name", name).put("id", c.optString("id"))
                            .put("response", JSONObject().put("result", result))
                    )
                }
                ws?.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses)).toString())
            }
        } catch (e: Exception) { /* session already closed */ }
    }
}

/** The phone's speaker. Plain media output, default routing (goes where reels / YouTube audio goes). */
class AndroidSink : PcmSink {
    private val rate = 24000
    private val track: AudioTrack
    init {
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            // 40 s: Gemini sends speech much faster than real time, a write must never block for long
            .setBufferSizeInBytes(maxOf(min, rate * 2 * 40))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try { track.setVolume(1.0f) } catch (e: Exception) {}
        // CRUCIAL: by default a streaming track does not start playing until its WHOLE buffer is full. With a big buffer a short
        // answer (1-2 s) would never be played at all. Start as soon as ~20 ms of audio is there.
        if (android.os.Build.VERSION.SDK_INT >= 31) try { track.startThresholdInFrames = 480 } catch (e: Exception) {}
    }
    override fun start() { track.play() }
    override fun write(b: ByteArray, off: Int, len: Int): Int = track.write(b, off, len)
    override fun headFrames(): Long = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
    override fun underruns(): Int = if (android.os.Build.VERSION.SDK_INT >= 24) track.underrunCount else 0
    override fun cut() { try { track.pause(); track.flush() } catch (e: Exception) {} }
    override fun release() { try { track.stop() } catch (e: Exception) {}; try { track.release() } catch (e: Exception) {} }
}
