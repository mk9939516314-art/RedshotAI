package app.redshot

/**
 * What the speaker is being told to play, as a loudness timeline (one value per 20 ms, indexed by output frame).
 * The mic hears that same sound a moment later, so "how loud should the echo be right now" is simply the loudness
 * of the last ~250 ms of what the speaker is playing at its CURRENT position. Owner speech is whatever is louder than that.
 */
class EchoRef {
    companion object { const val BIN = 480 /* frames of 24 kHz = 20 ms */; const val BACK_BINS = 12; const val FWD_BINS = 1 }
    private val lock = Any()
    private var bins = DoubleArray(4096)
    private var n = 0                  // bins filled
    private var carry = 0.0; private var carryN = 0; private var frame = 0L
    private var ref8 = FloatArray(8000 * 30); private var n8 = 0; private var acc8 = 0.0; private var acc8n = 0

    fun clear() { synchronized(lock) { n = 0; carry = 0.0; carryN = 0; frame = 0; n8 = 0; acc8 = 0.0; acc8n = 0 } }

    /** 8 kHz copy of what was played: ref8[idx] for idx in [from, from+out.size) (zeros outside what exists). idx 8k = played frame / 3 */
    fun copy8(from: Int, out: FloatArray) {
        synchronized(lock) {
            for (k in out.indices) { val i = from + k; out[k] = if (i in 0 until n8) ref8[i] else 0f }
        }
    }

    /** 16-bit little-endian mono PCM that was handed to the speaker (silence included, so frames line up with the player's head) */
    fun add(b: ByteArray, off: Int, len: Int) {
        synchronized(lock) {
            var i = off; val end = off + len - len % 2
            while (i < end) {
                val s = (b[i].toInt() and 0xFF) or (b[i + 1].toInt() shl 8)
                val v = s.toShort().toDouble()
                carry += v * v; carryN++; frame++
                acc8 += v; acc8n++
                if (acc8n == 3) { if (n8 == ref8.size) ref8 = ref8.copyOf(n8 * 2); ref8[n8++] = (acc8 / 3.0).toFloat(); acc8 = 0.0; acc8n = 0 }
                if (carryN == BIN) { push(Math.sqrt(carry / BIN)); carry = 0.0; carryN = 0 }
                i += 2
            }
        }
    }

    private fun push(v: Double) { if (n == bins.size) bins = bins.copyOf(n * 2); bins[n++] = v }

    /** loudest recent part of what the speaker is playing right now; [headFrames] = frames the speaker really played */
    fun level(headFrames: Long): Double {
        synchronized(lock) {
            if (n == 0) return 0.0
            val h = (headFrames / BIN).toInt()
            val lo = maxOf(0, h - BACK_BINS); val hi = minOf(n - 1, h + FWD_BINS)
            var m = 0.0
            for (k in lo..hi) if (bins[k] > m) m = bins[k]
            return m
        }
    }
}

/**
 * The owner talking over Redshot's own voice. Judged on the echo-CANCELLED mic (what is left after the known playback
 * is subtracted), against the residual echo that the playback level still explains.
 */
class RefBargeDetector {
    companion object {
        const val LEARN_MS = 1500L          // the canceller needs about a second to converge
        const val MIN_LEVEL = 1800.0
        const val FACTOR = 2.1
        const val MARGIN = 800.0
        const val NEED = 10.0               // leaky counter: loud frames count 1, quiet frames take 0.5 back (speech has gaps)
        const val MIN_REF = 500.0
    }
    private var gain = 0.0                  // residual per unit of speaker level
    private var run = 0.0

    fun reset() { gain = 0.0; run = 0.0 }

    fun onFrame(resid: Double, ref: Double, msSinceStart: Long, converged: Boolean = true): Boolean {
        val q = if (ref >= MIN_REF) resid / ref else 0.0
        if (msSinceStart < LEARN_MS) { if (q > gain) gain = q; run = 0.0; return false }
        gain *= 0.99
        val expected = gain * ref
        val thr = maxOf(MIN_LEVEL, expected * FACTOR + MARGIN)
        if (resid <= thr) {
            if (q > gain && resid <= expected * 1.5 + MARGIN) gain += 0.25 * (q - gain)
            run = maxOf(0.0, run - 0.5); return false
        }
        run += 1.0
        if (run >= NEED) { run = 0.0; return converged }
        return false
    }
}
