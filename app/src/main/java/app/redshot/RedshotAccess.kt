package app.redshot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The hands: runs in the background without any screen, can start apps, tap, type and navigate.
 */
class RedshotAccess : AccessibilityService() {

    companion object {
        @Volatile
        var instance: RedshotAccess? = null

        @Volatile
        var currentPackage: String = ""
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Try to bring the listener up as soon as the system connects us (e.g. after reboot).
        try {
            startForegroundService(Intent(this, RedshotService::class.java))
        } catch (e: Exception) {
            // Android may refuse starting the microphone service from the background; the user opens Redshot once then.
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { currentPackage = it }
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    fun global(name: String): Boolean = when (name) {
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        "quick_settings" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        "lock" -> if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) else false
        "screenshot" -> if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT) else false
        else -> false
    }

    /** Scroll the page in front of the user. dir: down (see more below) / up. */
    fun scroll(dir: String): Boolean {
        val down = dir == "down"
        val root = rootInActiveWindow
        if (root != null) {
            val node = findScrollable(root)
            if (node != null) {
                val act = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                if (node.performAction(act)) return true
            }
        }
        // fallback: a swipe gesture in the middle of the screen
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val y1 = dm.heightPixels * (if (down) 0.72f else 0.28f)
        val y2 = dm.heightPixels * (if (down) 0.28f else 0.72f)
        val p = Path().apply { moveTo(x, y1); lineTo(x, y2) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 260)).build()
        return dispatchGesture(g, null, null)
    }

    private fun findScrollable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isScrollable) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            findScrollable(c)?.let { return it }
        }
        return null
    }

    fun tapText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (n in nodes) {
            var x: AccessibilityNodeInfo? = n
            while (x != null && !x.isClickable) x = x.parent
            if (x != null && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            // not clickable by itself: tap its centre
            val r = android.graphics.Rect()
            n.getBoundsInScreen(r)
            if (!r.isEmpty && tapAt(r.exactCenterX(), r.exactCenterY())) return true
        }
        return false
    }

    /** Everything readable on the current screen as plain text ([btn] = clickable). */
    fun readScreen(maxChars: Int = 3500): String {
        val root = rootInActiveWindow ?: return "(screen padh nahi paya: koi window active nahi)"
        val sb = StringBuilder()
        sb.append("app: ").append(root.packageName ?: currentPackage).append('\n')
        var count = 0
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (sb.length >= maxChars || count > 220 || depth > 30) return
            if (!n.isVisibleToUser) return
            val t = (n.text?.toString() ?: "").trim()
            val d = (n.contentDescription?.toString() ?: "").trim()
            val label = if (t.isNotEmpty()) t else d
            if (label.isNotEmpty() || (n.isClickable && n.childCount == 0)) {
                count++
                if (n.isClickable) sb.append("[btn] ")
                if (n.isEditable) sb.append("[input] ")
                sb.append(if (label.isNotEmpty()) label.take(160) else "(icon)").append('\n')
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                walk(c, depth + 1)
            }
        }
        walk(root, 0)
        return sb.toString().take(maxChars)
    }

    fun tapPercent(xp: Int, yp: Int): Boolean {
        val dm = resources.displayMetrics
        val x = dm.widthPixels * xp.coerceIn(0, 100) / 100f
        val y = dm.heightPixels * yp.coerceIn(0, 100) / 100f
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build()
        return dispatchGesture(g, null, null)
    }

    /** dir = where the content moves: up (next reel / more below), down, left, right. */
    fun swipe(dir: String): Boolean {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        val (x1, y1, x2, y2) = when (dir) {
            "down" -> listOf(w * 0.5f, h * 0.3f, w * 0.5f, h * 0.75f)
            "left" -> listOf(w * 0.8f, h * 0.5f, w * 0.2f, h * 0.5f)
            "right" -> listOf(w * 0.2f, h * 0.5f, w * 0.8f, h * 0.5f)
            else -> listOf(w * 0.5f, h * 0.75f, w * 0.5f, h * 0.3f)
        }
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 220)).build()
        return dispatchGesture(g, null, null)
    }

    fun tapAt(x: Float, y: Float): Boolean {
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build()
        return dispatchGesture(g, null, null)
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Several fingers at once / timed strokes (games, pinch, joystick + look). Coordinates in percent. stroke: x1,y1,x2,y2,start_ms,dur_ms */
    fun gesture(strokes: List<DoubleArray>): Boolean {
        val dm = resources.displayMetrics
        val b = GestureDescription.Builder()
        for (s in strokes.take(10)) {
            val p = Path().apply {
                moveTo((dm.widthPixels * s[0] / 100.0).toFloat(), (dm.heightPixels * s[1] / 100.0).toFloat())
                if (s[0] != s[2] || s[1] != s[3]) lineTo((dm.widthPixels * s[2] / 100.0).toFloat(), (dm.heightPixels * s[3] / 100.0).toFloat())
            }
            b.addStroke(GestureDescription.StrokeDescription(p, s[4].toLong().coerceAtLeast(0), s[5].toLong().coerceIn(1, 60_000)))
        }
        return dispatchGesture(b.build(), null, null)
    }

    /** Does the screen currently contain this text (visible text or description)? */
    fun screenHas(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return root.findAccessibilityNodeInfosByText(text).any { it.isVisibleToUser }
    }

    /** Where on screen (percent) is this text? Returns "x%,y%" lines for up to 6 matches. */
    fun findOnScreen(text: String): String {
        val root = rootInActiveWindow ?: return "error: screen nahi padh paya"
        val dm = resources.displayMetrics
        val out = root.findAccessibilityNodeInfosByText(text).filter { it.isVisibleToUser }.take(6).map {
            val r = android.graphics.Rect(); it.getBoundsInScreen(r)
            "(${(r.centerX() * 100 / dm.widthPixels)}%, ${(r.centerY() * 100 / dm.heightPixels)}%) " + (it.text ?: it.contentDescription ?: "") + (if (it.isClickable) " [btn]" else "")
        }
        return if (out.isEmpty()) "error: '$text' screen par nahi dikha" else out.joinToString("\n")
    }

    /** First switch/toggle on screen: set it to `on` (does nothing if it is already so). */
    fun setFirstSwitch(on: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        fun find(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val cn = n.className?.toString() ?: ""
            if (n.isCheckable && (cn.contains("Switch") || cn.contains("Toggle") || cn.contains("CheckBox"))) return n
            for (i in 0 until n.childCount) { val c = n.getChild(i) ?: continue; find(c)?.let { return it } }
            return null
        }
        val sw = find(root) ?: return false
        if (sw.isChecked == on) return true
        var x: AccessibilityNodeInfo? = sw
        while (x != null && !x.isClickable) x = x.parent
        return (x ?: sw).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** Screenshot as a small JPEG so the model can see it (Android 11+). */
    fun shot(): ByteArray? {
        if (Build.VERSION.SDK_INT < 30) return null
        val latch = java.util.concurrent.CountDownLatch(1)
        var out: ByteArray? = null
        takeScreenshot(android.view.Display.DEFAULT_DISPLAY, java.util.concurrent.Executors.newSingleThreadExecutor(), object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                try {
                    val hb = r.hardwareBuffer
                    val bmp = android.graphics.Bitmap.wrapHardwareBuffer(hb, r.colorSpace)?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    hb.close()
                    if (bmp != null) {
                        val w = 720; val h = bmp.height * w / bmp.width
                        val sc = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true)
                        val bo = java.io.ByteArrayOutputStream()
                        sc.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, bo)
                        out = bo.toByteArray()
                    }
                } catch (e: Exception) {}
                latch.countDown()
            }
            override fun onFailure(code: Int) { latch.countDown() }
        })
        latch.await(4, java.util.concurrent.TimeUnit.SECONDS)
        return out
    }
}
