package app.redshot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * One-time setup screen. After setup Redshot never shows a screen: it runs in the background.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private val steps = ArrayList<Pair<TextView, () -> Boolean>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0B0F"))
            setPadding(48, 96, 48, 48)
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        root.addView(text("REDSHOT", 28f, "#FF2D2D", true))
        root.addView(text("Ek baar ye 5 cheezein on kar de, phir ye screen dobara nahi aayegi.", 14f, "#BBBBBB", false))
        status = text("", 14f, "#FFFFFF", false)

        addStep(root, "1. Mic permission",
            { checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.CALL_PHONE
                ), 1
            )
        }
        addStep(root, "2. Accessibility (haath: apps kholna, tap, type)", { accessibilityOn() }) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addStep(root, "3. Display over other apps", { Settings.canDrawOverlays(this) }) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        addStep(root, "4. Battery: Unrestricted", {
            (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        }) {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }
        addStep(root, "5. Redshot chalu kar", { false }) {
            startForegroundService(Intent(this, RedshotService::class.java))
            status.text = "Redshot chalu hai. Ab bol kuch bhi."
        }

        root.addView(status)
        root.addView(text("Device: " + Config.deviceId(this).take(8), 12f, "#666666", false))
    }

    override fun onResume() {
        super.onResume()
        for ((label, ok) in steps) {
            val base = label.text.toString().removePrefix("✅ ").removePrefix("❌ ")
            label.text = (if (ok()) "✅ " else "❌ ") + base
        }
        status.text = if (Config.brainUrl(this).contains("REPLACE_BRAIN_URL")) "Brain ka address set nahi hai." else ""
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
            visibility = View.VISIBLE
        }
}
