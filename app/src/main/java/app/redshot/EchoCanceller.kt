package app.redshot

/**
 * Software echo canceller (NLMS) for the loud-speaker case. It knows exactly what the speaker plays ([EchoRef]) and learns how
 * that sound arrives at the mic (delay + room), then subtracts it. What is left is the owner's voice. Everything runs at 8 kHz
 * (speech below 4 kHz is all that matters for "is somebody talking"): 1600 taps = 200 ms of delay/room.
 * Used ONLY to decide if the owner cuts in; the audio that goes to the model is never touched.
 */
class EchoCanceller(private val taps: Int = 1600, private val mu: Float = 0.5f, private val muLow: Float = 0.02f) {
    private val w = FloatArray(taps)
    private var win = FloatArray(0)
    private var headEst = 0.0
    private var lastNs = 0L
    private var converged = false
    private var curMu = mu
    @Volatile var lastMicRms = 0.0
    @Volatile var erleDb = 0.0

    fun reset() { java.util.Arrays.fill(w, 0f); headEst = 0.0; lastNs = 0L; converged = false; curMu = mu; erleDb = 0.0 }

    /** [mic] raw 16 kHz mono frame; [headFrames] speaker position (24 kHz frames). Returns the residual rms (16-bit scale). */
    fun process(mic: ShortArray, ref: EchoRef, headFrames: Long, nowNs: Long): Double {
        val n8 = mic.size / 2
        if (n8 == 0) return 0.0
        // smooth the coarse speaker position with the clock so frames stay aligned
        if (lastNs == 0L || nowNs - lastNs > 500_000_000L) headEst = headFrames.toDouble()
        else { headEst += (nowNs - lastNs) / 1e6 * 24.0; headEst += 0.3 * (headFrames - headEst) }
        lastNs = nowNs
        val end8 = (headEst / 3.0).toInt()
        val start8 = end8 - n8                               // first sample of this frame in ref8 coordinates
        val need = taps + n8 - 1
        if (win.size != need) win = FloatArray(need)
        ref.copy8(start8 - taps + 1, win)
        var e2 = 0.0; var m2 = 0.0
        var energy = 0.0
        for (t in 0 until taps) { val v = win[taps - 1 + 0 - t]; energy += v * v }
        for (j in 0 until n8) {
            val m = ((mic[2 * j] + mic[2 * j + 1]) * 0.5f)
            val base = taps - 1 + j                           // win[base - t] = ref at (start8 + j - t)
            var y = 0f
            for (t in 0 until taps) y += w[t] * win[base - t]
            val e = m - y
            val g = curMu * e / (energy.toFloat() + 1000f)
            for (t in 0 until taps) w[t] += g * win[base - t]
            if (j + 1 < n8) { val add = win[base + 1]; val drop = win[base + 1 - taps]; energy += add * add - drop * drop; if (energy < 0) energy = 0.0 }
            e2 += (e * e).toDouble(); m2 += (m * m).toDouble()
        }
        val er = Math.sqrt(e2 / n8); val mr = Math.sqrt(m2 / n8)
        if (er.isNaN() || er.isInfinite() || er > 1e6) { reset(); return mr }
        lastMicRms = mr
        if (mr > 1500) {
            val ratio = er / (mr + 1)
            erleDb = -20 * Math.log10(ratio.coerceIn(1e-3, 10.0))
            if (ratio < 0.35) converged = true
            curMu = if (converged && ratio > 0.6) muLow else mu       // owner talking over the echo: do not let the filter learn him as "echo"
        }
        return er
    }
}
