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
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
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
        val enrolling: Boolean get() = (instance?.ear?.enrollWanted ?: 0) > 0

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
    private val mindKeys = Regex("(baje|बजे|\\bkal\\b|कल|subah|सुबह|shaam|शाम|raat|class|क्लास|exam|एग्जाम|test|टेस्ट|train|ट्रेन|tatkal|तत्काल|book|बुक|alarm|अलार्म|yaad|याद|remind|meeting|मीटिंग|deadline|submit|interview|ticket|टिकट|payment|bill|fees|फीस|tomorrow|tonight|\\d\\s*(am|pm))", RegexOption.IGNORE_CASE)

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
        instance = this
        AutoScroll.appCtx = applicationContext
        Voiceprint.load(this)
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "redshot:ear").also { it.setReferenceCounted(false); it.acquire() }
        } catch (e: Exception) {}
        RedshotNotifs.onNew = { n -> onNotification(n) }
        keepAlive.scheduleWithFixedDelay(Runnable { flushLog() }, 6, 6, java.util.concurrent.TimeUnit.SECONDS)
        keepAlive.scheduleWithFixedDelay(Runnable { mindTick() }, 3, 15, java.util.concurrent.TimeUnit.MINUTES)
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
            ear = Ear(this, { text, pcm, wake, dist -> onHeard(text, pcm, wake, dist) }, { s -> state = s },
                { prewarm() }, { onUserVoice() }, { onBarge() }).also { it.start() }
        }
        return START_STICKY
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
        keepAlive.shutdownNow()
        super.onDestroy()
    }

    // ---------- one spoken sentence ----------

    private val acks = listOf("हाँ भाई, बोल।", "हाँ, बता।", "जी, बोलो।", "हाँ भाई, सुन रहा हूँ।")
    private val filler = "हम्म, एक सेकंड..."

    private fun musicOn(): Boolean = try { (getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive } catch (e: Exception) { false }

    private fun onHeard(text: String, pcm: ShortArray, wake: Int, dist: Double) {
        val now = System.currentTimeMillis()
        if (text.isNotEmpty() && text == lastText && now - lastTextAt < 3000) return
        lastText = text
        lastTextAt = now
        val strict = musicOn()
        val mine = !Voiceprint.enrolled || dist == -2.0 || Voiceprint.accept(dist, strict)
        heard = (if (wake > 0) "[Redshot suna] " else "") + (if (text.isEmpty()) "(kuch samajh nahi aaya)" else text) +
            (if (dist >= 0) "  (meri awaaz? %.2f)".format(dist) else "")

        // A live conversation is already open and the mic is in sentence mode (something is playing on the phone):
        // only the owner's sentences are passed on.
        if (liveActive && !earInLive) {
            val accept = if (Voiceprint.enrolled) (dist >= 0 && Voiceprint.accept(dist, true)) || (dist == -2.0 && wake > 0)
                         else wake > 0
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

            // Only the owner's voice may command (reels, TV, other people are ignored)
            if (!mine) { route = "awaaz teri nahi lagi (%.2f), ignore".format(dist); return@execute }

            val cmd = (cleaned ?: text).trim()
            val seconds = pcm.size / 16000.0
            val words = cmd.split(" ").filter { it.isNotBlank() }.size

            // short phone commands run locally and instantly; long/open sentences go to the live brain
            val handled = try { cmd.isNotEmpty() && words <= 7 && Router.handle(this, cmd, false, say, setMuted) } catch (e: Exception) { false }

            // just the name, nothing else (or only a junk word next to it)
            if (!handled && (cmd.isEmpty() || (cleaned != null && seconds < 1.3 && words <= 1))) {
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

    // ---------- logging (so problems can be fixed from facts) ----------

    private fun log(kind: String, content: String) {
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
            val r = Brain.post(this, "device/log", JSONObject().put("items", arr), 10000)
            if (r == null) { /* offline: drop, never block */ }
        } catch (e: Exception) {}
    }

    // ---------- live conversation ----------

    private fun loadLiveConfig(): Boolean {
        val now = System.currentTimeMillis()
        if (liveKey != null && liveSetup != null && now - liveCfgAt < 10 * 60_000L) return true
        if (now - liveCfgFailAt < 20_000L) return liveKey != null && liveSetup != null
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

    private fun newSession(): LiveSession? {
        val k = liveKey ?: return null
        var st = liveSetup ?: return null
        // short memory across sessions: what was just talked about (within 3 minutes)
        if (convoLog.isNotEmpty() && System.currentTimeMillis() - lastLiveEndAt < 3 * 60_000L) {
            try {
                st = JSONObject(st.toString())
                val part = st.getJSONObject("setup").getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0)
                part.put("text", part.getString("text") + "\n\nABHI KI PICHHLI BAAT-CHEET (isi ka silsila):\n" + convoLog.toString().takeLast(900))
            } catch (e: Exception) {}
        }
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
            if (liveActive) { s.sendBurst(pcm); onUserVoice(); return true }
            liveActive = true
            liveStartedAt = System.currentTimeMillis()
            lastLiveActivity = liveStartedAt
            silentTurns = 0
            userVoiceSinceTurn = true
            fallback = Triple(cmd, pcm, wakeLevel)
            liveHeard = ""; liveSaid = ""
            route = "live: Redshot ne turant suna"
            enterCallAudio()
            s.sendBurst(pcm)
            applyEarMode()
            log("wake", "heard: $cmd")
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

    private fun closeLive(reason: String) {
        var s: LiveSession? = null
        var was = false
        synchronized(liveLock) {
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
        if (was) lastLiveEndAt = System.currentTimeMillis()
        try { s?.close() } catch (e: Exception) {}
        live = "band: $reason"
        if (was) log("live_end", reason)
    }

    private fun liveWatchdog() {
        try {
            // the ears must never stay dead: restart them if the thread ended
            if (ear?.running == false) {
                log("error", "ear restart: " + earError + " state=" + state)
                ear?.stop()
                ear = Ear(this, { text, pcm, wake, dist -> onHeard(text, pcm, wake, dist) }, { s -> state = s },
                    { prewarm() }, { onUserVoice() }, { onBarge() }).also { it.start() }
            }
            val now = System.currentTimeMillis()
            val s = session
            if (!liveActive) {
                // a pre-opened connection nobody used
                if (s != null && now - openedAt > 8_000) closeLive("prewarm khatam")
                return
            }
            // connection that never became ready: hand the sentence to the normal brain right away
            if (s != null && !s.ready && now - liveStartedAt > 5_000) {
                val fb = fallback
                fallback = null
                closeLive("live connect nahi hua")
                if (fb != null) io.execute { askBrain(fb.first, fb.second, fb.third) }
                return
            }
            applyEarMode()
            if (!modelSpeaking && now - lastLiveActivity > 12_000) closeLive("chup rehne se band")
            else if (now - liveStartedAt > 8 * 60_000L) closeLive("8 min poore")
        } catch (e: Exception) {}
    }

    private fun onUserVoice() {
        lastLiveActivity = System.currentTimeMillis()
        userVoiceSinceTurn = true
    }

    /** Owner clearly talked over Redshot: stop its voice immediately. */
    private fun onBarge() {
        session?.cutPlayback()
        modelSpeaking = false
        ear?.liveGate = false
        userVoiceSinceTurn = true
        lastLiveActivity = System.currentTimeMillis()
    }

    // ----- audio route: loud speaker (not the ear-piece), call-style echo cancellation -----

    private var savedVoiceVol = -1

    private fun enterCallAudio() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            savedAudioMode = am.mode
            @Suppress("DEPRECATION")
            savedSpeaker = am.isSpeakerphoneOn
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val headset = outs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            if (Build.VERSION.SDK_INT >= 31) {
                val cands = am.availableCommunicationDevices
                val target = (if (headset != null) cands.firstOrNull { it.id == headset.id } else null)
                    ?: cands.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (target != null) am.setCommunicationDevice(target)
            } else if (headset == null) {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = true
            }
            // call-volume is a separate (often low) slider: lift it so the voice is clearly audible
            savedVoiceVol = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            if (savedVoiceVol < (max * 0.8).toInt()) am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (max * 0.85).toInt(), 0)
        } catch (e: Exception) { log("error", "audio route: " + e.message) }
    }

    private fun leaveCallAudio() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = savedSpeaker
            am.mode = savedAudioMode
            if (savedVoiceVol >= 0) am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedVoiceVol, 0)
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
            if (speaking) {
                live = "live: bol raha hoon"
                ear?.liveGate = true
            } else {
                // keep the mic closed a moment longer so the tail of its own voice is never heard
                keepAlive.schedule(Runnable { if (!modelSpeaking) ear?.liveGate = false }, 400, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }

        override fun onTurnComplete() {
            lastLiveActivity = System.currentTimeMillis()
            if (turnUser.isNotEmpty() || turnModel.isNotEmpty()) {
                log("live_user", turnUser.toString().trim())
                log("live_redshot", turnModel.toString().trim())
                convoLog.append("Owner: ").append(turnUser.toString().trim()).append("\nRedshot: ").append(turnModel.toString().trim()).append('\n')
                if (convoLog.length > 1500) convoLog.delete(0, convoLog.length - 1200)
                turnUser = StringBuilder(); turnModel = StringBuilder()
            }
            if (!liveActive) return
            // loop breaker: model keeps answering but the owner never spoke (echo / noise)
            if (userVoiceSinceTurn) silentTurns = 0 else silentTurns++
            userVoiceSinceTurn = false
            if (silentTurns >= 3) io.execute { closeLive("khud se baat ho rahi thi") }
        }

        override fun onUserText(t: String) {
            turnUser.append(t)
            liveHeard += t; if (liveHeard.length > 160) liveHeard = liveHeard.takeLast(160)
            heard = "[live] " + liveHeard
            fallback = null
            if (t.length > 1) mindAddSpeech(t)
        }

        override fun onModelText(t: String) {
            turnModel.append(t)
            liveSaid += t; if (liveSaid.length > 160) liveSaid = liveSaid.takeLast(160)
        }

        override fun onClosed(reason: String, wasReady: Boolean) {
            val fb = fallback
            val wasActive = liveActive
            fallback = null
            io.execute {
                if (session?.isClosed == true) session = null
                if (wasActive) {
                    closeLive(reason)
                    // dropped unexpectedly in the middle of talking: the next sentence needs no "Redshot"
                    windowUntil = System.currentTimeMillis() + 20_000
                } else live = "band: $reason"
                log("live_closed", "$reason ready=$wasReady")
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

    // ---------- proactive mind: sees notifications, hears owner's own talk, decides by itself ----------

    private fun mindAddSpeech(text: String) {
        synchronized(mindEvents) {
            mindEvents.add(JSONObject().put("kind", "speech").put("text", text.take(300)))
            while (mindEvents.size > 30) mindEvents.removeAt(0)
        }
        if (mindKeys.containsMatchIn(text)) scheduleMind(6_000)
    }

    private fun onNotification(n: RedshotNotifs.N) {
        synchronized(mindEvents) {
            mindEvents.add(JSONObject().put("kind", "notif").put("pkg", n.pkg).put("text", (n.title + ": " + n.text).take(300)))
            while (mindEvents.size > 30) mindEvents.removeAt(0)
        }
        scheduleMind(20_000)
    }

    private fun scheduleMind(delayMs: Long) {
        if (mindScheduled || muted) return
        mindScheduled = true
        keepAlive.schedule(Runnable {
            mindScheduled = false
            io.execute { runMind() }
        }, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
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
            if (now - lastMindSend < 15_000L) return
            val batch = JSONArray()
            synchronized(mindEvents) {
                if (mindEvents.isEmpty()) return
                val f = java.text.SimpleDateFormat("h:mm a", java.util.Locale.ENGLISH)
                for (e in mindEvents) batch.put(e.put("at", f.format(java.util.Date())))
                mindEvents.clear()
            }
            lastMindSend = now
            val body = JSONObject().put("events", batch).put("pkg", RedshotAccess.currentPackage)
            synchronized(told) { body.put("told", JSONArray(told.takeLast(8))) }
            val r = Brain.post(this, "device/mind", body, 20000) ?: run { mindLast = "mind fail: " + Brain.lastError; return }
            val say = r.optString("say", "")
            val note = r.optString("notify", "")
            val acts = r.optJSONArray("actions")
            if (say.isBlank() && note.isBlank() && (acts == null || acts.length() == 0)) { mindLast = "kuch karne layak nahi"; return }
            if (acts != null) for (i in 0 until acts.length()) { val a = acts.optJSONObject(i) ?: continue; Executor.run(this, a) }
            val msg = if (say.isNotBlank()) say else note
            if (msg.isNotBlank()) {
                synchronized(told) { if (told.contains(msg)) return; told.add(msg); while (told.size > 20) told.removeAt(0) }
                val canTalk = say.isNotBlank() && !liveActive && !muted && System.currentTimeMillis() - lastMindSay > 120_000L
                if (canTalk) { lastMindSay = System.currentTimeMillis(); speaker?.say(say, "hi-IN") } else Executor.notify(this, msg)
            }
            mindLast = "kiya: " + (if (msg.isNotBlank()) msg else "action").take(80)
            log("mind", "say=$say notify=$note acts=" + (acts?.toString() ?: "[]").take(200))
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
        val waiting = keepAlive.schedule(Runnable {
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
