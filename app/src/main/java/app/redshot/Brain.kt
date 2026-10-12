package app.redshot

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Brain {
    @Volatile var lastError: String = ""

    /** POST JSON to the brain. Returns parsed JSON, or null on any failure. Call off the main thread. */
    fun post(ctx: Context, path: String, body: JSONObject, readTimeoutMs: Int = 30000): JSONObject? {
        return try {
            val url = URL(Config.brainUrl(ctx).trimEnd('/') + "/_api/" + path)
            val c = url.openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            c.readTimeout = readTimeoutMs
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("X-Device-Id", Config.deviceId(ctx))
            c.setRequestProperty("X-Device-Secret", Config.deviceSecret(ctx))
            c.setRequestProperty("X-Device-Name", Config.deviceName())
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            c.disconnect()
            if (code in 200..299 && text.isNotBlank()) { lastError = ""; JSONObject(text) }
            else { lastError = "HTTP $code"; null }
        } catch (e: Exception) {
            lastError = e.javaClass.simpleName + ": " + (e.message ?: "")
            null
        }
    }
}
