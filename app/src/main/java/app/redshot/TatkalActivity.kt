package app.redshot

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Runs the IRCTC booking in a real WebView; the DOM-level engine (assets/tatkal/engine.js) does the filling and clicking.
 * This class is the safety net around it: network drops, blocked pages, dead renderer, UPI app hand-off, screen/CPU kept awake,
 * clock measured against IRCTC's own server, accidental back-press ignored.
 */
class TatkalActivity : ComponentActivity() {
    private val HOME = "https://www.irctc.co.in/nget/train-search"
    private lateinit var web: WebView
    private lateinit var status: TextView
    private var job = JSONObject()
    @Volatile private var skewMs = 0L
    @Volatile private var alive = true
    @Volatile private var engineOverride: String? = null
    private val ui = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null
    private var netFails = 0
    private var blockedN = 0
    private var stuckN = 0
    private var lastMainFrameErrorAt = 0L
    private val stopRx = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { stopAll("rok diya") } }

    inner class Bridge {
        @JavascriptInterface fun skew(): String = skewMs.toString()
        @JavascriptInterface fun event(type: String, json: String) { runOnUiThread { onEvent(type, json) } }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "redshot:tatkal")
            wake?.acquire(3 * 60 * 60 * 1000L)
        } catch (e: Throwable) {}
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stopRx, IntentFilter("app.redshot.TATKAL_STOP"), Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(stopRx, IntentFilter("app.redshot.TATKAL_STOP"))
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { Toast.makeText(this@TatkalActivity, "Booking chal rahi hai. Band karne ke liye STOP dabao.", Toast.LENGTH_SHORT).show() }
        })

        val root = FrameLayout(this)
        web = WebView(this)
        val s = web.settings
        s.javaScriptEnabled = true; s.domStorageEnabled = true; s.databaseEnabled = true
        s.userAgentString = "Mozilla/5.0 (Linux; Android 15; RMX5000) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        s.loadWithOverviewMode = true; s.useWideViewPort = true
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = true
        CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.addJavascriptInterface(Bridge(), "RSBridge")
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, url: String) { netFails = 0; inject(url) }
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val u = r.url.toString()
                val sch = r.url.scheme ?: ""
                if (sch == "http" || sch == "https" || sch == "about" || sch == "data" || sch == "blob") return false
                // UPI / bank apps / intent links: hand over to the real app
                try {
                    val i = if (u.startsWith("intent:")) Intent.parseUri(u, Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, r.url)
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                    RedshotService.tatkalLog("opened app for $sch link")
                } catch (e: ActivityNotFoundException) { RedshotService.tatkalLog("no app for $sch link") } catch (e: Exception) { RedshotService.tatkalLog("link failed: " + e.message) }
                return true
            }
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                if (!r.isForMainFrame) return
                netFails++
                setStatus("page nahi khula (net?), dobara koshish #$netFails")
                reloadSoon(minOf(1500L * netFails, 10000L), false)
            }
            override fun onReceivedHttpError(v: WebView, r: WebResourceRequest, resp: WebResourceResponse) {
                if (!r.isForMainFrame) return
                if (resp.statusCode == 403 || resp.statusCode == 429 || resp.statusCode >= 500) handleBlocked("HTTP ${resp.statusCode}")
            }
            override fun onReceivedSslError(v: WebView, h: android.webkit.SslErrorHandler, e: SslError) { h.cancel(); setStatus("SSL dikkat, dobara koshish"); reloadSoon(3000, false) }
            override fun onRenderProcessGone(v: WebView, d: RenderProcessGoneDetail): Boolean {
                RedshotService.tatkalLog("webview renderer gone (crash=${d.didCrash()}) -> restarting screen")
                ui.post { if (alive) recreate() }
                return true
            }
        }
        root.addView(web, FrameLayout.LayoutParams(-1, -1))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.parseColor("#E6101418")); setPadding(20, 12, 20, 12); gravity = Gravity.CENTER_VERTICAL }
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f; text = "Redshot: shuru ho raha hai…" }
        val stop = Button(this).apply { text = "STOP"; setOnClickListener { stopAll("owner ne roka") } }
        bar.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); bar.addView(stop)
        root.addView(bar, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)

        startJob(intent)
        measureSkewLoop()
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        val j = try { JSONObject(i.getStringExtra("job") ?: "{}") } catch (e: Exception) { JSONObject() }
        // the scheduled alarm and the in-process timer both start the same job: the second one is ignored
        val same = j.optString("trainNo") == job.optString("trainNo") && j.optString("date") == job.optString("date") &&
            j.optString("cls") == job.optString("cls") && j.optLong("openAtMs") == job.optLong("openAtMs")
        if (same && TatkalState.stage !in setOf("idle", "stopped", "confirmed")) return
        startJob(i)
    }

    private fun startJob(i: Intent) {
        job = try { JSONObject(i.getStringExtra("job") ?: "{}") } catch (e: Exception) { JSONObject() }
        val (u, pw) = Tatkal.credentials(this)
        job.put("username", u).put("password", pw)
        netFails = 0; blockedN = 0; stuckN = 0
        loadEngineOverride()
        setStatus("shuru ho raha hai…")
        web.loadUrl(HOME)
        RedshotService.tatkalLog("booking start: " + job.optString("fromName") + "->" + job.optString("toName") + " " + job.optString("date") + " " + job.optString("cls") + " " + job.optString("quota") + " train " + job.optString("trainNo"))
    }

    /** IRCTC's own clock (HTTP Date header) against the phone's, re-measured every minute, so "10:00:00" means IRCTC's 10:00:00. */
    private fun measureSkewLoop() {
        Thread {
            while (alive) {
                val sk = serverSkew()
                if (sk != null) { skewMs = sk; RedshotService.tatkalLog("clock skew vs server: ${sk}ms") }
                try { Thread.sleep(60_000) } catch (e: InterruptedException) { return@Thread }
            }
        }.also { it.isDaemon = true }.start()
    }

    private fun serverSkew(): Long? {
        for (u in listOf("https://www.irctc.co.in/", "https://www.google.com/", "https://www.cloudflare.com/")) {
            try {
                val c = URL(u).openConnection() as HttpURLConnection
                c.requestMethod = "HEAD"; c.connectTimeout = 3500; c.readTimeout = 3500
                val t0 = System.currentTimeMillis(); val d = c.date; val t1 = System.currentTimeMillis(); c.disconnect()
                if (d > 0) return d - (t0 + t1) / 2
            } catch (e: Exception) {}
        }
        return null
    }

    /** a fixed engine pushed from the brain (IRCTC changed its page) replaces the built-in one; the built-in one is the fallback */
    private fun loadEngineOverride() {
        val sp = getSharedPreferences("tatkal", Context.MODE_PRIVATE)
        engineOverride = sp.getString("engine_js", null)?.takeIf { it.length > 2000 && it.contains("__RS") }
        Thread {
            try {
                val r = Brain.post(applicationContext, "device/tatkal_engine", JSONObject().put("have", sp.getString("engine_version", "") ?: ""), 4000)
                val js = r?.optString("js", "") ?: ""
                val v = r?.optString("version", "") ?: ""
                if (js.length > 2000 && js.contains("__RS") && v.isNotEmpty()) {
                    sp.edit().putString("engine_js", js).putString("engine_version", v).apply()
                    engineOverride = js
                    RedshotService.tatkalLog("engine update from brain: $v")
                }
            } catch (e: Throwable) {}
        }.also { it.isDaemon = true }.start()
    }

    private fun inject(url: String) {
        if (!url.contains("irctc.co.in")) return
        val js = engineOverride ?: assets.open("tatkal/engine.js").bufferedReader().use { it.readText() }
        web.evaluateJavascript(js, null)
        web.evaluateJavascript("window.__RS && window.__RS.start(" + job.toString() + ");", null)
    }

    private fun reloadSoon(ms: Long, home: Boolean) {
        if (TatkalState.stage == "payment" || TatkalState.stage == "wallet") return        // never disturb a payment in progress
        ui.postDelayed({ if (alive) { if (home) web.loadUrl(HOME) else web.reload() } }, ms)
    }

    private fun handleBlocked(why: String) {
        if (TatkalState.stage == "payment" || TatkalState.stage == "wallet" || TatkalState.stage == "confirmed") return
        val now = System.currentTimeMillis()
        if (now - lastMainFrameErrorAt < 3000) return
        lastMainFrameErrorAt = now
        blockedN++
        setStatus("IRCTC ne rok diya ($why), dobara koshish #$blockedN")
        RedshotService.tatkalLog("blocked/error page: $why (#$blockedN)")
        if (blockedN == 3) RedshotService.tatkalSay("IRCTC site abhi mana kar rahi hai, main dobara koshish karta rahunga.")
        reloadSoon(minOf(3000L * blockedN, 25_000L), true)
    }

    private fun onEvent(type: String, json: String) {
        val d = try { JSONObject(json) } catch (e: Exception) { JSONObject() }
        when (type) {
            "stage" -> { val st = d.optString("stage", TatkalState.stage); TatkalState.stage = st; setStatus(st); if (st != "other") { blockedN = 0; stuckN = 0 } }
            "log" -> { val m = d.optString("msg", d.optString("m", "")); TatkalState.last = m; setStatus(m); RedshotService.tatkalLog(m) }
            "armed" -> { TatkalState.stage = "armed"; setStatus("tayyar, tatkal khulne ka intezaar"); RedshotService.tatkalSay("Main tayyar hu, tatkal khulte hi book karunga.") }
            "need_login" -> { TatkalState.stage = "login"; setStatus("login details nahi hain"); RedshotService.tatkalSay("IRCTC id aur password save nahi hain. Tatkal setup khol deta hu, wahan daal do.") ; ui.post { startActivity(Intent(this, TatkalSetupActivity::class.java)) } }
            "captcha" -> { buzz(); setStatus("CAPTCHA daalo"); RedshotService.tatkalSay(if (d.optBoolean("again")) "Captcha abhi baaki hai, daal do." else "Captcha daal do, baaki main kar dunga.") }
            "payment_ready" -> {
                buzz(); TatkalState.stage = "payment"; setStatus("PAYMENT karo")
                RedshotService.tatkalSay(if (d.optString("reason") == "balance") "Wallet me balance kam hai, payment tum kar do." else "Payment ka page aa gaya, payment tum kar do.")
            }
            "confirmed" -> {
                val pnr = d.optString("pnr"); TatkalState.stage = "confirmed"; TatkalState.pnr = pnr; buzz()
                setStatus("TICKET BOOK: PNR $pnr"); RedshotService.tatkalLog("CONFIRMED PNR $pnr")
                RedshotService.tatkalSay("Ticket book ho gaya. PNR $pnr.")
            }
            "blocked" -> handleBlocked("Access Denied page")
            "stuck" -> { stuckN++; if (stuckN <= 6) { RedshotService.tatkalLog("page ruki hui, search page par wapas (#$stuckN)"); reloadSoon(500, true) } }
            "error" -> {
                val m = d.optString("msg", d.optString("reason", "error")); TatkalState.error = m; setStatus("dikkat: $m"); RedshotService.tatkalLog("error: $m")
                if (d.optBoolean("fatal", false)) { TatkalState.stage = "failed"; RedshotService.tatkalSay("Booking ruk gayi: $m") }
            }
        }
    }

    private fun setStatus(t: String) { try { status.text = "Redshot: $t" } catch (e: Throwable) {} }
    private fun buzz() { try { val v = getSystemService(Vibrator::class.java); v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 250, 120, 250), -1)) } catch (e: Exception) {} }

    private fun stopAll(why: String) {
        try { web.evaluateJavascript("window.__RS && window.__RS.stop();", null) } catch (e: Exception) {}
        TatkalState.stage = "stopped"; TatkalState.stopRequested = true
        RedshotService.tatkalLog("booking roki: $why"); finish()
    }

    override fun onDestroy() {
        alive = false
        ui.removeCallbacksAndMessages(null)
        try { unregisterReceiver(stopRx) } catch (e: Exception) {}
        try { wake?.let { if (it.isHeld) it.release() } } catch (e: Exception) {}
        try { web.destroy() } catch (e: Exception) {}
        super.onDestroy()
    }
}
