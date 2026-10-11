package app.redshot

import android.content.Context
import android.os.Build
import java.util.UUID

object Config {
    // Brain (Floot) address. Updated by the build when the brain is published.
    const val DEFAULT_BRAIN_URL = "https://REPLACE_BRAIN_URL"

    private fun prefs(c: Context) = c.getSharedPreferences("redshot", Context.MODE_PRIVATE)

    fun brainUrl(c: Context): String =
        prefs(c).getString("brain_url", DEFAULT_BRAIN_URL) ?: DEFAULT_BRAIN_URL

    fun setBrainUrl(c: Context, url: String) {
        prefs(c).edit().putString("brain_url", url.trim()).apply()
    }

    fun deviceId(c: Context): String = stable(c, "device_id")

    // Random secret made on this phone, never leaves except to the brain. The owner approves it once.
    fun deviceSecret(c: Context): String = stable(c, "device_secret")

    fun deviceName(): String = (Build.MANUFACTURER + " " + Build.MODEL).trim()

    private fun stable(c: Context, key: String): String {
        val p = prefs(c)
        var v = p.getString(key, null)
        if (v == null) {
            v = UUID.randomUUID().toString().replace("-", "")
            p.edit().putString(key, v).apply()
        }
        return v
    }
}
