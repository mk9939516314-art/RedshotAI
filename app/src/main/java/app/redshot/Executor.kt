package app.redshot

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.content.ContentUris
import android.app.NotificationChannel
import android.app.NotificationManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import org.json.JSONObject
import java.util.Locale

object Executor {
    private var torchOn = false

    /** Start an activity even when Redshot has no screen open (accessibility service is allowed to). */
    fun startBg(ctx: Context, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val c: Context = RedshotAccess.instance ?: ctx
        c.startActivity(intent)
    }

    /** Runs one action from the brain. Returns a short result note, or null if everything went fine. */
    fun run(ctx: Context, a: JSONObject): String? {
        return try {
            when (a.optString("type")) {
                "open_app" -> {
                    val app = Apps.findByName(ctx, a.optString("app"))
                    if (app != null && Apps.launch(ctx, app)) null else "app nahi mila: " + a.optString("app")
                }
                "open_url" -> {
                    startBg(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(a.optString("url"))))
                    null
                }
                "alarm" -> {
                    var hh = a.optInt("hour")
                    var mm = a.optInt("minute")
                    if (a.has("in_minutes") && a.optInt("in_minutes") > 0) {
                        val c = Calendar.getInstance()
                        c.add(Calendar.MINUTE, a.optInt("in_minutes"))
                        hh = c.get(Calendar.HOUR_OF_DAY); mm = c.get(Calendar.MINUTE)
                    }
                    val i = Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, hh)
                        .putExtra(AlarmClock.EXTRA_MINUTES, mm)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Redshot"))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    startBg(ctx, i)
                    null
                }
                "timer" -> {
                    val i = Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, a.optInt("seconds", 60))
                        .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Redshot"))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    startBg(ctx, i)
                    null
                }
                "call" -> {
                    val n = a.optString("number").filter { it.isDigit() || it == '+' }
                    if (n.length < 3) "number galat hai"
                    else { startBg(ctx, Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(n)))); null }
                }
                "call_contact" -> callContact(ctx, a.optString("contact"))
                "torch" -> {
                    setTorch(ctx, if (a.has("on")) a.optBoolean("on") else !torchOn)
                    null
                }
                "volume" -> {
                    volume(ctx, a.optString("dir"))
                    null
                }
                "media" -> {
                    media(ctx, a.optString("name").ifBlank { a.optString("dir") })
                    null
                }
                "global" -> {
                    if (RedshotAccess.instance?.global(a.optString("name")) == true) null
                    else "accessibility band hai"
                }
                "scroll" -> {
                    if (RedshotAccess.instance?.scroll(a.optString("dir")) == true) null
                    else "scroll nahi hua"
                }
                "tap_text" -> {
                    if (RedshotAccess.instance?.tapText(a.optString("text")) == true) null
                    else "tap nahi hua: " + a.optString("text")
                }
                "type_text" -> {
                    if (RedshotAccess.instance?.typeText(a.optString("text")) == true) null
                    else "type nahi hua"
                }
                "settings" -> openSettings(ctx, a.optString("name").ifBlank { a.optString("page") })
                "navigate" -> {
                    val q = a.optString("query")
                    val i = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(q)))
                    try { startBg(ctx, i) } catch (e: ActivityNotFoundException) {
                        startBg(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q))))
                    }
                    null
                }
                "play" -> play(ctx, a.optString("query"), a.optString("app"))
                "tap_xy" -> {
                    if (RedshotAccess.instance?.tapPercent(a.optInt("x"), a.optInt("y")) == true) null else "tap nahi hua (accessibility band?)"
                }
                "swipe" -> {
                    if (RedshotAccess.instance?.swipe(a.optString("dir")) == true) null else "swipe nahi hua (accessibility band?)"
                }
                "whatsapp_call" -> whatsappCall(ctx, a.optString("contact"), a.optBoolean("video"))
                "whatsapp_message" -> whatsappMessage(ctx, a.optString("contact"), a.optString("text"))
                "whatsapp_open_chat" -> whatsappOpenChat(ctx, a.optString("contact"))
                "reply" -> { val r = RedshotNotifs.sendReply(a.optString("key"), a.optString("text")); if (r.startsWith("ok")) null else r }
                "notify" -> { notify(ctx, a.optString("text")); null }
                "auto_scroll" -> {
                    if (a.optBoolean("stop")) AutoScroll.stop() else AutoScroll.start(a.optInt("every_seconds", 20).coerceIn(3, 600))
                    null
                }
                else -> null
            }
        } catch (e: SecurityException) {
            "permission nahi hai (" + (e.message ?: "") + ")"
        } catch (e: Exception) {
            "error: " + (e.message ?: e.javaClass.simpleName)
        }
    }


    /** Same as run(), but for tools whose answer is data (read_screen, calendar, notifications). Always returns text for the model. */
    fun runForResult(ctx: Context, a: JSONObject): String {
        return try {
            when (a.optString("type")) {
                "read_screen" -> {
                    val w = a.optLong("wait_ms", 700).coerceIn(0, 4000)
                    if (w > 0) Thread.sleep(w)
                    val acc = RedshotAccess.instance ?: return "error: accessibility service band hai, isliye screen nahi padh sakta (Settings > Accessibility > Redshot on karo)"
                    acc.readScreen()
                }
                "notifications_read" -> RedshotNotifs.summary(a.optInt("count", 12).coerceIn(1, 40))
                "calendar" -> calendar(ctx, a.optInt("hours", 48).coerceIn(1, 24 * 14))
                else -> {
                    val viaAccess = Access.run(ctx, a.optString("type"), a)
                    if (viaAccess != null) {
                        if (!viaAccess.startsWith("error")) logAction(a, viaAccess)
                        viaAccess
                    } else {
                        val note = run(ctx, a)
                        if (note == null) { logAction(a, "ok"); "ok" } else "error: $note"
                    }
                }
            }
        } catch (e: Exception) {
            "error: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    private val logged = setOf("alarm", "timer", "call", "call_contact", "whatsapp_call", "whatsapp_message", "whatsapp_open_chat", "sms_send", "email_send",
        "calendar_add", "set_away", "reply_notification", "open_app", "play", "notify", "auto_scroll", "navigate")

    private fun logAction(a: JSONObject, res: String) {
        val t = a.optString("type")
        if (t in logged) ActionLog.add(t + " " + a.toString().take(160) + " -> " + res.take(60))
    }

    fun calendar(ctx: Context, hours: Int): String {
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
            return "error: calendar ki permission nahi di (Redshot app kholke allow karo)"
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now); ContentUris.appendId(it, now + hours * 3600_000L)
        }.build()
        val fmt = SimpleDateFormat("EEE d MMM, h:mm a", Locale.ENGLISH)
        val sb = StringBuilder()
        ctx.contentResolver.query(
            uri,
            arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.EVENT_LOCATION),
            null, null, CalendarContract.Instances.BEGIN + " ASC"
        )?.use { c ->
            var n = 0
            while (c.moveToNext() && n < 30) {
                n++
                sb.append("- ").append(fmt.format(Date(c.getLong(1)))).append(" to ").append(fmt.format(Date(c.getLong(2))).substringAfter(", "))
                    .append(": ").append(c.getString(0) ?: "(no title)")
                val loc = c.getString(3)
                if (!loc.isNullOrBlank()) sb.append(" @ ").append(loc)
                sb.append('\n')
            }
        }
        return if (sb.isEmpty()) "Calendar mein agle $hours ghante mein koi event nahi." else sb.toString()
    }

    fun notify(ctx: Context, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("redshot_alert", "Redshot yaad dilata hai", NotificationManager.IMPORTANCE_HIGH))
        val n = android.app.Notification.Builder(ctx, "redshot_alert")
            .setSmallIcon(R.drawable.ic_redshot)
            .setContentTitle("Redshot")
            .setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 100000).toInt() + 100, n)
    }

    // ---------- WhatsApp ----------

    private fun waPackage(ctx: Context): String? {
        val installed = Apps.list(ctx).map { it.pkg }.toSet()
        return listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { it in installed }
    }

    /** Best matching WhatsApp contact data row id for a spoken name, using the voice/video call rows WhatsApp adds to Contacts. */
    private fun waDataRow(ctx: Context, name: String, mime: String): Long? {
        var best: Long? = null
        var bestScore = 0
        ctx.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID, ContactsContract.Data.DISPLAY_NAME),
            ContactsContract.Data.MIMETYPE + "=?", arrayOf(mime), null
        )?.use { c ->
            while (c.moveToNext()) {
                val sc = nameScore(c.getString(1) ?: "", name)
                if (sc > bestScore) { bestScore = sc; best = c.getLong(0) }
            }
        }
        return if (bestScore >= 40) best else null
    }

    private fun whatsappCall(ctx: Context, name: String, video: Boolean): String? {
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            return "contacts ki permission nahi di"
        val pkg = waPackage(ctx) ?: return "phone mein WhatsApp installed nahi hai"
        val mime = if (video) "vnd.android.cursor.item/vnd.com.whatsapp.video.call" else "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
        val id = waDataRow(ctx, name, mime)
            ?: return "WhatsApp contacts mein '$name' naam ka koi contact nahi mila (naam thoda alag save ho sakta hai)"
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse("content://com.android.contacts/data/$id"), mime)
            .setPackage(pkg)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startBg(ctx, i)
        return null
    }

    /** Opens the conversation itself (not the profile picture): uses WhatsApp's own "message" contact row. */
    private fun whatsappOpenChat(ctx: Context, name: String): String? {
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            return "contacts ki permission nahi di"
        val pkg = waPackage(ctx) ?: return "phone mein WhatsApp installed nahi hai"
        val mime = "vnd.android.cursor.item/vnd.com.whatsapp.profile"
        val id = waDataRow(ctx, name, mime)
        if (id != null) {
            startBg(ctx, Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://com.android.contacts/data/$id"), mime).setPackage(pkg)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            return null
        }
        // not found as a WhatsApp contact: try by phone number
        val num = resolveContact(ctx, name)?.filter { it.isDigit() } ?: return "'$name' naam ka contact nahi mila"
        val full = if (num.length == 10) "91$num" else num
        startBg(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$full")).setPackage(pkg))
        return null
    }

    private fun whatsappMessage(ctx: Context, name: String, text: String): String? {
        val pkg = waPackage(ctx) ?: return "phone mein WhatsApp installed nahi hai"
        var num = name.filter { it.isDigit() || it == '+' }
        if (num.length < 7) num = resolveContact(ctx, name)?.filter { it.isDigit() || it == '+' } ?: ""
        if (num.length < 7) return "'$name' ka number contacts mein nahi mila"
        num = num.removePrefix("+")
        if (num.length == 10) num = "91$num"
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$num?text=" + Uri.encode(text))).setPackage(pkg)
        startBg(ctx, i)
        // wait for the chat to open, then press Send
        val acc = RedshotAccess.instance ?: return "message likh diya hai par accessibility band hai isliye Send nahi dab paya"
        for (k in 0 until 8) {
            Thread.sleep(700)
            if (acc.tapText("Send") || acc.tapText("भेजें")) return null
        }
        return "WhatsApp chat khul gayi aur message likha hai, par Send button nahi mila"
    }

    // ---------- contact matching ----------

    fun nameScore(display: String, spoken: String): Int {
        val want = Apps.norm(spoken)
        val n = Apps.norm(display)
        if (want.isEmpty() || n.isEmpty()) return 0
        val keys = HashSet<String>().apply {
            add(want)
            for ((_, group) in family) if (group.any { Apps.norm(it) == want }) group.forEach { add(Apps.norm(it)) }
        }
        var score = 0
        val sk = Apps.skel(display)
        if (sk.length >= 3 && sk == Apps.skel(spoken)) score = 55
        for (k in keys) {
            if (n == k) score = maxOf(score, 100)
            else if (n.startsWith(k)) score = maxOf(score, 80)
            else if (n.contains(k) && k.length >= 3) score = maxOf(score, 60)
            else if (k.contains(n) && n.length >= 3) score = maxOf(score, 40)
        }
        return score
    }

    // ---------- device controls ----------

    fun setTorch(ctx: Context, on: Boolean) {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return
        cm.setTorchMode(id, on)
        torchOn = on
    }

    fun volume(ctx: Context, dir: String) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val d = if (dir == "up") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        // two steps feels right for a spoken command
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, d, AudioManager.FLAG_SHOW_UI)
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, d, 0)
    }

    fun media(ctx: Context, name: String) {
        val code = when (name.lowercase(Locale.ROOT)) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    fun openSettings(ctx: Context, page: String): String? {
        val action = when (page.lowercase(Locale.ROOT)) {
            "wifi", "wi-fi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display", "brightness" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound", "volume" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "location", "gps" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "airplane", "flight" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "nfc" -> Settings.ACTION_NFC_SETTINGS
            "data", "mobile_data" -> Settings.ACTION_DATA_ROAMING_SETTINGS
            "hotspot" -> "android.settings.TETHER_SETTINGS"
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "date", "time" -> Settings.ACTION_DATE_SETTINGS
            "language" -> Settings.ACTION_LOCALE_SETTINGS
            "dnd" -> "android.settings.ZEN_MODE_SETTINGS"
            else -> Settings.ACTION_SETTINGS
        }
        return try { startBg(ctx, Intent(action)); null } catch (e: Exception) {
            try { startBg(ctx, Intent(Settings.ACTION_SETTINGS)); null } catch (e2: Exception) { "settings nahi khuli" }
        }
    }

    // ---------- play / call ----------

    private fun play(ctx: Context, query: String, app: String): String? {
        if (query.isBlank()) {
            // "song play kar": resume what was playing; if nothing starts, put on some music
            media(ctx, "play")
            Thread {
                try {
                    Thread.sleep(1000)
                    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    if (!am.isMusicActive) playSearch(ctx, "latest hindi songs", app)
                } catch (e: Exception) {}
            }.start()
            return null
        }
        return playSearch(ctx, query, app)
    }

    private fun playSearch(ctx: Context, query: String, app: String): String? {
        val installed = Apps.list(ctx).map { it.pkg }.toSet()
        val wanted = Apps.findByName(ctx, app)?.pkg
        val order = if (wanted != null) listOf(wanted)
        else listOf("com.spotify.music", "com.google.android.apps.youtube.music").filter { it in installed }

        for (pkg in order) {
            if (pkg == "com.google.android.youtube") break
            try {
                val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                    .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    .putExtra(android.app.SearchManager.QUERY, query)
                    .setPackage(pkg)
                startBg(ctx, i)
                return null
            } catch (e: ActivityNotFoundException) { /* try next */ }
        }
        val url = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query)))
        if ("com.google.android.youtube" in installed) url.setPackage("com.google.android.youtube")
        startBg(ctx, url)
        return null
    }

    private val family = mapOf(
        "mummy" to listOf("mummy", "mom", "mother", "maa", "ma", "mama", "mummyji", "मम्मी", "माँ", "मां", "मम्मा"),
        "papa" to listOf("papa", "dad", "father", "pappa", "पापा", "डैड", "पिताजी"),
        "bhai" to listOf("bhai", "brother", "bro", "भाई"),
        "behen" to listOf("behen", "didi", "sister", "sis", "बहन", "दीदी")
    )

    /** Phone number of the best matching contact, or null (no permission / no match). */
    fun resolveContact(ctx: Context, name: String): String? {
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val want = Apps.norm(name)
        if (want.isEmpty()) return null
        val keys = HashSet<String>().apply {
            add(want)
            for ((_, group) in family) if (group.any { Apps.norm(it) == want }) group.forEach { add(Apps.norm(it)) }
        }
        val spokenSkel = Apps.skel(name)
        var bestNum: String? = null
        var bestScore = 0
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.IS_PRIMARY
            ), null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val n = Apps.norm(c.getString(0) ?: "")
                val num = c.getString(1) ?: continue
                if (n.isEmpty()) continue
                var score = 0
                val sk = Apps.skel(c.getString(0) ?: "")
                if (sk.length >= 3 && sk == spokenSkel) score = 55
                for (k in keys) {
                    if (n == k) score = maxOf(score, 100)
                    else if (n.startsWith(k)) score = maxOf(score, 80)
                    else if (n.contains(k) && k.length >= 3) score = maxOf(score, 60)
                    else if (k.contains(n) && n.length >= 3) score = maxOf(score, 40)
                }
                if (c.getInt(2) > 0) score += 1
                if (score > bestScore) { bestScore = score; bestNum = num }
            }
        }
        return bestNum
    }

    private fun callContact(ctx: Context, name: String): String? {
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            return "contacts ki permission nahi di"
        val num = resolveContact(ctx, name) ?: return "contact nahi mila: $name"
        startBg(ctx, Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(num))))
        return null
    }
}
