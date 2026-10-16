package app.redshot

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentProviderOperation
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.ContactsContract
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Hands for files, clipboard, shell, system switches. Every answer is plain text; failures start with "error:". */
object Pro {

    fun run(ctx: Context, name: String, a: JSONObject): String? = when (name) {
        "file_write" -> fileWrite(ctx, a.optString("path"), a.optString("content"), false)
        "file_append" -> fileWrite(ctx, a.optString("path"), a.optString("content"), true)
        "file_list" -> fileList(ctx, a.optString("path"))
        "file_delete" -> fileDelete(ctx, a.optString("path"))
        "file_rename", "file_move" -> fileMove(ctx, a.optString("from"), a.optString("to"), false)
        "file_copy" -> fileMove(ctx, a.optString("from"), a.optString("to"), true)
        "folder_create" -> { val f = resolve(ctx, a.optString("path")); if (f.mkdirs() || f.isDirectory) "ok: ${f.path}" else "error: folder nahi bana" }
        "file_open" -> fileOpen(ctx, a.optString("path"))
        "zip_extract" -> zipExtract(ctx, a.optString("path"), a.optString("to"))
        "zip_create" -> zipCreate(ctx, a.optString("path"), a.optString("to"))
        "clipboard_write" -> clipWrite(ctx, a.optString("text"))
        "clipboard_read" -> clipRead(ctx)
        "shell" -> shell(a.optString("command"), a.optInt("timeout_s", 15))
        "brightness" -> brightness(ctx, a.optInt("percent", 50))
        "set_volume" -> setVolume(ctx, a.optString("stream", "music"), a.optInt("percent", 50))
        "dnd" -> dnd(ctx, a.optBoolean("on", true))
        "app_close" -> appClose(ctx, a.optString("app"))
        "contact_add" -> contactAdd(ctx, a.optString("name"), a.optString("number"), a.optString("email"))
        "toggle_switch" -> toggleSwitch(ctx, a.optString("page"), a.optBoolean("on", true))
        "system_info" -> sysInfo(ctx)
        else -> null
    }

    // ----- files -----

    fun root(): File = File(Environment.getExternalStorageDirectory(), "Documents/Redshot")

    private fun resolve(ctx: Context, p: String): File {
        val s = p.trim()
        val f = when {
            s.isEmpty() -> root()
            s.startsWith("/") -> File(s)
            s.startsWith("~/") -> File(Environment.getExternalStorageDirectory(), s.substring(2))
            else -> File(root(), s)
        }
        return f
    }

    private fun canWriteShared(): Boolean = android.os.Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    private fun fileWrite(ctx: Context, path: String, content: String, append: Boolean): String {
        if (path.isBlank()) return "error: file ka naam do"
        var f = resolve(ctx, path)
        var note = ""
        if (!canWriteShared() && !f.path.startsWith(ctx.getExternalFilesDir(null)?.path ?: "###")) {
            // no all-files permission: keep it inside Redshot's own folder, still openable
            f = File(ctx.getExternalFilesDir(null), File(path).name)
            note = " (All-files access band hai isliye app ke folder mein rakha; Redshot app mein 'Poora access' do to Documents/Redshot mein jayega)"
        }
        return try {
            f.parentFile?.mkdirs()
            if (append) f.appendText(content) else f.writeText(content)
            ActionLog.add("file ${if (append) "append" else "likhi"}: ${f.path} (${f.length()} bytes)")
            "ok: ${f.path} (${f.length()} bytes)$note"
        } catch (e: Exception) { "error: file nahi likh paya: ${e.message}" }
    }

    private fun fileList(ctx: Context, path: String): String {
        val f = resolve(ctx, path)
        if (!f.exists()) return "error: ${f.path} nahi mila"
        if (f.isFile) return "file: ${f.path} (${f.length()} bytes)"
        val items = f.listFiles()?.sortedByDescending { it.lastModified() }?.take(60)
            ?: return "error: folder nahi khul paya (All-files access chahiye)"
        return f.path + ":\n" + items.joinToString("\n") { (if (it.isDirectory) "[dir] " else "") + it.name + (if (it.isFile) " (${it.length()}b)" else "") }
    }

    private fun fileDelete(ctx: Context, path: String): String {
        val f = resolve(ctx, path)
        if (!f.exists()) return "error: ${f.path} nahi mila"
        if (f == Environment.getExternalStorageDirectory() || f.path.length < 12) return "error: ye folder delete nahi karunga"
        return if (f.deleteRecursively()) { ActionLog.add("delete: ${f.path}"); "ok: delete ho gaya ${f.path}" } else "error: delete nahi hua"
    }

    private fun fileMove(ctx: Context, from: String, to: String, copy: Boolean): String {
        val a = resolve(ctx, from); var b = resolve(ctx, to)
        if (!a.exists()) return "error: ${a.path} nahi mila"
        if (b.isDirectory) b = File(b, a.name)
        return try {
            b.parentFile?.mkdirs()
            if (copy) a.copyRecursively(b, true) else if (!a.renameTo(b)) { a.copyRecursively(b, true); a.deleteRecursively() }
            ActionLog.add((if (copy) "copy" else "move") + ": ${a.path} -> ${b.path}")
            "ok: ${b.path}"
        } catch (e: Exception) { "error: ${e.message}" }
    }

    private fun mime(f: File): String = when (f.extension.lowercase()) {
        "html", "htm" -> "text/html"; "txt", "md", "log" -> "text/plain"; "pdf" -> "application/pdf"
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "mp4" -> "video/mp4"; "mp3" -> "audio/mpeg"
        "csv" -> "text/csv"; "json" -> "application/json"; "apk" -> "application/vnd.android.package-archive"
        else -> "*/*"
    }

    private fun fileOpen(ctx: Context, path: String): String {
        val f = resolve(ctx, path)
        if (!f.exists()) return "error: ${f.path} nahi mila"
        return try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime(f))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            Executor.startBg(ctx, i)
            ActionLog.add("file khuli: ${f.path}")
            "ok: khol diya ${f.name}"
        } catch (e: Exception) { "error: file khol nahi paya: ${e.message}" }
    }

    private fun zipExtract(ctx: Context, path: String, to: String): String {
        val f = resolve(ctx, path)
        if (!f.exists()) return "error: ${f.path} nahi mila"
        val out = if (to.isBlank()) File(f.parentFile, f.nameWithoutExtension) else resolve(ctx, to)
        return try {
            var n = 0
            ZipFile(f).use { z -> z.entries().asSequence().forEach { e ->
                val t = File(out, e.name)
                if (!t.canonicalPath.startsWith(out.canonicalPath)) return@forEach
                if (e.isDirectory) t.mkdirs() else { t.parentFile?.mkdirs(); z.getInputStream(e).use { i -> t.outputStream().use { o -> i.copyTo(o) } }; n++ }
            } }
            "ok: $n files ${out.path} mein"
        } catch (e: Exception) { "error: ${e.message}" }
    }

    private fun zipCreate(ctx: Context, path: String, to: String): String {
        val src = resolve(ctx, path)
        if (!src.exists()) return "error: ${src.path} nahi mila"
        val out = if (to.isBlank()) File(src.parentFile, src.name + ".zip") else resolve(ctx, to)
        return try {
            ZipOutputStream(out.outputStream()).use { z ->
                val base = src.parentFile ?: src
                (if (src.isFile) listOf(src) else src.walkTopDown().filter { it.isFile }.toList()).forEach { f ->
                    z.putNextEntry(ZipEntry(f.relativeTo(base).path)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
                }
            }
            "ok: ${out.path} (${out.length()} bytes)"
        } catch (e: Exception) { "error: ${e.message}" }
    }

    // ----- clipboard / shell -----

    private fun clipWrite(ctx: Context, text: String): String = try {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("redshot", text))
        "ok: clipboard mein copy ho gaya"
    } catch (e: Exception) { "error: ${e.message}" }

    private fun clipRead(ctx: Context): String = try {
        val t = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
        if (t.isNullOrEmpty()) "error: clipboard khali hai ya Android ne background mein padhne nahi diya" else t.take(3000)
    } catch (e: Exception) { "error: ${e.message}" }

    private fun shell(cmd: String, timeoutS: Int): String {
        if (cmd.isBlank()) return "error: command do"
        return try {
            val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
            val out = StringBuilder()
            val rd = Thread { try { p.inputStream.bufferedReader().use { r -> val buf = CharArray(2048); while (true) { val n = r.read(buf); if (n < 0) break; if (out.length < 4000) out.append(buf, 0, n) } } } catch (e: Exception) {} }
            rd.start()
            if (!p.waitFor(timeoutS.coerceIn(1, 60).toLong(), TimeUnit.SECONDS)) { p.destroyForcibly(); rd.join(300); return "error: timeout\n" + out.toString().take(1500) }
            rd.join(500)
            (if (p.exitValue() != 0) "exit ${p.exitValue()}\n" else "") + out.toString().take(3500).ifBlank { "ok (koi output nahi)" }
        } catch (e: Exception) { "error: ${e.message}" }
    }

    // ----- system switches -----

    private fun brightness(ctx: Context, pct: Int): String {
        if (!Settings.System.canWrite(ctx)) {
            Executor.startBg(ctx, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName)))
            return "error: brightness ke liye 'Modify system settings' allow karna padega, settings khol di hain"
        }
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (pct.coerceIn(0, 100) * 255 / 100))
        return "ok: brightness $pct%"
    }

    private fun setVolume(ctx: Context, stream: String, pct: Int): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val s = when (stream.lowercase()) { "ring", "ringtone" -> AudioManager.STREAM_RING; "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION; "call" -> AudioManager.STREAM_VOICE_CALL; else -> AudioManager.STREAM_MUSIC }
        val max = am.getStreamMaxVolume(s)
        return try { am.setStreamVolume(s, (max * pct.coerceIn(0, 100) / 100f).toInt(), 0); "ok: $stream volume $pct%" } catch (e: Exception) { "error: ${e.message}" }
    }

    private fun dnd(ctx: Context, on: Boolean): String {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            Executor.startBg(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return "error: Do Not Disturb ka access allow karna padega, settings khol di hain"
        }
        nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_NONE else NotificationManager.INTERRUPTION_FILTER_ALL)
        return "ok: Do Not Disturb ${if (on) "on" else "off"}"
    }

    private fun appClose(ctx: Context, app: String): String {
        val a = Apps.findByName(ctx, app) ?: return "error: '$app' naam ka app nahi mila"
        val pkg = a.pkg
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(pkg)
        if (RedshotAccess.currentPackage == pkg) RedshotAccess.instance?.global("home")
        return "ok: $app band kiya"
    }

    private fun contactAdd(ctx: Context, name: String, number: String, email: String): String {
        if (ctx.checkSelfPermission(android.Manifest.permission.WRITE_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return "error: contacts likhne ki permission nahi (Redshot app > Poora access)"
        return try {
            val ops = ArrayList<ContentProviderOperation>()
            ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI).withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null).withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build())
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name).build())
            if (number.isNotBlank()) ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number).build())
            if (email.isNotBlank()) ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email).build())
            ctx.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            "ok: contact $name save ho gaya"
        } catch (e: Exception) { "error: ${e.message}" }
    }

    /** Wi-Fi / Bluetooth / hotspot / airplane etc.: Android does not let apps flip these, so open the page and flip its switch like a finger would. */
    private fun toggleSwitch(ctx: Context, page: String, on: Boolean): String {
        val acc = RedshotAccess.instance ?: return "error: accessibility band hai"
        val action = when (page.lowercase()) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS; "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS; "hotspot" -> "android.settings.TETHER_SETTINGS"
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS; "nfc" -> Settings.ACTION_NFC_SETTINGS
            "data" -> Settings.ACTION_DATA_ROAMING_SETTINGS
            else -> return "error: page samajh nahi aaya (wifi, bluetooth, airplane, hotspot, location, nfc)"
        }
        Executor.startBg(ctx, Intent(action))
        Thread.sleep(1200)
        val r = acc.setFirstSwitch(on)
        acc.global("home")
        return if (r) "ok: $page ${if (on) "on" else "off"}" else "error: $page ka switch nahi mila ya pehle se waisa hi tha"
    }

    private fun sysInfo(ctx: Context): String {
        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val st = StatFs(Environment.getDataDirectory().path)
        val cpu = shell("cat /proc/loadavg", 3).trim()
        return "RAM ${mi.availMem / 1048576}MB free of ${mi.totalMem / 1048576}MB, storage ${st.availableBytes / 1073741824.0}GB free of ${st.totalBytes / 1073741824.0}GB, CPU load $cpu, ${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE}"
            .replace(Regex("(\\d+\\.\\d)\\d+GB"), "$1GB")
    }
}
