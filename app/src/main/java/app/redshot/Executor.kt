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
                    val i = Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, a.optInt("hour"))
                        .putExtra(AlarmClock.EXTRA_MINUTES, a.optInt("minute"))
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
                else -> null
            }
        } catch (e: SecurityException) {
            "permission nahi hai (" + (e.message ?: "") + ")"
        } catch (e: Exception) {
            "error: " + (e.message ?: e.javaClass.simpleName)
        }
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

    private fun callContact(ctx: Context, name: String): String? {
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            return "contacts ki permission nahi di"
        val want = Apps.norm(name)
        if (want.isEmpty()) return "kisko call karna hai?"
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
        val num = bestNum ?: return "contact nahi mila: $name"
        startBg(ctx, Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(num))))
        return null
    }
}
