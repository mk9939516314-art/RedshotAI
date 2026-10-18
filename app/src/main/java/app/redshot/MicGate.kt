package app.redshot

/**
 * Decides what the live session hears while Redshot itself is talking.
 *  - model talking (+ a short tail after): the mic is muted for the model (it must not hear its own voice)
 *  - the owner can talk over it only when [bargeEnabled] (earphones / no echo) and he is clearly louder than the echo
 *  - the gate can never stay shut for more than 30 s
 */
class MicGate(private val clock: () -> Long = { System.currentTimeMillis() }) {
    enum class Action { PASS, ZERO, BARGE }

    @Volatile var modelTalking = false
    @Volatile var tailUntil = 0L
    @Volatile var bargeEnabled = false
    private var closedSince = 0L
    private var bargeUntil = 0L
    private val barge = BargeDetector()
    /** loudest the mic was while the gate was closed (physical proof that sound really comes out of the speaker) */
    @Volatile var echoPeak = 0.0

    val closed: Boolean get() {
        val now = clock()
        val c = modelTalking || now < tailUntil
        if (!c) { closedSince = 0L; return false }
        if (closedSince == 0L) { closedSince = now; barge.reset(); echoPeak = 0.0 }
        if (now - closedSince > 30_000L) { modelTalking = false; tailUntil = 0L; closedSince = 0L; return false }
        return true
    }

    fun reset() { modelTalking = false; tailUntil = 0L; closedSince = 0L; bargeUntil = 0L; barge.reset(); echoPeak = 0.0 }

    fun frame(rms: Double): Action {
        if (!closed) return Action.PASS
        val now = clock()
        if (rms > echoPeak) echoPeak = rms
        if (now < bargeUntil) return Action.PASS            // right after a barge the mic stays open for the owner
        if (bargeEnabled && barge.onFrame(rms, now - closedSince)) { bargeUntil = now + 4000; return Action.BARGE }
        return Action.ZERO
    }
}
