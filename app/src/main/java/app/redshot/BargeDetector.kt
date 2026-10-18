package app.redshot

/**
 * While Redshot talks, its own voice reaches the mic (speaker echo). The owner only counts as "talking over it"
 * when he is MUCH louder than that echo, for a good moment. The echo level is measured at the start of every answer.
 */
class BargeDetector {
    companion object {
        const val LEARN_MS = 900L       // first moments of an answer: measure the echo, never barge
        const val FACTOR = 2.5
        const val MIN_LEVEL = 3200.0
        const val NEED_FRAMES = 12      // 12 x 20 ms
    }
    private var echoRef = 0.0
    private var run = 0

    fun reset() { echoRef = 0.0; run = 0 }

    /** [rms] of the raw mic frame, [msSinceAnswerStart] since the mic gate closed. True = the owner really cut in. */
    fun onFrame(rms: Double, msSinceAnswerStart: Long): Boolean {
        if (msSinceAnswerStart < LEARN_MS) { echoRef = maxOf(echoRef, rms); run = 0; return false }
        val thr = maxOf(echoRef * FACTOR, MIN_LEVEL)
        run = if (rms > thr) run + 1 else 0
        if (run >= NEED_FRAMES) { run = 0; return true }
        return false
    }
}
