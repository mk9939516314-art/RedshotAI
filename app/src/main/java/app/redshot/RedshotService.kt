package app.redshot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Background body of Redshot: no screen.
 * Own ears -> instant on-phone commands -> brain (audio + text) for everything else -> premium voice.
 */
class RedshotService : Service() {

    companion object {
        @Volatile var state: String = "band"
        @Volatile var level: Int = 0
        @Volatile var threshold: Int = 0
        @Volatile var heard: String = ""
        @Volatile var route: String = ""
        @Volatile var lastAction: String = ""
        @Volatile var voice: String = "-"
        @Volatile var muted: Boolean = false

        private const val CONVERSATION_MS = 90_000L
        private const val STALE_MS = 20_000L
    }

    private val io = Executors.newSingleThreadExecutor()
    private val keepAlive = Executors.newSingleThreadScheduledExecutor()
    private var ear: Ear? = null
    private var speaker: Speaker? = null
    private var announcedPending = false

    @Volatile private var lastReplyAt = 0L
    @Volatile private var lastText = ""
    @Volatile private var lastTextAt = 0L
    @Volatile private var lastOfflineNoticeAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("redshot", "Redshot", NotificationManager.IMPORTANCE_MIN))
        val n: Notification = Notification.Builder(this, "redshot")
            .setSmallIcon(R.drawable.ic_redshot)
            .setContentTitle("Redshot")
            .setContentText("Sun raha hoon")
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(1, n)
            }
        } catch (e: Exception) {
            state = "service start fail: " + e.javaClass.simpleName
            stopSelf()
            return
        }
        speaker = Speaker(this) { busy -> ear?.setPaused(busy) }
        // keep the brain warm so the first reply is as fast as the rest
        keepAlive.scheduleWithFixedDelay({
            try { if (!muted) Brain.post(this, "device/ping", JSONObject().put("k", 1), 10000) } catch (e: Exception) {}
        }, 5, 240, java.util.concurrent.TimeUnit.SECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ear == null || ear?.running == false) {
            ear?.stop()
            ear = Ear(this, { text, pcm -> onHeard(text, pcm) }, { s -> state = s }).also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        ear?.stop()
        ear = null
        state = "band"
        speaker?.shutdown()
        io.shutdownNow()
        keepAlive.shutdownNow()
        super.onDestroy()
    }

    // ---------- one spoken sentence ----------

    private fun onHeard(text: String, pcm: ShortArray) {
        val now = System.currentTimeMillis()
        if (text.isNotEmpty() && text == lastText && now - lastTextAt < 3000) return
        lastText = text
        lastTextAt = now
        heard = if (text.isEmpty()) "(phone ne samjha nahi, brain ko audio bheja)" else text

        io.execute {
            if (System.currentTimeMillis() - now > STALE_MS) { route = "purani baat chhod di"; return@execute }
            process(text, pcm)
        }
    }

    private fun process(text: String, pcm: ShortArray) {
        // 1) fast lane: handled on the phone, no internet
        val handled = try {
            Router.handle(
                this, text, muted,
                { s, lang -> speaker?.say(s, lang); voice = speaker?.engine ?: "-" },
                { m -> muted = m }
            )
        } catch (e: Exception) { false }
        if (handled) {
            if (!muted) lastReplyAt = System.currentTimeMillis()
            route = if (muted) "chup mode (jagane ke liye bol: jaag ja)" else "phone ne turant kiya"
            return
        }
        if (muted) return

        // 2) brain: it hears the raw audio itself (any language) and decides if it was meant for Redshot
        route = "brain ko bheja..."
        val body = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("pkg", RedshotAccess.currentPackage)
            .put("audio", Audio.wavBase64(pcm))
        if (text.isNotBlank()) body.put("text", text)

        val res = Brain.post(this, "device/hear", body, 15000)
        if (res == null) {
            route = "brain fail: " + Brain.lastError
            offlineNotice()
            return
        }
        apply(res)
    }

    private fun offlineNotice() {
        val now = System.currentTimeMillis()
        val inConversation = now - lastReplyAt < CONVERSATION_MS
        if (inConversation && now - lastOfflineNoticeAt > 60_000) {
            lastOfflineNoticeAt = now
            speaker?.say("इंटरनेट नहीं है भाई, अभी बस फोन वाले कमांड चलेंगे।", "hi-IN")
        }
    }

    private fun apply(res: JSONObject) {
        if (res.optBoolean("pending")) {
            route = "device approve nahi hai"
            if (!announcedPending) {
                announcedPending = true
                speaker?.say("भाई, ये फोन अभी approve नहीं है।", "hi-IN")
            }
            return
        }

        val acts = res.optJSONArray("actions")
        val say = res.optString("say", "")
        val lang = res.optString("lang", "hi-IN").ifBlank { "hi-IN" }
        val hadReply = say.isNotBlank() || (acts != null && acts.length() > 0)
        if (!hadReply) { route = "brain: mujhse nahi tha"; return }

        var failure: String? = null
        if (acts != null) {
            for (i in 0 until acts.length()) {
                val a = acts.optJSONObject(i) ?: continue
                val note = Executor.run(this, a)
                lastAction = a.optString("type") + (if (note != null) " -> " + note else " ok")
                if (note != null && failure == null) failure = note
            }
        }
        lastReplyAt = System.currentTimeMillis()
        route = "brain ne jawab diya"

        if (failure != null) {
            // never claim success for something that did not happen
            speaker?.say("भाई, ये नहीं हो पाया। $failure", "hi-IN")
        } else if (say.isNotBlank()) {
            speaker?.say(say, lang)
        }
        voice = speaker?.engine ?: "-"
    }
}
