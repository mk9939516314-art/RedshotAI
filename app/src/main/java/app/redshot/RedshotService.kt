package app.redshot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
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
        @Volatile var live: String = "band"
        @Volatile var liveHeard: String = ""
        @Volatile var liveSaid: String = ""

        private const val CONVERSATION_MS = 90_000L
        private const val STALE_MS = 20_000L
    }

    private val io = Executors.newFixedThreadPool(2)
    private val fast = Executors.newSingleThreadExecutor()
    private val keepAlive = Executors.newSingleThreadScheduledExecutor()
    private var ear: Ear? = null
    private var speaker: Speaker? = null
    private var announcedPending = false

    @Volatile private var lastReplyAt = 0L
    @Volatile private var windowUntil = 0L
    @Volatile private var lastText = ""
    @Volatile private var lastTextAt = 0L
    @Volatile private var lastOfflineNoticeAt = 0L

    // ---- live voice session (Gemini Live) ----
    private var liveKey: String? = null
    private var liveSetup: JSONObject? = null
    @Volatile private var liveCfgAt = 0L
    @Volatile private var liveCfgFailAt = 0L
    @Volatile private var session: LiveSession? = null
    @Volatile private var liveActive = false
    @Volatile private var openedAt = 0L
    @Volatile private var liveStartedAt = 0L
    @Volatile private var lastLiveActivity = 0L
    @Volatile private var modelSpeaking = false
    @Volatile private var userVoiceSinceTurn = false
    @Volatile private var silentTurns = 0
    private var fallback: Triple<String, ShortArray, Int>? = null
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private var savedSpeaker = false
    private val liveLock = Any()

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
        speaker?.prefetch(acks.map { it to "hi-IN" } + listOf(
            filler to "hi-IN",
            "इंटरनेट नहीं है भाई, अभी बस फोन वाले कमांड चलेंगे।" to "hi-IN",
            "दिमाग तक बात देर से पहुँच रही है, एक बार फिर बोल।" to "hi-IN",
            "हाँ भाई, बोल।" to "hi-IN"
        ))
        // keep the brain warm so the first reply is as fast as the rest
        keepAlive.scheduleWithFixedDelay(Runnable { liveWatchdog() }, 1, 1, java.util.concurrent.TimeUnit.SECONDS)
        keepAlive.scheduleWithFixedDelay({
            try { if (!muted) Brain.post(this, "device/ping", JSONObject().put("k", 1), 10000) } catch (e: Exception) {}
        }, 5, 240, java.util.concurrent.TimeUnit.SECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ear == null || ear?.running == false) {
            ear?.stop()
            ear = Ear(this, { text, pcm, wake -> onHeard(text, pcm, wake) }, { s -> state = s },
                { prewarm() }, { onUserVoice() }).also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        closeLive("service stop")
        ear?.stop()
        ear = null
        state = "band"
        speaker?.shutdown()
        io.shutdownNow()
        fast.shutdownNow()
        keepAlive.shutdownNow()
        super.onDestroy()
    }

    // ---------- one spoken sentence ----------

    private val acks = listOf("हाँ भाई, बोल।", "हाँ, बता।", "जी, बोलो।", "हाँ भाई, सुन रहा हूँ।")
    private val filler = "हम्म, एक सेकंड..."

    private fun onHeard(text: String, pcm: ShortArray, wake: Int) {
        val now = System.currentTimeMillis()
        if (text.isNotEmpty() && text == lastText && now - lastTextAt < 3000) return
        lastText = text
        lastTextAt = now
        heard = (if (wake > 0) "[Redshot suna] " else "") + (if (text.isEmpty()) "(kuch samajh nahi aaya)" else text)

        // Phone commands never wait behind a slow brain call: they have their own lane.
        fast.execute {
            val say = { s: String, lang: String -> speaker?.say(s, lang); voice = speaker?.engine ?: "-" }
            val setMuted = { m: Boolean -> muted = m }

            // while muted, only the wake-up phrase counts (no "Redshot" needed)
            if (muted) {
                if (text.isNotBlank()) Router.handle(this, text, true, say, setMuted)
                route = if (muted) "chup mode (jagane ke liye bol: jaag ja)" else "wapas sun raha hoon"
                return@execute
            }

            // Only act when "Redshot" was said, or we are in the middle of a conversation.
            val cleaned = Wake.detect(text, wake)
            val inWindow = now < windowUntil
            if (cleaned == null && !inWindow) { route = "Redshot naam nahi suna, ignore kiya"; return@execute }
            val cmd = (cleaned ?: text).trim()
            val seconds = pcm.size / 16000.0

            val handled = try { cmd.isNotEmpty() && Router.handle(this, cmd, false, say, setMuted) } catch (e: Exception) { false }

            // just the name, nothing else (or only a junk word next to it)
            if (!handled && (cmd.isEmpty() || (cleaned != null && seconds < 1.3 && cmd.split(" ").size <= 1))) {
                windowUntil = now + 10_000
                route = "Redshot suna, jawab diya"
                say(acks[(now % acks.size).toInt()], "hi-IN")
                return@execute
            }

            if (handled) {
                windowUntil = System.currentTimeMillis() + 8_000
                lastReplyAt = System.currentTimeMillis()
                route = if (muted) "chup mode (jagane ke liye bol: jaag ja)" else "phone ne turant kiya"
            } else {
                val level = if (cleaned != null) (if (wake >= 2) 2 else 1) else 2
                io.execute {
                    if (System.currentTimeMillis() - now > STALE_MS) { route = "purani baat chhod di"; return@execute }
                    if (!startLive(cmd, pcm, level)) askBrain(cmd, pcm, level)
                }
            }
        }
    }

    // ---------- live conversation ----------

    private fun loadLiveConfig(): Boolean {
        val now = System.currentTimeMillis()
        if (liveKey != null && liveSetup != null && now - liveCfgAt < 10 * 60_000L) return true
        if (now - liveCfgFailAt < 60_000L) return liveKey != null && liveSetup != null
        val r = Brain.post(this, "device/live_config", JSONObject(), 8000)
        val k = r?.optString("key", "") ?: ""
        val st = r?.optJSONObject("setup")
        if (k.isBlank() || st == null) {
            liveCfgFailAt = now
            live = "config nahi mila: " + Brain.lastError
            return liveKey != null && liveSetup != null
        }
        liveKey = k; liveSetup = st; liveCfgAt = now
        return true
    }

    private fun newSession(): LiveSession? {
        val k = liveKey ?: return null
        val st = liveSetup ?: return null
        val s = LiveSession(liveHost)
        session = s
        openedAt = System.currentTimeMillis()
        live = "connect ho raha hai"
        try { s.open(k, st) } catch (e: Exception) { session = null; return null }
        return s
    }

    /** "red"/"shot" heard in the first words: open the connection now so it is ready when the sentence ends. */
    private fun prewarm() {
        if (muted || liveActive || session != null) return
        io.execute {
            synchronized(liveLock) {
                if (muted || liveActive || session != null) return@execute
                if (!loadLiveConfig()) return@execute
                newSession()
            }
        }
    }

    /** Returns true when the live session took over (it answers by voice). */
    private fun startLive(cmd: String, pcm: ShortArray, wakeLevel: Int): Boolean {
        synchronized(liveLock) {
            if (!loadLiveConfig()) return false
            var s = session
            if (s == null || s.isClosed) { session = null; s = newSession() }
            if (s == null) return false
            if (liveActive) return true
            liveActive = true
            liveStartedAt = System.currentTimeMillis()
            lastLiveActivity = liveStartedAt
            silentTurns = 0
            userVoiceSinceTurn = true
            fallback = Triple(cmd, pcm, wakeLevel)
            route = "live: Redshot ne turant suna"
            enterCallAudio()
            s.sendBurst(pcm)
            ear?.startLive { f -> session?.feed(f) }
            return true
        }
    }

    private fun closeLive(reason: String) {
        var s: LiveSession? = null
        synchronized(liveLock) {
            s = session
            session = null
            val was = liveActive
            liveActive = false
            fallback = null
            modelSpeaking = false
            if (was) leaveCallAudio()
        }
        ear?.stopLive()
        try { s?.close() } catch (e: Exception) {}
        live = "band: $reason"
    }

    private fun liveWatchdog() {
        try {
            val now = System.currentTimeMillis()
            val s = session
            if (!liveActive) {
                // a pre-opened connection nobody used
                if (s != null && now - openedAt > 8_000) closeLive("prewarm khatam")
                return
            }
            if (!modelSpeaking && now - lastLiveActivity > 12_000) closeLive("chup rehne se band")
            else if (now - liveStartedAt > 8 * 60_000L) closeLive("8 min poore")
        } catch (e: Exception) {}
    }

    private fun onUserVoice() {
        lastLiveActivity = System.currentTimeMillis()
        userVoiceSinceTurn = true
    }

    private fun enterCallAudio() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            savedAudioMode = am.mode
            @Suppress("DEPRECATION")
            savedSpeaker = am.isSpeakerphoneOn
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            val headset = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            @Suppress("DEPRECATION")
            if (!headset) am.isSpeakerphoneOn = true
        } catch (e: Exception) {}
    }

    private fun leaveCallAudio() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = savedSpeaker
            am.mode = savedAudioMode
        } catch (e: Exception) {}
    }

    private val liveHost = object : LiveSession.Host {
        override fun onReady() { live = if (liveActive) "live: sun raha hoon" else "live: taiyaar" }

        override fun onModelAudio() {
            fallback = null
            lastLiveActivity = System.currentTimeMillis()
        }

        override fun onModelSpeaking(speaking: Boolean) {
            modelSpeaking = speaking
            lastLiveActivity = System.currentTimeMillis()
            if (speaking) live = "live: bol raha hoon"
        }

        override fun onTurnComplete() {
            lastLiveActivity = System.currentTimeMillis()
            if (!liveActive) return
            // loop breaker: model keeps answering but the user never spoke (echo / noise)
            if (userVoiceSinceTurn) silentTurns = 0 else silentTurns++
            userVoiceSinceTurn = false
            if (silentTurns >= 3) io.execute { closeLive("khud se baat ho rahi thi") }
        }

        override fun onUserText(t: String) { liveHeard += t; if (liveHeard.length > 160) liveHeard = liveHeard.takeLast(160); heard = "[live] " + liveHeard; fallback = null }
        override fun onModelText(t: String) { liveSaid += t; if (liveSaid.length > 160) liveSaid = liveSaid.takeLast(160) }

        override fun onClosed(reason: String, wasReady: Boolean) {
            val fb = fallback
            val wasActive = liveActive
            fallback = null
            io.execute {
                if (session?.isClosed == true) session = null
                if (wasActive) closeLive(reason)
                else live = "band: $reason"
                if (!wasReady && fb != null) {
                    route = "live nahi laga ($reason), brain se"
                    askBrain(fb.first, fb.second, fb.third)
                }
            }
        }

        override fun onTool(name: String, args: JSONObject): String {
            lastLiveActivity = System.currentTimeMillis()
            return when (name) {
                "web_search" -> {
                    val r = Brain.post(this@RedshotService, "device/search", JSONObject().put("query", args.optString("query")), 14000)
                    r?.optString("result", "")?.ifBlank { null } ?: "error: search nahi hua (${Brain.lastError})"
                }
                "remember" -> {
                    val r = Brain.post(this@RedshotService, "device/remember", JSONObject().put("fact", args.optString("fact")), 8000)
                    if (r != null) "ok" else "error: yaad nahi rakh paya"
                }
                "mute_listening" -> {
                    muted = true
                    io.execute { Thread.sleep(2500); closeLive("mute") }
                    "ok"
                }
                else -> {
                    val a = JSONObject(args.toString()).put("type", name)
                    val note = Executor.run(this@RedshotService, a)
                    lastAction = name + (if (note != null) " -> " + note else " ok")
                    if (note == null) "ok" else "error: $note"
                }
            }
        }
    }

    // wakeLevel: 2 = sure it was meant for Redshot, 1 = spotter unsure (the brain double-checks the audio)
    private fun askBrain(text: String, pcm: ShortArray, wakeLevel: Int) {
        route = "brain ko bheja..."
        val body = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("pkg", RedshotAccess.currentPackage)
            .put("wake", true)
            .put("weak", wakeLevel < 2)
            .put("audio", Audio.wavBase64(pcm))
        if (text.isNotBlank()) body.put("text", text)

        // if the brain takes a moment, say something short so it never feels dead
        val waiting = keepAlive.schedule(Runnable {
            speaker?.say(filler, "hi-IN")
        }, 1700, java.util.concurrent.TimeUnit.MILLISECONDS)

        val t0 = System.currentTimeMillis()
        val res = Brain.post(this, "device/hear", body, 14000)
        waiting.cancel(false)
        if (res == null) {
            route = "brain fail: " + Brain.lastError
            offlineNotice(Brain.lastError)
            return
        }
        route = "brain: " + (System.currentTimeMillis() - t0) + "ms"
        apply(res)
    }

    private fun offlineNotice(err: String) {
        val now = System.currentTimeMillis()
        if (now - lastOfflineNoticeAt < 8_000) return
        lastOfflineNoticeAt = now
        val noNet = err.contains("UnknownHost") || err.contains("ConnectException") || err.contains("Unreachable")
        speaker?.say(
            if (noNet) "इंटरनेट नहीं है भाई, अभी बस फोन वाले कमांड चलेंगे।"
            else "दिमाग तक बात देर से पहुँच रही है, एक बार फिर बोल।",
            "hi-IN"
        )
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
        windowUntil = lastReplyAt + (if (say.isNotBlank()) 25_000L else 8_000L)
        route = route + " (jawab mila)"

        if (failure != null) {
            // never claim success for something that did not happen
            speaker?.say("भाई, ये नहीं हो पाया। $failure", "hi-IN")
        } else if (say.isNotBlank()) {
            speaker?.say(say, lang)
        }
        voice = speaker?.engine ?: "-"
    }
}
