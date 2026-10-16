package app.redshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.net.ssl.HttpsURLConnection

/** Documents, images, sensors and network diagnostics on the phone. Plain-text answers, errors start with "error:". */
object Pro2 {

    fun run(ctx: Context, name: String, a: JSONObject): String? = when (name) {
        "download_file" -> download(ctx, a.optString("url"), a.optString("path"))
        "pdf_create" -> pdfCreate(ctx, a.optString("path"), a.optString("title"), a.optString("text"))
        "docx_create" -> docxCreate(ctx, a.optString("path"), a.optString("text"))
        "xlsx_create" -> xlsxCreate(ctx, a.optString("path"), a.opt("rows"))
        "pdf_page" -> pdfPage(ctx, a.optString("path"), a.optInt("page", 1))
        "image_view" -> imageView(ctx, a.optString("path"))
        "video_frame" -> videoFrame(ctx, a.optString("path"), a.optDouble("at_s", 1.0))
        "image_edit" -> imageEdit(ctx, a)
        "qr_generate" -> qr(ctx, a.optString("text"), a.optString("path", "qr.png"))
        "sensors" -> sensors(ctx, a.optString("kind", "all"))
        "ping" -> ping(a.optString("host"))
        "dns_lookup" -> dns(a.optString("host"))
        "port_check" -> portCheck(a.optString("host"), a.optInt("port", 443))
        "cert_check" -> certCheck(a.optString("host"))
        "audio_transcribe" -> audioTranscribe(ctx, a.optString("path"))
        else -> null
    }

    private fun resolve(ctx: Context, p: String): File = when {
        p.isBlank() -> Pro.root()
        p.startsWith("/") -> File(p)
        p.startsWith("~/") -> File(android.os.Environment.getExternalStorageDirectory(), p.substring(2))
        else -> File(Pro.root(), p)
    }

    private fun outFile(ctx: Context, p: String): File {
        val f = resolve(ctx, p)
        if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager() &&
            !f.path.startsWith(ctx.getExternalFilesDir(null)?.path ?: "###")) return File(ctx.getExternalFilesDir(null), f.name)
        f.parentFile?.mkdirs()
        return f
    }

    private fun download(ctx: Context, url: String, path: String): String {
        if (!url.startsWith("http")) return "error: poora https link do"
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 30000; c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) Redshot")
            val name = path.ifBlank { url.substringAfterLast('/').substringBefore('?').ifBlank { "download.bin" } }
            val f = outFile(ctx, name)
            c.inputStream.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            ActionLog.add("download: $url -> ${f.path}")
            "ok: ${f.path} (${f.length() / 1024}KB)"
        } catch (e: Exception) { "error: download nahi hua: ${e.message}" }
    }

    // ----- creating documents -----

    private fun pdfCreate(ctx: Context, path: String, title: String, text: String): String {
        if (path.isBlank()) return "error: file ka naam do"
        return try {
            val doc = PdfDocument()
            val paint = android.graphics.Paint().apply { textSize = 12f; isAntiAlias = true }
            val head = android.graphics.Paint().apply { textSize = 18f; isFakeBoldText = true; isAntiAlias = true }
            val w = 595; val h = 842; val margin = 40f
            var pageNo = 1
            var page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create())
            var y = margin + 10
            if (title.isNotBlank()) { page.canvas.drawText(title, margin, y, head); y += 30 }
            for (para in text.split("\n")) {
                val words = para.split(" ")
                var line = ""
                fun flush() {
                    if (y > h - margin) { doc.finishPage(page); pageNo++; page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, pageNo).create()); y = margin + 10 }
                    page.canvas.drawText(line, margin, y, paint); y += 17; line = ""
                }
                for (wd in words) {
                    if (paint.measureText("$line $wd") > w - 2 * margin) flush()
                    line = if (line.isEmpty()) wd else "$line $wd"
                }
                flush()
            }
            doc.finishPage(page)
            val f = outFile(ctx, path)
            f.outputStream().use { doc.writeTo(it) }
            doc.close()
            ActionLog.add("pdf bani: ${f.path}")
            "ok: ${f.path} ($pageNo page)"
        } catch (e: Exception) { "error: ${e.message}" }
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun zipTo(f: File, files: Map<String, String>) {
        ZipOutputStream(f.outputStream()).use { z -> files.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
    }

    private fun docxCreate(ctx: Context, path: String, text: String): String {
        if (path.isBlank()) return "error: file ka naam do"
        return try {
            val body = text.split("\n").joinToString("") { "<w:p><w:r><w:t xml:space=\"preserve\">${esc(it)}</w:t></w:r></w:p>" }
            val f = outFile(ctx, if (path.endsWith(".docx")) path else "$path.docx")
            zipTo(f, mapOf(
                "[Content_Types].xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>",
                "_rels/.rels" to "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>",
                "word/document.xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>$body</w:body></w:document>"
            ))
            "ok: ${f.path}"
        } catch (e: Exception) { "error: ${e.message}" }
    }

    private fun colName(i: Int): String { var n = i; var s = ""; while (n >= 0) { s = ('A' + n % 26) + s; n = n / 26 - 1 }; return s }

    private fun xlsxCreate(ctx: Context, path: String, rowsAny: Any?): String {
        if (path.isBlank()) return "error: file ka naam do"
        val rows = when (rowsAny) { is JSONArray -> rowsAny; is String -> try { JSONArray(rowsAny) } catch (e: Exception) { return "error: rows JSON array of arrays hona chahiye" }; else -> return "error: rows do" }
        return try {
            val sb = StringBuilder()
            for (r in 0 until rows.length()) {
                val row = rows.optJSONArray(r) ?: continue
                sb.append("<row r=\"${r + 1}\">")
                for (c in 0 until row.length()) {
                    val v = row.get(c); val ref = colName(c) + (r + 1)
                    if (v is Number) sb.append("<c r=\"$ref\"><v>$v</v></c>")
                    else if (v.toString().startsWith("=")) sb.append("<c r=\"$ref\"><f>${esc(v.toString().substring(1))}</f></c>")
                    else sb.append("<c r=\"$ref\" t=\"inlineStr\"><is><t>${esc(v.toString())}</t></is></c>")
                }
                sb.append("</row>")
            }
            val f = outFile(ctx, if (path.endsWith(".xlsx")) path else "$path.xlsx")
            zipTo(f, mapOf(
                "[Content_Types].xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>",
                "_rels/.rels" to "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>",
                "xl/workbook.xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
                "xl/_rels/workbook.xml.rels" to "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>",
                "xl/worksheets/sheet1.xml" to "<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>$sb</sheetData></worksheet>"
            ))
            "ok: ${f.path}"
        } catch (e: Exception) { "error: ${e.message}" }
    }

    // ----- seeing files: everything goes to the live model as a picture -----

    private fun send(bmp: Bitmap, label: String): String {
        val w = 900; val h = (bmp.height * w.toFloat() / bmp.width).toInt().coerceAtLeast(1)
        val sc = if (bmp.width > w) Bitmap.createScaledBitmap(bmp, w, h, true) else bmp
        val bo = ByteArrayOutputStream(); sc.compress(Bitmap.CompressFormat.JPEG, 70, bo)
        val ok = Deep.sendImage?.invoke(bo.toByteArray()) ?: false
        return if (ok) "ok: $label tujhe bhej diya, ab dekh ke padh/samajh" else "error: tasveer live session tak nahi pahunchi"
    }

    private fun pdfPage(ctx: Context, path: String, page: Int): String {
        val f = resolve(ctx, path)
        if (!f.exists()) return "error: ${f.path} nahi mila"
        return try {
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r ->
                    val i = (page - 1).coerceIn(0, r.pageCount - 1)
                    r.openPage(i).use { p ->
                        val bmp = Bitmap.createBitmap(p.width * 2, p.height * 2, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        send(bmp, "PDF page ${i + 1}/${r.pageCount}")
                    }
                }
            }
        } catch (e: Exception) { "error: pdf nahi khula: ${e.message}" }
    }

    private fun imageView(ctx: Context, path: String): String {
        val f = resolve(ctx, path)
        if (!f.exists()) return "error: ${f.path} nahi mila"
        val bmp = BitmapFactory.decodeFile(f.path) ?: return "error: ye image nahi hai"
        return send(bmp, f.name)
    }

    private fun videoFrame(ctx: Context, path: String, atS: Double): String {
        val f = resolve(ctx, path)
        if (!f.exists()) return "error: ${f.path} nahi mila"
        return try {
            val m = MediaMetadataRetriever(); m.setDataSource(f.path)
            val dur = (m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: "0").toLong()
            val bmp = m.getFrameAtTime((atS * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST) ?: return "error: frame nahi mila"
            m.release()
            send(bmp, "video frame at ${atS}s (video ${dur / 1000}s ki hai)")
        } catch (e: Exception) { "error: ${e.message}" }
    }

    private fun imageEdit(ctx: Context, a: JSONObject): String {
        val src = resolve(ctx, a.optString("path"))
        if (!src.exists()) return "error: ${src.path} nahi mila"
        var bmp = BitmapFactory.decodeFile(src.path) ?: return "error: ye image nahi hai"
        val ops = a.optString("op", "resize").split(",").map { it.trim() }
        for (op in ops) when (op) {
            "resize" -> { val w = a.optInt("w", bmp.width); val h = a.optInt("h", if (a.has("w")) bmp.height * w / bmp.width else bmp.height); bmp = Bitmap.createScaledBitmap(bmp, w.coerceAtLeast(1), h.coerceAtLeast(1), true) }
            "crop" -> { val x = a.optInt("x", 0); val y = a.optInt("y", 0); val w = a.optInt("w", bmp.width - x).coerceAtMost(bmp.width - x); val h = a.optInt("h", bmp.height - y).coerceAtMost(bmp.height - y); bmp = Bitmap.createBitmap(bmp, x, y, w, h) }
            "rotate" -> { val m = Matrix(); m.postRotate(a.optDouble("degrees", 90.0).toFloat()); bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true) }
            "grayscale" -> { val g = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888); val c = android.graphics.Canvas(g)
                val p = android.graphics.Paint(); p.colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) }); c.drawBitmap(bmp, 0f, 0f, p); bmp = g }
            "compress" -> {}
            else -> return "error: op samajh nahi aaya (resize, crop, rotate, grayscale, compress, comma se jod sakta hai)"
        }
        val out = outFile(ctx, a.optString("to").ifBlank { src.nameWithoutExtension + "_edit." + (if (a.optString("format") == "png") "png" else "jpg") })
        val png = out.extension.lowercase() == "png"
        out.outputStream().use { bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, a.optInt("quality", 85).coerceIn(5, 100), it) }
        return "ok: ${out.path} (${bmp.width}x${bmp.height}, ${out.length() / 1024}KB)"
    }

    private fun qr(ctx: Context, text: String, path: String): String =
        download(ctx, "https://api.qrserver.com/v1/create-qr-code/?size=600x600&data=" + java.net.URLEncoder.encode(text, "UTF-8"), path)

    // ----- sensors & network -----

    private fun sensors(ctx: Context, kind: String): String {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val want = mapOf("accelerometer" to Sensor.TYPE_ACCELEROMETER, "light" to Sensor.TYPE_LIGHT, "proximity" to Sensor.TYPE_PROXIMITY,
            "pressure" to Sensor.TYPE_PRESSURE, "temperature" to Sensor.TYPE_AMBIENT_TEMPERATURE, "humidity" to Sensor.TYPE_RELATIVE_HUMIDITY,
            "gyroscope" to Sensor.TYPE_GYROSCOPE, "magnetic" to Sensor.TYPE_MAGNETIC_FIELD, "steps" to Sensor.TYPE_STEP_COUNTER)
            .filter { kind == "all" || it.key == kind }
        val out = StringBuilder()
        for ((n, t) in want) {
            val s = sm.getDefaultSensor(t) ?: run { out.append("$n: phone mein nahi hai\n"); null } ?: continue
            val latch = CountDownLatch(1); var vals: FloatArray? = null
            val l = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) { vals = e.values.clone(); latch.countDown() }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }
            sm.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL)
            latch.await(1200, TimeUnit.MILLISECONDS); sm.unregisterListener(l)
            out.append("$n: ").append(vals?.joinToString(", ") { "%.2f".format(it) } ?: "reading nahi mili").append('\n')
        }
        return out.toString().ifBlank { "error: sensor ka naam (all, accelerometer, light, proximity, pressure, temperature, humidity, gyroscope, magnetic, steps)" }
    }

    private fun ping(host: String): String = try {
        val t0 = System.nanoTime(); val ok = InetAddress.getByName(host).isReachable(2500)
        if (ok) "ok: $host ${(System.nanoTime() - t0) / 1_000_000}ms" else "error: $host reply nahi de raha"
    } catch (e: Exception) { "error: ${e.message}" }

    private fun dns(host: String): String = try {
        "ok: $host -> " + InetAddress.getAllByName(host).joinToString(", ") { it.hostAddress ?: "" }
    } catch (e: Exception) { "error: ${e.message}" }

    /** Single-port reachability check (diagnostics for your own hosts); not a range scanner. */
    private fun portCheck(host: String, port: Int): String = try {
        Socket().use { it.connect(InetSocketAddress(host, port), 2500) }; "ok: $host:$port khula hai"
    } catch (e: Exception) { "error: $host:$port band/unreachable (${e.message})" }

    private fun certCheck(host: String): String = try {
        val c = URL("https://$host").openConnection() as HttpsURLConnection
        c.connectTimeout = 6000; c.readTimeout = 6000; c.connect()
        val x = c.serverCertificates.first() as java.security.cert.X509Certificate
        c.disconnect()
        "ok: $host certificate: issuer=${x.issuerX500Principal.name.take(80)}, valid ${x.notBefore} se ${x.notAfter} tak"
    } catch (e: Exception) { "error: certificate theek nahi / nahi mila: ${e.message}" }

    private fun audioTranscribe(ctx: Context, path: String): String {
        val f = resolve(ctx, path)
        if (!f.exists()) return "error: ${f.path} nahi mila"
        if (f.length() > 6_000_000) return "error: file bahut badi hai (6MB tak)"
        val b64 = android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
        val r = Brain.post(ctx, "device/tool", JSONObject().put("name", "transcribe").put("args", JSONObject().put("audio", b64).put("mime", if (f.extension == "wav") "audio/wav" else "audio/mpeg")), 60000)
        return r?.optString("result", "")?.ifBlank { null } ?: "error: transcribe nahi hua (${Brain.lastError})"
    }
}
