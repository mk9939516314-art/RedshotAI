package app.redshot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Background body of Redshot: no screen. Own offline ears -> instant on-phone commands -> brain for everything else.
 */
class RedshotService : Service() {

    companion object {
        @Volatile var state: String = "band"
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var ear: Ear? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var announcedPending = false

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
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("hi", "IN")
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { ear?.paused = false }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { ear?.paused = false }
                })
                ttsReady = true
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ear == null) {
            ear = Ear(this, { text -> handleUtterance(text) }, { s -> state = s }).also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        ear?.stop()
        ear = null
        state = "band"
        tts?.shutdown()
        super.onDestroy()
    }

    private fun handleUtterance(text: String) {
        // Fast lane first: handled on the phone, no internet, near-instant.
        if (Router.handle(this, text)) return

        // Everything else: the brain decides if it was meant for Redshot and what to do.
        io.execute {
            val body = JSONObject()
                .put("text", text)
                .put("ts", System.currentTimeMillis())
                .put("pkg", RedshotAccess.currentPackage)
            val res = Brain.post(this, "device/hear", body) ?: return@execute
            main.post { apply(res) }
        }
    }

    private fun apply(res: JSONObject) {
        if (res.optBoolean("pending")) {
            if (!announcedPending) {
                announcedPending = true
                speak("Bhai, ye phone abhi approve nahi hai.")
            }
            return
        }
        val acts = res.optJSONArray("actions")
        if (acts != null) {
            for (i in 0 until acts.length()) {
                val a = acts.optJSONObject(i) ?: continue
                Executor.run(this, a)
            }
        }
        val say = res.optString("say", "")
        if (say.isNotBlank()) speak(say)
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        ear?.paused = true
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "u" + System.nanoTime())
    }
}
