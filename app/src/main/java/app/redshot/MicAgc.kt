package app.redshot

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Software gain for the live session: the raw mic of the phone is quiet at arm's length, which is why the model
 * "heard" nonsense. Brings speech to a steady level (never amplifies silence/noise, never clips).
 */
class MicAgc(private val target: Double = 2800.0, private val maxGain: Double = 8.0, private val noiseFloor: Double = 250.0) {
    private var gain = 1.0
    fun process(f: ShortArray): ShortArray {
        var s = 0.0
        for (v in f) s += v.toDouble() * v.toDouble()
        val rms = sqrt(s / f.size)
        val want = if (rms < noiseFloor) 1.0 else (target / rms).coerceIn(1.0, maxGain)
        // fast up for speech onset, slow down; never jumps
        gain = if (want > gain) gain + (want - gain) * 0.35 else gain + (want - gain) * 0.1
        if (gain <= 1.02) return f
        val o = ShortArray(f.size)
        for (i in f.indices) {
            val v = f[i] * gain
            o[i] = (if (v > 32000) 32000 else if (v < -32000) -32000 else v).toInt().toShort()
        }
        return o
    }
}
