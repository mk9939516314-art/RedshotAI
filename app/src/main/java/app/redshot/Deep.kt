package app.redshot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Deep tools: exact-time waiting, retries until something appears on screen, multi-finger gestures, seeing the screen,
 * and macros (tools the model defines for itself and can run again and again).
 */
object Deep {
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var runningName = ""
    @Volatile private var lastStatus = "(koi macro nahi chali)"
    @Volatile private var cancel = false
    @Volatile var sendImage: ((ByteArray) -> Boolean)? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("macros", Context.MODE_PRIVATE)

    fun run(ctx: Context, name: String, a: JSONObject): String? = when (name) {
        "wait_for_text" -> waitForText(a.optString("text"), a.optInt("timeout_s", 15))
        "wait_until_time" -> waitUntil(a.optString("time"), a.optInt("max_wait_s", 900))
        "tap_retry" -> tapRetry(a.optString("text"), a.optInt("attempts", 10), a.optLong("interval_ms", 400))
        "find_on_screen" -> RedshotAccess.instance?.findOnScreen(a.optString("text")) ?: "error: accessibility band hai"
        "long_press" -> gesture(listOf(doubleArrayOf(a.optDouble("x"), a.optDouble("y"), a.optDouble("x"), a.optDouble("y"), 0.0, a.optDouble("ms", 800.0))))
        "drag" -> gesture(listOf(doubleArrayOf(a.optDouble("x1"), a.optDouble("y1"), a.optDouble("x2"), a.optDouble("y2"), 0.0, a.optDouble("ms", 400.0))))
        "gesture" -> {
            val arr = a.optJSONArray("strokes") ?: JSONArray()
            gesture((0 until arr.length()).map { val o = arr.getJSONObject(it)
                doubleArrayOf(o.optDouble("x1"), o.optDouble("y1"), o.optDouble("x2", o.optDouble("x1")), o.optDouble("y2", o.optDouble("y1")), o.optDouble("start_ms", 0.0), o.optDouble("ms", 300.0)) })
        }
        "multi_tap" -> multiTap(a)
        "see_screen" -> seeScreen()
        "macro_define" -> macroDefine(ctx, a)
        "macro_run" -> macroRun(ctx, a)
        "macro_list" -> macroList(ctx)
        "macro_delete" -> { prefs(ctx).edit().remove(a.optString("name")).apply(); "ok: macro hata di" }
        "macro_status" -> lastStatus
        "macro_stop" -> { cancel = true; "ok: macro rok rahi hu" }
        else -> null
    }

    private fun multiTap(a: JSONObject): String {
        val pts = a.optJSONArray("points") ?: JSONArray()
        val acc = RedshotAccess.instance ?: return "error: accessibility band hai"
        for (i in 0 until pts.length()) { val p = pts.getJSONObject(i); acc.tapPercent(p.optInt("x"), p.optInt("y")); Thread.sleep(a.optLong("interval_ms", 150)) }
        return "ok: ${pts.length()} tap"
    }

    private fun gesture(strokes: List<DoubleArray>): String {
        val acc = RedshotAccess.instance ?: return "error: accessibility band hai"
        return if (acc.gesture(strokes)) "ok" else "error: gesture nahi chala"
    }

    private fun seeScreen(): String {
        val acc = RedshotAccess.instance ?: return "error: accessibility band hai"
        val jpg = acc.shot() ?: return "error: screenshot nahi mila (Android 11+ aur accessibility chahiye)"
        val ok = sendImage?.invoke(jpg) ?: false
        return if (ok) "ok: screen ki tasveer tujhe bhej di (${jpg.size / 1024}KB), ab dekh le" else "error: tasveer live session tak nahi pahunchi"
    }

    private fun waitForText(text: String, timeoutS: Int): String {
        val acc = RedshotAccess.instance ?: return "error: accessibility band hai"
        val end = System.currentTimeMillis() + timeoutS.coerceIn(1, 120) * 1000L
        while (System.currentTimeMillis() < end) {
            if (cancel) return "error: rok diya"
            if (acc.screenHas(text)) return "ok: '$text' aa gaya"
            Thread.sleep(150)
        }
        return "error: '$text' ${timeoutS}s mein nahi aaya. Screen par abhi:\n" + acc.readScreen(800)
    }

    /** time = "HH:mm" or "HH:mm:ss" (IST on the phone). Waits right up to the second, then returns. */
    private fun waitUntil(time: String, maxWaitS: Int): String {
        val p = time.trim().split(":").mapNotNull { it.toIntOrNull() }
        if (p.size < 2) return "error: time ka format HH:mm ya HH:mm:ss"
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, p[0]); c.set(Calendar.MINUTE, p[1]); c.set(Calendar.SECOND, p.getOrElse(2) { 0 }); c.set(Calendar.MILLISECOND, 0)
        val target = c.timeInMillis
        val wait = target - System.currentTimeMillis()
        if (wait < -60_000) return "error: ye time nikal chuka hai"
        if (wait > maxWaitS * 1000L) return "error: abhi $time mein ${wait / 1000}s baaki hai, max_wait_s se zyada"
        while (true) {
            if (cancel) return "error: rok diya"
            val left = target - System.currentTimeMillis()
            if (left <= 0) break
            Thread.sleep(if (left > 200) 100 else 5)
        }
        return "ok: $time ho gaya"
    }

    private fun tapRetry(text: String, attempts: Int, intervalMs: Long): String {
        val acc = RedshotAccess.instance ?: return "error: accessibility band hai"
        for (i in 1..attempts.coerceIn(1, 200)) {
            if (cancel) return "error: rok diya"
            if (acc.tapText(text)) return "ok: '$text' dab gaya (koshish $i)"
            Thread.sleep(intervalMs.coerceIn(50, 5000))
        }
        return "error: '$text' ${attempts} koshish mein nahi mila"
    }

    // ---------- macros: tools the model makes for itself ----------

    private fun macroDefine(ctx: Context, a: JSONObject): String {
        val n = a.optString("name").trim()
        if (n.isEmpty()) return "error: macro ka naam do"
        val steps = a.opt("steps")
        val arr = when (steps) { is JSONArray -> steps; is String -> try { JSONArray(steps) } catch (e: Exception) { return "error: steps JSON array hona chahiye" }; else -> return "error: steps do" }
        val o = JSONObject().put("name", n).put("about", a.optString("about")).put("steps", arr)
        prefs(ctx).edit().putString(n, o.toString()).apply()
        return "ok: macro '$n' save (${arr.length()} steps). macro_run se chala sakta hai."
    }

    private fun macroList(ctx: Context): String {
        val all = prefs(ctx).all
        if (all.isEmpty()) return "koi macro nahi bani abhi"
        return all.values.joinToString("\n") { try { val o = JSONObject(it as String); "- ${o.optString("name")}: ${o.optString("about")} (${o.getJSONArray("steps").length()} steps)" } catch (e: Exception) { "" } }
    }

    /**
     * Step = any tool as {"type":"tap_text","text":"Book Now"} plus optional control keys:
     * retries, retry_ms, expect_text (must appear afterwards), on_fail ("stop" default | "continue"), delay_ms.
     * Macro options: repeat, stop_when_text, max_seconds. Short macros answer directly, long ones continue in the background.
     */
    private fun macroRun(ctx: Context, a: JSONObject): String {
        val n = a.optString("name")
        val raw = prefs(ctx).getString(n, null) ?: return "error: '$n' naam ki macro nahi hai (macro_list dekh)"
        val m = JSONObject(raw)
        val steps = m.getJSONArray("steps")
        val repeat = a.optInt("repeat", 1).coerceIn(1, 500)
        val stopWhen = a.optString("stop_when_text")
        val maxMs = a.optInt("max_seconds", 120).coerceIn(5, 1800) * 1000L
        if (runningName.isNotEmpty()) return "error: '$runningName' abhi chal rahi hai (macro_stop se rok sakta hai)"
        cancel = false
        runningName = n
        val job = pool.submit(Callable {
            val t0 = System.currentTimeMillis()
            val log = StringBuilder()
            var result = "ok"
            try {
                outer@ for (r in 1..repeat) {
                    for (i in 0 until steps.length()) {
                        if (cancel) { result = "error: rok di"; break@outer }
                        if (System.currentTimeMillis() - t0 > maxMs) { result = "error: max time khatam"; break@outer }
                        val st = JSONObject(steps.getJSONObject(i).toString())
                        val retries = st.optInt("retries", 1).coerceIn(1, 100)
                        val retryMs = st.optLong("retry_ms", 400)
                        val expect = st.optString("expect_text")
                        val onFail = st.optString("on_fail", "stop")
                        var res = ""
                        var good = false
                        for (k in 1..retries) {
                            res = if (st.optString("type") == "wait") { Thread.sleep(st.optLong("ms", 500).coerceIn(10, 20000)); "ok" }
                                  else Executor.runForResult(ctx, st)
                            good = !res.startsWith("error")
                            if (good && expect.isNotEmpty()) good = RedshotAccess.instance?.let { acc ->
                                val until = System.currentTimeMillis() + st.optLong("expect_ms", 3000); var f = false
                                while (System.currentTimeMillis() < until && !f) { f = acc.screenHas(expect); if (!f) Thread.sleep(120) }; f } ?: false
                            if (good) break
                            Thread.sleep(retryMs.coerceIn(30, 5000))
                        }
                        log.append("r$r s${i + 1} ${st.optString("type")}: ${res.take(60)}\n")
                        if (st.optLong("delay_ms", 0) > 0) Thread.sleep(st.optLong("delay_ms"))
                        if (!good && onFail != "continue") { result = "error: step ${i + 1} (${st.optString("type")}) fail: ${res.take(100)}"; break@outer }
                        if (stopWhen.isNotEmpty() && RedshotAccess.instance?.screenHas(stopWhen) == true) { result = "ok: '$stopWhen' dikh gaya, ruk gaya"; break@outer }
                    }
                }
            } catch (e: Exception) { result = "error: " + e.message }
            lastStatus = "macro '$n' $result\n" + log.toString().takeLast(1500)
            ActionLog.add("macro $n -> " + result.take(80))
            runningName = ""
            lastStatus
        })
        return try { job.get(12, TimeUnit.SECONDS) } catch (e: TimeoutException) { "macro '$n' background mein chal rahi hai. macro_status se dekh, macro_stop se rok." }
    }
}
