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

    private fun tapAt(x: Float, y: Float): Boolean {
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
}
