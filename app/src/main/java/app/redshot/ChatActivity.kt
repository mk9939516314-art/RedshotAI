package app.redshot

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.*
import org.json.JSONObject

/**
 * Optional typed chat. It never touches the always-listening voice pipeline: it only calls the brain with plain text
 * (same brain, same memory) and shows the reply as text. Close it and nothing about listening has changed.
 */
class ChatActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: Button
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun bubble(text: String, mine: Boolean): TextView = TextView(this).apply {
        this.text = text; textSize = 15f; setTextColor(Color.WHITE); setTextIsSelectable(true)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.parseColor(if (mine) "#B02A2A" else "#1E1E2A")) }
    }

    private fun add(text: String, mine: Boolean): TextView {
        val b = bubble(text, mine)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6); gravity = if (mine) Gravity.END else Gravity.START
            if (mine) leftMargin = dp(48) else rightMargin = dp(48)
        }
        list.addView(b, lp)
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        return b
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#0A0A0F")); setPadding(dp(12), dp(36), dp(12), dp(10)) }
        root.addView(TextView(this).apply { text = "Redshot se chat"; textSize = 20f; setTextColor(Color.parseColor("#FF3B3B")); typeface = android.graphics.Typeface.DEFAULT_BOLD })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        input = EditText(this).apply {
            hint = "Yaha likho..."; setHintTextColor(Color.parseColor("#777788")); setTextColor(Color.WHITE); textSize = 15f
            maxLines = 4; imeOptions = EditorInfo.IME_ACTION_SEND; setSingleLine(false)
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(Color.parseColor("#15151D")) }
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        send = Button(this).apply {
            text = "Bhejo"; isAllCaps = false; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(Color.parseColor("#FF3B3B")) }
            setOnClickListener { go() }
        }
        row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(send, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)).apply { leftMargin = dp(8) })
        root.addView(row)
        setContentView(root)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        add("Bol bhai, likh ke jo kaam ya sawaal ho. Ye chat sirf tab chalti hai jab tum yaha likhte ho; sunna isse alag hai.", false)
    }

    private fun go() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText(""); add(t, true)
        send.isEnabled = false
        val wait = add("...", false)
        Thread {
            val res = Brain.post(applicationContext, "device/hear", JSONObject().put("ts", System.currentTimeMillis()).put("text", t).put("wake", true).put("weak", false).put("pkg", "app.redshot.chat"), 25000)
            var reply = ""
            if (res == null) reply = "Brain tak baat nahi pahunchi (" + Brain.lastError + ")"
            else if (res.optBoolean("pending")) reply = "Ye phone abhi approve nahi hai."
            else {
                var fail: String? = null
                val acts = res.optJSONArray("actions")
                if (acts != null) for (i in 0 until acts.length()) {
                    val a = acts.optJSONObject(i) ?: continue
                    val note = try { Executor.run(applicationContext, a) } catch (e: Exception) { e.message }
                    ActionLog.add("(chat) " + a.toString().take(150) + " -> " + (note ?: "ok"))
                    if (note != null && fail == null) fail = note
                }
                reply = res.optString("say", "").trim()
                if (fail != null) reply = (if (reply.isBlank()) "" else reply + "\n") + "Ye nahi ho paya: " + fail
                else if (reply.isBlank()) reply = if (acts != null && acts.length() > 0) "Ho gaya." else "(kuch bolne layak nahi tha)"
            }
            ui.post { wait.text = reply; send.isEnabled = true; scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) } }
        }.also { it.isDaemon = true }.start()
    }
}
