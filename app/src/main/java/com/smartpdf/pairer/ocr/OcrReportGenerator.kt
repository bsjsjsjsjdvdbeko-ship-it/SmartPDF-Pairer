package com.smartpdf.pairer.ocr

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.sqrt

/**
 * Conservative OCR assistant for PDFs whose visual text is intact but whose
 * embedded character mapping is broken. It never rewrites the source PDF.
 * The report keeps recognized source text and adds Arabic translations only
 * for clearly Latin-only lines.
 */
object OcrReportGenerator {
    private const val MODEL_BASE = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/"
    private val englishLine = Regex("[A-Za-z]{2,}")
    private val arabicChars = Regex("[\\u0600-\\u06FF]")

    fun generate(
        context: Context,
        pdfFile: File,
        onProgress: (Int, Int, String) -> Unit
    ): String {
        require(pdfFile.isFile && pdfFile.length() > 0L) { "ملف PDF غير موجود أو فارغ." }
        val tessData = File(context.filesDir, "tesseract/tessdata")
        if (!tessData.exists()) tessData.mkdirs()
        ensureModel(File(tessData, "ara.traineddata"), "ara.traineddata")
        ensureModel(File(tessData, "eng.traineddata"), "eng.traineddata")

        val api = TessBaseAPI()
        check(api.init(File(context.filesDir, "tesseract").absolutePath, "ara+eng")) {
            "تعذر تشغيل محرك OCR العربي والإنجليزي."
        }
        api.setVariable("preserve_interword_spaces", "1")

        try {
            PDDocument.load(pdfFile).use { document ->
                val renderer = PDFRenderer(document)
                val total = document.numberOfPages
                val report = StringBuilder()
                report.append("SmartPDF Pairer — تقرير OCR\n")
                report.append("الملف: ${pdfFile.name}\nعدد الصفحات: $total\n")
                report.append("ملاحظة: هذا تقرير نصي مساعد؛ لم يتم تعديل PDF الأصلي.\n")
                report.append("=".repeat(60)).append('\n')

                for (index in 0 until total) {
                    var bitmap: Bitmap? = null
                    try {
                        val pageBox = document.getPage(index).mediaBox
                        val estimatedDpi = sqrt(12_000_000.0 * 72.0 * 72.0 /
                            (pageBox.width.toDouble() * pageBox.height.toDouble())).toFloat()
                        val renderDpi = minOf(200f, estimatedDpi).coerceAtLeast(96f)
                        bitmap = renderer.renderImageWithDPI(index, renderDpi)
                        api.setImage(bitmap)
                        val recognized = api.getUTF8Text().orEmpty().trim()
                        val confidence = api.meanConfidence()
                        report.append("\n\nالصفحة ${index + 1} — ثقة OCR التقريبية: $confidence%\n")
                        report.append("-".repeat(40)).append('\n')
                        if (recognized.isBlank()) {
                            report.append("لم يتم التعرف على نص واضح في هذه الصفحة.\n")
                        } else {
                            report.append("النص المستخرج (مع الحفاظ على النص العربي كما تعرف عليه OCR):\n")
                            report.append(recognized).append('\n')
                            val candidates = if (confidence >= 50) recognized.lineSequence()
                                .map { it.trim() }
                                .filter { it.length >= 4 && englishLine.containsMatchIn(it) && !arabicChars.containsMatchIn(it) }
                                .distinct()
                                .take(80)
                                .toList() else emptyList()
                            if (confidence < 50) {
                                report.append("\nتم تخطي الترجمة الآلية لهذه الصفحة لأن ثقة OCR منخفضة. راجع النص يدويًا.\n")
                            }
                            if (candidates.isNotEmpty()) {
                                report.append("\nترجمة الأسطر الإنجليزية المكتشفة إلى العربية (مراجعة مطلوبة):\n")
                                for (line in candidates) {
                                    val translated = translateEnglishToArabic(line)
                                    report.append("EN: ").append(line).append('\n')
                                    report.append("AR: ").append(translated ?: "[تعذرت الترجمة — احتفظ بالنص الأصلي]").append("\n\n")
                                }
                            }
                        }
                        onProgress(index + 1, total, "OCR الصفحة ${index + 1} من $total")
                    } finally {
                        api.clear()
                        bitmap?.recycle()
                    }
                }
                return report.toString()
            }
        } finally {
            api.end()
        }
    }

    private fun ensureModel(destination: File, modelName: String) {
        if (destination.isFile && destination.length() > 100_000L) return
        val temporary = File(destination.parentFile, "$modelName.part")
        val connection = (URL(MODEL_BASE + modelName).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 60000
            requestMethod = "GET"
        }
        try {
            connection.connect()
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "تعذر تنزيل نموذج OCR ($modelName): HTTP ${connection.responseCode}. تحقق من اتصال الإنترنت."
            }
            connection.inputStream.use { input -> temporary.outputStream().use { output -> input.copyTo(output) } }
            check(temporary.length() > 100_000L) { "ملف نموذج OCR غير مكتمل: $modelName" }
            if (destination.exists()) destination.delete()
            check(temporary.renameTo(destination)) { "تعذر حفظ نموذج OCR: $modelName" }
        } finally {
            connection.disconnect()
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun translateEnglishToArabic(text: String): String? {
        val encoded = URLEncoder.encode(text.take(450), "UTF-8")
        val url = URL("https://api.mymemory.translated.net/get?q=$encoded&langpair=en%7Car")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 20000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "SmartPDFPairer/1.3")
        }
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONObject(body).optJSONObject("responseData")?.optString("translatedText")
                ?.takeIf { it.isNotBlank() && it != "null" }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
