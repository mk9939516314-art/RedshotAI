package app.redshot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * Setup + status screen. After setup Redshot never needs a screen: it runs in the background.
 */
class MainActivity : Activity() {

    private val BG = Color.parseColor("#0A0A0F")
    private val CARD = Color.parseColor("#15151D")
    private val RED = Color.parseColor("#FF3B3B")
    private val GREEN = Color.parseColor("#2ECC71")
    private val MUTED = Color.parseColor("#8A8A99")
    private val TEXT = Color.parseColor("#F2F2F7")

    private class Step(val dot: TextView, val title: TextView, val button: Button, val ok: () -> Boolean)

    private val steps = ArrayList<Step>()
    private val main = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() { refresh(); main.postDelayed(this, 500) }
    }

    private lateinit var pill: TextView
    private lateinit var headline: TextView
    private lateinit var setupCard: LinearLayout
    private lateinit var setupTitle: TextView
    private lateinit var enrollBox: LinearLayout
    private lateinit var enrollText: TextView
    private lateinit var enrollBar: ProgressBar
    private lateinit var liveHeard: TextView
    private lateinit var liveSaid: TextView
    private lateinit var liveState: TextView
    private lateinit var details: TextView
    private lateinit var brainBtn: Button
    private var showDetails = false
    private lateinit var updCard: LinearLayout
    private lateinit var updTitle: TextView
    private lateinit var updMsg: TextView
    private lateinit var updBar: ProgressBar
    private lateinit var updBtn: Button

    private val enrollLines = listOf(
        "Aaj mausam bahut achha hai, main apne ghar se baahar ghoomne jaa raha hoon.",
        "Redshot, mummy ko phone laga aur phir mere liye ek achha sa gaana chala de.",
        "Kal subah saat baje mujhe utha dena, kyunki kal meri class jaldi shuru hoti hai.",
        "Mujhe aaj bahut saare kaam karne hain, isliye mera dhyaan rakhna aur yaad dilana.",
        "Mera naam Mayank hai aur main Jammu mein rehkar apni padhai karta hoon.",
        "Tum mujhe batao ki aaj ke din mujhe kaun kaun se kaam karne chahiye."
    )

    // ---------- small view helpers ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun bg(color: Int, radius: Int, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != 0) setStroke(dp(1), stroke)
        }

    private fun tv(s: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun card(parent: LinearLayout): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(CARD, 18, Color.parseColor("#22222E"))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(12)
            parent.addView(this, lp)
        }

    private fun pillButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label; isAllCaps = false; textSize = 13f
            setTextColor(Color.WHITE)
            background = bg(RED, 20)
            setPadding(dp(16), 0, dp(16), 0)
            minHeight = 0; minimumHeight = 0
            setOnClickListener { onClick() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(18), dp(44), dp(18), dp(30))
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(BG); isFillViewport = true; addView(root) })

        // ----- header -----
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(tv("REDSHOT", 26f, RED, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        pill = tv("...", 12f, Color.WHITE, true).apply {
            setPadding(dp(12), dp(5), dp(12), dp(5)); background = bg(MUTED, 14)
        }
        head.addView(pill)
        root.addView(head)
        headline = tv("", 14f, MUTED).apply { setPadding(0, dp(6), 0, 0) }
        root.addView(headline)

        // ----- app update -----
        updCard = card(root)
        updTitle = tv("APP UPDATE", 11f, MUTED, true)
        updCard.addView(updTitle)
        updMsg = tv("", 14f, TEXT).apply { setPadding(0, dp(6), 0, dp(8)) }
        updCard.addView(updMsg)
        updBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; visibility = View.GONE }
        updCard.addView(updBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)))
        updBtn = pillButton("Update karo") { startUpdate() }.apply { visibility = View.GONE }
        updCard.addView(updBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)).apply { topMargin = dp(10) })

        // ----- live card -----
        val live = card(root)
        live.addView(tv("ABHI KYA HO RAHA HAI", 11f, MUTED, true))
        liveState = tv("", 15f, TEXT, true).apply { setPadding(0, dp(6), 0, dp(8)) }
        live.addView(liveState)
        live.addView(tv("Maine suna", 11f, MUTED))
        liveHeard = tv("-", 14f, TEXT).apply { setPadding(0, dp(2), 0, dp(8)) }
        live.addView(liveHeard)
        live.addView(tv("Redshot ne kaha", 11f, MUTED))
        liveSaid = tv("-", 14f, TEXT).apply { setPadding(0, dp(2), 0, 0) }
        live.addView(liveSaid)

        // ----- how to talk -----
        val talk = card(root)
        talk.addView(tv("KAISE BOLNA HAI", 11f, MUTED, true))
        talk.addView(tv(
            "\"Redshot, mummy ko WhatsApp call laga\"\n" +
                "\"Redshot, chrome khol\"\n" +
                "\"Redshot, mere class ke hisaab se alarm laga\"\n" +
                "\"Redshot, reels apne aap scroll kar\"\n" +
                "Chup karane ke liye: \"Redshot chup ho ja\"  •  Jagane ke liye: \"jaag ja\"",
            13f, TEXT
        ).apply { setPadding(0, dp(8), 0, 0); setLineSpacing(0f, 1.2f) })

        // ----- setup card -----
        setupCard = card(root)
        setupTitle = tv("SETUP", 11f, MUTED, true)
        setupCard.addView(setupTitle)

        addStep("Mic + permissions (kaan)", { checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.READ_CALENDAR
                ), 1
            )
        }
        addStep("Accessibility (haath: apps chalana)", { accessibilityOn() }) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addStep("Battery: Unrestricted (band na ho)", {
            (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        }) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
        addStep("Notification access (sab apps ke notification)", { notifOn() }) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        addStep("Poora access (SMS, call log, location, files)", {
            listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CALL_LOG,
                Manifest.permission.WRITE_CALENDAR, Manifest.permission.ACCESS_FINE_LOCATION)
                .all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } &&
                (Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager())
        }) {
            val need = listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CALL_LOG,
                Manifest.permission.WRITE_CALENDAR, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (need.isNotEmpty()) {
                requestPermissions(need.toTypedArray(), 2)
                headline.text = "SMS/call log 'allow' na dikhe to: Settings > Apps > Redshot > 3 dot > 'Allow restricted settings', phir dobara dabao."
            } else if (Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        addStep("Meri awaaz sikhao (sirf teri awaaz pe kaam)", { Voiceprint.enrolled }) {
            if (!RedshotService.spkReady) {
                headline.text = "Awaaz model load ho raha hai, 10 second baad dobara dabao."
            } else {
                RedshotService.startEnroll(6)
            }
        }

        // ----- enrollment box -----
        enrollBox = card(root)
        enrollBox.addView(tv("AWAAZ SIKHANA", 11f, MUTED, true))
        enrollText = tv("", 15f, Color.parseColor("#FFD27D")).apply { setPadding(0, dp(8), 0, dp(8)); setLineSpacing(0f, 1.2f) }
        enrollBox.addView(enrollText)
        enrollBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 6 }
        enrollBox.addView(enrollBar)
        enrollBox.visibility = View.GONE

        // ----- details -----
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val lp0 = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)).apply { topMargin = dp(14); rightMargin = dp(8) }
        row.addView(Button(this).apply {
            text = "Details"; isAllCaps = false; textSize = 12f; setTextColor(TEXT)
            background = bg(CARD, 19, Color.parseColor("#2A2A38"))
            setPadding(dp(14), 0, dp(14), 0); minHeight = 0; minimumHeight = 0
            setOnClickListener { showDetails = !showDetails; details.visibility = if (showDetails) View.VISIBLE else View.GONE }
        }, lp0)
        brainBtn = Button(this).apply {
            text = "Brain check"; isAllCaps = false; textSize = 12f; setTextColor(TEXT)
            background = bg(CARD, 19, Color.parseColor("#2A2A38"))
            setPadding(dp(14), 0, dp(14), 0); minHeight = 0; minimumHeight = 0
            setOnClickListener { brainCheck() }
        }
        row.addView(brainBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)).apply { topMargin = dp(14); rightMargin = dp(8) })
        row.addView(Button(this).apply {
            text = "Chat"; isAllCaps = false; textSize = 12f; setTextColor(TEXT)
            background = bg(CARD, 19, Color.parseColor("#2A2A38"))
            setPadding(dp(14), 0, dp(14), 0); minHeight = 0; minimumHeight = 0
            setOnClickListener { startActivity(android.content.Intent(this@MainActivity, ChatActivity::class.java)) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)).apply { topMargin = dp(14) })
        root.addView(row)

        details = tv("", 12f, MUTED).apply {
            visibility = View.GONE; setPadding(dp(4), dp(10), 0, 0); setLineSpacing(0f, 1.15f)
            typeface = Typeface.MONOSPACE
        }
        root.addView(details)
        root.addView(tv("Device: " + Config.deviceId(this).take(8), 11f, Color.parseColor("#555566")).apply { setPadding(0, dp(18), 0, 0) })
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            try { startForegroundService(Intent(this, RedshotService::class.java)) } catch (e: Exception) {}
        }
        main.post(refresher)
        if (Updater.status != "downloading" && Updater.status != "installing") Thread { Updater.check(applicationContext) }.start()
    }

    private fun startUpdate() {
        if (Updater.status == "downloading" || Updater.status == "installing") return
        Thread {
            val ctx = applicationContext
            if (Updater.status != "ready") { if (Updater.check(ctx, true) == null && Updater.status != "ready") return@Thread; if (Updater.download(ctx) == null) return@Thread }
            Updater.install(ctx)
        }.start()
    }

    private fun refreshUpdate() {
        val st = Updater.status
        val i = Updater.info
        val mb = { b: Long -> "%.1f".format(b / 1048576.0) }
        when (st) {
            "downloading" -> {
                val d = Updater.done.get(); val t = maxOf(1L, Updater.total)
                val sp = Updater.speedBps; val eta = if (sp > 0) ((t - d) / sp) else 0
                updMsg.text = "Download: ${mb(d)} / ${mb(t)} MB  •  ${"%.1f".format(sp / 1048576.0)} MB/s" + (if (sp > 0) "  •  ${eta}s baaki" else "")
                updBar.visibility = View.VISIBLE; updBar.progress = (d * 1000 / t).toInt(); updBtn.visibility = View.GONE
            }
            "installing" -> { updMsg.text = Updater.message; updBar.visibility = View.VISIBLE; updBar.isIndeterminate = true; updBtn.visibility = View.GONE }
            "available", "ready" -> {
                updMsg.text = "Naya version ${i?.tag ?: ""} taiyaar" + (if (st == "ready") " (download ho chuka)" else " (${mb(i?.size ?: 0)} MB)") + (if (!i?.notes.isNullOrBlank()) "\n${i?.notes}" else "")
                updBar.visibility = View.GONE; updBar.isIndeterminate = false
                updBtn.visibility = View.VISIBLE; updBtn.text = if (st == "ready") "Abhi install karo" else "Update karo"
            }
            "error" -> { updMsg.text = Updater.message; updBar.visibility = View.GONE; updBtn.visibility = if (i != null) View.VISIBLE else View.GONE; updBtn.text = "Dobara try karo" }
            "checking" -> { updMsg.text = "Naya version dhundh raha hoon..."; updBar.visibility = View.GONE; updBtn.visibility = View.GONE }
            else -> { updMsg.text = Updater.message.ifBlank { "Version ${Updater.currentName(this)}" }; updBar.visibility = View.GONE; updBtn.visibility = View.GONE }
        }
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(refresher)
    }

    private fun setPill(text: String, color: Int) {
        pill.text = text
        pill.background = bg(color, 14)
    }

    private fun refresh() {
        refreshUpdate()
        var done = 0
        for (s in steps) {
            val ok = s.ok()
            if (ok) done++
            s.dot.text = if (ok) "✓" else "!"
            s.dot.background = bg(if (ok) GREEN else RED, 12)
            s.button.visibility = if (ok) View.GONE else View.VISIBLE
        }
        val all = done == steps.size
        setupTitle.text = if (all) "SETUP: SAB READY ✓" else "SETUP: $done/${steps.size} ho gaya"
        // finished steps stay as one quiet line each; pending ones show a button

        // enrollment
        val enrolling = RedshotService.enrolling
        enrollBox.visibility = if (enrolling || RedshotService.enrollMsg.isNotBlank()) View.VISIBLE else View.GONE
        if (!enrolling && RedshotService.enrollMsg.isNotBlank()) enrollText.text = RedshotService.enrollMsg
        if (enrolling) {
            val have = RedshotService.enrollHave.coerceIn(0, 5)
            enrollText.text = "Ye vakya normal awaaz mein poora bol:\n\n\"" + enrollLines[have] + "\"\n\n(" + RedshotService.enrollHave + "/6 ho gaye)" +
                (if (RedshotService.enrollMsg.isNotBlank()) "\n\n" + RedshotService.enrollMsg else "")
            enrollBar.progress = RedshotService.enrollHave
        }

        // top pill + headline
        val live = RedshotService.live
        when {
            RedshotService.muted -> { setPill("CHUP", MUTED); headline.text = "Chup mode. Jagane ke liye bol: \"Redshot jaag ja\"." }
            live.startsWith("live: bol") -> { setPill("BOL RAHA HAI", RED); headline.text = "Redshot jawab de raha hai." }
            live.startsWith("live") -> { setPill("LIVE", GREEN); headline.text = "Baat-cheet chal rahi hai, bol." }
            RedshotService.state.startsWith("sun") -> { setPill("SUN RAHA HAI", GREEN); headline.text = "Bas \"Redshot\" bol, aur kaam bata." }
            else -> { setPill("...", MUTED); headline.text = RedshotService.state }
        }

        liveState.text = (if (RedshotService.muted) "Chup mode" else RedshotService.state) +
            (if (live.isNotBlank() && live != "band") "  •  " + live.removePrefix("live: ") else "")
        liveHeard.text = RedshotService.heard.ifBlank { "-" }
        liveSaid.text = RedshotService.liveSaid.ifBlank { "-" }

        if (showDetails) {
            val brain = if (Config.brainUrl(this).contains("REPLACE_BRAIN_URL")) "address set nahi" else Config.brainUrl(this)
            details.text = "awaaz level: " + RedshotService.level + " (trigger " + RedshotService.threshold + ")" +
                "\nrasta: " + RedshotService.route +
                "\nvoice: " + RedshotService.voice +
                "\nkaam: " + RedshotService.lastAction +
                "\nmeri awaaz distance: " + (if (RedshotService.spkDist >= 0) "%.2f".format(RedshotService.spkDist) else "-") +
                " (limit " + "%.2f".format(Voiceprint.base) + ")" +
                "\nmind: " + RedshotService.mindLast +
                "\near error: " + RedshotService.earError.ifBlank { "-" } +
                "\nspk frames: " + RedshotService.spkFramesLast + " model: " + RedshotService.spkReady +
                "\nbrain: " + brain
        }
    }

    private fun brainCheck() {
        brainBtn.text = "Check ho raha hai..."
        Thread {
            val r = Brain.post(this, "device/ping", JSONObject().put("hello", "redshot"), 15000)
            main.post {
                brainBtn.text = if (r != null) "Brain OK ✓" else "Brain tak nahi pahunch paya"
                main.postDelayed({ brainBtn.text = "Brain check" }, 3000)
            }
        }.start()
    }

    private fun accessibilityOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.contains("$packageName/")
    }

    private fun notifOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return s.contains(packageName)
    }

    private fun addStep(title: String, ok: () -> Boolean, action: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        val dot = tv("!", 13f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; background = bg(RED, 12)
        }
        row.addView(dot, LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(12) })
        val t = tv(title, 14f, TEXT)
        row.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val b = pillButton("Kar") { action() }
        row.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)))
        setupCard.addView(row)
        steps.add(Step(dot, t, b, ok))
    }
}
