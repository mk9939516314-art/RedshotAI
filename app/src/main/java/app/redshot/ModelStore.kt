package app.redshot

import android.content.Context
import java.io.File

/** Copies the offline speech models out of the APK once, so they can be opened as normal folders. */
object ModelStore {
    fun ensure(ctx: Context, assetDir: String = "model-hi"): String? {
        return try {
            val target = File(ctx.filesDir, assetDir)
            val marker = File(target, ".ok-" + versionTag(ctx))
            if (!marker.exists()) {
                target.deleteRecursively()
                copyDir(ctx, assetDir, target)
                marker.writeText("ok")
            }
            target.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun versionTag(ctx: Context): String =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0" } catch (e: Exception) { "0" }

    private fun copyDir(ctx: Context, assetPath: String, dest: File) {
        val am = ctx.assets
        val children = am.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            am.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
        } else {
            dest.mkdirs()
            for (c in children) copyDir(ctx, "$assetPath/$c", File(dest, c))
        }
    }
}
