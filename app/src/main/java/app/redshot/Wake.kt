package app.redshot

import java.util.Locale

/**
 * Wake word "Redshot", however the offline recogniser happens to write it
 * ("रेड शॉट", "रेडशॉट", "red shot", "redshoot", ...). Matching is by sound (consonant skeleton), not spelling.
 */
object Wake {
    private const val TARGET = "rdst"

    private fun tokens(s: String): List<String> =
        s.lowercase(Locale.ROOT).split(Regex("[\\s,.!?।]+")).filter { it.isNotEmpty() }

    private fun matches(sk: String): Boolean {
        if (sk.length < 4 || sk.length > 6) return false
        return Apps.dist(sk, TARGET) <= 1
    }

    /** Returns the sentence with the wake word removed, or null if the wake word was not said. */
    fun strip(text: String): String? {
        val ws = tokens(text)
        var i = 0
        while (i < ws.size) {
            if (matches(Apps.skel(ws[i]))) {
                return (ws.subList(0, i) + ws.subList(i + 1, ws.size)).joinToString(" ")
            }
            if (i + 1 < ws.size && matches(Apps.skel(ws[i] + ws[i + 1]))) {
                return (ws.subList(0, i) + ws.subList(i + 2, ws.size)).joinToString(" ")
            }
            i++
        }
        return null
    }

    /**
     * Wake decision + cleaned command. level = how many of the two keyword spotters fired (0..2).
     * Returns null when "Redshot" was not said.
     */
    fun detect(text: String, level: Int): String? {
        Wake.strip(text)?.let { return it }
        if (level == 0) return null
        // The spotter heard it but the free recogniser garbled it ("रेड शौच"): drop the garbled bit
        val ws = tokens(text).toMutableList()
        val i = ws.indexOfFirst { Apps.skel(it) == "rd" }
        if (i >= 0) {
            ws.removeAt(i)
            if (i < ws.size && (ws[i].startsWith("श") || ws[i].startsWith("sh") || ws[i].startsWith("स"))) ws.removeAt(i)
        } else if (ws.isNotEmpty() && (ws[0].startsWith("श") || ws[0].startsWith("sh"))) {
            ws.removeAt(0)
        } else if (ws.size == 1 && ws[0].length <= 5) {
            ws.clear()   // only noise next to the spotted name
        }
        return ws.joinToString(" ")
    }
}
