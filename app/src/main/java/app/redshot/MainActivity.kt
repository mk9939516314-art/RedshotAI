package app.redshot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * One-time setup screen. After setup Redshot never shows a screen: it runs in the background.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private val steps = ArrayList<Pair<TextView, () -> Boolean>>()
    private val main = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() { refresh(); main.postDelayed(this, 400) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0B0F"))
            setPadding(48, 96, 48, 48)
        }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(text("REDSHOT", 28f, "#FF2D2D", true))
        root.addView(text("Ek baar ye 3 cheezein on kar de. Uske baad ye screen dobara nahi aayegi.\n\nBolna: \"Redshot, mummy ko call laga\" / \"Redshot, chrome khol\" / \"Redshot, song play kar\" / \"Redshot, kuch dikkat ho gayi\". Chup karane ke liye: \"Redshot chup ho ja\", jagane ke liye: \"jaag ja\".", 14f, "#BBBBBB", false))

        addStep(root, "1. Mic (kaan)",
            { checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.CALL_PHONE,
                    Manifest.permission.READ_CONTACTS
                ), 1
            )
        }
        addStep(root, "2. Accessibility (haath: background se apps kholna)", { accessibilityOn() }) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addStep(root, "3. Battery: Unrestricted (taaki band na ho)", {
            (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        }) {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }

        status = text("", 14f, "#FFFFFF", false)
        root.addView(status)

        val check = Button(this).apply {
            text = "Brain check"
            setOnClickListener { brainCheck() }
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.START
        root.addView(check, lp)

        root.addView(text("Device: " + Config.deviceId(this).take(8), 12f, "#666666", false))
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            try { startForegroundService(Intent(this, RedshotService::class.java)) } catch (e: Exception) {}
        }
        main.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(refresher)
    }

    private fun refresh() {
        for ((label, ok) in steps) {
            val base = label.text.toString().removePrefix("✅ ").removePrefix("❌ ")
            label.text = (if (ok()) "✅ " else "❌ ") + base
        }
        val brain = if (Config.brainUrl(this).contains("REPLACE_BRAIN_URL")) "Brain: address set nahi" else "Brain: " + Config.brainUrl(this)
        status.text = "Kaan: " + RedshotService.state +
            "\nAwaaz level: " + RedshotService.level + " (trigger: " + RedshotService.threshold + ")" +
            "\nSuna: " + RedshotService.heard +
            "\nRasta: " + RedshotService.route +
            "\nAwaaz (voice): " + RedshotService.voice +
            (if (RedshotService.muted) "\nMODE: chup (mute)" else "") +
            "\nKaam: " + RedshotService.lastAction +
            "\n" + brain
    }

    private fun brainCheck() {
        status.text = "Brain check ho raha hai..."
        Thread {
            val r = Brain.post(this, "device/ping", JSONObject().put("hello", "redshot"), 15000)
            main.post {
                status.text = if (r != null) "Brain OK: " + r.optString("say", "ok") else "Brain tak nahi pahunch paya"
            }
        }.start()
    }

    private fun accessibilityOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.contains("$packageName/")
    }

    private fun addStep(root: LinearLayout, title: String, ok: () -> Boolean, action: () -> Unit) {
        val label = text("❌ $title", 15f, "#FFFFFF", false)
        root.addView(label)
        val b = Button(this).apply {
            text = "Kar"
            setOnClickListener { action() }
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.START
        root.addView(b, lp)
        steps.add(Pair(label, ok))
    }

    private fun text(s: String, size: Float, color: String, bold: Boolean): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(Color.parseColor(color))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 24, 0, 8)
        }
}
