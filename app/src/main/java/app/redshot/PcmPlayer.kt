package app.redshot

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Where the speech goes. Real phones use [AndroidSink]; the tests use a fake one that "plays" in real time. */
interface PcmSink {
    fun start()
    /** blocking write of 16-bit mono 24 kHz PCM; returns bytes accepted */
    fun write(b: ByteArray, off: Int, len: Int): Int
    /** frames the speaker has really played so far */
    fun headFrames(): Long
    fun underruns(): Int
    fun cut()
    fun release()
}

/**
 * Plays the model's speech.
 *
 * Measured facts this is built on (real Gemini Live session): audio arrives FASTER than real time in bursts
 * (36 s of speech in ~5 s, a short "haan bol bhai" fully within 130 ms), so a long cushion is not needed;
 * what ruins it is (1) the speaker amplifier waking up late and eating the first syllables, (2) cutting
 * playback by mistake, (3) re-buffering in the middle of a sentence.
 *
 * So: per answer one output, 200 ms of silence first (wakes the amp), start after ~150 ms of speech,
 * then just stream. Never pause or re-buffer mid answer. The answer ends only when everything was played
 * and nothing new came for a while. flush() = silence instantly.
 */
class PcmPlayer(
    private val newSink: () -> PcmSink,
    private val onSpeaking: (Boolean) -> Unit,
    private val onStats: (String) -> Unit = {},
) {
    companion object {
        const val BYTES_PER_MS = 48
        const val LEAD_IN_MS = 200
        const val START_AFTER_MS = 150
        const val START_MAX_WAIT_MS = 250L
        const val END_GAP_MS = 350L
    }

    private class Chunk(val gen: Int, val data: ByteArray)

    private val q = LinkedBlockingQueue<Chunk>()
    private val lock = Any()
    @Volatile private var sink: PcmSink? = null
    @Volatile private var running = true
    @Volatile private var speaking = false
    @Volatile private var gen = 0
    private val thread = Thread { loop() }.also { it.name = "redshot-player"; it.isDaemon = true; it.start() }

    fun enqueue(b: ByteArray) { if (b.isNotEmpty()) q.add(Chunk(gen, b)) }

    /** Silence right now (owner talked over it). Safe from any thread. */
    fun flush() {
        gen++
        q.clear()
        val s: PcmSink?
        synchronized(lock) { s = sink; sink = null }
        try { s?.cut() } catch (e: Throwable) {}
        try { s?.release() } catch (e: Throwable) {}
        setSpeaking(false)
    }

    fun release() {
        running = false
        flush()
        thread.interrupt()
    }

    private fun setSpeaking(v: Boolean) {
        val changed: Boolean
        synchronized(lock) { changed = speaking != v; speaking = v }
        if (changed) try { onSpeaking(v) } catch (e: Throwable) {}
    }

    private fun loop() {
        val pre = ArrayList<ByteArray>()
        var preBytes = 0
        var started = false
        var firstAt = 0L
        var lastChunkAt = 0L
        var written = 0L          // frames handed to the sink (silence + speech)
        var speechBytes = 0L
        var chunks = 0
        var myGen = gen
        var local: PcmSink? = null
        var lastHead = -1L
        var headMovedAt = 0L

        fun reset() { pre.clear(); preBytes = 0; started = false; written = 0; speechBytes = 0; chunks = 0; local = null; lastHead = -1L; headMovedAt = 0L }

        fun put(s: PcmSink, b: ByteArray) {
            var off = 0
            val n = b.size - b.size % 2
            while (off < n) {
                val w = s.write(b, off, n - off)
                if (w <= 0) break
                off += w
            }
            written += off / 2
        }

        while (running) {
            try {
                if (myGen != gen) { myGen = gen; reset() }
                val c = q.poll(20, TimeUnit.MILLISECONDS)
                val now = System.currentTimeMillis()
                if (c != null && c.gen == myGen) {
                    lastChunkAt = now
                    chunks++
                    speechBytes += c.data.size
                    if (local == null) {
                        val s = newSink()
                        synchronized(lock) { if (myGen != gen) { s.release(); null } else { sink = s; s } }?.let { ns ->
                            local = ns
                            ns.start()
                            put(ns, ByteArray(LEAD_IN_MS * BYTES_PER_MS))
                            firstAt = now
                            setSpeaking(true)
                        }
                    }
                    val s = local
                    if (s != null) {
                        if (!started) { pre.add(c.data); preBytes += c.data.size } else put(s, c.data)
                    }
                }
                val s = local ?: continue
                if (!started && pre.isNotEmpty() && (preBytes >= START_AFTER_MS * BYTES_PER_MS || now - firstAt >= START_MAX_WAIT_MS || (c == null && now - lastChunkAt > 60))) {
                    for (p in pre) put(s, p)
                    pre.clear(); preBytes = 0; started = true
                }
                if (started) {
                    val h = s.headFrames()
                    if (h != lastHead) { lastHead = h; headMovedAt = now }
                }
                // finished = nothing new for a while AND (everything played OR the speaker's position stopped moving:
                // some phones never report the last few frames, which must not keep the mic muted)
                if (started && c == null && now - lastChunkAt > END_GAP_MS && (s.headFrames() >= written || now - headMovedAt > 700)) {
                    // answer finished and fully played
                    val u = s.underruns()
                    synchronized(lock) { if (sink === s) sink = null }
                    try { s.release() } catch (e: Throwable) {}
                    onStats("voice: ${speechBytes / BYTES_PER_MS}ms audio, $chunks chunks, underruns=$u")
                    reset()
                    setSpeaking(false)
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: Throwable) {
                // a sink that died under us (flush released it): next chunk opens a new one
                val dead = local
                reset()
                try { dead?.release() } catch (x: Throwable) {}
                synchronized(lock) { if (sink === dead) sink = null }
                if (speaking && gen == myGen) setSpeaking(false)
            }
        }
    }
}
