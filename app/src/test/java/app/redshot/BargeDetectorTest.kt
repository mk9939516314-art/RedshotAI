package app.redshot

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class BargeDetectorTest {
    private fun run(d: BargeDetector, levelAt: (Long) -> Double, fromMs: Long, toMs: Long): Long? {
        var t = fromMs
        while (t < toMs) { if (d.onFrame(levelAt(t), t)) return t; t += 20 }
        return null
    }

    @Test fun speakerEchoWithSyllablesNeverCutsItself() {
        // speech envelope: syllables from 10% to 100% of peak, 4 per second, peak 1500..9000
        for (peak in listOf(1500.0, 3000.0, 6000.0, 9000.0)) {
            val d = BargeDetector()
            val hit = run(d, { t -> peak * (0.1 + 0.9 * abs(sin(t / 110.0))) }, 0, 20_000)
            assertNull("echo peak $peak must not barge", hit)
        }
    }

    @Test fun phoneMicGainSwellingOverTheFirstSecondNeverCutsItself() {
        // automatic gain control ramps the echo up 6x over 3 seconds
        for (peak in listOf(800.0, 2000.0, 3000.0)) {
            val d = BargeDetector()
            val hit = run(d, { t -> peak * (0.15 + 0.85 * minOf(1.0, t / 3000.0)) * (0.6 + 0.4 * abs(sin(t / 95.0))) }, 0, 15_000)
            assertNull("swelling echo $peak must not barge", hit)
        }
    }

    @Test fun firstMomentsNeverBarge() {
        val d = BargeDetector()
        assertNull(run(d, { 20000.0 }, 0, BargeDetector.LEARN_MS))
    }

    @Test fun earphonesOwnerTalkingOverCutsIn() {
        val d = BargeDetector()
        val hit = run(d, { t -> if (t < 2000) 60.0 else 7000.0 }, 0, 6000)   // no echo with earphones
        assertNotNull(hit)
        assertTrue("reacts within ~0.3 s", hit!! in 2000..2400)
    }

    @Test fun shortLoudBlipDoesNotBarge() {
        val d = BargeDetector()
        assertNull(run(d, { t -> if (t in 3000..3200) 9000.0 else 100.0 }, 0, 6000))
    }
}
