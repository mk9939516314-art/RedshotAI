package app.redshot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Background body of Redshot: no screen.
 * Ears -> instant on-phone commands -> ONE live voice brain (same voice for everything) -> proactive mind.
 * Threads are separated on purpose: a slow network call can never block the ears, the mic gate or the watchdog.
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
        @Volatile var spkDist: Double = -1.0
        @Volatile var spkReady: Boolean = false
        @Volatile var enrollHave: Int = 0
        @Volatile var mindLast: String = "-"
        @Volatile var earError: String = ""
        @Volatile var spkFramesLast: Int = 0
        @Volatile var enrollMsg: String = ""
        @Volatile var instance: RedshotService? = null

        fun startEnroll(sentences: Int) {
            Voiceprint.clear()
            enrollHave = 0
            instance?.ear?.enrollWanted = sentences
        }
        /** Tatkal screen -> owner: say it with the live voice, and keep a trail in the brain log. */
        fun tatkalSay(text: String) { instance?.let { i -> Thread { if (!i.speakViaLive(text)) i.speaker?.say(text, "hi-IN") }.start() } }
        fun tatkalLog(content: String) { instance?.log("tatkal", content) }
        fun classReminders(list: List<Pair<Long, String>>) { instance?.scheduleClassReminders(list) }
        fun classChanged(changes: List<String>) { instance?.classChangedEvent(changes) }
        val enrolling: Boolean get() = (instance?.ear?.enrollWanted ?: 0) > 0

        private val SERVER_TOOLS = setOf("web_fetch", "calculate", "utility", "currency_rate", "weather", "geocode", "ip_lookup", "stock_quote", "image_generate", "url_safety", "translate_text", "deep_think")
        private const val STALE_MS = 20_000L
        private const val IDLE_MS = 45_000L          // live conversation stays open this long without talk
        private const val WINDOW_MS = 120_000L       // after it closes, the next sentence still needs no "Redshot"
        private const val HARD_CAP_MS = 14 * 60_000L // Gemini live sessions end at 15 min
    }

    private val io = Executors.newFixedThreadPool(4)
    private val fast = Executors.newSingleThreadExecutor()          // local router lane
    private val wd = Executors.newSingleThreadScheduledExecutor()   // watchdog only: never blocks
    private val bg = Executors.newScheduledThreadPool(2)            // pings, logs, proactive mind
    private var ear: Ear? = null
    private var speaker: Speaker? = null
    private var announcedPending = false

    @Volatile private var windowUntil = 0L
    @Volatile private var lastText = ""
    @Volatile private var lastTextAt = 0L
    @Volatile private var lastOfflineNoticeAt = 0L

    // ---- live voice session (Gemini Live) ----
    private var liveKey: String? = null
    private var liveSetup: JSONObject? = null
    @Volatile private var liveCfgAt = 0L
    @Volatile private var liveCfgFailAt = 0L
    private val cfgLock = Any()
    @Volatile private var session: LiveSession? = null
    @Volatile private var gen = 0
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
    private var wake: PowerManager.WakeLock? = null
    @Volatile private var earInLive = false
    private val logQ = ConcurrentLinkedQueue<JSONObject>()
    private var turnUser = StringBuilder()
    private var turnModel = StringBuilder()
    private var convoLog = StringBuilder()
    @Volatile private var lastLiveEndAt = 0L

    // ---- proactive mind ----
    private val mindEvents = ArrayList<JSONObject>()
    private val told = ArrayList<String>()
    @Volatile private var lastMindSend = 0L
    @Volatile private var lastMindSay = 0L
    @Volatile private var mindScheduled = false
    private val mindKeys = Regex("(baje|बजे|\\bkal\\b|कल|subah|सुबह|shaam|शाम|raat|class|क्लास|exam|एग्जाम|test|टेस्ट|train|ट्रेन|tatkal|तत्काल|book|बुक|alarm|अलार्म|yaad|याद|remind|meeting|मीटिंग|deadline|submit|interview|ticket|टिकट|payment|bill|fees|फीस|tomorrow|tonight|jaa raha|ja raha|जा रहा|\\d\\s*(am|pm))", RegexOption.IGNORE_CASE)

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
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(1, n)
        } catch (e: Exception) {
            state = "service start fail: " + e.javaClass.simpleName
            stopSelf()
            return
        }
        instance = this
        AutoScroll.appCtx = applicationContext
        Voiceprint.load(this)
        AwayMode.load(this)
        AwayLog.load(this)
        scheduleScan04()
        RedshotNotifs.appCtx = applicationContext
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "redshot:ear").also { it.setReferenceCounted(false); it.acquire() }
        } catch (e: Exception) {}
        RedshotNotifs.onNew = { n2 -> onNotification(n2) }
        Deep.sendImage = { jpg -> val ss = session; if (ss != null && !ss.isClosed) { ss.sendImage(jpg); true } else false }

        speaker = Speaker(this) { busy -> ear?.setPaused(busy) }
        speaker?.prefetch(acks.map { it to "hi-IN" } + listOf(
            filler to "hi-IN",
            "इंटरनेट नहीं है भाई, अभी बस फोन वाले कमांड चलेंगे।" to "hi-IN",
            "दिमाग तक बात देर से पहुँच रही है, एक बार फिर बोल।" to "hi-IN"
        ))

        // watchdog: own thread, never does network work
        wd.scheduleWithFixedDelay(Runnable { watchdog() }, 1, 1, TimeUnit.SECONDS)
        // everything that touches the network lives on its own pool
        bg.scheduleWithFixedDelay(Runnable { flushLog() }, 6, 6, TimeUnit.SECONDS)
        bg.scheduleWithFixedDelay(Runnable { mindTick() }, 3, 15, TimeUnit.MINUTES)
        bg.scheduleWithFixedDelay(Runnable { try { Updater.backgroundTick(applicationContext) } catch (e: Exception) {} }, 2, 30, TimeUnit.MINUTES)
        bg.scheduleWithFixedDelay(Runnable {
            try { if (!muted) { Brain.post(this, "device/ping", JSONObject().put("k", 1), 10000); loadLiveConfig() } } catch (e: Exception) {}
        }, 5, 240, TimeUnit.SECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "app.redshot.SCAN04") { classScan04(intent.getIntExtra("try", 0)); scheduleScan04() }
        if (ear == null || ear?.running == false) startEar()
        return START_STICKY
    }

    private fun startEar() {
        ear?.stop()
        ear = Ear(this, { text, pcm, wk, dist -> onHeard(text, pcm, wk, dist) }, { s -> state = s },
            { prewarm() }, { onUserVoice() }, { onBarge() }).also { it.start() }
    }

    override fun onDestroy() {
        closeLive("service stop")
        instance = null
        RedshotNotifs.onNew = null
        try { wake?.release() } catch (e: Exception) {}
        ear?.stop()
        ear = null
        state = "band"
        speaker?.shutdown()
        io.shutdownNow()
        fast.shutdownNow()
        wd.shutdownNow()
        bg.shutdownNow()
        super.onDestroy()
    }

    // ---------- one spoken sentence ----------

    private val acks = listOf("हाँ भाई, बोल।", "हाँ, बता।", "जी, बोलो।", "हाँ भाई, सुन रहा हूँ।")
    private val filler = "हम्म, एक सेकंड..."

    /** Something else (reels, music, video) is playing on the phone; Redshot's own voice does not count. */
    private fun musicOn(): Boolean = try {
        !modelSpeaking && System.currentTimeMillis() - lastSpeakEnd > 1800 && (getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive
    } catch (e: Exception) { false }

    /** One voice for everything: the live voice says it; the older stock voice is only the fallback when live is unreachable. */
    private fun sayOut(text: String, lang: String) {
        if (text.isBlank()) return
        if (muted) return
        if (speakViaLive(text)) { voice = "live"; return }
        speaker?.say(text, lang); voice = speaker?.engine ?: "-"
    }

    private fun onHeard(text: String, pcm: ShortArray, wk: Int, dist: Double) {
        val now = System.currentTimeMillis()
        if (text.isNotEmpty() && text == lastText && now - lastTextAt < 3000) return
        lastText = text
        lastTextAt = now
        val strict = musicOn()
        val mine = !Voiceprint.enrolled || dist == -2.0 || Voiceprint.accept(dist, strict)
        heard = (if (wk > 0) "[Redshot suna] " else "") + (if (text.isEmpty()) "(kuch samajh nahi aaya)" else text) +
            (if (dist >= 0) "  (meri awaaz? %.2f)".format(dist) else "")

        // A live conversation is open and the mic is in sentence mode (something is playing on the phone):
        // only the owner's sentences are passed on.
        if (liveActive && !earInLive) {
            val accept = if (Voiceprint.enrolled) (dist >= 0 && Voiceprint.accept(dist, true)) || (dist == -2.0 && wk > 0) else wk > 0
            if (accept && !modelSpeaking) {
                session?.sendBurst(pcm)
                onUserVoice()
                route = "live (sentence mode): teri baat bheji"
            } else route = "live: awaaz teri nahi lagi, ignore"
            return
        }

        // what the owner says around the phone (not to Redshot) is kept for the proactive mind
        if (text.isNotBlank() && !liveActive && !muted && mine &&
            ((Voiceprint.enrolled && dist >= 0) || (!Voiceprint.enrolled && !strict))) {
            mindAddSpeech(text)
        }

        fast.execute {
            val say = { s: String, lang: String -> sayOut(s, lang) }
            val setMuted = { m: Boolean -> muted = m }

            if (muted) {
                if (text.isNotBlank()) Router.handle(this, text, true, { s, l -> speaker?.say(s, l) }, setMuted)
                route = if (muted) "chup mode (jagane ke liye bol: jaag ja)" else "wapas sun raha hoon"
                return@execute
            }

            val words0 = text.split(" ").filter { it.isNotBlank() }.size
            // a long sentence where no wake spotter fired is almost always TV / other people, not "Redshot ..."
            var cleaned = Wake.detect(text, wk)
            if (cleaned != null && wk == 0 && words0 > 6) cleaned = null
            val inWindow = now < windowUntil
            if (cleaned == null && !inWindow) { route = "Redshot naam nahi suna, ignore kiya"; return@execute }

            if (!mine) { route = "awaaz teri nahi lagi (%.2f), ignore".format(dist); return@execute }

            val cmd = (cleaned ?: text).trim()
            val seconds = pcm.size / 16000.0
            val words = cmd.split(" ").filter { it.isNotBlank() }.size
            val level = if (cleaned != null) (if (wk >= 2) 2 else 1) else 2

            // short phone commands run locally and instantly; open/long sentences go to the live brain
            val handled = try { cmd.isNotEmpty() && words <= 7 && Router.handle(this, cmd, false, say, setMuted) } catch (e: Exception) { false }

            if (handled) {
                windowUntil = System.currentTimeMillis() + 20_000
                route = "phone ne turant kiya"
                ActionLog.add("local command: " + cmd.take(80))
                return@execute
            }

            // just the name: the live voice answers ("haan bol bhai") so there is only ONE voice
            val nameOnly = cmd.isEmpty() || (cleaned != null && seconds < 1.3 && words <= 1)
            io.execute {
                if (System.currentTimeMillis() - now > STALE_MS) { route = "purani baat chhod di"; return@execute }
                if (startLive(cmd, pcm, level)) {
                    route = if (nameOnly) "Redshot suna, live ne jawab diya" else "live ko bheja"
                } else if (nameOnly) {
                    windowUntil = System.currentTimeMillis() + 15_000
                    route = "Redshot suna (live nahi mila), purani awaaz"
                    speaker?.say(acks[(now % acks.size).toInt()], "hi-IN")
                } else {
                    askBrain(cmd, pcm, level)
                }
            }
        }
    }

    // ---------- logging (so problems can be fixed from facts) ----------

    internal fun log(kind: String, content: String) {
        if (content.isBlank()) return
        logQ.add(JSONObject().put("kind", kind).put("content", content.take(1000)))
        while (logQ.size > 200) logQ.poll()
    }

    private fun flushLog() {
        try {
            if (logQ.isEmpty()) return
            val arr = JSONArray()
            var n = 0
            while (n < 50) { val o = logQ.poll() ?: break; arr.put(o); n++ }
            Brain.post(this, "device/log", JSONObject().put("items", arr), 10000)
        } catch (e: Exception) {}
    }

    // ---------- live conversation ----------

    private fun loadLiveConfig(): Boolean {
        synchronized(cfgLock) {
            val now = System.currentTimeMillis()
            if (liveKey != null && liveSetup != null && now - liveCfgAt < 10 * 60_000L) return true
            if (now - liveCfgFailAt < 15_000L) return liveKey != null && liveSetup != null
            val r = Brain.post(this, "device/live_config", JSONObject(), 8000)
            val k = r?.optString("key", "") ?: ""
            val st = r?.optJSONObject("setup")
            if (k.isBlank() || st == null) {
                liveCfgFailAt = now
                live = "config nahi mila: " + Brain.lastError
                log("error", "live_config: " + Brain.lastError)
                return liveKey != null && liveSetup != null
            }
            liveKey = k; liveSetup = st; liveCfgAt = now
            return true
        }
    }

    /** Facts that change every minute are added fresh at every session start (time, what was just done, away mode, last talk). */
    private fun freshContext(): String {
        val sb = StringBuilder()
        sb.append("\n\nABHI KA SAMAY: ").append(java.text.SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", java.util.Locale.ENGLISH).format(java.util.Date())).append(" IST")
        if (AwayMode.active) sb.append("\nAWAY MODE CHALU: ").append(AwayMode.describe())
        if (AwayLog.size() > 0) {
            if (AwayMode.active) sb.append("\nAWAY LOG ABHI TAK (owner pooche 'kya hua' toh yahi bata):\n").append(AwayLog.peek())
            else sb.append("\nOWNER KE PICHHLE AWAY ME YE HUA (owner se baat shuru hote hi dosti ki zubaan me saaf-saaf bata de, phir ye khatam):\n").append(AwayLog.drain())
        }
        sb.append("\nPHONE/REDSHOT NE ABHI TAK YE KIYA (sab dimaag milake, ye tera hi kiya hua hai, mana mat kar):\n").append(ActionLog.recent(12))
        if (convoLog.isNotEmpty() && System.currentTimeMillis() - lastLiveEndAt < 3 * 60_000L)
            sb.append("\nABHI KI PICHHLI BAAT-CHEET (isi ka silsila):\n").append(convoLog.toString().takeLast(900))
        return sb.toString()
    }

    private fun newSession(): LiveSession? {
        val k = liveKey ?: return null
        var st = liveSetup ?: return null
        try {
            st = JSONObject(st.toString())
            val part = st.getJSONObject("setup").getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0)
            part.put("text", part.getString("text") + freshContext())
        } catch (e: Exception) {}
        val g = ++gen
        val s = LiveSession(H(g))
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
            if (!loadLiveConfig()) return@execute
            synchronized(liveLock) {
                if (muted || liveActive || session != null) return@execute
                newSession()
            }
        }
    }

    private fun beginActive(s: LiveSession) {
        liveActive = true
        liveStartedAt = System.currentTimeMillis()
        lastLiveActivity = liveStartedAt
        silentTurns = 0
        userVoiceSinceTurn = true
        liveHeard = ""; liveSaid = ""
        enterCallAudio()
        applyEarMode()
    }

    /** Returns true when the live session took over (it answers by voice). */
    private fun startLive(cmd: String, pcm: ShortArray, wakeLevel: Int): Boolean {
        if (!loadLiveConfig()) return false
        synchronized(liveLock) {
            var s = session
            if (s == null || s.isClosed) { session = null; s = newSession() }
            if (s == null) return false
            if (liveActive) { s.sendBurst(pcm); onUserVoice(); return true }
            fallback = if (cmd.isNotBlank()) Triple(cmd, pcm, wakeLevel) else null
            beginActive(s)
            s.sendBurst(pcm)
            log("wake", "heard: $cmd")
            return true
        }
    }

    /** The phone itself wants to say something (reminder, local answer): same live voice, mic open for a reply. */
    internal fun speakViaLive(text: String): Boolean {
        if (!loadLiveConfig()) return false
        synchronized(liveLock) {
            var s = session
            if (s == null || s.isClosed) { session = null; s = newSession() }
            if (s == null) return false
            if (!liveActive) { fallback = null; beginActive(s) }
            s.sendText("[Phone se, owner ne kuch bola nahi] Owner ko ab apni awaaz mein, dost ki tarah chhota sa ye bata (naye shabdon mein, ek-do vaakya): $text")
            lastLiveActivity = System.currentTimeMillis()
            return true
        }
    }

    /** Streaming (fast, natural) normally; sentence-by-sentence with voice check while the phone itself plays audio. */
    private fun applyEarMode() {
        if (!liveActive) return
        val strict = musicOn()
        if (!strict && !earInLive) {
            earInLive = true
            ear?.startLive { f -> session?.feed(f) }
        } else if (strict && earInLive) {
            earInLive = false
            ear?.stopLive()
        }
    }

    private fun closeLive(reason: String, onlyIfIdle: Boolean = false) {
        var s: LiveSession? = null
        var was = false
        synchronized(liveLock) {
            // a close decided a moment ago must not kill a conversation that just got going again
            if (onlyIfIdle && System.currentTimeMillis() - lastLiveActivity < IDLE_MS - 2000) return
            s = session
            session = null
            was = liveActive
            liveActive = false
            fallback = null
            modelSpeaking = false
            earInLive = false
            if (was) leaveCallAudio()
        }
        ear?.stopLive()
        if (was) { lastLiveEndAt = System.currentTimeMillis(); windowUntil = System.currentTimeMillis() + WINDOW_MS }
        try { s?.close() } catch (e: Exception) {}
        live = if (was) "so gaya ($reason)" else "band"
        if (was) log("live_end", reason)
    }

    /** Runs on its own thread every second and never blocks. */
    private fun watchdog() {
        try {
            // the ears must never stay dead
            if (ear?.running == false) {
                log("error", "ear restart: " + earError + " state=" + state)
                startEar()
            }
            val now = System.currentTimeMillis()
            val s = session
            if (!liveActive) {
                if (s != null && now - openedAt > 8_000) io.execute { closeLive("prewarm khatam") }
                return
            }
            if (s != null && !s.ready && now - liveStartedAt > 5_000) {
                // never became ready: fall back to the normal brain right away
                val fb = fallback
                fallback = null
                io.execute {
                    closeLive("live connect nahi hua")
                    if (fb != null) askBrain(fb.first, fb.second, fb.third)
                }
                return
            }
            applyEarMode()
            if (!modelSpeaking && now - lastLiveActivity > IDLE_MS) io.execute { closeLive("chup raha", true) }
            else if (now - liveStartedAt > HARD_CAP_MS) io.execute { closeLive("time limit") }
        } catch (e: Exception) {}
    }

    private fun onUserVoice() {
        lastLiveActivity = System.currentTimeMillis()
        userVoiceSinceTurn = true
    }

    /** Owner clearly talked over Redshot: stop its voice immediately. */
    private fun onBarge() {
        log("audio", "barge: owner ne beech me kaata")
        session?.cutPlayback()
        modelSpeaking = false
        ear?.modelTalking = false
        userVoiceSinceTurn = true
        lastLiveActivity = System.currentTimeMillis()
    }

    // ----- audio: plain media output through the loud speaker (no call mode: nothing else on the phone gets disturbed) -----

    private var savedMusicVol = -1
    private var focusReq: android.media.AudioFocusRequest? = null
    @Volatile private var lastSpeakEnd = 0L

    /** earphones / bluetooth connected: no speaker echo, so the owner can talk over Redshot */
    private fun headsetOn(): Boolean = try {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET ||
            it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
        }
    } catch (e: Exception) { false }

    private fun enterCallAudio() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (cur < (max * 0.6).toInt()) {
                if (savedMusicVol < 0) savedMusicVol = cur
                am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * 0.7).toInt(), 0)
            }
            log("audio", "mode=${am.mode} music=$cur/$max call=${am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)} speakerOn=${am.isSpeakerphoneOn} btsco=${am.isBluetoothScoOn}")
        } catch (e: Exception) { log("error", "audio route: " + e.message) }
    }

    private fun leaveCallAudio() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (savedMusicVol >= 0) { am.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVol, 0); savedMusicVol = -1 }
            abandonFocus()
        } catch (e: Exception) {}
    }

    /** While Redshot talks, reels/music duck instead of fighting with it. */
    private fun takeFocus() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (focusReq == null) {
                val r = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener { }.build()
                focusReq = r
                am.requestAudioFocus(r)
            }
        } catch (e: Exception) {}
    }

    private fun abandonFocus() {
        try {
            val r = focusReq ?: return
            focusReq = null
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(r)
        } catch (e: Exception) {}
    }

    /** Callbacks of ONE live session; an old session can never disturb a newer one. */
    private inner class H(val g: Int) : LiveSession.Host {
        private fun cur() = g == gen

        override fun onReady() { if (cur()) live = if (liveActive) "live: sun raha hoon" else "live: taiyaar" }

        override fun onModelAudio() {
            if (!cur()) return
            fallback = null
            lastLiveActivity = System.currentTimeMillis()
        }

        override fun onModelSpeaking(speaking: Boolean) {
            if (!cur()) return
            modelSpeaking = speaking
            lastLiveActivity = System.currentTimeMillis()
            ear?.modelTalking = speaking
            if (speaking) { ear?.gate?.let { g -> g.bargeEnabled = headsetOn(); g.useRef = true; g.ref = { session?.echoLevel() ?: 0.0 }; g.aec = { b -> session?.echoResidual(b) ?: -1.0 }; g.aecConverged = { session?.aecConverged() ?: false } }; takeFocus(); live = "live: bol raha hoon" }
            else { ear?.gate?.let { log("audio", "mic ne suna: echoPeak=${it.echoPeak.toInt()} headset=${it.bargeEnabled} erle=${(session?.aecErleDb() ?: 0.0).toInt()}dB") }; lastSpeakEnd = System.currentTimeMillis(); abandonFocus(); ear?.gateTailUntil = System.currentTimeMillis() + 400; if (liveActive) live = "live: sun raha hoon" }
        }

        override fun onAudioStats(s: String) { log("audio", s) }

        override fun onTurnComplete() {
            if (!cur()) return
            lastLiveActivity = System.currentTimeMillis()
            if (turnUser.isNotEmpty() || turnModel.isNotEmpty()) {
                log("live_user", turnUser.toString().trim())
                log("live_redshot", turnModel.toString().trim())
                convoLog.append("Owner: ").append(turnUser.toString().trim()).append("\nRedshot: ").append(turnModel.toString().trim()).append('\n')
                if (convoLog.length > 1500) convoLog.delete(0, convoLog.length - 1200)
                turnUser = StringBuilder(); turnModel = StringBuilder()
            }
            if (!liveActive) return
            if (userVoiceSinceTurn) silentTurns = 0 else silentTurns++
            userVoiceSinceTurn = false
            if (silentTurns >= 3) io.execute { closeLive("khud se baat ho rahi thi") }
        }

        override fun onUserText(t: String) {
            if (!cur()) return
            turnUser.append(t)
            liveHeard += t; if (liveHeard.length > 160) liveHeard = liveHeard.takeLast(160)
            heard = "[live] " + liveHeard
            fallback = null
            if (t.length > 1) mindAddSpeech(t)
        }

        override fun onModelText(t: String) {
            if (!cur()) return
            turnModel.append(t)
            liveSaid += t; if (liveSaid.length > 160) liveSaid = liveSaid.takeLast(160)
        }

        override fun onClosed(reason: String, wasReady: Boolean) {
            val mine = cur()
            val fb = if (mine) fallback else null
            if (mine) fallback = null
            io.execute {
                log("live_closed", "$reason ready=$wasReady current=$mine")
                if (!mine) return@execute
                if (liveActive) closeLive(reason)        // dropped mid-talk: next sentence needs no "Redshot" (window set in closeLive)
                else { if (session?.isClosed == true) session = null; live = "band" }
                if (!wasReady && fb != null) {
                    route = "live nahi laga ($reason), brain se"
                    askBrain(fb.first, fb.second, fb.third)
                }
            }
        }

        override fun onTool(name: String, args: JSONObject): String {
            lastLiveActivity = System.currentTimeMillis()
            val result = when (name) {
                "web_search" -> {
                    val r = Brain.post(this@RedshotService, "device/search", JSONObject().put("query", args.optString("query")), 22000)
                    r?.optString("result", "")?.ifBlank { null } ?: "error: internet search nahi chal paya (${Brain.lastError})"
                }
                "remember" -> {
                    val r = Brain.post(this@RedshotService, "device/remember", JSONObject().put("fact", args.optString("fact")), 8000)
                    if (r != null) "ok" else "error: yaad nahi rakh paya (${Brain.lastError})"
                }
                in SERVER_TOOLS -> {
                    val r = Brain.post(this@RedshotService, "device/tool", JSONObject().put("name", name).put("args", args), 40000)
                    val txt = r?.optString("result", "")?.ifBlank { null } ?: "error: $name nahi chal paya (${Brain.lastError})"
                    when {
                        txt.startsWith("IMG:") -> try {
                            val f = java.io.File(Pro.root(), "gen_" + System.currentTimeMillis() / 1000 + ".jpg")
                            f.parentFile?.mkdirs()
                            f.writeBytes(android.util.Base64.decode(txt.substring(4), android.util.Base64.DEFAULT))
                            "ok: image bani ${f.path}. file_open se dikha sakta hai."
                        } catch (e: Exception) { "error: image save nahi hui: ${e.message}" }
                        txt.startsWith("IMGURL:") -> Pro2.run(this@RedshotService, "download_file", JSONObject().put("url", txt.substring(7)).put("path", "gen_" + System.currentTimeMillis() / 1000 + ".jpg")) ?: "error"
                        else -> txt
                    }
                }
                "mute_listening" -> {
                    muted = true
                    io.execute { Thread.sleep(2500); closeLive("mute") }
                    "ok"
                }
                else -> {
                    val a = JSONObject(args.toString()).put("type", name)
                    val res = Executor.runForResult(this@RedshotService, a)
                    lastAction = name + " -> " + res.take(60)
                    res
                }
            }
            lastLiveActivity = System.currentTimeMillis()
            log("tool", name + " " + args.toString().take(200) + " -> " + result.take(300))
            return result
        }
    }

    private val classTasks = ArrayList<java.util.concurrent.ScheduledFuture<*>>()

    /** The ONLY automatic class check: once a day around 04:00, while the owner is asleep. Never while he is using the phone. */
    private fun classScan04(tryNo: Int) {
        if (muted) return
        Thread {
            try {
                val r = ClassScan.run(this, "Allen", "04:00")
                log("classscan", "04:00 try $tryNo: " + r.take(400))
                if (r.startsWith("skip") && tryNo < 4) {          // locked / screen off: try again in 30 min, but only inside the night window
                    val at = System.currentTimeMillis() + 30 * 60_000L
                    alarmAt(at, Intent(this, RedshotService::class.java).setAction("app.redshot.SCAN04").putExtra("try", tryNo + 1), 7703)
                }
            } catch (e: Exception) {}
        }.also { it.isDaemon = true }.start()
    }

    private fun alarmAt(at: Long, i: Intent, code: Int) {
        try {
            val pi = android.app.PendingIntent.getService(applicationContext, code, i, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            (getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager).setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: Throwable) {}
    }

    internal fun scheduleScan04() {
        val c = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 4); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
        if (c.timeInMillis <= System.currentTimeMillis() + 60_000L) c.add(java.util.Calendar.DAY_OF_YEAR, 1)
        alarmAt(c.timeInMillis, Intent(this, RedshotService::class.java).setAction("app.redshot.SCAN04"), 7702)
    }

    internal fun scheduleClassReminders(list: List<Pair<Long, String>>) {
        synchronized(classTasks) {
            classTasks.forEach { it.cancel(false) }; classTasks.clear()
            for ((at, say) in list) {
                val d = at - System.currentTimeMillis()
                if (d > 0) classTasks.add(bg.schedule(Runnable { classRemind(say) }, d, TimeUnit.MILLISECONDS))
            }
        }
        ActionLog.add("(khud se) class reminders lage: " + list.size)
    }

    private fun classRemind(say: String) {
        ActionLog.add("(khud se) class reminder: " + say.take(80))
        if (AwayMode.active) AwayLog.add("class reminder: " + say)
        val spoke = !muted && speakViaLive(say)
        if (!spoke && !muted) speaker?.say(say, "hi-IN")
        Executor.notify(this, say)
    }

    internal fun classChangedEvent(changes: List<String>) {
        val t = "Class me badlav: " + changes.joinToString("; ")
        ActionLog.add("(khud se) " + t.take(150))
        AwayLog.add(t)
        synchronized(mindEvents) { mindEvents.add(JSONObject().put("kind", "class_update").put("text", t.take(500))) }
        scheduleMind(4_000)
    }

    // ---------- proactive mind: sees notifications, hears owner's own talk, decides by itself ----------

    private fun mindAddSpeech(text: String) {
        synchronized(mindEvents) {
            mindEvents.add(JSONObject().put("kind", "speech").put("text", text.take(300)))
            while (mindEvents.size > 30) mindEvents.removeAt(0)
        }
        if (mindKeys.containsMatchIn(text)) scheduleMind(6_000)
    }

    private val keyTitle = LinkedHashMap<String, String>()

    private fun onNotification(n: RedshotNotifs.N) {
        if (n.canReply) {
            Convo.add(n.title, "them", n.text)
            synchronized(keyTitle) { keyTitle[n.key] = n.title; while (keyTitle.size > 80) keyTitle.remove(keyTitle.keys.first()) }
            if (AwayMode.active) AwayLog.add(n.title + " ne likha: " + n.text)
        }
        synchronized(mindEvents) {
            val o = JSONObject().put("kind", "notif").put("pkg", n.pkg).put("text", (n.title + ": " + n.text).take(300))
            if (n.canReply) {
                o.put("key", n.key).put("from", n.title.take(70))
                o.put("thread", JSONArray(Convo.thread(n.title)))
            }
            mindEvents.add(o)
            while (mindEvents.size > 30) mindEvents.removeAt(0)
        }
        // when the owner is away, chat messages are answered quickly
        scheduleMind(if (AwayMode.active && n.canReply) 2_500 else 20_000)
    }

    private fun scheduleMind(delayMs: Long) {
        if (mindScheduled || muted) return
        mindScheduled = true
        bg.schedule(Runnable { mindScheduled = false; runMind() }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun mindTick() {
        try {
            if (muted) return
            val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            if (h < 6) return
            val cal = Executor.calendar(this, 4)
            synchronized(mindEvents) {
                if (!cal.startsWith("Calendar mein") && !cal.startsWith("error")) mindEvents.add(JSONObject().put("kind", "calendar").put("text", cal.take(500)))
                mindEvents.add(JSONObject().put("kind", "tick").put("text", "samay-samay ki jaanch"))
            }
            runMind()
        } catch (e: Exception) {}
    }

    private fun runMind() {
        try {
            val now = System.currentTimeMillis()
            if (now - lastMindSend < 8_000L) { scheduleMind(9_000); return }
            val batch = JSONArray()
            synchronized(mindEvents) {
                if (mindEvents.isEmpty()) return
                val f = java.text.SimpleDateFormat("h:mm a", java.util.Locale.ENGLISH)
                for (e in mindEvents) batch.put(e.put("at", f.format(java.util.Date())))
                mindEvents.clear()
            }
            lastMindSend = now
            val body = JSONObject().put("events", batch).put("pkg", RedshotAccess.currentPackage)
                .put("did", ActionLog.recent(8))
            if (AwayMode.active) body.put("away", AwayMode.describe())
            synchronized(told) { body.put("told", JSONArray(told.takeLast(8))) }
            val r = Brain.post(this, "device/mind", body, 27000) ?: run { mindLast = "mind fail: " + Brain.lastError; return }
            val say = r.optString("say", "")
            val note = r.optString("notify", "")
            val acts = r.optJSONArray("actions")
            if (say.isBlank() && note.isBlank() && (acts == null || acts.length() == 0)) { mindLast = "kuch karne layak nahi"; return }
            if (acts != null) for (i in 0 until acts.length()) {
                val a = acts.optJSONObject(i) ?: continue
                val res = Executor.run(this, a)
                ActionLog.add("(khud se) " + a.toString().take(150) + " -> " + (res ?: "ok"))
                if (a.optString("type") == "reply" && res == null) {
                    val who = synchronized(keyTitle) { keyTitle[a.optString("key")] } ?: "kisi"
                    Convo.add(who, "me", a.optString("text"))
                    AwayLog.add(who + " ko jawab diya: " + a.optString("text"))
                }
            }
            val msg = if (say.isNotBlank()) say else note
            if (msg.isNotBlank()) {
                synchronized(told) { if (told.contains(msg)) return; told.add(msg); while (told.size > 20) told.removeAt(0) }
                ActionLog.add("(khud se) owner ko bataya: " + msg.take(120))
                if (AwayMode.active) AwayLog.add("zaroori: " + msg)
                val canTalk = say.isNotBlank() && !muted && System.currentTimeMillis() - lastMindSay > 90_000L
                if (canTalk) { lastMindSay = System.currentTimeMillis(); if (!speakViaLive(say)) speaker?.say(say, "hi-IN") }
                else Executor.notify(this, msg)
            }
            mindLast = "kiya: " + (if (msg.isNotBlank()) msg else "action").take(80)
            log("mind", "say=$say notify=$note acts=" + (acts?.toString() ?: "[]").take(300))
        } catch (e: Exception) { mindLast = "mind error: " + e.message }
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
        val waiting = bg.schedule(Runnable {
            speaker?.say(filler, "hi-IN")
        }, 1700, java.util.concurrent.TimeUnit.MILLISECONDS)

        val t0 = System.currentTimeMillis()
        val res = Brain.post(this, "device/hear", body, 14000)
        waiting.cancel(false)
        if (res == null) {
            route = "brain fail: " + Brain.lastError
            log("error", "brain fail: " + Brain.lastError)
            offlineNotice(Brain.lastError)
            return
        }
        route = "brain: " + (System.currentTimeMillis() - t0) + "ms"
        log("brain", "heard: " + text.take(120) + " -> say: " + res.optString("say").take(160) + " acts=" + (res.optJSONArray("actions")?.length() ?: 0))
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
        windowUntil = System.currentTimeMillis() + (if (say.isNotBlank()) 25_000L else 8_000L)
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
