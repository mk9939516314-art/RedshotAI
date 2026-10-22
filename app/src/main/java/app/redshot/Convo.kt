package app.redshot

/** Short per-person chat memory, so Redshot's replies while the owner is away are a real conversation and not one fixed line. */
object Convo {
    private val threads = LinkedHashMap<String, ArrayDeque<String>>()
    private const val MAX_LINES = 14
    private const val MAX_PEOPLE = 40

    private fun k(who: String) = who.trim().lowercase().take(60)

    /** speaker: "them" (the other person wrote) or "me" (Redshot answered for the owner). */
    @Synchronized fun add(who: String, speaker: String, text: String) {
        if (who.isBlank() || text.isBlank()) return
        val q = threads.getOrPut(k(who)) { ArrayDeque() }
        val line = (if (speaker == "me") "Redshot: " else who.trim().take(40) + ": ") + text.replace('\n', ' ').trim().take(280)
        if (q.lastOrNull() == line) return            // the same notification posted twice
        q.addLast(line)
        while (q.size > MAX_LINES) q.removeFirst()
        while (threads.size > MAX_PEOPLE) threads.remove(threads.keys.first())
    }

    @Synchronized fun thread(who: String): List<String> = threads[k(who)]?.toList() ?: emptyList()

    @Synchronized fun clear() = threads.clear()
}
