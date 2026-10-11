package app.redshot

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * Fast lane: simple commands are understood on the phone itself, no internet, near-instant.
 * Anything it does not recognise returns false and goes to the brain.
 */
object Router {
    private val openWords = listOf(
        "khol", "kholo", "kholna", "kholdo", "open", "chalu", "chalao", "start", "launch", "chala",
        "खोल", "खोलो", "खोलना", "खोलदो", "चालू", "चलाओ", "चला", "ओपन", "स्टार्ट"
    )
    private val onWords = listOf("on", "chalu", "jala", "jalao", "jalado", "चालू", "जला", "जलाओ", "ऑन", "आन")
    private val offWords = listOf("off", "band", "bujha", "bujhao", "बंद", "बुझा", "बुझाओ", "ऑफ")
    private val upWords = listOf("up", "badha", "badhao", "zyada", "jyada", "increase", "tez", "बढ़ा", "बढ़ाओ", "ज्यादा", "तेज")
    private val downWords = listOf("down", "kam", "ghata", "ghatao", "decrease", "dheere", "कम", "घटा", "घटाओ", "धीमा", "धीरे")

    private val aliases = mapOf(
        "क्रोम" to "chrome", "व्हाट्सएप" to "whatsapp", "व्हाट्सऐप" to "whatsapp", "वाट्सएप" to "whatsapp",
        "यूट्यूब" to "youtube", "इंस्टाग्राम" to "instagram", "कैमरा" to "camera", "गैलरी" to "gallery",
        "सेटिंग्स" to "settings", "सेटिंग" to "settings", "फोन" to "phone", "मैसेज" to "messages",
        "जीमेल" to "gmail", "मैप्स" to "maps", "स्पॉटिफाई" to "spotify", "टेलीग्राम" to "telegram",
        "फेसबुक" to "facebook", "डिस्कॉर्ड" to "discord", "कैलकुलेटर" to "calculator", "घड़ी" to "clock",
        "टॉर्च" to "torch", "टार्च" to "torch", "फ्लैशलाइट" to "torch", "वॉल्यूम" to "volume",
        "आवाज़" to "volume", "आवाज" to "volume", "होम" to "home", "वापस" to "back", "पीछे" to "back"
    )

    private fun words(s: String): List<String> =
        s.split(Regex("[\\s,.!?।]+")).filter { it.isNotEmpty() }

    private fun hasAny(ws: List<String>, list: List<String>) = ws.any { it in list }

    fun handle(ctx: Context, raw: String): Boolean {
        var t = raw.lowercase(Locale.ROOT).trim()
        for ((k, v) in aliases) t = t.replace(k, v)
        val ws = words(t)
        if (ws.isEmpty() || ws.size > 7) return false

        // torch
        if ("torch" in ws || "flashlight" in ws || ("flash" in ws && "light" in ws)) {
            val on = hasAny(ws, onWords)
            val off = hasAny(ws, offWords)
            Executor.run(ctx, JSONObject().put("type", "torch").apply {
                if (on && !off) put("on", true) else if (off && !on) put("on", false)
            })
            return true
        }

        // volume
        if ("volume" in ws) {
            val up = hasAny(ws, upWords)
            val down = hasAny(ws, downWords)
            if (up || down) {
                Executor.run(ctx, JSONObject().put("type", "volume").put("dir", if (up) "up" else "down"))
                return true
            }
        }

        // navigation (only very short sentences, so normal talk about "home" does not trigger it)
        if (ws.size <= 3) {
            val nav = when {
                "home" in ws -> "home"
                "back" in ws || "wapas" in ws -> "back"
                "recent" in ws || "recents" in ws -> "recents"
                else -> null
            }
            if (nav != null) {
                Executor.run(ctx, JSONObject().put("type", "global").put("name", nav))
                return true
            }
        }

        // open an app
        if (hasAny(ws, openWords)) {
            val app = Apps.findIn(ctx, t)
            if (app != null && Apps.launch(ctx, app)) return true
        }
        return false
    }
}
