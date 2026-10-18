package app.redshot

/**
 * While Redshot talks through the loud speaker its own voice reaches the mic (and phone mic gain-control makes that echo
 * swell up over the first second). The owner only counts as "talking over it" when he is far louder than the LOUDEST echo
 * seen in this answer so far - which on a speaker is practically never, and with earphones (no echo) is easy.
 */
class BargeDetector {
    companion object {
        const val LEARN_MS = 900L       // first moments of an answer: only measure, never barge
        const val FACTOR = 3.0          // x the loudest echo of this answer
        const val MIN_LEVEL = 3200.0
        const val NEED_FRAMES = 12      // 12 x 20 ms
    }
    private var echoMax = 0.0
    private var run = 0

    fun reset() { echoMax = 0.0; run = 0 }

    /** [rms] of the raw mic frame, [msSinceAnswerStart] since the mic gate closed. True = the owner really cut in. */
    fun onFrame(rms: Double, msSinceAnswerStart: Long): Boolean {
        if (msSinceAnswerStart < LEARN_MS) { echoMax = maxOf(echoMax, rms); run = 0; return false }
        val thr = maxOf(echoMax * FACTOR, MIN_LEVEL)
        if (rms <= thr) { echoMax = maxOf(echoMax, rms * 0.98); run = 0; return false }   // quiet or echo: keep learning
        run++
        if (run >= NEED_FRAMES) { run = 0; return true }
        return false
    }
}
