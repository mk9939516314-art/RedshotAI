package app.redshot

import android.content.Context
import android.os.PowerManager
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** "Reel khatam ho toh apne aap agla": swipes to the next reel/page every few seconds until told to stop. */
object AutoScroll {
    private val ex = Executors.newSingleThreadScheduledExecutor()
    private var job: ScheduledFuture<*>? = null
    @Volatile var every = 0
    @Volatile private var startedAt = 0L
    lateinit var appCtx: Context

    @Synchronized fun start(sec: Int) {
        stop()
        every = sec
        startedAt = System.currentTimeMillis()
        job = ex.scheduleWithFixedDelay({
            try {
                val pm = appCtx.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isInteractive || System.currentTimeMillis() - startedAt > 90 * 60_000L) { stop(); return@scheduleWithFixedDelay }
                RedshotAccess.instance?.swipe("up")
            } catch (e: Exception) {}
        }, sec.toLong(), sec.toLong(), TimeUnit.SECONDS)
    }

    @Synchronized fun stop() {
        job?.cancel(false)
        job = null
        every = 0
    }

    val active: Boolean get() = job != null
}
