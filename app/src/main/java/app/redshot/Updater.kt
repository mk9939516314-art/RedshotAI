package app.redshot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * In-app updates: checks the latest GitHub release, downloads the APK over many parallel connections
 * (resuming failed pieces), verifies it, and hands it to Android's PackageInstaller.
 */
object Updater {
    private const val REPO = "mk9939516314-art/RedshotAI"
    private const val CHANNEL = "redshot_update"

    data class Info(val code: Int, val tag: String, val url: String, val size: Long, val notes: String)

    @Volatile var status = "idle"          // idle checking uptodate available downloading ready installing error
    @Volatile var message = ""
    @Volatile var info: Info? = null
    @Volatile var total = 0L
    val done = AtomicLong(0)
    @Volatile var speedBps = 0L
    @Volatile var lastCheckAt = 0L
    @Volatile var installSession = -1
    @Volatile private var busy = false

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))                // separate TCP connections => real parallel speed
            .connectionPool(ConnectionPool(12, 2, TimeUnit.MINUTES))
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true).build()
    }

    fun currentCode(ctx: Context): Int =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt() } catch (e: Exception) { 0 }

    fun currentName(ctx: Context): String =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }

    private fun apkFile(ctx: Context, code: Int) = File(ctx.cacheDir, "update-$code.apk")

    /** Asks GitHub for the newest build. Returns it only if it is newer than what is installed. */
    fun check(ctx: Context, force: Boolean = false): Info? {
        if (busy && status != "idle") return info
        if (!force && System.currentTimeMillis() - lastCheckAt < 60_000 && status != "error") return info
        status = "checking"; message = "naya version dhundh raha hoon..."
        return try {
            val r = client.newCall(Request.Builder().url("https://api.github.com/repos/$REPO/releases/latest")
                .header("Accept", "application/vnd.github+json").header("User-Agent", "Redshot-Updater").build()).execute()
            val j = JSONObject(r.body?.string() ?: "{}")
            r.close()
            lastCheckAt = System.currentTimeMillis()
            val tag = j.optString("tag_name")
            val code = Regex("(\\d+)$").find(tag)?.value?.toIntOrNull() ?: 0
            val assets = j.optJSONArray("assets")
            var url = ""; var size = 0L
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); size = a.optLong("size"); break }
            }
            if (code == 0 || url.isBlank()) { status = "error"; message = "release samajh nahi aaya"; return null }
            val notes = j.optString("body").ifBlank { j.optString("name") }.lineSequence().firstOrNull()?.take(140) ?: ""
            if (code > currentCode(ctx)) {
                info = Info(code, tag, url, size, notes); total = size
                val f = apkFile(ctx, code)
                if (f.exists() && f.length() == size) { status = "ready"; message = "download ho chuka hai, install ke liye taiyaar"; done.set(size) }
                else { status = "available"; message = "naya version aaya hai" }
                info
            } else { info = null; status = "uptodate"; message = "app latest hai (${currentName(ctx)})"; null }
        } catch (e: Exception) {
            status = "error"; message = "check nahi hua: " + (e.message ?: e.javaClass.simpleName); null
        }
    }

    fun metered(ctx: Context): Boolean = try { (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered } catch (e: Exception) { false }

    /** Downloads with up to 8 parallel connections. Blocks until done. Returns the verified file or null. */
    fun download(ctx: Context): File? {
        val i = info ?: return null
        val out = apkFile(ctx, i.code)
        if (out.exists() && out.length() == i.size && verify(ctx, out)) { status = "ready"; done.set(i.size); return out }
        status = "downloading"; message = "download ho raha hai"; done.set(0); speedBps = 0
        ctx.cacheDir.listFiles()?.filter { it.name.startsWith("update-") && it.name != out.name }?.forEach { it.delete() }
        return try {
            // resolve the final CDN address once (GitHub redirects) and check that ranges work
            val probe = client.newCall(Request.Builder().url(i.url).header("Range", "bytes=0-0").build()).execute()
            val finalUrl = probe.request.url.toString()
            val ranged = probe.code == 206
            probe.close()
            val raf = RandomAccessFile(out, "rw"); raf.setLength(i.size)
            val ch = raf.channel
            val t0 = System.currentTimeMillis()
            val speedThread = Thread {
                var last = 0L; var lastT = System.currentTimeMillis()
                while (status == "downloading") {
                    Thread.sleep(500)
                    val now = System.currentTimeMillis(); val d = done.get()
                    val inst = (d - last) * 1000 / maxOf(1, now - lastT)
                    speedBps = if (speedBps == 0L) inst else (speedBps * 0.6 + inst * 0.4).toLong()
                    last = d; lastT = now
                }
            }.also { it.isDaemon = true; it.start() }
            var failed: String? = null
            if (ranged && i.size > 4_000_000) {
                val chunk = 3_000_000L
                val q = ConcurrentLinkedQueue<LongArray>()
                var s = 0L
                while (s < i.size) { q.add(longArrayOf(s, minOf(s + chunk, i.size) - 1)); s += chunk }
                val pool = Executors.newFixedThreadPool(8)
                repeat(8) {
                    pool.execute {
                        while (failed == null) {
                            val c = q.poll() ?: break
                            var pos = c[0]; var tries = 0
                            while (pos <= c[1] && failed == null) {
                                try {
                                    val r = client.newCall(Request.Builder().url(finalUrl).header("Range", "bytes=$pos-${c[1]}").build()).execute()
                                    if (r.code != 206 && r.code != 200) { r.close(); throw RuntimeException("HTTP ${r.code}") }
                                    r.body!!.byteStream().use { ins ->
                                        val buf = ByteArray(64 * 1024)
                                        while (pos <= c[1]) {
                                            val n = ins.read(buf); if (n < 0) break
                                            val take = minOf(n.toLong(), c[1] - pos + 1).toInt()
                                            ch.write(java.nio.ByteBuffer.wrap(buf, 0, take), pos)
                                            pos += take; done.addAndGet(take.toLong())
                                        }
                                    }
                                } catch (e: Exception) {
                                    if (++tries > 6) failed = e.message ?: e.javaClass.simpleName else Thread.sleep(400L * tries)
                                }
                            }
                        }
                    }
                }
                pool.shutdown(); pool.awaitTermination(30, TimeUnit.MINUTES)
            } else {
                val r = client.newCall(Request.Builder().url(finalUrl).build()).execute()
                r.body!!.byteStream().use { ins -> val buf = ByteArray(64 * 1024); var pos = 0L
                    while (true) { val n = ins.read(buf); if (n < 0) break; ch.write(java.nio.ByteBuffer.wrap(buf, 0, n), pos); pos += n; done.addAndGet(n.toLong()) } }
                r.close()
            }
            ch.force(false); ch.close(); raf.close()
            if (failed != null) { status = "error"; message = "download ruk gaya: $failed (dobara dabao, jahan tha wahin se chalega)"; return null }
            if (out.length() != i.size || !verify(ctx, out)) { out.delete(); status = "error"; message = "file adhoori/kharab thi, dobara try karo"; return null }
            val secs = maxOf(1, (System.currentTimeMillis() - t0) / 1000)
            status = "ready"; message = "download ho gaya (${i.size / 1048576}MB, ${secs}s mein)"
            out
        } catch (e: Exception) {
            status = "error"; message = "download fail: " + (e.message ?: e.javaClass.simpleName); null
        }
    }

    private fun verify(ctx: Context, f: File): Boolean = try {
        val pi = ctx.packageManager.getPackageArchiveInfo(f.path, 0)
        pi != null && pi.packageName == ctx.packageName && pi.longVersionCode.toInt() > currentCode(ctx)
    } catch (e: Exception) { false }

    /** Hands the verified APK to Android. First time (or if Android insists) one confirm tap is needed. */
    fun install(ctx: Context): Boolean {
        val i = info ?: return false
        val f = apkFile(ctx, i.code)
        if (!f.exists() || !verify(ctx, f)) { status = "error"; message = "update file taiyaar nahi"; return false }
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            status = "error"; message = "pehle 'Is source se install' allow karo (settings khul gayi), phir dobara dabao"
            try { ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
            return false
        }
        return try {
            val pin = ctx.packageManager.packageInstaller
            val p = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            p.setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            val id = pin.createSession(p)
            installSession = id
            pin.openSession(id).use { s ->
                s.openWrite("redshot.apk", 0, f.length()).use { o -> f.inputStream().use { it.copyTo(o, 256 * 1024) }; s.fsync(o) }
                val pi = PendingIntent.getBroadcast(ctx, id, Intent(ctx, UpdateReceiver::class.java).setPackage(ctx.packageName),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                status = "installing"; message = "install ho raha hai..."
                s.commit(pi.intentSender)
            }
            true
        } catch (e: Exception) { status = "error"; message = "install shuru nahi hua: " + (e.message ?: ""); false }
    }

    /** check -> download -> install in one go (used by the button and by voice). */
    fun run(ctx: Context, action: String): String {
        if (busy) return "update pehle se chal raha hai: $message"
        busy = true
        try {
            val i = check(ctx, true)
            if (i == null) return if (status == "error") "error: $message" else message
            if (action == "check") return "naya version ${i.tag} available hai (${i.size / 1048576}MB). ${i.notes}"
            if (status != "ready") { if (download(ctx) == null) return "error: $message" }
            return if (install(ctx)) "ok: install shuru ho gaya, ek confirm popup aaye to 'Update' dabana. App kuch second mein restart hoga." else "error: $message"
        } finally { busy = false }
    }

    /** Background routine: look for a new build and keep it downloaded, so installing is instant. */
    fun backgroundTick(ctx: Context) {
        if (busy || status == "downloading" || status == "installing") return
        val i = check(ctx) ?: return
        if (status == "available" && !metered(ctx)) { busy = true; try { download(ctx) } finally { busy = false } }
        if (status == "ready") notifyReady(ctx, i)
        else if (status == "available") notifyReady(ctx, i)
    }

    private var notifiedFor = 0
    private fun notifyReady(ctx: Context, i: Info) {
        if (notifiedFor == i.code) return
        notifiedFor = i.code
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Redshot update", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(ctx, 77, Intent(ctx, MainActivity::class.java).putExtra("update", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(77, android.app.Notification.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_redshot)
                .setContentTitle("Redshot ka naya version taiyaar").setContentText(i.notes.ifBlank { "Tap karke update karo" })
                .setContentIntent(open).setAutoCancel(true).build())
        } catch (e: Exception) {}
    }
}

/** Receives the installer's progress/result. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val st = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        val sid = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (sid != Updater.installSession) return
        when (st) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                Updater.message = "Android confirm maang raha hai: 'Update' dabao"
                try { ctx.startActivity(confirm) } catch (e: Exception) {}
                try {
                    val nm = ctx.getSystemService(NotificationManager::class.java)
                    nm.createNotificationChannel(NotificationChannel("redshot_update", "Redshot update", NotificationManager.IMPORTANCE_HIGH))
                    val pi = PendingIntent.getActivity(ctx, 78, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    nm.notify(78, android.app.Notification.Builder(ctx, "redshot_update").setSmallIcon(R.drawable.ic_redshot)
                        .setContentTitle("Update install karo").setContentText("Tap karke 'Update' dabao").setContentIntent(pi).setAutoCancel(true).build())
                } catch (e: Exception) {}
            }
            PackageInstaller.STATUS_SUCCESS -> { Updater.status = "installing"; Updater.message = "update ho gaya" }
            else -> {
                val m = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $st"
                Updater.status = "error"
                Updater.message = if (m.contains("signature", true) || m.contains("INCOMPATIBLE", true)) "install nahi hua: purani app ka signature alag hai (ek baar purani app uninstall karke nayi APK daalni padegi)" else "install nahi hua: $m"
            }
        }
    }
}

/** After an update Android restarts nothing by itself: bring the listener back and tell the owner. */
class ReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        try { ctx.startForegroundService(Intent(ctx, RedshotService::class.java)) } catch (e: Exception) {}
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("redshot_update", "Redshot update", NotificationManager.IMPORTANCE_DEFAULT))
            nm.cancel(77); nm.cancel(78)
            val open = PendingIntent.getActivity(ctx, 79, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(79, android.app.Notification.Builder(ctx, "redshot_update").setSmallIcon(R.drawable.ic_redshot)
                .setContentTitle("Redshot update ho gaya").setContentText("Version ${Updater.currentName(ctx)}. Tap karo agar sunna band ho").setContentIntent(open).setAutoCancel(true).build())
        } catch (e: Exception) {}
    }
}
