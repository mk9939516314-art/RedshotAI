package app.redshot

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reads the owner's online-class app (Allen) from the screen: Today / Tomorrow / Day after tomorrow, sends the text to the brain,
 * which replaces the stored schedule. Reminders (5 min before, and at the start, of the day's main classes) come from that fresh scan,
 * never from old memory.
 */
object ClassScan {
    private const val P = "classscan"
    @Volatile private var running = false
    @Volatile var lastAt = 0L
    @Volatile var lastNote = ""

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun doneToday(ctx: Context): Boolean = ctx.getSharedPreferences(P, Context.MODE_PRIVATE).getString("day", "") == today()

    /** morning scan still missing (after 06:00), or, while the owner is away, the last one is older than 3 hours */
    fun due(ctx: Context, hour: Int, away: Boolean, now: Long = System.currentTimeMillis()): Boolean =
        !running && ((hour >= 6 && !doneToday(ctx)) || (away && now - lastAt > 3 * 3600_000L))

    private fun phoneReady(ctx: Context): String? {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!pm.isInteractive) {
            try {
                @Suppress("DEPRECATION")
                val w = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "redshot:scan")
                w.acquire(8000)
                Thread.sleep(1200)
            } catch (e: Throwable) {}
        }
        if (!pm.isInteractive) return "skip: screen band hai"
        if (km.isKeyguardLocked) return "skip: phone locked hai, unlock hote hi dekh lunga"
        return null
    }

    fun run(ctx: Context, appName: String = "Allen", why: String = "owner"): String {
        if (running) return "error: scan pehle se chal raha hai"
        running = true
        try {
            phoneReady(ctx)?.let { lastNote = it; return it }
            val app = Apps.findByName(ctx, appName) ?: return "error: $appName app phone me nahi mila"
            if (!Apps.launch(ctx, app)) return "error: $appName app khul nahi paaya"
            Thread.sleep(3800)
            val parts = ArrayList<String>()
            fun read(label: String) {
                val s = Executor.runForResult(ctx, JSONObject().put("type", "read_screen").put("wait_ms", 900))
                if (!s.startsWith("error")) parts.add("== $label ==\n" + s.take(3000)) else parts.add("== $label ==\n$s")
            }
            read("Today")
            for (tab in listOf("Tomorrow", "Day after tomorrow")) {
                val r = Executor.runForResult(ctx, JSONObject().put("type", "tap_text").put("text", tab))
                if (!r.startsWith("error")) { Thread.sleep(900); read(tab) }
            }
            Executor.runForResult(ctx, JSONObject().put("type", "global").put("name", "home"))
            val text = parts.joinToString("\n")
            if (text.length < 40) return "error: app ki screen padh nahi paaya (accessibility on hai?)"
            val r = Brain.post(ctx, "device/scan", JSONObject().put("kind", "class").put("text", text).put("app", appName), 28000)
                ?: return "error: brain tak scan nahi pahuncha (" + Brain.lastError + ")"
            if (!r.optBoolean("ok")) { lastNote = "schedule ki screen nahi mili"; return "error: " + r.optString("summary", "schedule nahi mila") }
            lastAt = System.currentTimeMillis()
            ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putString("day", today()).apply()
            val rem = r.optJSONArray("reminders")
            val list = ArrayList<Pair<Long, String>>()
            if (rem != null) for (i in 0 until rem.length()) { val o = rem.optJSONObject(i) ?: continue; list.add(o.optLong("atMs") to o.optString("say")) }
            RedshotService.classReminders(list)
            val ch = r.optJSONArray("changes")
            val changes = ArrayList<String>()
            if (ch != null) for (i in 0 until ch.length()) changes.add(ch.optString(i))
            if (changes.isNotEmpty()) RedshotService.classChanged(changes)
            lastNote = "scan ok (" + why + ")"
            return r.optString("summary") + (if (changes.isNotEmpty()) "\nBADLAV: " + changes.joinToString("; ") else "\n(pichhle scan se koi badlav nahi)") +
                "\nReminders lage: " + list.size
        } catch (e: Exception) {
            return "error: scan fail (" + (e.message ?: e.javaClass.simpleName) + ")"
        } finally { running = false }
    }
}
