package app.redshot

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

/**
 * Simulated loud-speaker playback: speech-like speaker signal, clipped, through a delayed room response with
 * mic-AGC swell; sometimes the owner talks over it. Mirrors the Python simulation the numbers were tuned on.
 */
class EchoRefTest {
    private val FR = 160                      // 8 kHz samples per 20 ms

    private fun speech(n: Int, rng: Random, rms: Double): DoubleArray {
        val b = doubleArrayOf(1.0, 0.9, 0.5, 0.2)
        val w = DoubleArray(n) { rng.nextGaussian() }
        val y = DoubleArray(n)
        for (i in 0 until n) { var a = 0.0; for (k in b.indices) if (i - k >= 0) a += b[k] * w[i - k]; y[i] = a }
        val f = 3.5 + rng.nextDouble(); val ph = rng.nextDouble() * 6
        var e2 = 0.0
        for (i in 0 until n) {
            val t = i / 8000.0
            var env = Math.pow(Math.sin(2 * Math.PI * f * t), 2.0) * 1.4 - 0.15
            env = env.coerceIn(0.0, 1.0)
            if (Math.sin(2 * Math.PI * 0.6 * t + ph) <= -0.6) env = 0.0
            y[i] *= env; e2 += y[i] * y[i]
        }
        val g = rms / (Math.sqrt(e2 / n) + 1e-9)
        for (i in 0 until n) y[i] *= g
        return y
    }

    private fun bytes24(x: DoubleArray): ByteArray {         // 8 kHz -> 24 kHz (x3), 16-bit LE
        val b = ByteArray(x.size * 3 * 2)
        for (i in x.indices) { val v = x[i].toInt().coerceIn(-32768, 32767); for (r in 0 until 3) { val o = (i * 3 + r) * 2; b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() } }
        return b
    }

    private class Out(val detectedMs: Long, val erleSteady: Double)

    private fun sim(seed: Long, gain: Double, ownerRms: Double, ownerFromS: Double, secs: Int = 10): Out {
        val rng = Random(seed); val n = secs * 8000
        val refSig = speech(n, rng, 3500.0)
        val sp = DoubleArray(n) { Math.tanh(refSig[it] / 9000.0) * 9000.0 }
        val d = 720; val tail = 400
        val h = DoubleArray(d + tail); h[d] = 1.0
        for (k in 0 until tail) h[d + k] += rng.nextGaussian() * Math.exp(-k / (tail / 4.0)) * 0.5
        for (k in h.indices) h[k] *= gain
        val mic = DoubleArray(n)
        for (i in 0 until n) { var a = 0.0; val lim = minOf(h.size - 1, i); for (k in 0..lim) a += h[k] * sp[i - k]; mic[i] = a * (1 + 1.4 * Math.min(1.0, i / (1.2 * 8000))) }
        if (ownerRms > 0) { val o = speech(n, rng, ownerRms); for (i in (ownerFromS * 8000).toInt() until n) mic[i] += o[i] }
        for (i in 0 until n) mic[i] += rng.nextGaussian() * 40

        val echo = EchoRef(); echo.add(bytes24(refSig), 0, n * 6)
        val ec = EchoCanceller(); val det = RefBargeDetector()
        var hit = -1L; var micE = 0.0; var resE = 0.0; var cnt = 0
        val frames = n / FR
        for (k in 0 until frames) {
            val m16 = ShortArray(FR * 2)
            for (j in 0 until FR) { val v = mic[k * FR + j].toInt().coerceIn(-32768, 32767).toShort(); m16[2 * j] = v; m16[2 * j + 1] = v }
            val head = (k + 1L) * FR * 3
            val res = ec.process(m16, echo, head, k * 20_000_000L)
            val refLevel = echo.level(head)
            if (hit < 0 && det.onFrame(res, refLevel, k * 20L, ec.converged)) hit = k * 20L
            if (k * 20 in 3000..4800) { micE += ec.lastMicRms; resE += res; cnt++ }
        }
        return Out(hit, if (resE > 0) 20 * Math.log10(micE / resE) else 0.0)
    }

    @Test fun echoRefAlignsWithPlaybackHead() {
        val ref = EchoRef(); val rnd = Random(3)
        ref.add(ByteArray(EchoRef.BIN * 2 * 10), 0, EchoRef.BIN * 2 * 10)            // 200 ms silence
        val loud = ByteArray(EchoRef.BIN * 2); for (i in 0 until EchoRef.BIN) { val v = (rnd.nextGaussian() * 6000).toInt(); loud[2 * i] = v.toByte(); loud[2 * i + 1] = (v shr 8).toByte() }
        ref.add(loud, 0, loud.size)                                                  // one loud 20 ms bin at index 10
        assertTrue(ref.level(EchoRef.BIN * 4L) < 100)
        assertTrue(ref.level(EchoRef.BIN * 11L) > 4000)
        assertTrue(ref.level(EchoRef.BIN * 40L) < 100)
        ref.clear(); assertEquals(0.0, ref.level(1000L), 0.0)
    }

    @Test fun cancellerRemovesMostOfTheSpeakerEcho() {
        for (g in listOf(0.1, 0.2, 0.35)) {
            val o = sim(11, g, 0.0, 0.0, secs = 6)
            assertTrue("gain $g: echo reduced only ${o.erleSteady} dB", o.erleSteady >= 7.0)
        }
    }

    @Test fun neverBargesOnItsOwnVoice() {
        var fb = 0; var total = 0
        for (seed in 1L..4L) for (g in listOf(0.1, 0.2, 0.35)) { total++; if (sim(seed, g, 0.0, 0.0).detectedMs >= 0) fb++ }
        assertEquals("false barges in $total runs", 0, fb)
    }

    @Test fun ownerTalkingOverTheSpeakerCutsInQuickly() {
        var hits = 0; var total = 0; var slow = 0
        for (seed in 1L..4L) for (g in listOf(0.1, 0.2, 0.35)) for (o in listOf(3500.0, 5500.0)) {
            total++
            val r = sim(seed, g, o, 5.0)
            if (r.detectedMs >= 5000) { hits++; if (r.detectedMs - 5000 > 2000) slow++ }
        }
        assertTrue("owner heard in $hits of $total runs", hits >= total * 0.7)
        assertEquals("slower than 2 s", 0, slow)
    }

    @Test fun earphonesNoEchoStillWorks() {
        val det = RefBargeDetector(); var fired = false
        for (k in 0 until 200) if (det.onFrame(if (k > 120) 4000.0 else 100.0, 5000.0, k * 20L)) fired = true
        assertTrue(fired)
    }
}
