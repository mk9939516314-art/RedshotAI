package app.redshot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The ears and mouth. Runs in the background with no screen, listens all the time (no wake word),
 * handles simple commands instantly on the phone, and sends everything else to the brain.
 */
class RedshotService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    @Volatile private var running = false
    @Volatile private var speaking = false
    private var useOnDevice = Build.VERSION.SDK_INT >= 33
    private var announcedPending = false

    private val listenRunnable = Runnable { startListening() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("redshot", "Redshot", NotificationManager.IMPORTANCE_MIN)
        )
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
                    override fun onDone(id: String?) { doneSpeaking() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { doneSpeaking() }
                })
                ttsReady = true
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running) {
            running = true
            scheduleListen(0)
            Thread { pollLoop() }.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacks(listenRunnable)
        try { recognizer?.destroy() } catch (e: Exception) {}
        tts?.shutdown()
        super.onDestroy()
    }

    // ---------- listening ----------

    private fun scheduleListen(delayMs: Long) {
        main.removeCallbacks(listenRunnable)
        main.postDelayed(listenRunnable, delayMs)
    }

    private fun startListening() {
        if (!running || speaking) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            scheduleListen(5000)
            return
        }
        try {
            recognizer?.destroy()
            recognizer = createRecognizer()
            recognizer?.setRecognitionListener(listener)
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
            muteEarcon(true)
            recognizer?.startListening(i)
            main.postDelayed({ muteEarcon(false) }, 700)
        } catch (e: Exception) {
            scheduleListen(3000)
        }
    }

    private fun createRecognizer(): SpeechRecognizer {
        return if (useOnDevice && Build.VERSION.SDK_INT >= 33 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
    }

    // Silences the start/stop beep of the system recognizer for a moment each time it restarts.
    private fun muteEarcon(mute: Boolean) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val d = if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
            am.adjustStreamVolume(AudioManager.STREAM_SYSTEM, d, 0)
            am.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, d, 0)
        } catch (e: Exception) {
            // Some phones refuse this under Do Not Disturb rules; the beep just stays then.
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            // 12 / 13 = on-device language pack missing: fall back to the normal recognizer.
            if ((error == 12 || error == 13) && useOnDevice) useOnDevice = false
            val delay = when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1500L
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 150L
                else -> 1000L
            }
            scheduleListen(delay)
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (text.isNotEmpty()) handleUtterance(text)
            scheduleListen(100)
        }
    }

    // ---------- understanding ----------

    private fun handleUtterance(text: String) {
        // Fast lane first: handled on the phone, no internet.
        if (Router.handle(this, text)) return

        // Everything else: the brain decides whether it was meant for Redshot and what to do.
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
                speak("Bhai, pairing baaki hai. Dashboard se mujhe approve kar de.")
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

    // ---------- speaking ----------

    private fun speak(text: String) {
        if (!ttsReady) return
        speaking = true
        try { recognizer?.cancel() } catch (e: Exception) {}
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "u" + System.nanoTime())
    }

    private fun doneSpeaking() {
        speaking = false
        main.post { scheduleListen(200) }
    }

    // ---------- brain push (commands, proactive messages, other devices) ----------

    private fun pollLoop() {
        while (running) {
            val res = Brain.post(
                this, "device/poll",
                JSONObject().put("pkg", RedshotAccess.currentPackage), 40000
            )
            if (res == null) {
                sleep(5000)
                continue
            }
            if (res.optBoolean("pending")) {
                sleep(8000)
                continue
            }
            if (res.has("say") || res.has("actions")) main.post { apply(res) }
            sleep(300)
        }
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (e: InterruptedException) {}
    }
}
