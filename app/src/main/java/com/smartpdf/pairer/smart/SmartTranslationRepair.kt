package com.smartpdf.pairer.smart

import android.content.Context
import android.graphics.Bitmap
import android.icu.text.ArabicShaping
import android.icu.text.Bidi
import com.googlecode.tesseract.android.TessBaseAPI
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

/**
 * Conservative repair of English text that is still visibly present in the Arabic-side PDF.
 * Native PDF text is preferred. OCR (including hOCR bounding boxes) is used only when native
 * extraction is empty or clearly corrupted. A region is overwritten only when its placement
 * and a light/white background are sufficiently safe; otherwise it is reported for review.
 */
object SmartTranslationRepair {
    data class Result(
        val outputPdf: File,
        val report: String,
        val translatedRegions: Int,
        val reviewRegions: Int,
        val pagesProcessed: Int
    )

    private data class TextRegion(
        val text: String,
        val x: Float,
        val baselineFromTop: Float,
        val width: Float,
        val height: Float,
        val confidence: Int = 100,
        val fromOcr: Boolean = false,
        val bitmapBox: IntArray? = null
    )

    private val englishOnly = Regex("^[\\p{Latin}\\p{Punct}\\p{Zs}\\p{N}]+$")
    private val latinWord = Regex("[A-Za-z]{2,}")
    private val arabic = Regex("[\\u0600-\\u06FF]")
    private const val MODEL_BASE = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/"
    private const val FONT_URL = "https://fonts.gstatic.com/s/notonaskharabic/v33/RrQ5bpV-9Dd1b1OAGA6M9PkyDuVBePeKNaxcsss0Y7bwvc5krK0z9_Mnuw.ttf"
    private const val FONT_SHA256 = "b957e8c71a24e50c1aad4df775c46282bbe5e62e2b2b2ca72b153d75b6a15edd"

    fun repair(
        context: Context,
        sourcePdf: File,
        translationPdf: File,
        outputPdf: File,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }
    ): Result {
        require(sourcePdf.isFile && sourcePdf.length() > 0L) { "الملف الأصلي غير موجود أو فارغ." }
        require(translationPdf.isFile && translationPdf.length() > 0L) { "ملف الترجمة غير موجود أو فارغ." }

        var tesseract: TessBaseAPI? = null
        var translatedCount = 0
        var reviewCount = 0
        val report = StringBuilder()
        report.append("SmartPDF Pairer — تقرير الدمج الذكي وتحسين الترجمة\n")
        report.append("الملف الأصلي: ${sourcePdf.name}\nملف الترجمة: ${translationPdf.name}\n")
        report.append("السياسة: ترجمة النص الإنجليزي المتبقي فقط؛ الحفاظ على العربية؛ الإدراج في موضع النص؛ عدم تعديل الملفات المصدرية.\n")
        report.append("ملاحظة: لا يمكن إثبات غياب فقرة بالكامل من ملف الترجمة بمجرد البحث عن الإنجليزية؛ يوضح التقرير حالات النص المتبقي التي أمكن رصدها.\n")
        report.append("=".repeat(64)).append('\n')

        try {
            PDDocument.load(sourcePdf).use { sourceDoc ->
                PDDocument.load(translationPdf).use { translatedDoc ->
                    require(sourceDoc.numberOfPages == translatedDoc.numberOfPages) {
                        "عدد الصفحات غير متساوٍ: الأصلي ${sourceDoc.numberOfPages}، والترجمة ${translatedDoc.numberOfPages}. يلزم تطابق الصفحات حاليًا."
                    }
                    require(sourceDoc.numberOfPages > 0) { "ملفات PDF لا تحتوي على صفحات." }

                    val targetRenderer = PDFRenderer(translatedDoc)
                    val nativeStripper = PositionedTextStripper()
                    val total = translatedDoc.numberOfPages

                    for (pageIndex in 0 until total) {
                        val sourceLines = nativeStripper.extract(sourceDoc, pageIndex)
                        val targetLines = nativeStripper.extract(translatedDoc, pageIndex)
                        val targetPage = translatedDoc.getPage(pageIndex)
                        val pageWidth = targetPage.mediaBox.width
                        val pageHeight = targetPage.mediaBox.height
                        val targetPageHasCorruptText = isExtractionCorrupt(targetLines)
                        val hasImages = pageHasImageXObjects(targetPage)

                        var ocrBitmap: Bitmap? = null
                        var ocrRegions = emptyList<TextRegion>()
                        if (targetPageHasCorruptText || hasImages) {
                            try {
                                if (tesseract == null) tesseract = createOcrIfNeeded(context)
                                val dpi = 180f
                                ocrBitmap = targetRenderer.renderImageWithDPI(pageIndex, dpi)
                                tesseract?.setImage(ocrBitmap)
                                val hocr = tesseract?.let { invokeHocr(it) }.orEmpty()
                                ocrRegions = parseHocr(hocr, dpi)
                                if (ocrRegions.isEmpty()) {
                                    report.append("الصفحة ${pageIndex + 1}: تعذر تهيئة OCR أو لم تتوفر إحداثيات hOCR موثوقة؛ لم يتم إدراج نص عشوائي.\n")
                                }
                            } catch (e: Exception) {
                                report.append("الصفحة ${pageIndex + 1}: تعذر OCR: ${e.message ?: e.javaClass.simpleName}\n")
                            }
                        }

                        val nativeCandidates = targetLines.filter { isEnglishOnly(it.text) }
                        val nativeKeys = nativeCandidates.map { normalize(it.text) }.toSet()
                        val imageCandidates = ocrRegions.filter {
                            isEnglishOnly(it.text) && normalize(it.text) !in nativeKeys
                        }
                        val candidates = if (targetPageHasCorruptText) imageCandidates else nativeCandidates + imageCandidates

                        if (candidates.isEmpty()) {
                            val sourceEnglishCount = sourceLines.count { isEnglishOnly(it.text) }
                            report.append("الصفحة ${pageIndex + 1}: لم يُكتشف نص إنجليزي متبقٍ قابل للاستبدال")
                            if (sourceEnglishCount > 0) report.append(" (الأصل يحتوي $sourceEnglishCount سطرًا إنجليزيًا قابلًا للاستخراج)")
                            report.append(".\n")
                            onProgress(pageIndex + 1, total, "فحص الصفحة ${pageIndex + 1} من $total")
                            ocrBitmap?.recycle()
                            tesseract?.clear()
                            continue
                        }

                        report.append("\nالصفحة ${pageIndex + 1}: اكتشاف ${candidates.size} منطقة إنجليزية متبقية.\n")
                        val fontFile = ensureFont(context)
                        val font = FileInputStream(fontFile).use { PDType0Font.load(translatedDoc, it, true) }
                        // Render a visual sample even for text-only pages: vector backgrounds
                        // can also be non-white and must not be painted over blindly.
                        val backgroundBitmap = ocrBitmap ?: try {
                            targetRenderer.renderImageWithDPI(pageIndex, 144f)
                        } catch (_: Exception) { null }

                        for (region in candidates) {
                            val rawText = region.text.trim()
                            val translated = translate(rawText)
                            if (translated.isNullOrBlank()) {
                                reviewCount++
                                report.append("[مراجعة] تعذرت الترجمة: $rawText\n")
                                continue
                            }

                            val placement = if (region.fromOcr) {
                                val box = region.bitmapBox
                                if (box == null || ocrBitmap == null || !isWhiteBackground(ocrBitmap, box)) null
                                else {
                                    val sx = pageWidth / ocrBitmap.width.toFloat()
                                    val sy = pageHeight / ocrBitmap.height.toFloat()
                                    TextRegion(
                                        text = region.text,
                                        x = box[0] * sx,
                                        baselineFromTop = box[3] * sy,
                                        width = (box[2] - box[0]) * sx,
                                        height = (box[3] - box[1]) * sy,
                                        confidence = region.confidence,
                                        fromOcr = true,
                                        bitmapBox = box
                                    )
                                }
                            } else region

                            val backgroundSafe = when {
                                region.fromOcr -> ocrBitmap != null && region.bitmapBox != null && isWhiteBackground(ocrBitmap, region.bitmapBox)
                                backgroundBitmap != null -> isWhiteBackground(backgroundBitmap, pointsToBitmapBox(placement ?: region, pageWidth, pageHeight, backgroundBitmap))
                                else -> false
                            }
                            if (placement == null || !backgroundSafe) {
                                reviewCount++
                                report.append("[مراجعة] لم يتم التعديل لأن موضع النص أو خلفيته غير آمن: $rawText\n")
                                continue
                            }
                            if (region.confidence < 65) {
                                reviewCount++
                                report.append("[مراجعة] ثقة OCR منخفضة (${region.confidence}%): $rawText\n")
                                continue
                            }

                            val success = drawReplacement(
                                translatedDoc,
                                pageIndex,
                                placement,
                                translated,
                                font,
                                pageWidth,
                                pageHeight
                            )
                            if (success) {
                                translatedCount++
                                report.append("[تمت الترجمة والإضافة في الموضع نفسه]\nEN: $rawText\nAR: $translated\n")
                            } else {
                                reviewCount++
                                report.append("[مراجعة] الترجمة أطول من المساحة الآمنة أو تعذر رسمها؛ لم تُخفَ المنطقة الأصلية: $rawText\nAR: $translated\n")
                            }
                        }
                        onProgress(pageIndex + 1, total, "معالجة الصفحة ${pageIndex + 1} من $total")
                        if (backgroundBitmap !== ocrBitmap) backgroundBitmap?.recycle()
                        ocrBitmap?.recycle()
                        tesseract?.clear()
                    }

                    outputPdf.parentFile?.mkdirs()
                    translatedDoc.save(outputPdf)
                }
            }
        } finally {
            tesseract?.end()
        }

        report.append("\nملخص التنفيذ\n")
        report.append("الصفحات: ${PDDocument.load(outputPdf).use { it.numberOfPages }}\n")
        report.append("المناطق التي تمت ترجمتها وإضافتها: $translatedCount\n")
        report.append("المناطق التي تحتاج مراجعة أو تعذر تعديلها: $reviewCount\n")
        report.append("تنبيه: لا يعني عدم اكتشاف نص متبقٍ أن كل فقرة غائبة قد تمت ترجمتها؛ يجب مراجعة التقرير والصفحات.\n")

        return Result(outputPdf, report.toString(), translatedCount, reviewCount, totalPages(outputPdf))
    }

    private fun totalPages(file: File): Int = PDDocument.load(file).use { it.numberOfPages }

    private class PositionedTextStripper : PDFTextStripper() {
        private val pageLines = ArrayList<TextRegion>()
        init { sortByPosition = true }

        fun extract(document: PDDocument, pageIndex: Int): List<TextRegion> {
            pageLines.clear()
            startPage = pageIndex + 1
            endPage = pageIndex + 1
            getText(document)
            return pageLines.toList()
        }

        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            if (text.isBlank() || textPositions.isEmpty()) return
            val minX = textPositions.minOf { it.xDirAdj }
            val maxX = textPositions.maxOf { it.xDirAdj + it.widthDirAdj }
            val baseline = textPositions.map { it.yDirAdj }.average().toFloat()
            val height = textPositions.maxOf { it.heightDir }
            pageLines.add(TextRegion(text.trim(), minX, baseline, maxX - minX, height))
        }
    }

    private fun isExtractionCorrupt(lines: List<TextRegion>): Boolean {
        val text = lines.joinToString(" ") { it.text }
        if (text.length < 12) return true
        val suspicious = text.count { it == '\uFFFD' || it in '\uE000'..'\uF8FF' }
        if (suspicious.toFloat() / text.length.toFloat() > 0.03f) return true
        val arabicCount = text.count { it in '\u0600'..'\u06FF' }
        val latinCount = text.count { it in 'A'..'Z' || it in 'a'..'z' }
        // Translation-side pages are expected to contain Arabic. A Latin-only extraction
        // can indicate an untranslated page or a broken ToUnicode/CMap mapping.
        return arabicCount == 0 && latinCount >= 20
    }

    private fun normalize(value: String): String = value.lowercase().replace(Regex("[^a-z0-9]+"), "").trim()

    private fun isEnglishOnly(value: String): Boolean {
        val clean = value.trim()
        return clean.length >= 4 && latinWord.containsMatchIn(clean) && !arabic.containsMatchIn(clean) && englishOnly.matches(clean)
    }

    private fun pageHasImageXObjects(page: com.tom_roush.pdfbox.pdmodel.PDPage): Boolean = try {
        val names = page.resources.xObjectNames
        names.any { name -> page.resources.getXObject(name) is com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject }
    } catch (_: Exception) { false }

    private fun drawReplacement(
        document: PDDocument,
        pageIndex: Int,
        region: TextRegion,
        translated: String,
        font: PDType0Font,
        pageWidth: Float,
        pageHeight: Float
    ): Boolean {
        return try {
            val shaped = ArabicShaping(ArabicShaping.LETTERS_SHAPE).shape(translated)
            val bidi = Bidi(shaped, Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT)
            val visualText = bidi.writeReordered(Bidi.DO_MIRRORING)
            val availableWidth = region.width.coerceAtLeast(12f)
            val originalHeight = region.height.coerceAtLeast(7f)
            var fontSize = min(11f, max(7.5f, originalHeight * 0.92f))
            while (fontSize > 7.4f && font.getStringWidth(visualText) / 1000f * fontSize > availableWidth * 0.96f) {
                fontSize -= 0.5f
            }
            val textWidth = font.getStringWidth(visualText) / 1000f * fontSize
            if (textWidth > availableWidth * 0.98f) return false

            val baseline = pageHeight - region.baselineFromTop
            val rectHeight = max(originalHeight * 1.35f, fontSize * 1.25f)
            val rectY = (baseline - originalHeight * 0.35f).coerceAtLeast(0f)
            val rectX = region.x.coerceAtLeast(0f)
            if (rectX + region.width > pageWidth + 2f || rectY + rectHeight > pageHeight + 2f) return false

            PDPageContentStream(
                document,
                document.getPage(pageIndex),
                PDPageContentStream.AppendMode.APPEND,
                true,
                true
            ).use { stream ->
                stream.saveGraphicsState()
                stream.setNonStrokingColor(255, 255, 255)
                stream.addRect(rectX - 1f, rectY, region.width + 2f, rectHeight)
                stream.fill()
                stream.beginText()
                stream.setFont(font, fontSize)
                stream.setNonStrokingColor(20, 20, 20)
                stream.newLineAtOffset(rectX + region.width - textWidth, baseline)
                stream.showText(visualText)
                stream.endText()
                stream.restoreGraphicsState()
            }
            true
        } catch (_: Exception) { false }
    }

    private fun createOcrIfNeeded(context: Context): TessBaseAPI? {
        val root = File(context.filesDir, "tesseract")
        val data = File(root, "tessdata")
        if (!data.exists()) data.mkdirs()
        try {
            ensureRemoteFile(File(data, "eng.traineddata"), MODEL_BASE + "eng.traineddata")
            ensureRemoteFile(File(data, "ara.traineddata"), MODEL_BASE + "ara.traineddata")
            return TessBaseAPI().also { api ->
                check(api.init(root.absolutePath, "ara+eng")) { "تعذر تهيئة OCR." }
                api.setVariable("preserve_interword_spaces", "1")
            }
        } catch (_: Exception) { return null }
    }

    private fun invokeHocr(api: TessBaseAPI): String {
        val method = api.javaClass.methods.firstOrNull {
            it.name == "getHOCRText" && it.parameterTypes.size == 1
        } ?: return ""
        return method.invoke(api, 0) as? String ?: ""
    }

    private fun parseHocr(hocr: String, dpi: Float): List<TextRegion> {
        if (hocr.isBlank()) return emptyList()
        val result = ArrayList<TextRegion>()
        val openPattern = Regex("(?is)<span\\b[^>]*class=['\\\"]ocr_line['\\\"][^>]*>")
        val spanToken = Regex("(?is)</?span\\b[^>]*>")
        val scale = 72f / dpi
        for (opening in openPattern.findAll(hocr)) {
            val tag = opening.value
            val bbox = Regex("(?i)bbox\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)").find(tag) ?: continue
            val innerStart = opening.range.last + 1
            var depth = 1
            var closingStart = -1
            var closingEnd = -1
            for (token in spanToken.findAll(hocr, innerStart)) {
                if (token.value.startsWith("</", ignoreCase = true)) depth-- else depth++
                if (depth == 0) {
                    closingStart = token.range.first
                    closingEnd = token.range.last + 1
                    break
                }
            }
            if (closingStart < 0) continue
            val inner = hocr.substring(innerStart, closingStart)
            val text = android.text.Html.fromHtml(inner, android.text.Html.FROM_HTML_MODE_LEGACY)
                .toString().replace(Regex("\\s+"), " ").trim()
            if (text.isBlank()) continue
            val box = IntArray(4) { bbox.groupValues[it + 1].toInt() }
            val titleConfidence = Regex("(?i)x_wconf\\s+(\\d+)").find(tag)?.groupValues?.get(1)?.toIntOrNull()
            val wordConfidences = Regex("(?i)x_wconf\\s+(\\d+)").findAll(inner).mapNotNull {
                it.groupValues[1].toIntOrNull()
            }.toList()
            val confidence = titleConfidence ?: wordConfidences.takeIf { it.isNotEmpty() }?.average()?.toInt() ?: 0
            result.add(TextRegion(text, box[0] * scale, box[3] * scale,
                (box[2] - box[0]) * scale, (box[3] - box[1]) * scale,
                confidence = confidence, fromOcr = true, bitmapBox = box))
            if (closingEnd <= innerStart) break
        }
        return result
    }

    private fun pointsToBitmapBox(region: TextRegion, pageWidth: Float, pageHeight: Float, bitmap: Bitmap): IntArray {
        val sx = bitmap.width / pageWidth
        val sy = bitmap.height / pageHeight
        val top = (region.baselineFromTop - region.height * 1.2f).coerceAtLeast(0f)
        val bottom = (region.baselineFromTop + region.height * 0.45f).coerceAtMost(pageHeight)
        return intArrayOf(
            (region.x * sx).toInt(), (top * sy).toInt(),
            ((region.x + region.width) * sx).toInt(), (bottom * sy).toInt()
        )
    }

    private fun isWhiteBackground(bitmap: Bitmap, box: IntArray): Boolean {
        val left = box[0].coerceAtLeast(0)
        val top = box[1].coerceAtLeast(0)
        val right = box[2].coerceAtMost(bitmap.width - 1)
        val bottom = box[3].coerceAtMost(bitmap.height - 1)
        if (right <= left || bottom <= top) return false
        val margin = 5
        var tested = 0
        var white = 0
        val sampleStepX = max(1, (right - left) / 30)
        val sampleStepY = max(1, (bottom - top) / 12)
        for (x in (left - margin).coerceAtLeast(0)..(right + margin).coerceAtMost(bitmap.width - 1) step sampleStepX) {
            for (y in (top - margin).coerceAtLeast(0)..(bottom + margin).coerceAtMost(bitmap.height - 1) step sampleStepY) {
                if (x in left..right && y in top..bottom) continue
                val color = bitmap.getPixel(x, y)
                val r = (color shr 16) and 0xff
                val g = (color shr 8) and 0xff
                val b = color and 0xff
                tested++
                if (r > 232 && g > 232 && b > 232) white++
            }
        }
        return tested > 0 && white.toFloat() / tested >= 0.90f
    }

    private fun ensureFont(context: Context): File {
        val font = File(context.filesDir, "fonts/NotoNaskhArabic.ttf")
        if (font.isFile && font.length() > 100_000L) return font
        font.parentFile?.mkdirs()
        ensureRemoteFile(font, FONT_URL, FONT_SHA256)
        return font
    }

    private fun ensureRemoteFile(destination: File, url: String, expectedSha256: String? = null) {
        if (destination.isFile && destination.length() > 100_000L) {
            if (expectedSha256 == null || sha256(destination).equals(expectedSha256, ignoreCase = true)) return
            destination.delete()
        }
        val part = File(destination.parentFile, destination.name + ".part")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 90000
            requestMethod = "GET"
        }
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "تعذر تنزيل أحد ملفات OCR/الخط: HTTP ${connection.responseCode}"
            }
            connection.inputStream.use { input -> part.outputStream().use { output -> input.copyTo(output) } }
            check(part.length() > 100_000L) { "الملف المنزّل غير مكتمل." }
            if (expectedSha256 != null) {
                val actual = sha256(part)
                check(actual.equals(expectedSha256, ignoreCase = true)) { "فشل التحقق من سلامة ملف الخط العربي (SHA-256)." }
            }
            if (destination.exists()) destination.delete()
            check(part.renameTo(destination)) { "تعذر حفظ الملف المنزّل." }
        } finally {
            connection.disconnect()
            if (part.exists()) part.delete()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun translate(text: String): String? {
        val encoded = URLEncoder.encode(text.take(450), "UTF-8")
        val connection = (URL("https://api.mymemory.translated.net/get?q=$encoded&langpair=en%7Car").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 25000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "SmartPDFPairer/1.3")
        }
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val json = JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            if (json.optInt("responseStatus", 200) != 200) return null
            json.optJSONObject("responseData")?.optString("translatedText")
                ?.let { android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_LEGACY).toString() }
                ?.takeIf { it.isNotBlank() && it != "null" && arabic.containsMatchIn(it) }
        } catch (_: Exception) { null }
        finally { connection.disconnect() }
    }
}
