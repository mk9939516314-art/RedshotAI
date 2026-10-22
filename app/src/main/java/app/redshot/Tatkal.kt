package app.redshot

import android.content.Context
import android.content.Intent
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Tatkal / any-ticket booking tools. The page work is done by assets/tatkal/engine.js inside TatkalActivity. */
object Tatkal {
    private const val P = "tatkal"
    private const val KEY = "redshot_tatkal"
    @Volatile private var matcher: StationMatcher? = null
    @Volatile var lastJob: JSONObject? = null
    private val handler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    @Volatile private var pending: Runnable? = null

    fun matcher(ctx: Context): StationMatcher {
        matcher?.let { return it }
        val lines = ctx.assets.open("tatkal/stations.txt").bufferedReader().use { it.readLines() }
        return StationMatcher(lines).also { matcher = it }
    }

    // ---- secret storage (IRCTC password never leaves the phone, never goes to the brain) ----
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }
    private fun enc(s: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(s.toByteArray()), Base64.NO_WRAP)
    }
    private fun dec(s: String): String = try {
        val b = Base64.decode(s, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b.copyOfRange(0, 12)))
        String(c.doFinal(b, 12, b.size - 12))
    } catch (e: Exception) { "" }

    private fun sp(ctx: Context) = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
    fun profile(ctx: Context): JSONObject = try { JSONObject(sp(ctx).getString("profile", "{}")!!) } catch (e: Exception) { JSONObject() }
    fun password(ctx: Context): String = dec(sp(ctx).getString("pw", "") ?: "")

    // ---- opening time of the booking window (IST) ----
    private val IST = TimeZone.getTimeZone("Asia/Kolkata")
    /** epoch ms when tatkal opens for this journey date (DD/MM/YYYY) and class; 0 = no waiting needed. */
    fun openAt(date: String, cls: String, quota: String, now: Long = System.currentTimeMillis()): Long {
        val f = SimpleDateFormat("dd/MM/yyyy", Locale.US).apply { timeZone = IST; isLenient = false }
        val d = try { f.parse(date) } catch (e: Exception) { return 0 } ?: return 0
        val tatkal = quota.uppercase() in setOf("TQ", "PT")
        val c = Calendar.getInstance(IST).apply {
            time = d
            if (tatkal) { add(Calendar.DAY_OF_MONTH, -1); set(Calendar.HOUR_OF_DAY, if (cls.uppercase() in setOf("SL", "2S")) 11 else 10) }
            else { add(Calendar.DAY_OF_MONTH, -60); set(Calendar.HOUR_OF_DAY, 8) }     // advance booking (ARP 60 days) opens 08:00 IST
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        return if (c.timeInMillis > now) c.timeInMillis else 0
    }

    private fun station(ctx: Context, v: String): Triple<String, String, String>? {
        val h = matcher(ctx).find(v, 3)
        val top = h.firstOrNull() ?: return null
        if (top.score < 85) return Triple("", "", h.joinToString(" / ") { "${it.name} (${it.code})" })
        return Triple(top.code, top.name, "")
    }

    fun run(ctx: Context, name: String, a: JSONObject): String? = when (name) {
        "station_find" -> {
            val h = matcher(ctx).find(a.optString("query"), 5)
            if (h.isEmpty()) "koi station nahi mila" else h.joinToString("\n") { "${it.name} = ${it.code} (match ${it.score})" } +
                (if (h[0].score < 85) "\nSURE NAHI: owner se poochh kaun sa." else "")
        }
        "tatkal_profile_set" -> saveProfile(ctx, a)
        "tatkal_setup_open" -> { Executor.startBg(ctx, Intent(ctx, TatkalSetupActivity::class.java)); "ok: setup screen khol di. Owner wahan IRCTC id, password, mobile, passengers khud type karega (password bolke nahi)." }
        "tatkal_profile_get" -> describe(ctx)
        "tatkal_plan" -> {
            val t = openAt(a.optString("date"), a.optString("cls", "3A"), a.optString("quota", "TQ"))
            if (t == 0L) "Booking abhi khuli hai: turant start ho sakta hai."
            else { val f = SimpleDateFormat("dd MMM HH:mm:ss", Locale.US).apply { timeZone = IST }; "Booking khulegi ${f.format(t)} IST (${(t - System.currentTimeMillis()) / 60000} min baad). openAtMs=$t" }
        }
        "tatkal_start" -> start(ctx, a)
        "tatkal_status" -> TatkalState.describe()
        "tatkal_stop" -> { pending?.let { handler.removeCallbacks(it) }; pending = null; cancelAlarm(ctx); TatkalState.stopRequested = true; ctx.sendBroadcast(Intent("app.redshot.TATKAL_STOP").setPackage(ctx.packageName)); "ok: rok diya" }
        "tatkal_captcha_done" -> "ok"
        else -> null
    }

    private fun list(v: Any?): JSONArray {
        val o = JSONArray()
        when (v) { is JSONArray -> for (i in 0 until v.length()) o.put(v.get(i).toString().trim().uppercase())
            is String -> v.split(',', ' ').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.forEach { o.put(it) } }
        return o
    }

    private fun cancelAlarm(ctx: Context) {
        try {
            val pi = android.app.PendingIntent.getActivity(ctx.applicationContext, 7701, Intent(ctx, TatkalActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_NO_CREATE)
            if (pi != null) { (ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager).cancel(pi); pi.cancel() }
        } catch (e: Throwable) {}
    }

    fun saveProfile(ctx: Context, a: JSONObject): String {
        val p = profile(ctx)
        for (k in listOf("username", "mobile", "email", "payMode", "autoUpgrade", "autoWalletConfirm")) if (a.has(k)) p.put(k, a.get(k))
        if (a.has("passengers")) { // array or JSON string
            val v = a.get("passengers"); p.put("passengers", if (v is JSONArray) v else try { JSONArray(v.toString()) } catch (e: Exception) { JSONArray() })
        }
        val e = sp(ctx).edit().putString("profile", p.toString())
        if (a.optString("password").isNotBlank()) e.putString("pw", enc(a.optString("password")))
        e.apply()
        return "ok: profile save (password sirf phone me encrypted). " + describe(ctx)
    }

    private val CLASSES = setOf("1A", "2A", "3A", "3E", "SL", "CC", "EC", "2S", "FC")
    private val QUOTAS = setOf("GN", "TQ", "PT", "LD", "SS", "HP", "DF", "YU", "FT")

    /** things that can only fail at the worst moment are rejected up front, in plain words */
    fun validate(date: String, trainNo: String, cls: String, quota: String, passengers: JSONArray, now: Long = System.currentTimeMillis()): String? {
        if (trainNo.isNotEmpty() && !Regex("\\d{5}").matches(trainNo)) return "error: train number 5 digit ka chahiye (mila: '$trainNo')"
        if (cls !in CLASSES) return "error: class '$cls' nahi pata (SL, 3A, 2A, 1A, CC, EC, 2S, 3E)"
        if (quota !in QUOTAS) return "error: quota '$quota' nahi pata (GN, TQ, PT, LD, SS...)"
        val f = SimpleDateFormat("dd/MM/yyyy", Locale.US).apply { timeZone = IST; isLenient = false }
        val d = try { f.parse(date) } catch (e: Exception) { null } ?: return "error: date DD/MM/YYYY me chahiye (mila: '$date')"
        val dayStart = Calendar.getInstance(IST).apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        if (d.time < dayStart) return "error: ye date nikal chuki hai ($date)"
        val days = (d.time - dayStart) / 86_400_000L
        if (days > 120) return "error: IRCTC me 120 din se aage ki booking nahi khulti ($date)"
        val max = if (quota == "TQ" || quota == "PT") 4 else 6
        if (passengers.length() == 0) return "error: passengers ka naam/umar/gender chahiye"
        if (passengers.length() > max) return "error: $quota me ek ticket par max $max passenger ho sakte hain (diye ${passengers.length()})"
        for (i in 0 until passengers.length()) {
            val p = passengers.optJSONObject(i) ?: return "error: passenger ${i + 1} galat format"
            if (p.optString("name").isBlank()) return "error: passenger ${i + 1} ka naam nahi"
            val age = p.optInt("age", -1)
            if (age !in 1..125) return "error: ${p.optString("name")} ki umar galat/nahi (${p.opt("age")})"
            if (p.optString("gender").uppercase() !in setOf("M", "F", "T")) return "error: ${p.optString("name")} ka gender M/F/T chahiye"
        }
        return null
    }

    private fun describe(ctx: Context): String {
        val p = profile(ctx)
        val ps = p.optJSONArray("passengers") ?: JSONArray()
        val names = (0 until ps.length()).joinToString(", ") { ps.getJSONObject(it).let { x -> x.optString("name") + " " + x.optString("age") + x.optString("gender") } }
        return "IRCTC id: ${p.optString("username").ifBlank { "NAHI" }}, password: ${if (password(ctx).isBlank()) "NAHI" else "saved"}, mobile: ${p.optString("mobile").ifBlank { "NAHI" }}, " +
            "passengers: ${names.ifBlank { "NAHI" }}, payMode: ${p.optString("payMode", "ewallet")}"
    }

    private fun start(ctx: Context, a: JSONObject): String {
        val p = profile(ctx)
        val pw = password(ctx)
        val from = station(ctx, a.optString("from")) ?: return "error: from station samajh nahi aaya"
        val to = station(ctx, a.optString("to")) ?: return "error: to station samajh nahi aaya"
        if (from.first.isEmpty()) return "error: FROM station pakka nahi: ${from.third}. Owner se poochh."
        if (to.first.isEmpty()) return "error: TO station pakka nahi: ${to.third}. Owner se poochh."
        val cls = a.optString("cls", "SL").uppercase(); val quota = a.optString("quota", "TQ").uppercase()
        val chk = a.optBoolean("checkOnly", false)       // only look at availability: no login, no passengers, no booking
        val ps0 = if (a.has("passengers")) (a.get("passengers").let { if (it is JSONArray) it else try { JSONArray(it.toString()) } catch (e: Exception) { JSONArray() } }) else (p.optJSONArray("passengers") ?: JSONArray())
        val ps = if (chk && ps0.length() == 0) JSONArray().put(JSONObject().put("name", "CHECK").put("age", 30).put("gender", "M")) else ps0
        if (!chk && ps.length() == 0) return "error: passengers ka naam/umar/gender chahiye (tatkal_profile_set ya yaha do)"
        if (!chk && (p.optString("username").isBlank() || pw.isBlank())) return "error: IRCTC user id aur password chahiye (tatkal_profile_set)"
        val date = a.optString("date")
        validate(date, a.optString("trainNo").trim(), cls, quota, ps)?.let { return it }
        val open = if (chk) 0L else openAt(date, cls, quota)
        val job = JSONObject()
            .put("from", from.first).put("fromName", from.second).put("to", to.first).put("toName", to.second).put("date", date)
            .put("trainNo", a.optString("trainNo").trim()).put("trainName", a.optString("trainName").trim()).put("cls", cls).put("quota", quota).put("passengers", ps)
            .put("mobile", a.optString("mobile").ifBlank { p.optString("mobile") })
            .put("autoUpgrade", a.optBoolean("autoUpgrade", p.optBoolean("autoUpgrade", false)))
            .put("confirmOnly", a.optBoolean("confirmOnly", false)).put("checkOnly", chk)
            .put("payMode", a.optString("payMode").ifBlank { p.optString("payMode", "ewallet") })
            .put("autoWalletConfirm", p.optBoolean("autoWalletConfirm", false))
            .put("clsFallback", list(a.opt("clsFallback"))).put("altTrains", list(a.opt("altTrains")))
            .put("openAtMs", open)
        lastJob = job
        TatkalState.reset(job)
        val i = Intent(ctx, TatkalActivity::class.java).putExtra("job", job.toString())
        val lead = if (open > 0) open - System.currentTimeMillis() - 5 * 60_000 else 0L   // log in + arm 5 min before the window
        pending?.let { handler.removeCallbacks(it) }
        cancelAlarm(ctx)
        if (lead > 60_000) {
            val r = Runnable { pending = null; try { Executor.startBg(ctx.applicationContext, i); RedshotService.tatkalSay("Tatkal khulne me paanch minute hain, login karta hu. Captcha aaye to daal dena.") } catch (e: Exception) { RedshotService.tatkalLog("scheduled launch failed: " + e.message) } }
            pending = r; handler.postDelayed(r, lead); TatkalState.stage = "scheduled"
            try {                                                      // also a system alarm-clock entry: survives the app being killed
                val pi = android.app.PendingIntent.getActivity(ctx.applicationContext, 7701, i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                am.setAlarmClock(android.app.AlarmManager.AlarmClockInfo(System.currentTimeMillis() + lead, pi), pi)
            } catch (e: Throwable) { RedshotService.tatkalLog("alarm schedule failed: " + e.message) }
            return "ok: booking set: ${from.second}(${from.first}) -> ${to.second}(${to.first}) $date $cls $quota. Tatkal khulne se 5 min pehle khud shuru hoga (${lead / 60000} min baad), login + tayyari ke saath. App/phone band mat karna, charging pe rakho. Captcha owner ka, payment owner ka."
        }
        Executor.startBg(ctx, i)
        return "ok: booking shuru: ${from.second}(${from.first}) -> ${to.second}(${to.first}) $date $cls $quota. " +
            (if (open > 0) "Tatkal khulne ka intezaar karega (${(open - System.currentTimeMillis()) / 60000} min). " else "") +
            "Captcha owner ko khud daalna hai, payment owner ka."
    }

    // credentials are attached only inside the activity so they never sit in a job string / log
    fun credentials(ctx: Context): Pair<String, String> = profile(ctx).optString("username") to password(ctx)
}

/** Latest facts about the running booking, readable by tatkal_status and by the brain. */
object TatkalState {
    @Volatile var stopRequested = false
    @Volatile var stage = "idle"
    @Volatile var last = ""
    @Volatile var pnr = ""
    @Volatile var error = ""
    @Volatile var job: JSONObject? = null
    fun reset(j: JSONObject) { stopRequested = false; stage = "starting"; last = ""; pnr = ""; error = ""; job = j }
    fun describe(): String = "stage=$stage" + (if (pnr.isNotBlank()) " PNR=$pnr" else "") + (if (error.isNotBlank()) " error=$error" else "") + (if (last.isNotBlank()) " last=$last" else "")
}
