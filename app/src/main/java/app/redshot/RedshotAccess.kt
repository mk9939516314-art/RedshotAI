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
        }
        return false
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}
