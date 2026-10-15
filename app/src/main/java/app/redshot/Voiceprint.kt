package app.redshot

import android.content.Context
import org.json.JSONArray
import kotlin.math.sqrt

/** Owner's voice fingerprint (x-vector from the Vosk speaker model). Used to ignore reels, TV and other people. */
object Voiceprint {
    private var vecs = ArrayList<DoubleArray>()
    private var mean: DoubleArray? = null
    @Volatile var base = 0.5
    private lateinit var appCtx: Context

    val enrolled: Boolean get() = mean != null && vecs.size >= 3
    val count: Int get() = vecs.size

    fun load(ctx: Context) {
        appCtx = ctx.applicationContext
        try {
            val txt = appCtx.getSharedPreferences("voiceprint", Context.MODE_PRIVATE).getString("v", null) ?: return
            val arr = JSONArray(txt)
            val list = ArrayList<DoubleArray>()
            for (i in 0 until arr.length()) {
                val a = arr.getJSONArray(i)
                list.add(DoubleArray(a.length()) { a.getDouble(it) })
            }
            vecs = list
            recompute()
        } catch (e: Exception) {}
    }

    private fun save() {
        try {
            val arr = JSONArray()
            for (v in vecs) arr.put(JSONArray().also { a -> v.forEach { a.put(it) } })
            appCtx.getSharedPreferences("voiceprint", Context.MODE_PRIVATE).edit().putString("v", arr.toString()).apply()
        } catch (e: Exception) {}
    }

    fun clear() { vecs = ArrayList(); mean = null; save() }

    fun addEnroll(v: DoubleArray) {
        vecs.add(v)
        recompute()
        save()
    }

    private fun recompute() {
        if (vecs.isEmpty()) { mean = null; return }
        val m = DoubleArray(vecs[0].size)
        for (v in vecs) for (i in m.indices) m[i] += v[i]
        mean = m
        if (vecs.size >= 3) {
            var maxIntra = 0.0
            for (v in vecs) maxIntra = maxOf(maxIntra, cosDist(m, v))
            base = (maxIntra * 1.25 + 0.08).coerceIn(0.42, 0.6)
        }
    }

    fun cosDist(a: DoubleArray, b: DoubleArray): Double {
        var d = 0.0; var na = 0.0; var nb = 0.0
        val n = minOf(a.size, b.size)
        for (i in 0 until n) { d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        if (na == 0.0 || nb == 0.0) return 1.0
        return 1.0 - d / (sqrt(na) * sqrt(nb))
    }

    /** Distance to the owner (0 = identical), or -1 if nothing enrolled. */
    fun dist(v: DoubleArray): Double {
        val m = mean ?: return -1.0
        if (!enrolled) return -1.0
        var best = cosDist(m, v)
        for (e in vecs) best = minOf(best, cosDist(e, v) * 1.05)
        return best
    }

    /** strict = something else is playing audio on the phone (reel, video, music). */
    fun accept(d: Double, strict: Boolean): Boolean = d < 0 || d <= (if (strict) base + 0.12 else 0.8)

    /** The owner's own confident speech slowly sharpens the print. */
    fun adapt(v: DoubleArray, d: Double) {
        if (!enrolled || d < 0 || d > base * 0.6 || vecs.size >= 40) return
        vecs.add(v)
        recompute()
        if (vecs.size % 5 == 0) save()
    }

    fun parseVec(a: JSONArray?): DoubleArray? {
        if (a == null || a.length() < 32) return null
        return DoubleArray(a.length()) { a.getDouble(it) }
    }
}
