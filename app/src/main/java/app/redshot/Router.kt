package app.redshot

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import org.json.JSONObject
import java.util.Calendar
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
        "आवाज़" to "volume", "आवाज" to "volume", "होम" to "home", "वापस" to "back", "पीछे" to "back",
        "टाइमर" to "timer", "स्क्रीनशॉट" to "screenshot", "स्क्रॉल" to "scroll", "बैटरी" to "battery",
        "वाईफाई" to "wifi", "वाइफाई" to "wifi", "ब्लूटूथ" to "bluetooth", "हॉटस्पॉट" to "hotspot",
        "म्यूट" to "mute", "अनम्यूट" to "unmute", "नोटिफिकेशन" to "notification", "लॉक" to "lock",
        "पॉज़" to "pause", "पॉज" to "pause", "प्ले" to "play", "नेक्स्ट" to "next"
    )

    private val numberWords = mapOf(
        "ek" to 1, "एक" to 1, "do" to 2, "दो" to 2, "teen" to 3, "तीन" to 3, "char" to 4, "chaar" to 4, "चार" to 4,
        "paanch" to 5, "panch" to 5, "पांच" to 5, "पाँच" to 5, "chhe" to 6, "chah" to 6, "che" to 6, "छह" to 6, "छे" to 6, "छः" to 6,
        "saat" to 7, "सात" to 7, "aath" to 8, "आठ" to 8, "nau" to 9, "नौ" to 9, "das" to 10, "दस" to 10,
        "gyarah" to 11, "ग्यारह" to 11, "barah" to 12, "बारह" to 12, "pandrah" to 15, "पंद्रह" to 15,
        "bees" to 20, "बीस" to 20, "pachees" to 25, "पच्चीस" to 25, "tees" to 30, "तीस" to 30,
        "chalis" to 40, "चालीस" to 40, "paintalis" to 45, "पैंतालीस" to 45, "pachaas" to 50, "pachas" to 50, "पचास" to 50,
        "saath" to 60, "साठ" to 60, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "fifteen" to 15, "twenty" to 20, "thirty" to 30
    )

    private fun words(s: String): List<String> =
        s.split(Regex("[\\s,.!?।]+")).filter { it.isNotEmpty() }

    private fun hasAny(ws: List<String>, list: List<String>) = ws.any { it in list }

    private fun num(w: String): Int? = w.toIntOrNull() ?: numberWords[w]

    fun isUnmute(raw: String): Boolean {
        val ws = words(raw.lowercase(Locale.ROOT))
        return hasAny(ws, listOf("unmute", "अनम्यूट", "jaag", "jag", "जाग", "uth", "उठ", "wapas", "वापस")) || ("redshot" in ws)
    }

    /**
     * Returns true when the sentence was fully handled here.
     * say(text, lang) speaks; setMuted(true/false) switches listening off/on.
     */
    fun handle(
        ctx: Context,
        raw: String,
        muted: Boolean,
        say: (String, String) -> Unit,
        setMuted: (Boolean) -> Unit
    ): Boolean {
        var t = raw.lowercase(Locale.ROOT).trim()
        for ((k, v) in aliases) t = t.replace(k, v)
        val ws = words(t)
        if (ws.isEmpty()) return false

        // while muted nothing goes anywhere, except the wake-up phrase
        if (muted) {
            if (isUnmute(raw) || hasAny(ws, listOf("unmute", "jaag", "jag", "uth", "wapas", "back"))) {
                setMuted(false)
                say("हाँ भाई, बोल।", "hi-IN")
            }
            return true
        }

        if (ws.size > 9) return false

        // mute: "chup ho ja", "mute", "so ja"
        if ((hasAny(ws, listOf("chup", "चुप", "mute", "shant", "शांत")) && ws.size <= 4) ||
            ("so" in ws && "ja" in ws) || ("सो" in ws && "जा" in ws)
        ) {
            setMuted(true)
            say("ठीक है, चुप हो गया। जगाना हो तो बोलना जाग जा।", "hi-IN")
            return true
        }

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

        // what time is it
        if ((("kitne" in ws || "कितने" in ws) && ("baje" in ws || "बजे" in ws)) ||
            ("time" in ws && hasAny(ws, listOf("kya", "kitna", "batao", "bata", "what", "क्या", "बताओ", "बता"))) ||
            ("टाइम" in ws && hasAny(ws, listOf("क्या", "कितना", "बताओ", "बता")))
        ) {
            val c = Calendar.getInstance()
            var h = c.get(Calendar.HOUR)
            if (h == 0) h = 12
            val m = c.get(Calendar.MINUTE)
            say(if (m == 0) "अभी $h बजे हैं।" else "अभी $h बजकर $m मिनट हुए हैं।", "hi-IN")
            return true
        }

        // battery
        if ("battery" in ws && ws.size <= 6 && !hasAny(ws, listOf("saver", "setting", "settings", "सेवर"))) {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val st = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            say("बैटरी $pct प्रतिशत है" + (if (charging) ", चार्ज हो रही है।" else "।"), "hi-IN")
            return true
        }

        // timer: "5 minute ka timer", "do ghante ka timer"
        if ("timer" in ws) {
            var total = 0
            val parts = ArrayList<String>()
            for (i in 1 until ws.size) {
                val n = num(ws[i - 1]) ?: continue
                when (ws[i]) {
                    "minute", "minutes", "min", "mint", "मिनट" -> { total += n * 60; parts.add("$n मिनट") }
                    "second", "seconds", "sec", "सेकंड", "सेकण्ड", "सेकेंड" -> { total += n; parts.add("$n सेकंड") }
                    "ghanta", "ghante", "hour", "hours", "घंटा", "घंटे" -> { total += n * 3600; parts.add("$n घंटे") }
                }
            }
            if (total > 0) {
                Executor.run(ctx, JSONObject().put("type", "timer").put("seconds", total).put("label", "Redshot"))
                say("ठीक है, " + parts.joinToString(" ") + " का टाइमर लगा दिया।", "hi-IN")
                return true
            }
            return false // unclear length -> let the brain ask / decide
        }

        // media controls (short sentences only, "play <song name>" goes to the brain)
        if (ws.size <= 4) {
            val musicNoun = hasAny(ws, listOf("gaana", "gana", "song", "music", "गाना", "गाने", "म्यूजिक"))
            val media = when {
                "pause" in ws || (musicNoun && hasAny(ws, offWords)) -> "pause"
                ws.size == 1 && (ws[0] == "play" || ws[0] == "resume") -> "play"
                musicNoun && hasAny(ws, listOf("chalao", "chala", "play", "चलाओ", "चला")) -> "play"
                hasAny(ws, listOf("next", "agla", "अगला", "skip")) -> "next"
                hasAny(ws, listOf("previous", "prev", "pichla", "पिछला")) -> "previous"
                else -> null
            }
            if (media != null) {
                Executor.run(ctx, JSONObject().put("type", "media").put("name", media))
                return true
            }
        }

        // scroll
        val downW = listOf("down", "neeche", "niche", "नीचे")
        val upW = listOf("up", "upar", "ऊपर", "उपर")
        if (("scroll" in ws && ws.size <= 5) || (ws.size == 1 && (ws[0] in downW || ws[0] in upW))) {
            val dir = if (hasAny(ws, upW)) "up" else "down"
            Executor.run(ctx, JSONObject().put("type", "scroll").put("dir", dir))
            return true
        }

        // screen utilities
        if ("screenshot" in ws && ws.size <= 5) {
            Executor.run(ctx, JSONObject().put("type", "global").put("name", "screenshot"))
            return true
        }
        if ("notification" in ws || "notifications" in ws) {
            if (ws.size <= 5) {
                Executor.run(ctx, JSONObject().put("type", "global").put("name", "notifications"))
                return true
            }
        }
        if ("lock" in ws && ("phone" in ws || "screen" in ws || ws.size <= 2)) {
            Executor.run(ctx, JSONObject().put("type", "global").put("name", "lock"))
            return true
        }

        // settings pages: "wifi settings", "bluetooth khol"
        val page = when {
            "wifi" in ws -> "wifi"
            "bluetooth" in ws -> "bluetooth"
            "hotspot" in ws -> "hotspot"
            else -> null
        }
        if (page != null && ws.size <= 5 && (hasAny(ws, openWords) || "settings" in ws || hasAny(ws, onWords) || hasAny(ws, offWords))) {
            Executor.run(ctx, JSONObject().put("type", "settings").put("name", page))
            if (hasAny(ws, onWords) || hasAny(ws, offWords)) {
                say("Android ab ye khud on-off nahi karne deta, इसलिए settings खोल दी है, वहाँ से कर ले।", "hi-IN")
            }
            return true
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
        if (hasAny(ws, openWords) && ws.size <= 7) {
            val app = Apps.findIn(ctx, t) ?: Apps.findSoundAlike(ctx, ws.filter { it !in openWords })
            if (app != null && Apps.launch(ctx, app)) return true
        }
        return false
    }
}
