package app.redshot

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** One shared diary of everything Redshot did on the phone (any brain: live voice, proactive mind, local router). */
object ActionLog {
    private val items = ArrayDeque<String>()
    private val f = SimpleDateFormat("h:mm a", Locale.ENGLISH)

    @Synchronized fun add(text: String) {
        items.addFirst(f.format(Date()) + " - " + text.take(220))
        while (items.size > 40) items.removeLast()
    }

    @Synchronized fun recent(n: Int = 15): String =
        if (items.isEmpty()) "(abhi tak kuch nahi kiya)" else items.take(n).joinToString("\n") { "- $it" }
}

/** "Main tuition ja raha hoon, meri jagah sambhal" mode. */
object AwayMode {
    private const val P = "away"
    @Volatile var note: String = ""
    @Volatile var until: Long = 0L
    private var ctx: android.content.Context? = null

    fun load(c: android.content.Context) {
        ctx = c.applicationContext
        val sp = c.getSharedPreferences(P, android.content.Context.MODE_PRIVATE)
        note = sp.getString("note", "") ?: ""
        until = sp.getLong("until", 0L)
    }

    val active: Boolean get() = note.isNotBlank() && System.currentTimeMillis() < until

    fun set(n: String, minutes: Int) {
        note = n
        until = System.currentTimeMillis() + minutes.coerceIn(5, 24 * 60) * 60_000L
        ctx?.getSharedPreferences(P, android.content.Context.MODE_PRIVATE)?.edit()?.putString("note", note)?.putLong("until", until)?.apply()
    }

    fun clear() { note = ""; until = 0; ctx?.getSharedPreferences(P, android.content.Context.MODE_PRIVATE)?.edit()?.clear()?.apply() }

    fun describe(): String {
        if (!active) return ""
        val left = ((until - System.currentTimeMillis()) / 60_000L).toInt()
        return "$note (abhi se lagbhag $left minute tak)"
    }
}
