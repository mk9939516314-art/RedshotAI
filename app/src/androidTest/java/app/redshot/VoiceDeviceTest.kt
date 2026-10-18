package app.redshot

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.sin

/** Runs on a real Android (emulator) with the real AudioTrack / AudioRecord - not mocks. */
class VoiceDeviceTest {
    @get:Rule val perm: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    /** 24 kHz mono 16-bit speech-like tone (loud, with a syllable envelope) */
    private fun speech(ms: Int, amp: Double = 8000.0): ByteArray {
        val n = 24 * ms
        val b = ByteArray(n * 2)
        for (i in 0 until n) {
            val env = 0.3 + 0.7 * Math.abs(sin(2 * PI * 4 * i / 24000.0))
            val v = (amp * env * sin(2 * PI * 180 * i / 24000.0)).toInt()
            b[i * 2] = (v and 0xFF).toByte(); b[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return b
    }

    private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(20) }
        return cond()
    }

    @Test fun realAudioTrackAcceptsAndPlaysSpeech() {
        val s = AndroidSink()
        try {
            s.start()
            val pcm = speech(1000)
            assertEquals("whole second accepted", pcm.size, s.write(pcm, 0, pcm.size))
            val ok = waitFor(4000) { s.headFrames() >= 20000 }
            assertTrue("speaker head advances (really playing) head=${s.headFrames()}/24000", ok)
        } finally { s.release() }
    }

    @Test fun fortySecondBufferIsCreatedAndAcceptsALongStoryAtOnce() {
        val s = AndroidSink()
        try {
            s.start()
            val story = speech(36_000)          // the 36 s Gemini story arrived in ~5 s: must not block
            val t = System.currentTimeMillis()
            var off = 0
            while (off < story.size) { val w = s.write(story, off, story.size - off); assertTrue("write returned $w", w > 0); off += w }
            assertTrue("write did not stall (${System.currentTimeMillis() - t} ms)", System.currentTimeMillis() - t < 3000)
            assertTrue(waitFor(4000) { s.headFrames() > 24000 })
            val c = System.currentTimeMillis()
            s.cut()
            assertTrue("cut is instant", System.currentTimeMillis() - c < 200)
        } finally { s.release() }
    }

    @Test fun playerEndToEndOnRealSpeakerPath() {
        val events = CopyOnWriteArrayList<Boolean>()
        val stats = CopyOnWriteArrayList<String>()
        val p = PcmPlayer({ AndroidSink() }, { events.add(it) }, { stats.add(it) })
        try {
            // a Gemini-like short answer: everything within ~130 ms
            val a = speech(1100)
            val parts = listOf(0.15, 0.2, 0.2, 0.2, 0.25)
            var o = 0
            for (f in parts) { val n = ((a.size * f).toInt() / 2) * 2; p.enqueue(a.copyOfRange(o, minOf(a.size, o + n))); o += n; Thread.sleep(25) }
            assertTrue("speaking true", waitFor(2000) { events.contains(true) })
            assertTrue("answer ends after it was played events=$events stats=$stats", waitFor(6000) { events.size >= 2 && !events.last() })
            assertEquals(listOf(true, false), events.toList())
            assertEquals(1, stats.size)
            assertTrue(stats[0], stats[0].startsWith("voice: 1"))
        } finally { p.release() }
    }

    @Test fun playbackKeepsRunningWhileTheMicRecordsAtTheSameTime() {
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400))
        assertEquals("mic opens", AudioRecord.STATE_INITIALIZED, rec.state)
        rec.startRecording()
        val s = AndroidSink()
        try {
            s.start()
            val pcm = speech(2000)
            s.write(pcm, 0, pcm.size)
            val buf = ShortArray(320)
            val end = System.currentTimeMillis() + 1500
            var reads = 0
            while (System.currentTimeMillis() < end) { val n = rec.read(buf, 0, buf.size); assertTrue("mic read $n", n >= 0); reads++ }
            assertTrue("mic delivered frames", reads > 10)
            assertTrue("speaker kept playing during recording head=${s.headFrames()}", s.headFrames() > 24000)
        } finally { s.release(); rec.stop(); rec.release() }
    }

    @Test fun flushThenNextAnswerStillPlaysOnRealTrack() {
        val events = CopyOnWriteArrayList<Boolean>()
        val p = PcmPlayer({ AndroidSink() }, { events.add(it) }, {})
        try {
            p.enqueue(speech(5000))
            assertTrue(waitFor(2000) { events.contains(true) })
            Thread.sleep(300)
            p.flush()
            assertEquals(listOf(true, false), events.toList())
            p.enqueue(speech(800))
            assertTrue("second answer plays and ends events=$events", waitFor(6000) { events.size == 4 })
            assertEquals(listOf(true, false, true, false), events.toList())
        } finally { p.release() }
    }
}
