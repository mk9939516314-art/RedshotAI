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
    private val player = PcmPlayer { speaking -> host.onModelSpeaking(speaking) }

    val isClosed: Boolean get() = closed.get()

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

    /** Owner talked over the model: cut its voice right now. */
    fun cutPlayback() { player.flush() }

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
                    player.enqueue(Base64.decode(data, Base64.DEFAULT))
                    host.onModelAudio()
                }
            }
            if (sc.optBoolean("interrupted")) player.flush()
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

/** Plays the model's 24 kHz speech as it streams in; can be cut instantly when the user interrupts. */
class PcmPlayer(private val onSpeaking: (Boolean) -> Unit) {
    private val q = LinkedBlockingQueue<ByteArray>()
    @Volatile private var track: AudioTrack? = null
    @Volatile private var running = true
    @Volatile private var speaking = false
    @Volatile private var written = 0L
    private val thread = Thread { loop() }.also { it.name = "redshot-player"; it.start() }

    fun enqueue(b: ByteArray) { q.add(b) }

    fun flush() {
        q.clear()
        val t = track
        track = null
        written = 0
        try { t?.pause(); t?.flush(); t?.release() } catch (e: Exception) {}
        if (speaking) { speaking = false; onSpeaking(false) }
    }

    fun release() {
        running = false
        q.clear()
        thread.interrupt()
        val t = track
        track = null
        try { t?.stop(); t?.release() } catch (e: Exception) {}
        if (speaking) { speaking = false; onSpeaking(false) }
    }

    private fun ensureTrack(): AudioTrack {
        track?.let { return it }
        val rate = 24000
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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
            .setBufferSizeInBytes(maxOf(min, rate * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        t.play()
        track = t
        written = 0
        return t
    }

    private fun loop() {
        val pre = ArrayList<ByteArray>()
        var preBytes = 0
        var firstAt = 0L
        while (running) {
            try {
                val b = q.poll(150, TimeUnit.MILLISECONDS)
                if (b == null) {
                    // short reply that never reached the prebuffer size: play what we have
                    if (pre.isNotEmpty() && System.currentTimeMillis() - firstAt > 220) {
                        playChunks(pre); pre.clear(); preBytes = 0
                    }
                    val t = track
                    if (speaking && t != null && pre.isEmpty() && t.playbackHeadPosition >= written) {
                        speaking = false
                        onSpeaking(false)
                    }
                    continue
                }
                if (!speaking) { speaking = true; onSpeaking(true) }
                val t0 = track
                if (t0 == null || t0.playbackHeadPosition >= written) {
                    // (re)starting after silence: build a small cushion first so network jitter never makes it stutter
                    if (pre.isEmpty()) firstAt = System.currentTimeMillis()
                    pre.add(b); preBytes += b.size
                    if (preBytes >= 14400 || System.currentTimeMillis() - firstAt > 220) {
                        playChunks(pre); pre.clear(); preBytes = 0
                    }
                } else {
                    if (pre.isNotEmpty()) { playChunks(pre); pre.clear(); preBytes = 0 }
                    playChunks(listOf(b))
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                // track was released by flush(); next chunk makes a new one
                pre.clear(); preBytes = 0
            }
        }
    }

    private fun playChunks(list: List<ByteArray>) {
        val t = ensureTrack()
        for (b in list) {
            t.write(b, 0, b.size - b.size % 2)
            written += b.size / 2
        }
    }
}
