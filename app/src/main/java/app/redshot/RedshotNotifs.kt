package app.redshot

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Redshot's eyes on notifications from every app (needs Notification access once). */
class RedshotNotifs : NotificationListenerService() {

    data class N(val at: Long, val pkg: String, val title: String, val text: String)

    companion object {
        private val recent = ArrayDeque<N>()
        @Volatile var connected = false
        @Volatile var onNew: ((N) -> Unit)? = null

        @Synchronized fun add(n: N) { recent.addFirst(n); while (recent.size > 80) recent.removeLast() }

        @Synchronized fun summary(count: Int): String {
            if (!connected) return "error: notification access band hai (Redshot app kholke 'Notifications' wala step on karo)"
            if (recent.isEmpty()) return "Abhi koi naya notification nahi aaya."
            val f = SimpleDateFormat("h:mm a", Locale.ENGLISH)
            return recent.take(count).joinToString("\n") {
                "- " + f.format(Date(it.at)) + " [" + label(it.pkg) + "] " + it.title + ": " + it.text
            }
        }

        fun label(pkg: String): String = pkg.substringAfterLast('.')
    }

    override fun onListenerConnected() { connected = true }
    override fun onListenerDisconnected() { connected = false }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val s = sbn ?: return
        if (s.packageName == packageName) return
        val n = s.notification ?: return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val e = n.extras ?: return
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        if (title.isBlank() && text.isBlank()) return
        val item = N(System.currentTimeMillis(), s.packageName, title.take(80), text.take(300))
        add(item)
        try { onNew?.invoke(item) } catch (ex: Exception) {}
    }
}
