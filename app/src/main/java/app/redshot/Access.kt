package app.redshot

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.SmsManager
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile

/** Everything on the phone beyond apps and screen: messages, calls, files, location, status, mail. Every function answers in plain text (errors start with "error:"). */
object Access {

    private fun has(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private val dt = SimpleDateFormat("d MMM, h:mm a", Locale.ENGLISH)

    fun run(ctx: Context, name: String, a: JSONObject): String? = when (name) {
        "sms_read" -> smsRead(ctx, a.optInt("count", 8).coerceIn(1, 30), a.optString("from"))
        "sms_send" -> smsSend(ctx, a.optString("to"), a.optString("text"))
        "call_log" -> callLog(ctx, a.optInt("count", 8).coerceIn(1, 30))
        "contacts_search" -> contactsSearch(ctx, a.optString("name"))
        "files_search" -> filesSearch(a.optString("query"))
        "files_read" -> filesRead(a.optString("path"))
        "location" -> location(ctx)
        "device_status" -> status(ctx)
        "email_send" -> emailSend(ctx, a.optString("to"), a.optString("subject"), a.optString("body"))
        "calendar_add" -> calendarAdd(ctx, a.optString("title"), a.optString("start"), a.optInt("minutes", 60))
        "set_away" -> {
            if (a.optBoolean("off")) { AwayMode.clear(); "ok: away mode band" }
            else { AwayMode.set(a.optString("note").ifBlank { "owner abhi available nahi hai" }, a.optInt("minutes", 180)); "ok: away mode chalu: " + AwayMode.describe() }
        }
        "reply_notification" -> RedshotNotifs.replyTo(a.optString("to"), a.optString("text"))
        "recent_actions" -> ActionLog.recent(a.optInt("count", 15).coerceIn(1, 40))
        "list_apps" -> Apps.list(ctx).joinToString(", ") { it.label }.take(3000)
        "app_update" -> Updater.run(ctx, a.optString("action", "install"))
        "wait" -> { Thread.sleep(a.optLong("ms", 800).coerceIn(100, 5000)); "ok" }
        else -> Tatkal.run(ctx, name, a) ?: Pro.run(ctx, name, a) ?: Pro2.run(ctx, name, a) ?: Deep.run(ctx, name, a)
    }

    // ----- SMS -----

    private fun smsRead(ctx: Context, n: Int, from: String): String {
        if (!has(ctx, Manifest.permission.READ_SMS)) return "error: SMS padhne ki permission nahi di (Redshot app > Poora access wala step)"
        val sb = StringBuilder()
        ctx.contentResolver.query(
            Uri.parse("content://sms/inbox"), arrayOf("address", "body", "date"), null, null, "date DESC"
        )?.use { c ->
            var k = 0
            while (c.moveToNext() && k < n) {
                val addr = c.getString(0) ?: ""
                if (from.isNotBlank() && !addr.contains(from, true) && Executor.nameScore(contactName(ctx, addr), from) < 40) continue
                k++
                sb.append("- ").append(dt.format(Date(c.getLong(2)))).append(" [").append(contactName(ctx, addr).ifBlank { addr }).append("] ")
                    .append((c.getString(1) ?: "").take(280)).append('\n')
            }
        }
        return if (sb.isEmpty()) "Koi SMS nahi mila." else sb.toString()
    }

    private fun contactName(ctx: Context, number: String): String {
        if (!has(ctx, Manifest.permission.READ_CONTACTS) || number.isBlank()) return ""
        return try {
            ctx.contentResolver.query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { if (it.moveToFirst()) it.getString(0) ?: "" else "" } ?: ""
        } catch (e: Exception) { "" }
    }

    private fun smsSend(ctx: Context, to: String, text: String): String {
        if (!has(ctx, Manifest.permission.SEND_SMS)) return "error: SMS bhejne ki permission nahi di (Redshot app > Poora access wala step)"
        if (text.isBlank()) return "error: message khaali hai"
        var num = to.filter { it.isDigit() || it == '+' }
        if (num.length < 7) num = Executor.resolveContact(ctx, to)?.filter { it.isDigit() || it == '+' } ?: ""
        if (num.length < 7) return "error: '$to' ka number contacts mein nahi mila"
        val sm = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
        val parts = sm.divideMessage(text)
        sm.sendMultipartTextMessage(num, null, parts, null, null)
        return "ok: SMS $num ko bhej diya"
    }

    // ----- calls & contacts -----

    private fun callLog(ctx: Context, n: Int): String {
        if (!has(ctx, Manifest.permission.READ_CALL_LOG)) return "error: call log ki permission nahi di (Redshot app > Poora access wala step)"
        val sb = StringBuilder()
        ctx.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
            null, null, CallLog.Calls.DATE + " DESC"
        )?.use { c ->
            var k = 0
            while (c.moveToNext() && k < n) {
                k++
                val type = when (c.getInt(2)) { CallLog.Calls.INCOMING_TYPE -> "aaya"; CallLog.Calls.OUTGOING_TYPE -> "gaya"; CallLog.Calls.MISSED_TYPE -> "MISSED"; else -> "other" }
                sb.append("- ").append(dt.format(Date(c.getLong(3)))).append(" ").append(type).append(" ")
                    .append(c.getString(1) ?: c.getString(0) ?: "?").append(" (").append(c.getInt(4)).append("s)\n")
            }
        }
        return if (sb.isEmpty()) "Call log khaali hai." else sb.toString()
    }

    private fun contactsSearch(ctx: Context, name: String): String {
        if (!has(ctx, Manifest.permission.READ_CONTACTS)) return "error: contacts ki permission nahi di"
        val out = ArrayList<Pair<Int, String>>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val sc = Executor.nameScore(c.getString(0) ?: "", name)
                if (sc >= 40) out.add(sc to ((c.getString(0) ?: "") + ": " + (c.getString(1) ?: "")))
            }
        }
        return if (out.isEmpty()) "Is naam ka koi contact nahi mila." else out.sortedByDescending { it.first }.take(6).joinToString("\n") { "- " + it.second }
    }

    // ----- files -----

    private fun allFilesOk(): Boolean = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    private fun filesSearch(q: String): String {
        if (!allFilesOk()) return "error: 'All files access' band hai (Redshot app > Poora access wala step)"
        if (q.isBlank()) return "error: kya dhundhna hai?"
        val root = Environment.getExternalStorageDirectory()
        val words = q.lowercase().split(" ", "_", "-").filter { it.length > 1 }
        val hits = ArrayList<File>()
        fun walk(d: File, depth: Int) {
            if (hits.size >= 60 || depth > 7) return
            val list = d.listFiles() ?: return
            for (f in list) {
                if (f.name.startsWith(".") || f.name == "Android") continue
                if (f.isDirectory) walk(f, depth + 1)
                else if (words.all { f.name.lowercase().contains(it) }) hits.add(f)
            }
        }
        walk(root, 0)
        if (hits.isEmpty()) return "Is naam ki koi file nahi mili."
        return hits.sortedByDescending { it.lastModified() }.take(12).joinToString("\n") {
            "- " + it.absolutePath + " (" + (it.length() / 1024) + " KB, " + dt.format(Date(it.lastModified())) + ")"
        }
    }

    private fun filesRead(path: String): String {
        if (!allFilesOk()) return "error: 'All files access' band hai (Redshot app > Poora access wala step)"
        val f = File(path)
        if (!f.exists() || !f.isFile) return "error: file nahi mili: $path"
        val ext = f.extension.lowercase()
        return try {
            when (ext) {
                "txt", "md", "csv", "json", "log", "xml", "html", "vcf", "ics" -> f.readText().take(6000)
                "docx" -> ZipFile(f).use { z ->
                    val e = z.getEntry("word/document.xml") ?: return "error: docx kharab hai"
                    z.getInputStream(e).bufferedReader().readText().replace(Regex("</w:p>"), "\n").replace(Regex("<[^>]+>"), "").take(6000)
                }
                "xlsx" -> ZipFile(f).use { z ->
                    val e = z.getEntry("xl/sharedStrings.xml") ?: return "error: xlsx mein text nahi mila"
                    z.getInputStream(e).bufferedReader().readText().replace("</si>", "\n").replace(Regex("<[^>]+>"), "").take(6000)
                }
                else -> "error: .$ext file ka text phone par nahi padh sakta (open_url / open_app se kholke read_screen use kar)"
            }
        } catch (e: Exception) { "error: " + e.message }
    }

    // ----- location & status -----

    private fun location(ctx: Context): String {
        if (!has(ctx, Manifest.permission.ACCESS_FINE_LOCATION) && !has(ctx, Manifest.permission.ACCESS_COARSE_LOCATION))
            return "error: location ki permission nahi di (Redshot app > Poora access wala step)"
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: android.location.Location? = null
        for (p in lm.getProviders(true)) {
            val l = try { lm.getLastKnownLocation(p) } catch (e: SecurityException) { null } ?: continue
            if (best == null || l.time > best.time) best = l
        }
        val l = best ?: return "error: abhi location nahi mil rahi (GPS/location on karo)"
        val ageMin = (System.currentTimeMillis() - l.time) / 60000
        var addr = ""
        try {
            @Suppress("DEPRECATION")
            val r = Geocoder(ctx, Locale.getDefault()).getFromLocation(l.latitude, l.longitude, 1)
            addr = r?.firstOrNull()?.getAddressLine(0) ?: ""
        } catch (e: Exception) {}
        return "lat=%.5f lon=%.5f (%d min purani) %s".format(l.latitude, l.longitude, ageMin, addr)
    }

    private fun status(ctx: Context): String {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = ctx.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1).let { it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL }
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val net = when {
            caps == null -> "internet nahi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            else -> "network"
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val ring = when (am.ringerMode) { android.media.AudioManager.RINGER_MODE_SILENT -> "silent"; android.media.AudioManager.RINGER_MODE_VIBRATE -> "vibrate"; else -> "normal" }
        return "battery $pct%${if (charging) " (charge ho raha)" else ""}, $net, ringer $ring, screen par app: ${RedshotAccess.currentPackage}, music chal raha: ${am.isMusicActive}, abhi: ${SimpleDateFormat("EEE d MMM h:mm a", Locale.ENGLISH).format(Date())}"
    }

    // ----- mail & calendar -----

    private fun emailSend(ctx: Context, to: String, subject: String, body: String): String {
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(to)))
            .putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, body)
        Executor.startBg(ctx, i)
        val acc = RedshotAccess.instance ?: return "error: mail khul gaya par accessibility band hai isliye Send nahi dab paya"
        for (k in 0 until 10) {
            Thread.sleep(700)
            if (acc.tapText("Send") || acc.tapText("भेजें")) return "ok: mail bhej diya"
        }
        return "error: mail compose khula hai par Send button nahi mila"
    }

    private fun calendarAdd(ctx: Context, title: String, start: String, minutes: Int): String {
        if (!has(ctx, Manifest.permission.WRITE_CALENDAR)) return "error: calendar likhne ki permission nahi di"
        val t0 = try { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH).parse(start)?.time } catch (e: Exception) { null }
            ?: return "error: start ka format 'yyyy-MM-dd HH:mm' hona chahiye"
        var calId = -1L
        ctx.contentResolver.query(android.provider.CalendarContract.Calendars.CONTENT_URI, arrayOf(android.provider.CalendarContract.Calendars._ID),
            android.provider.CalendarContract.Calendars.VISIBLE + "=1 AND " + android.provider.CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + ">=500", null, null)?.use {
            if (it.moveToFirst()) calId = it.getLong(0)
        }
        if (calId < 0) return "error: koi likhne layak calendar nahi mila"
        val v = android.content.ContentValues().apply {
            put(android.provider.CalendarContract.Events.CALENDAR_ID, calId)
            put(android.provider.CalendarContract.Events.TITLE, title)
            put(android.provider.CalendarContract.Events.DTSTART, t0)
            put(android.provider.CalendarContract.Events.DTEND, t0 + minutes * 60_000L)
            put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
        }
        ctx.contentResolver.insert(android.provider.CalendarContract.Events.CONTENT_URI, v)
        return "ok: calendar mein '$title' daal diya"
    }
}
