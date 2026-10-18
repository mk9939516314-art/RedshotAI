package app.redshot

import org.junit.Assert.*
import org.junit.Test

class BargeDetectorTest {
    private fun run(d: BargeDetector, levelAt: (Long) -> Double, fromMs: Long, toMs: Long): Long? {
        var t = fromMs
        while (t < toMs) { if (d.onFrame(levelAt(t), t)) return t; t += 20 }
        return null
    }

    @Test fun loudSpeakerEchoNeverCutsItself() {
        // echo of its own voice at RMS 1500..6000 with natural wobble: must never count as the owner
        for (base in listOf(1500.0, 3000.0, 5000.0, 6000.0)) {
            val d = BargeDetector()
            val hit = run(d, { t -> base * (0.8 + 0.4 * Math.abs(Math.sin(t / 90.0))) * 1.0 }, 0, 12_000)
            assertNull("echo at $base must not barge", hit)
        }
    }

    @Test fun firstMomentsNeverBarge() {
        val d = BargeDetector()
        assertNull(run(d, { 20000.0 }, 0, BargeDetector.LEARN_MS))
    }

    @Test fun ownerMuchLouderThanEchoCutsIn() {
        val d = BargeDetector()
        // echo ~2000 during the first second, then owner at 9000 from 2 s
        val hit = run(d, { t -> if (t < 2000) 2000.0 else 9000.0 }, 0, 6000)
        assertNotNull(hit)
        assertTrue("reacts within ~0.3 s of speaking", hit!! in 2000..2400)
    }

    @Test fun shortLoudBlipDoesNotBarge() {
        val d = BargeDetector()
        val hit = run(d, { t -> if (t in 3000..3200) 9000.0 else 1800.0 }, 0, 6000)   // 200 ms click
        assertNull(hit)
    }

    @Test fun quietRoomOwnerAboveFloorCutsIn() {
        val d = BargeDetector()
        val hit = run(d, { t -> if (t < 1500) 300.0 else 4000.0 }, 0, 4000)
        assertNotNull(hit)
    }
}
