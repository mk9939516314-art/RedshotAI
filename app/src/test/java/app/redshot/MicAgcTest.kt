package app.redshot

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class MicAgcTest {
    private fun tone(amp: Double, n: Int = 320, phase: Int = 0) = ShortArray(n) { (amp * sin(2 * PI * 200 * (it + phase) / 16000.0)).toInt().toShort() }
    private fun rms(f: ShortArray) = sqrt(f.sumOf { it.toDouble() * it } / f.size)

    @Test fun quietSpeechIsBroughtUpToAUsefulLevel() {
        val agc = MicAgc()
        var last = ShortArray(0)
        repeat(40) { last = agc.process(tone(700.0, phase = it * 320)) }   // rms ~495 -> needs boost
        assertTrue("rms ${rms(last)}", rms(last) > 1800)
    }

    @Test fun silenceAndRoomNoiseAreNotAmplified() {
        val agc = MicAgc()
        var last = ShortArray(0)
        repeat(40) { last = agc.process(tone(120.0, phase = it * 320)) }   // rms ~85
        assertTrue(rms(last) < 130)
    }

    @Test fun loudSpeechIsNotBoostedOrClipped() {
        val agc = MicAgc()
        repeat(20) { agc.process(tone(9000.0, phase = it * 320)) }
        val o = agc.process(tone(9000.0))
        assertTrue(o.all { abs(it.toInt()) <= 32000 })
        assertEquals(rms(tone(9000.0)), rms(o), 200.0)
    }

    @Test fun suddenLoudBurstAfterGainWasHighNeverClips() {
        val agc = MicAgc()
        repeat(30) { agc.process(tone(600.0, phase = it * 320)) }
        val o = agc.process(tone(30000.0))
        assertTrue(o.all { abs(it.toInt()) <= 32000 })
    }
}
