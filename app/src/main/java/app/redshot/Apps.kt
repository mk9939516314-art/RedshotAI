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
            ?: findSoundAlike(ctx, listOf(name))
    }


    // ---- sound-alike matching: works on whatever the offline recogniser wrote (Devanagari or Latin) ----

    private val devMap: Map<Char, Char> = mapOf(
        'क' to 'k', 'ख' to 'k', 'ग' to 'g', 'घ' to 'g', 'च' to 'k', 'छ' to 'k', 'ज' to 'j', 'झ' to 'j',
        'ट' to 't', 'ठ' to 't', 'ड' to 'd', 'ढ' to 'd', 'त' to 't', 'थ' to 't', 'द' to 'd', 'ध' to 'd',
        'न' to 'n', 'प' to 'p', 'फ' to 'p', 'ब' to 'b', 'भ' to 'b', 'म' to 'm', 'य' to 'y', 'र' to 'r',
        'ल' to 'l', 'व' to 'v', 'श' to 's', 'ष' to 's', 'स' to 's', 'ं' to 'n', 'ँ' to 'n', 'ण' to 'n'
    )

    /** Consonant skeleton: "chrome" and "क्रोम" both become "krm". */
    fun skel(s: String): String {
        val sb = StringBuilder()
        for (ch in s.lowercase(Locale.ROOT)) {
            val c: Char? = devMap[ch] ?: when (ch) {
                'c', 'q', 'k' -> 'k'
                'w' -> 'v'
                'f' -> 'p'
                'z' -> 'j'
                'x' -> 'k'
                'b', 'd', 'g', 'j', 'l', 'm', 'n', 'p', 'r', 's', 't', 'v', 'y' -> ch
                else -> null
            }
            if (c != null && (sb.isEmpty() || sb.last() != c)) sb.append(c)
        }
        return sb.toString()
    }

    private fun dist(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]
            d[0] = i
            for (j in 1..b.length) {
                val tmp = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return d[b.length]
    }

    /** Best installed app whose name sounds like one of the words (or word pairs) spoken. */
    fun findSoundAlike(ctx: Context, words: List<String>): App? {
        val cands = ArrayList<String>()
        for (i in words.indices) {
            cands.add(skel(words[i]))
            if (i + 1 < words.size) cands.add(skel(words[i] + words[i + 1]))
        }
        var best: App? = null
        var bestScore = 99
        for (app in list(ctx)) {
            val sk = skel(app.label)
            if (sk.length < 3) continue
            for (c in cands) {
                if (c.length < 3) continue
                val d = dist(sk, c)
                val ok = d == 0 || (d == 1 && sk.length >= 4 && c.length >= 4)
                if (ok && d < bestScore) { best = app; bestScore = d }
            }
        }
        return best
    }

    fun launch(ctx: Context, app: App): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(app.pkg) ?: return false
        Executor.startBg(ctx, i)
        return true
    }
}
