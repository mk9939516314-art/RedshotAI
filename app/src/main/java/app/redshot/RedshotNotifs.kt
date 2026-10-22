package app.redshot

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Redshot's eyes on notifications from every app (needs Notification access once); can also reply to chat notifications. */
class RedshotNotifs : NotificationListenerService() {

    data class N(val at: Long, val pkg: String, val title: String, val text: String, val key: String, val canReply: Boolean)

    companion object {
        private val recent = ArrayDeque<N>()
        private val replyActs = HashMap<String, Notification.Action>()
        @Volatile var connected = false
        @Volatile var onNew: ((N) -> Unit)? = null
        @Volatile var appCtx: Context? = null

        @Synchronized fun add(n: N, act: Notification.Action?) {
            recent.addFirst(n)
            if (act != null) replyActs[n.key] = act
            while (recent.size > 100) { val r = recent.removeLast(); if (replyActs.remove(r.key) != null) { /* dropped */ } }
        }

        @Synchronized fun summary(count: Int): String {
            if (!connected) return "error: notification access band hai (Redshot app kholke 'Notification access' wala step on karo)"
            if (recent.isEmpty()) return "Abhi koi naya notification nahi aaya."
            val f = SimpleDateFormat("h:mm a", Locale.ENGLISH)
            return recent.take(count).joinToString("\n") {
                "- " + f.format(Date(it.at)) + " [" + label(it.pkg) + "] " + it.title + ": " + it.text + (if (it.canReply) "  (reply ho sakta hai)" else "")
            }
        }

        fun label(pkg: String): String = pkg.substringAfterLast('.')

        /** Reply to the latest chat notification whose sender/app matches `to` (name or app) via its own Reply action: no app opening needed. */
        @Synchronized fun replyTo(to: String, text: String): String {
            if (!connected) return "error: notification access band hai"
            if (text.isBlank()) return "error: reply khaali hai"
            val want = Apps.norm(to)
            val cand = recent.firstOrNull { it.canReply && (want.isEmpty() || Apps.norm(it.title).contains(want) || Apps.norm(label(it.pkg)).contains(want) || it.key == to) }
                ?: return "error: '$to' ka koi reply-layak message notification abhi nahi hai (shayad pehle hi dismiss ho gaya)"
            val res = sendReply(cand.key, text)
            if (res.startsWith("ok")) { Convo.add(cand.title, "me", text); if (AwayMode.active) AwayLog.add(cand.title + " ko jawab diya: " + text) }
            return res
        }

        @Synchronized fun sendReply(key: String, text: String): String {
            val act = replyActs[key] ?: return "error: us notification ka reply option ab nahi hai"
            val ctx = appCtx ?: return "error: context nahi"
            return try {
                val ri = act.remoteInputs?.firstOrNull() ?: return "error: reply box nahi mila"
                val i = Intent()
                val b = Bundle()
                b.putCharSequence(ri.resultKey, text)
                RemoteInput.addResultsToIntent(act.remoteInputs, i, b)
                act.actionIntent.send(ctx, 0, i)
                "ok: reply bhej diya"
            } catch (e: Exception) { "error: reply nahi gaya (" + (e.message ?: e.javaClass.simpleName) + ")" }
        }
    }

    override fun onCreate() { super.onCreate(); appCtx = applicationContext }
    override fun onListenerConnected() { connected = true; appCtx = applicationContext }
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
        val act = n.actions?.firstOrNull { a -> a.remoteInputs?.isNotEmpty() == true && a.actionIntent != null }
        val item = N(System.currentTimeMillis(), s.packageName, title.take(80), text.take(300), s.key, act != null)
        add(item, act)
        try { onNew?.invoke(item) } catch (ex: Exception) {}
    }
}
