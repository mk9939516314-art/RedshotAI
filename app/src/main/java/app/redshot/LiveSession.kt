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
    fun cutPlayback() { dropUntil = System.currentTimeMillis() + 1500; player.flush() }
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
    private val lock = Any()
    @Volatile private var track: AudioTrack? = null
    @Volatile private var running = true
    @Volatile private var speaking = false
    @Volatile private var flushGen = 0
    private var written = 0L
    private var bufferMs = 450          // adaptive: grows after a choppy answer, shrinks slowly when clean
    private val thread = Thread { loop() }.also { it.name = "redshot-player"; it.start() }

    private companion object {
        const val BYTES_PER_MS = 48          // 24 kHz, 16-bit mono
        const val LEAD_IN_MS = 300           // silence first: wakes the speaker amplifier so the first syllables are not eaten
        const val END_GAP_MS = 400L          // this long without audio (and everything played) = answer finished
    }

    fun enqueue(b: ByteArray) { q.add(b) }

    /** Owner talked over the model (or the server interrupted it): silence right now. */
    fun flush() {
        flushGen++
        q.clear()
        synchronized(lock) {
            val t = track; track = null; written = 0
            try { t?.pause(); t?.flush(); t?.release() } catch (e: Exception) {}
        }
        if (speaking) { speaking = false; onSpeaking(false) }
    }

    fun release() {
        running = false
        q.clear()
        thread.interrupt()
        synchronized(lock) {
            val t = track; track = null
            try { t?.stop(); t?.release() } catch (e: Exception) {}
        }
        if (speaking) { speaking = false; onSpeaking(false) }
    }

    private fun createTrack(): AudioTrack {
        val rate = 24000
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, rate * 2 * 6))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try { t.setVolume(1.0f) } catch (e: Exception) {}
        try {
            // pin the output: loud speaker (or a connected headset), whatever the phone's call/route state is
            val c = RedshotService.instance
            val am = c?.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
            val outs = am?.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)?.toList() ?: emptyList()
            val pick = outs.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET || it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET }
                ?: outs.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (pick != null) t.preferredDevice = pick
        } catch (e: Exception) {}
        return t
    }

    private fun write(t: AudioTrack, b: ByteArray) {
        var off = 0
        val n = b.size - b.size % 2
        while (off < n && running) {
            val w = t.write(b, off, n - off)
            if (w <= 0) break
            off += w
        }
        written += off / 2
    }

    /** One answer = one track: lead-in silence + a cushion of audio, then a steady stream. Mid-answer gaps just continue (no re-buffering stalls). */
    private fun loop() {
        val pre = ArrayList<ByteArray>()
        var preBytes = 0
        var firstAt = 0L
        var lastChunkAt = 0L
        var playing = false
        var gen = flushGen
        var underrunsAtStart = 0
        while (running) {
            try {
                if (gen != flushGen) { gen = flushGen; pre.clear(); preBytes = 0; playing = false }
                val b = q.poll(30, TimeUnit.MILLISECONDS)
                val now = System.currentTimeMillis()
                if (b != null) {
                    lastChunkAt = now
                    if (!speaking) { speaking = true; onSpeaking(true) }
                    if (!playing) {
                        if (pre.isEmpty()) {
                            firstAt = now
                            // open the output right away and feed it silence while the cushion fills
                            synchronized(lock) {
                                val t = createTrack(); track = t; written = 0
                                t.play()
                                write(t, ByteArray(LEAD_IN_MS * BYTES_PER_MS))
                                underrunsAtStart = if (android.os.Build.VERSION.SDK_INT >= 24) t.underrunCount else 0
                            }
                        }
                        pre.add(b); preBytes += b.size
                    } else {
                        synchronized(lock) { track?.let { write(it, b) } }
                    }
                }
                if (!playing && pre.isNotEmpty()) {
                    val target = bufferMs * BYTES_PER_MS
                    val idle = now - lastChunkAt
                    // start: cushion full, or the whole (short) answer already arrived, or we waited long enough
                    if (preBytes >= target || (idle > 120 && b == null) || now - firstAt > bufferMs + 300L) {
                        synchronized(lock) { track?.let { t -> for (c in pre) write(t, c) } }
                        pre.clear(); preBytes = 0; playing = true
                    }
                }
                if (playing && b == null && now - lastChunkAt > END_GAP_MS) {
                    val t = track
                    if (t == null || t.playbackHeadPosition.toLong() >= written) {
                        // finished: learn from stutters, free the speaker
                        synchronized(lock) {
                            val tt = track
                            if (tt != null && android.os.Build.VERSION.SDK_INT >= 24) {
                                val choppy = tt.underrunCount - underrunsAtStart
                                bufferMs = if (choppy > 2) (bufferMs + 250).coerceAtMost(1500) else (bufferMs - 40).coerceAtLeast(350)
                            }
                            track = null; written = 0
                            try { tt?.stop(); tt?.release() } catch (e: Exception) {}
                        }
                        playing = false
                        if (speaking) { speaking = false; onSpeaking(false) }
                    }
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                pre.clear(); preBytes = 0; playing = false
            }
        }
    }
}
