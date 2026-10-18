package app.redshot

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** A speaker that "plays" in real time: 24 frames per millisecond from the moment start() is called. */
class FakeSink : PcmSink {
    @Volatile var startedAt = 0L
    @Volatile var framesIn = 0L
    @Volatile var cutAt = 0L
    @Volatile var released = false
    @Volatile var failNextWrite = false
    var speechBytes = 0L
    var firstSpeechAt = 0L
    val order = CopyOnWriteArrayList<Int>()
    override fun start() { startedAt = System.currentTimeMillis() }
    override fun write(b: ByteArray, off: Int, len: Int): Int {
        if (failNextWrite) { failNextWrite = false; throw IllegalStateException("dead track") }
        if (cutAt != 0L) return -1
        // the first 200 ms of silence is the lead-in (all zero)
        if (b.any { it.toInt() != 0 }) { if (firstSpeechAt == 0L) firstSpeechAt = System.currentTimeMillis(); speechBytes += len; order.add(b[0].toInt()) }
        framesIn += len / 2
        return len
    }
    override fun headFrames(): Long {
        if (startedAt == 0L) return 0
        val played = (System.currentTimeMillis() - startedAt) * 24
        return minOf(played, framesIn)
    }
    override fun underruns() = 0
    override fun cut() { cutAt = System.currentTimeMillis() }
    override fun release() { released = true }
}

class PcmPlayerTest {
    private fun chunk(n: Int, tag: Int = 1): ByteArray = ByteArray(n) { tag.toByte() }

    private class Rig {
        val sinks = CopyOnWriteArrayList<FakeSink>()
        val speak = CopyOnWriteArrayList<Pair<Long, Boolean>>()
        val stats = CopyOnWriteArrayList<String>()
        val player = PcmPlayer({ FakeSink().also { sinks.add(it) } }, { speak.add(System.currentTimeMillis() to it) }, { stats.add(it) })
        fun trues() = speak.count { it.second }
        fun falses() = speak.count { !it.second }
    }

    private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(10) }
        return cond()
    }

    /** the real arrival pattern of a short Gemini answer ("haan bol bhai"): everything within ~130 ms */
    @Test fun shortAnswerPlaysWholeAndStartsQuickly() {
        val r = Rig()
        val t0 = System.currentTimeMillis()
        val plan = listOf(0L to 2, 1L to 7680, 4L to 9600, 36L to 9600, 69L to 9600, 127L to 11520, 128L to 7680)
        for ((at, n) in plan) { val w = at - (System.currentTimeMillis() - t0); if (w > 0) Thread.sleep(w); r.player.enqueue(chunk(n)) }
        val total = plan.sumOf { it.second }
        assertTrue("answer must end", waitFor(4000) { r.falses() == 1 })
        assertEquals(1, r.trues())
        val s = r.sinks.single()
        // 2-byte chunk is odd-sized: the player may drop the half sample, nothing else
        assertTrue("all speech reached the speaker (${s.speechBytes}/$total)", s.speechBytes >= total - 2 - 2)
        assertEquals("never cut", 0L, s.cutAt)
        assertTrue("started within 400 ms of the first chunk", s.firstSpeechAt - t0 < 400)
        assertTrue("sink released at the end", s.released)
        assertTrue(r.stats.single().startsWith("voice:"))
    }

    /** a long story: 40 chunks arriving in bursts, much faster than real time, with one 500 ms hiccup */
    @Test fun longAnswerPlaysEverythingInOrderWithoutStopping() {
        val r = Rig()
        var total = 0L
        for (i in 0 until 40) {
            val n = 4800 + (i % 5) * 2400
            total += n
            r.player.enqueue(chunk(n, (i % 100) + 1))
            Thread.sleep(if (i == 20) 500 else 15)
        }
        assertTrue("ends", waitFor(15000) { r.falses() >= 1 })
        assertTrue(waitFor(1000) { r.sinks.sumOf { it.speechBytes } >= total })
        assertEquals("every byte played", total, r.sinks.sumOf { it.speechBytes })
        assertTrue("no cut", r.sinks.all { it.cutAt == 0L })
        // chunks are played in the order they arrived
        val tags = r.sinks.flatMap { it.order }
        assertEquals(tags, tags.sorted().let { tags }) // sanity: list exists
        val expected = (0 until 40).map { (it % 100) + 1 }
        assertEquals(expected, tags)
    }

    @Test fun flushSilencesAtOnceAndNextAnswerStillPlays() {
        val r = Rig()
        for (i in 0 until 30) r.player.enqueue(chunk(9600))
        assertTrue(waitFor(1500) { r.sinks.firstOrNull()?.speechBytes ?: 0 > 0 })
        val t = System.currentTimeMillis()
        r.player.flush()
        val s = r.sinks.first()
        assertTrue("cut immediately", s.cutAt != 0L && s.cutAt - t < 100)
        assertEquals("speaking false", 1, r.falses())
        val before = s.speechBytes
        Thread.sleep(300)
        assertEquals("nothing more written after the cut", before, s.speechBytes)
        // next answer
        r.player.enqueue(chunk(24000, 7)); r.player.enqueue(chunk(24000, 7))
        assertTrue(waitFor(4000) { r.sinks.size == 2 && r.falses() == 2 })
        assertEquals(48000L, r.sinks[1].speechBytes)
        assertEquals(0L, r.sinks[1].cutAt)
    }

    /** the first chunk right after a flush must never be lost (player thread is waiting in poll when flush happens) */
    @Test fun chunkRightAfterFlushIsNotLost() {
        repeat(20) {
            val r = Rig()
            r.player.enqueue(chunk(9600)); Thread.sleep(40)
            r.player.flush()
            r.player.enqueue(chunk(9600, 9))
            assertTrue("answer after flush plays (round $it)", waitFor(3000) { r.sinks.size >= 2 && r.sinks.last().speechBytes >= 9600 })
            r.player.release()
        }
    }

    @Test fun flushWhileNothingPlayingIsHarmless() {
        val r = Rig()
        r.player.flush(); r.player.flush()
        r.player.enqueue(chunk(4800))
        assertTrue(waitFor(3000) { r.falses() == 1 })
        assertEquals(1, r.trues())
    }

    @Test fun deadSinkDoesNotKillThePlayer() {
        val r = Rig()
        val failing = AtomicInteger(0)
        val p = PcmPlayer({ FakeSink().also { if (failing.getAndIncrement() == 0) it.failNextWrite = true; r.sinks.add(it) } }, { r.speak.add(0L to it) }, {})
        p.enqueue(chunk(9600))
        Thread.sleep(500)
        p.enqueue(chunk(9600, 3)); p.enqueue(chunk(9600, 3))
        assertTrue("second answer is played", waitFor(4000) { r.sinks.size >= 2 && r.sinks.last().speechBytes >= 19200 })
        p.release()
    }

    @Test fun releaseStopsEverything() {
        val r = Rig()
        r.player.enqueue(chunk(48000))
        Thread.sleep(100)
        r.player.release()
        assertTrue(r.sinks.first().released)
        assertEquals(r.trues(), r.falses())
    }
}
