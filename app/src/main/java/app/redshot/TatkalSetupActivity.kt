package app.redshot

import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import org.json.JSONArray
import org.json.JSONObject

/** One small form where the owner types IRCTC id/password/mobile/passengers himself (a password must never be spoken). */
class TatkalSetupActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val p = Tatkal.profile(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40) }
        fun label(t: String) = TextView(this).apply { text = t; textSize = 13f; setPadding(0, 24, 0, 4) }
        fun field(hint: String, v: String, type: Int = InputType.TYPE_CLASS_TEXT, lines: Int = 1) = EditText(this).apply {
            this.hint = hint; setText(v); inputType = type or (if (lines > 1) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0); if (lines > 1) { minLines = lines; gravity = android.view.Gravity.TOP }
        }
        col.addView(TextView(this).apply { text = "Tatkal setup"; textSize = 22f })
        col.addView(TextView(this).apply { text = "Ye sirf is phone me rehta hai. Password encrypted save hota hai, kahin bheja nahi jata."; textSize = 12f })
        col.addView(label("IRCTC user id"))
        val user = field("user id", p.optString("username")); col.addView(user)
        col.addView(label("IRCTC password (khali chhodo to purana rahega)"))
        val pw = field("password", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD); col.addView(pw)
        col.addView(label("Mobile number"))
        val mob = field("10 digit", p.optString("mobile"), InputType.TYPE_CLASS_PHONE); col.addView(mob)
        col.addView(label("Passengers: har line me  naam, umar, M/F/T, berth (LB/MB/UB/SL/SU ya khali)"))
        val ps = p.optJSONArray("passengers") ?: JSONArray()
        val cur = (0 until ps.length()).joinToString("\n") { i -> ps.getJSONObject(i).let { x -> listOf(x.optString("name"), x.optString("age"), x.optString("gender"), x.optString("berth")).joinToString(", ") } }
        val pax = field("RAVI KUMAR, 21, M, LB\nSITA DEVI, 45, F", cur, lines = 5); col.addView(pax)
        col.addView(label("Payment"))
        val rg = RadioGroup(this)
        val rw = RadioButton(this).apply { text = "IRCTC eWallet (khud chunega, CONFIRM tum)"; id = 1 }
        val ru = RadioButton(this).apply { text = "UPI (app khulega, tum approve karo)"; id = 2 }
        val rm = RadioButton(this).apply { text = "Payment page par ruk jao, sab main karunga"; id = 3 }
        rg.addView(rw); rg.addView(ru); rg.addView(rm)
        rg.check(when (p.optString("payMode", "ewallet")) { "upi" -> 2; "manual" -> 3; else -> 1 })
        col.addView(rg)
        val save = Button(this).apply { text = "SAVE" }
        col.addView(save, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        save.setOnClickListener {
            val arr = JSONArray()
            for (line in pax.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }) {
                val f = line.split(',').map { it.trim() }
                if (f.size < 3) { Toast.makeText(this, "Is line me naam, umar, gender chahiye: $line", Toast.LENGTH_LONG).show(); return@setOnClickListener }
                val o = JSONObject().put("name", f[0].uppercase()).put("age", f[1].toIntOrNull() ?: 0).put("gender", f[2].uppercase().take(1))
                if (f.size > 3 && f[3].isNotEmpty()) o.put("berth", f[3].uppercase())
                arr.put(o)
            }
            val a = JSONObject().put("username", user.text.toString().trim()).put("mobile", mob.text.toString().trim()).put("passengers", arr)
                .put("payMode", when (rg.checkedRadioButtonId) { 2 -> "upi"; 3 -> "manual"; else -> "ewallet" })
            if (pw.text.isNotEmpty()) a.put("password", pw.text.toString())
            Tatkal.saveProfile(this, a)
            Toast.makeText(this, "Save ho gaya", Toast.LENGTH_SHORT).show()
            RedshotService.tatkalLog("tatkal setup saved by owner")
            finish()
        }
        setContentView(ScrollView(this).apply { addView(col) })
    }
}
