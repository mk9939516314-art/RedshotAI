package app.redshot

import android.content.Context
import android.content.Intent
import java.util.Locale

object Apps {
    data class App(val label: String, val pkg: String)

    private var cache: List<App> = emptyList()
    private var cachedAt = 0L

    fun list(ctx: Context): List<App> {
        val now = System.currentTimeMillis()
        if (cache.isEmpty() || now - cachedAt > 60_000) {
            val pm = ctx.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            cache = pm.queryIntentActivities(intent, 0)
                .map { App(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                .distinctBy { it.pkg }
            cachedAt = now
        }
        return cache
    }

    fun norm(s: String): String =
        s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9ऀ-ॿ]"), "")

    /** Longest installed app label that appears inside the spoken sentence. */
    fun findIn(ctx: Context, utterance: String): App? {
        val u = norm(utterance)
        return list(ctx)
            .filter { val l = norm(it.label); l.length >= 3 && u.contains(l) }
            .maxByOrNull { norm(it.label).length }
    }

    /** Best match for an app name given by the brain. */
    fun findByName(ctx: Context, name: String): App? {
        val n = norm(name)
        if (n.isEmpty()) return null
        val all = list(ctx)
        return all.firstOrNull { norm(it.label) == n }
            ?: all.firstOrNull { val l = norm(it.label); l.length >= 3 && (l.contains(n) || n.contains(l)) }
            ?: all.firstOrNull { it.pkg.lowercase(Locale.ROOT).contains(n) }
    }

    fun launch(ctx: Context, app: App): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(app.pkg) ?: return false
        Executor.startBg(ctx, i)
        return true
    }
}
