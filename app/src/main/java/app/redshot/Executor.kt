package app.redshot

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import org.json.JSONObject

object Executor {
    private var torchOn = false

    /** Start an activity even when Redshot has no screen open (accessibility service is allowed to). */
    fun startBg(ctx: Context, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val c: Context = RedshotAccess.instance ?: ctx
        c.startActivity(intent)
    }

    /** Runs one action from the brain. Returns a short result note, or null if nothing to report. */
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
                    startBg(ctx, Intent(Intent.ACTION_CALL, Uri.parse("tel:" + a.optString("number"))))
                    null
                }
                "torch" -> {
                    setTorch(ctx, if (a.has("on")) a.optBoolean("on") else !torchOn)
                    null
                }
                "volume" -> {
                    volume(ctx, a.optString("dir"))
                    null
                }
                "global" -> {
                    if (RedshotAccess.instance?.global(a.optString("name")) == true) null
                    else "accessibility band hai"
                }
                "tap_text" -> {
                    if (RedshotAccess.instance?.tapText(a.optString("text")) == true) null
                    else "tap nahi hua: " + a.optString("text")
                }
                "type_text" -> {
                    if (RedshotAccess.instance?.typeText(a.optString("text")) == true) null
                    else "type nahi hua"
                }
                else -> null
            }
        } catch (e: Exception) {
            "error: " + (e.message ?: e.javaClass.simpleName)
        }
    }

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
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, d, AudioManager.FLAG_SHOW_UI)
    }
}
