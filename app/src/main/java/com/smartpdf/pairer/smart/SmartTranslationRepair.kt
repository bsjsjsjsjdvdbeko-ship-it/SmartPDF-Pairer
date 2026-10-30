package com.smartpdf.pairer.smart

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import com.googlecode.tesseract.android.TessBaseAPI
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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
        val pagesProcessed: Int,
        val modifiedPages: Set<Int>
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
        var ocrInitializationAttempted = false
        var ocrInitializationError: String? = null
        val translationCache = HashMap<String, String?>()
        var translatedCount = 0
        var reviewCount = 0
        val modifiedPages = linkedSetOf<Int>()
        val report = StringBuilder()
        report.append("SmartPDF Pairer — تقرير الدمج الذكي وتحسين الترجمة\n")
        report.append("الملف الأصلي: ${sourcePdf.name}\nملف الترجمة: ${translationPdf.name}\n")
        report.append("السياسة: ترجمة النص الإنجليزي المتبقي، وفحص اكتمال كتل عربية قابلة للمطابقة بمقارنة مرجعية احترازية؛ لا تُستبدل العربية إلا عند ظهور نقص واضح جداً ووجود موضع وخلفية آمنين.\n")
        report.append("V1.7: إصلاح نص الصور OCR يدعم الخلفية الموحدة وبعض التدرجات اللونية السلسة فقط؛ الصور والأنسجة والحواف المعقدة تبقى للمراجعة دون طمس.\n")
        report.append(if (AiAuditClient.isConfigured(context)) "مراجعة المعنى والصور مفعّلة عبر نموذج AI متوافق مع OpenAI API؛ مراجعة الصور تقريرية فقط، أما النصوص العادية فلا يتم استبدالها إلا عند ثقة 85% أو أعلى وخلفية آمنة.\n" else "ملاحظة: لا توجد إعدادات AI. يستخدم التطبيق فحص تغطية مرجعي احترازياً، وليس حكماً دلالياً كاملاً. الفقرات الغائبة بالكامل تُعلّم للمراجعة ولا تُنشأ في موضع تخميني.\n")
        report.append("=".repeat(64)).append('\n')

        try {
            PDDocument.load(sourcePdf).use { sourceDoc ->
                PDDocument.load(translationPdf).use { translatedDoc ->
                    require(sourceDoc.numberOfPages == translatedDoc.numberOfPages) {
                        "عدد الصفحات غير متساوٍ: الأصلي ${sourceDoc.numberOfPages}، والترجمة ${translatedDoc.numberOfPages}. يلزم تطابق الصفحات حاليًا."
                    }
                    require(sourceDoc.numberOfPages > 0) { "ملفات PDF لا تحتوي على صفحات." }

                    val targetRenderer = PDFRenderer(translatedDoc)
                    val sourceRenderer = PDFRenderer(sourceDoc)
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
                        val sourceHasImages = pageHasImageXObjects(sourceDoc.getPage(pageIndex))
                        // A low-resolution visual comparison detects image/layout changes even
                        // when the PDF stores artwork as vector paths rather than image XObjects.
                        val visualDifference = try {
                            val sourceThumb = sourceRenderer.renderImageWithDPI(pageIndex, 48f)
                            val targetThumb = targetRenderer.renderImageWithDPI(pageIndex, 48f)
                            try { visualDifferencePercent(sourceThumb, targetThumb) }
                            finally { sourceThumb.recycle(); targetThumb.recycle() }
                        } catch (_: Exception) { -1 }
                        val visualImageMismatch = visualDifference >= 38 || sourceHasImages != hasImages
                        val sourceEnglishCount = sourceLines.count { isEnglishOnly(it.text) }
                        val targetArabicCount = targetLines.count { arabic.containsMatchIn(it.text) }
                        report.append("\nالصفحة ${pageIndex + 1}: الأصل يحتوي $sourceEnglishCount منطقة/سطرًا إنجليزيًا قابلاً للاستخراج، وملف الترجمة يحتوي $targetArabicCount منطقة/سطرًا عربياً قابلاً للاستخراج.")
                        if (sourceEnglishCount > 0 && targetArabicCount == 0) {
                            report.append(" [تنبيه قوي: لم يُستخرج نص عربي من صفحة الترجمة؛ يلزم فحص OCR/مراجعة الصفحة]")
                            reviewCount++
                        } else if (sourceEnglishCount >= 4 && targetArabicCount < (sourceEnglishCount / 3)) {
                            report.append(" [تنبيه تغطية محتمل: عدد المناطق العربية أقل بكثير من مناطق الأصل؛ هذا مؤشر تقريبي وليس حكماً على جودة الترجمة]")
                            reviewCount++
                        }
                        if (visualDifference >= 0) {
                            report.append("مقارنة بصرية مصغرة: اختلاف تقريبي ${visualDifference}% بين الصفحتين.")
                            if (visualImageMismatch) report.append(" [اختلاف بصري ملحوظ؛ سيتم فحص عناصر الصفحة بصرياً]")
                            report.append('\n')
                        }
                        if (hasImages && !sourceHasImages) {
                            report.append("[صورة/عنصر مرئي في صفحة الترجمة دون صورة مقابلة في الأصل؛ فحص OCR للنصوص داخل الصفحة مفعّل. لا تُحذف الصورة تلقائياً.]\n")
                        } else if (visualImageMismatch) {
                            report.append("[اختلاف في المحتوى المرئي بين الأصل والترجمة؛ تتم مراجعة نصوص الصور والعناصر المرئية في جهة الترجمة.]\n")
                        }
                        report.append('\n')

                        var ocrBitmap: Bitmap? = null
                        var sourceOcrBitmap: Bitmap? = null
                        var sourceOcrRegions = emptyList<TextRegion>()
                        var ocrRegions = emptyList<TextRegion>()
                        if (targetPageHasCorruptText || hasImages || visualImageMismatch) {
                            try {
                                if (!ocrInitializationAttempted) {
                                    ocrInitializationAttempted = true
                                    try {
                                        tesseract = createOcrIfNeeded(context)
                                    } catch (e: Exception) {
                                        ocrInitializationError = e.message ?: e.javaClass.simpleName
                                    }
                                }
                                if (tesseract == null) {
                                    report.append("الصفحة ${pageIndex + 1}: OCR غير متاح: ${ocrInitializationError ?: "تعذر تهيئة المحرك"}.\n")
                                } else {
                                    val dpi = 180f
                                    ocrBitmap = targetRenderer.renderImageWithDPI(pageIndex, dpi)
                                    tesseract?.setImage(ocrBitmap)
                                    val hocr = tesseract?.let { invokeHocr(it) }.orEmpty()
                                    ocrRegions = parseHocr(hocr, dpi)
                                    // OCR both sides when visual artwork differs. This lets the
                                    // report distinguish text visible only in the translation-side
                                    // artwork from text that is already present in the source page.
                                    if (visualImageMismatch || sourceHasImages) {
                                        sourceOcrBitmap = sourceRenderer.renderImageWithDPI(pageIndex, dpi)
                                        tesseract?.setImage(sourceOcrBitmap)
                                        sourceOcrRegions = parseHocr(tesseract?.let { invokeHocr(it) }.orEmpty(), dpi)
                                        tesseract?.clear()
                                    }
                                    if (ocrRegions.isEmpty()) {
                                        report.append("الصفحة ${pageIndex + 1}: لم يوفر OCR إحداثيات hOCR موثوقة؛ لم يتم إدراج نص عشوائي.\n")
                                    }
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
                        val sourceVisibleKeys = (sourceLines + sourceOcrRegions).map { normalize(it.text) }.toSet()
                        val targetOnlyImageText = imageCandidates.filter { normalize(it.text) !in sourceVisibleKeys }
                        if (targetOnlyImageText.isNotEmpty()) {
                            report.append("[نص داخل صورة/عنصر مرئي في جهة الترجمة دون مقابل نصي واضح في الأصل] تم العثور على ${targetOnlyImageText.size} منطقة OCR؛ ستدخل مناطق الإنجليزية منها مسار الترجمة الآمن.\n")
                        }

                        if (candidates.isNotEmpty()) {
                            report.append("\nالصفحة ${pageIndex + 1}: اكتشاف ${candidates.size} منطقة إنجليزية متبقية.\n")
                        }
                        // V1.6: multimodal page audit checks text embedded in screenshots,
                        // charts and illustrations. Findings are report-only until a safe
                        // region segmentation/inpainting pipeline can prove exact geometry.
                        if (AiAuditClient.isConfigured(context) &&
                            (hasImages || sourceHasImages || visualImageMismatch)) {
                            var sourceVisionBitmap: Bitmap? = null
                            var targetVisionBitmap: Bitmap? = null
                            try {
                                val sourceVision = sourceRenderer.renderImageWithDPI(pageIndex, 96f)
                                sourceVisionBitmap = sourceVision
                                val targetVision = targetRenderer.renderImageWithDPI(pageIndex, 96f)
                                targetVisionBitmap = targetVision
                                val visionFindings = AiAuditClient.auditPageImages(
                                    context, sourceVision, targetVision
                                )
                                if (visionFindings.isEmpty()) {
                                    report.append("[فحص الصور بالذكاء الاصطناعي] لم يُكتشف نقص نصي واضح داخل الصور أو الرسومات.\n")
                                } else {
                                    report.append("[فحص الصور بالذكاء الاصطناعي] اكتشاف ${visionFindings.size} ملاحظة داخل الصور/الرسومات؛ لم يتم طمس أو تعديل الصور تلقائياً.\n")
                                    visionFindings.forEachIndexed { findingIndex, finding ->
                                        report.append("  ${findingIndex + 1}) ثقة ${(finding.confidence * 100).toInt()}% — ${finding.issue}\n")
                                        report.append("     النص في الأصل: ${finding.sourceText}\n")
                                        if (finding.targetText.isNotBlank()) report.append("     النص في الترجمة: ${finding.targetText}\n")
                                        if (finding.suggestedArabic.isNotBlank()) report.append("     اقتراح عربي: ${finding.suggestedArabic}\n")
                                        reviewCount++
                                    }
                                }
                            } catch (e: Exception) {
                                report.append("[تعذر فحص الصور بالذكاء الاصطناعي] ${e.message ?: e.javaClass.simpleName}\n")
                            } finally {
                                sourceVisionBitmap?.recycle()
                                targetVisionBitmap?.recycle()
                            }
                        }

                        // Render a visual sample even for text-only pages: vector backgrounds
                        // can also be non-white and must not be painted over blindly.
                        val backgroundBitmap = ocrBitmap ?: try {
                            targetRenderer.renderImageWithDPI(pageIndex, 144f)
                        } catch (_: Exception) { null }

                        // Semantic quality audit: translate a small set of source English
                        // blocks as independent references, then compare them with the
                        // nearest Arabic block on the same page. Only very-low-overlap cases
                        // are candidates for repair; uncertain geometry/background is always
                        // reported rather than overwritten.
                        val sourceSemanticCandidates = sourceLines
                            .filter { isEnglishOnly(it.text) && it.text.length >= 18 }
                            .take(4)
                        val targetArabicCandidates = targetLines
                            .filter { arabic.containsMatchIn(it.text) && it.text.length >= 12 }
                        val semanticRepaired = HashSet<String>()
                        for (sourceRegion in sourceSemanticCandidates) {
                            val targetRegion = targetArabicCandidates.minByOrNull {
                                kotlin.math.abs(it.baselineFromTop - sourceRegion.baselineFromTop)
                            }
                            if (targetRegion == null || kotlin.math.abs(targetRegion.baselineFromTop - sourceRegion.baselineFromTop) > pageHeight * 0.075f) {
                                // A fully omitted paragraph has no target region to overwrite. Report
                                // it as a candidate, never guess coordinates or paint over artwork.
                                reviewCount++
                                report.append("[فقرة محتمل أنها مفقودة بالكامل] لم يُعثر على كتلة عربية قريبة مكانياً من النص الأصلي؛ يلزم مطابقة بصرية قبل الإدراج.\nEN: ${sourceRegion.text}\n")
                                continue
                            }
                            val targetKey = normalize(targetRegion.text)
                            if (targetKey in semanticRepaired) continue
                            val sourceKey = normalize(sourceRegion.text)

                            // When configured, use the user's OpenAI-compatible model to judge
                            // meaning and omissions. Without credentials, retain the conservative
                            // reference-coverage fallback and clearly disclose that it is heuristic.
                            val aiAudit = if (AiAuditClient.isConfigured(context)) {
                                try {
                                    AiAuditClient.audit(context, sourceRegion.text, targetRegion.text)
                                } catch (e: Exception) {
                                    report.append("[تعذر تدقيق AI] ${e.message}\n")
                                    null
                                }
                            } else null
                            if (aiAudit != null) {
                                report.append("[تدقيق دلالي AI] الحالة=${aiAudit.status}، الثقة=${(aiAudit.confidence * 100).toInt()}%: ${aiAudit.explanation}\n")
                                if (aiAudit.status == "good") continue
                                if (aiAudit.confidence >= 0.85 && aiAudit.correctedArabic.isNotBlank()) {
                                    val safeBox = backgroundBitmap?.let { pointsToBitmapBox(targetRegion, pageWidth, pageHeight, it) }
                                    val safeColor = if (backgroundBitmap != null && safeBox != null)
                                        sampleFlatBackgroundColor(backgroundBitmap, safeBox) else null
                                    if (safeColor != null && drawReplacement(translatedDoc, pageIndex, targetRegion,
                                            aiAudit.correctedArabic, pageWidth, pageHeight, safeColor)) {
                                        translatedCount++
                                        modifiedPages.add(pageIndex)
                                        semanticRepaired.add(targetKey)
                                        report.append("[تم إصلاح الترجمة بتوصية AI عالية الثقة]\nEN: ${sourceRegion.text}\nAR: ${aiAudit.correctedArabic}\n")
                                    } else {
                                        reviewCount++
                                        report.append("[مراجعة بشرية] AI وجد نقصاً/خطأ، لكن موضع الاستبدال أو الخلفية غير آمنين.\nEN: ${sourceRegion.text}\nAR: ${targetRegion.text}\n")
                                    }
                                } else {
                                    reviewCount++
                                    report.append("[مراجعة بشرية] AI وجد احتمال نقص/خطأ لكن الثقة أقل من 85% أو لم يقدم صياغة كاملة؛ لم يتم الاستبدال.\n")
                                }
                                continue
                            }

                            val referenceArabic = if (translationCache.containsKey(sourceKey)) {
                                translationCache[sourceKey]
                            } else {
                                translate(sourceRegion.text).also { translationCache[sourceKey] = it }
                            }
                            if (referenceArabic.isNullOrBlank()) continue
                            val similarity = arabicCoverage(referenceArabic, targetRegion.text)
                            val referenceTokenCount = arabicTokenCount(referenceArabic)
                            val targetTokenCount = arabicTokenCount(targetRegion.text)
                            // Automatic replacement is deliberately limited to obvious
                            // omissions: a tiny target fragment plus almost no reference
                            // vocabulary coverage. Low lexical overlap alone is not proof
                            // of a bad translation and must never trigger destructive edits.
                            val clearlyIncomplete = referenceTokenCount >= 5 &&
                                targetTokenCount < (referenceTokenCount * 0.35f) && similarity < 0.12f
                            if (!clearlyIncomplete) continue

                            val safeBox = backgroundBitmap?.let {
                                pointsToBitmapBox(targetRegion, pageWidth, pageHeight, it)
                            }
                            val safeColor = if (backgroundBitmap != null && safeBox != null)
                                sampleFlatBackgroundColor(backgroundBitmap, safeBox) else null
                            if (safeColor != null && drawReplacement(
                                    translatedDoc, pageIndex, targetRegion, referenceArabic,
                                    pageWidth, pageHeight, safeColor
                                )) {
                                translatedCount++
                                modifiedPages.add(pageIndex)
                                semanticRepaired.add(targetKey)
                                report.append("[إصلاح جودة محتمل] تشابه منخفض جداً مع ترجمة مرجعية (${(similarity * 100).toInt()}%)؛ استُبدلت المنطقة العربية المقابلة بحذر.\n")
                                report.append("EN: ${sourceRegion.text}\nAR: $referenceArabic\n")
                            } else {
                                reviewCount++
                                report.append("[مراجعة جودة] ترجمة عربية محتملة الضعف (تغطية مرجعية ${(similarity * 100).toInt()}%)، لكن لم يتم استبدالها لأن موضعها/خلفيتها غير آمنين أو لا تتسع للمحتوى.\nEN: ${sourceRegion.text}\nAR: ${targetRegion.text}\n")
                            }
                        }

                        for (region in candidates) {
                            val rawText = region.text.trim()
                            val cacheKey = normalize(rawText)
                            val translated = if (translationCache.containsKey(cacheKey)) {
                                translationCache[cacheKey]
                            } else {
                                translate(rawText).also { translationCache[cacheKey] = it }
                            }
                            if (translated.isNullOrBlank()) {
                                reviewCount++
                                report.append("[مراجعة] تعذرت الترجمة: $rawText\n")
                                continue
                            }

                            val placement = if (region.fromOcr) {
                                val box = region.bitmapBox
                                if (box == null || ocrBitmap == null ||
                                    !hasSafeBackground(ocrBitmap, box)) null
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

                            val ocrBackgroundPatch = if (region.fromOcr && ocrBitmap != null && region.bitmapBox != null &&
                                sampleFlatBackgroundColor(ocrBitmap, region.bitmapBox) == null)
                                createSmoothBackgroundPatch(ocrBitmap, region.bitmapBox) else null
                            val backgroundColor = when {
                                region.fromOcr -> if (ocrBitmap != null && region.bitmapBox != null)
                                    sampleFlatBackgroundColor(ocrBitmap, region.bitmapBox) else null
                                backgroundBitmap != null -> sampleFlatBackgroundColor(
                                    backgroundBitmap,
                                    pointsToBitmapBox(placement ?: region, pageWidth, pageHeight, backgroundBitmap)
                                )
                                else -> null
                            }
                            if (placement == null || (backgroundColor == null && ocrBackgroundPatch == null)) {
                                ocrBackgroundPatch?.recycle()
                                reviewCount++
                                report.append("[مراجعة] لم يتم التعديل لأن موضع النص أو خلفيته غير آمن: $rawText\n")
                                continue
                            }
                            if (region.confidence < 65) {
                                ocrBackgroundPatch?.recycle()
                                reviewCount++
                                report.append("[مراجعة] ثقة OCR منخفضة (${region.confidence}%): $rawText\n")
                                continue
                            }

                            val success = drawReplacement(
                                translatedDoc,
                                pageIndex,
                                placement,
                                translated,
                                pageWidth,
                                pageHeight,
                                backgroundColor ?: android.graphics.Color.WHITE,
                                ocrBackgroundPatch
                            )
                            ocrBackgroundPatch?.recycle()
                            if (success) {
                                translatedCount++
                                modifiedPages.add(pageIndex)
                                report.append("[تمت الترجمة والإضافة في الموضع نفسه]\nEN: $rawText\nAR: $translated\n")
                            } else {
                                reviewCount++
                                report.append("[مراجعة] الترجمة أطول من المساحة الآمنة أو تعذر رسمها؛ لم تُخفَ المنطقة الأصلية: $rawText\nAR: $translated\n")
                            }
                        }
                        if (candidates.isEmpty()) {
                            report.append("الصفحة ${pageIndex + 1}: لم يُكتشف نص إنجليزي متبقٍ قابل للاستبدال. اكتمل تدقيق جودة المناطق القابلة للمطابقة.\n")
                        }
                        onProgress(pageIndex + 1, total, "معالجة الصفحة ${pageIndex + 1} من $total")
                        if (backgroundBitmap !== ocrBitmap) backgroundBitmap?.recycle()
                        ocrBitmap?.recycle()
                        sourceOcrBitmap?.recycle()
                        tesseract?.clear()
                    }

                    val outputParent = outputPdf.absoluteFile.parentFile
                    check(outputParent == null || outputParent.exists() || outputParent.mkdirs()) {
                        "تعذر إنشاء مجلد ملف PDF الناتج."
                    }
                    val temporaryOutput = File(outputParent, outputPdf.name + ".partial")
                    if (temporaryOutput.exists()) temporaryOutput.delete()
                    try {
                        translatedDoc.save(temporaryOutput)
                        check(temporaryOutput.isFile && temporaryOutput.length() > 0L) {
                            "لم يتم حفظ ملف PDF المحسّن بصورة صحيحة."
                        }
                        if (outputPdf.exists() && !outputPdf.delete()) {
                            error("تعذر استبدال ملف PDF الناتج السابق.")
                        }
                        check(temporaryOutput.renameTo(outputPdf)) {
                            "تعذر اعتماد ملف PDF الناتج بعد الحفظ."
                        }
                    } finally {
                        if (temporaryOutput.exists()) temporaryOutput.delete()
                    }
                }
            }
        } finally {
            tesseract?.end()
        }

        report.append("\nملخص التنفيذ\n")
        report.append("الصفحات: ${PDDocument.load(outputPdf).use { it.numberOfPages }}\n")
        report.append("المناطق التي تمت ترجمتها وإضافتها: $translatedCount\n")
        report.append("المناطق التي تحتاج مراجعة أو تعذر تعديلها: $reviewCount\n")
        report.append("الصفحات التي تحتوي تعديلات آلية: ${modifiedPages.sorted().joinToString(", ") { (it + 1).toString() }.ifBlank { "لا توجد" }}\n")
        report.append("تنبيه: لا يعني عدم اكتشاف نص متبقٍ أن كل فقرة غائبة قد تمت ترجمتها؛ يجب مراجعة التقرير والصفحات.\n")

        return Result(outputPdf, report.toString(), translatedCount, reviewCount,
            totalPages(outputPdf), modifiedPages.toSet())
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

            // Mixed Arabic/English lines were previously skipped as a whole. Extract
            // independently positioned Latin runs so an English fragment can be handled
            // without painting over the neighboring Arabic text.
            if (arabic.containsMatchIn(text) && textPositions.any { latinWord.containsMatchIn(it.unicode.orEmpty()) }) {
                var run = ArrayList<TextPosition>()
                fun flushRun() {
                    if (run.isEmpty()) return
                    val runText = run.joinToString("") { it.unicode.orEmpty() }.trim()
                    if (runText.length >= 4 && latinWord.containsMatchIn(runText) && !arabic.containsMatchIn(runText)) {
                        val left = run.minOf { it.xDirAdj }
                        val right = run.maxOf { it.xDirAdj + it.widthDirAdj }
                        val runBaseline = run.map { it.yDirAdj }.average().toFloat()
                        val runHeight = run.maxOf { it.heightDir }
                        pageLines.add(TextRegion(runText, left, runBaseline, right - left, runHeight))
                    }
                    run = ArrayList()
                }
                for (position in textPositions) {
                    val glyph = position.unicode.orEmpty()
                    if (glyph.isNotEmpty() && glyph.any { it in 'A'..'Z' || it in 'a'..'z' || it.isWhitespace() || it in ".,:;!?()[]{}-_/" }) {
                        run.add(position)
                    } else {
                        flushRun()
                    }
                }
                flushRun()
            }
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

    /** Fraction of meaningful reference Arabic tokens represented in the target block.
     * Diacritics and common Alef variants are normalized to reduce false negatives.
     */
    private fun arabicTokens(value: String): Set<String> = value
        .replace(Regex("[\u064B-\u065F\u0670]"), "")
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
        .replace('ى', 'ي').replace('ة', 'ه')
        .split(Regex("[^\u0621-\u064A]+"))
        .filter { it.length >= 2 }
        .toSet()

    private fun arabicTokenCount(value: String): Int = arabicTokens(value).size

    private fun arabicCoverage(reference: String, target: String): Float {
        val expected = arabicTokens(reference)
        val actual = arabicTokens(target)
        if (expected.isEmpty()) return 1f
        return expected.count { it in actual }.toFloat() / expected.size.toFloat()
    }

    private fun isEnglishOnly(value: String): Boolean {
        val clean = value.trim()
        return clean.length >= 4 && latinWord.containsMatchIn(clean) && !arabic.containsMatchIn(clean) && englishOnly.matches(clean)
    }

    private fun pageHasImageXObjects(page: com.tom_roush.pdfbox.pdmodel.PDPage): Boolean = try {
        val names = page.resources.xObjectNames
        names.any { name -> page.resources.getXObject(name) is com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject }
    } catch (_: Exception) { false }

    /**
     * Draws the Arabic replacement with Android's native text shaping (Canvas/Paint),
     * then embeds only this small replacement region as a lossless PNG image.
     * The rest of the PDF page remains vector/text content and is not rasterized.
     */
    private fun drawReplacement(
        document: PDDocument,
        pageIndex: Int,
        region: TextRegion,
        translated: String,
        pageWidth: Float,
        pageHeight: Float,
        backgroundColor: Int = android.graphics.Color.WHITE,
        backgroundPatch: Bitmap? = null
    ): Boolean {
        var overlay: Bitmap? = null
        return try {
            val availableWidth = region.width.coerceAtLeast(12f)
            val originalHeight = region.height.coerceAtLeast(7f)
            val baseline = pageHeight - region.baselineFromTop
            val rectHeight = max(originalHeight * 1.55f, 13f)
            val rectX = region.x.coerceAtLeast(0f)
            val rectY = (baseline - originalHeight * 0.48f).coerceAtLeast(0f)
            val rectWidth = availableWidth + 2f
            if (rectX + rectWidth > pageWidth + 2f || rectY + rectHeight > pageHeight + 2f) return false

            // Render at 4x page-point scale for crisp Arabic glyph edges when zoomed.
            val scale = 4
            val bitmapWidth = (rectWidth * scale).toInt().coerceAtLeast(8)
            val bitmapHeight = (rectHeight * scale).toInt().coerceAtLeast(8)
            val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
            overlay = bitmap
            val canvas = Canvas(bitmap)
            if (backgroundPatch != null && !backgroundPatch.isRecycled) {
                val source = android.graphics.Rect(0, 0, backgroundPatch.width, backgroundPatch.height)
                val destination = android.graphics.Rect(0, 0, bitmapWidth, bitmapHeight)
                val patchPaint = Paint(Paint.FILTER_BITMAP_FLAG)
                canvas.drawBitmap(backgroundPatch, source, destination, patchPaint)
            } else {
                canvas.drawColor(backgroundColor)
            }

            if (translated.contains('\n') || translated.contains('\r')) return false
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                color = android.graphics.Color.rgb(20, 20, 20)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                textSize = (min(11f, max(7.5f, originalHeight * 0.92f)) * scale)
            }
            val layoutWidth = (bitmapWidth - scale * 3).coerceAtLeast(8)
            var textLayout = StaticLayout.Builder.obtain(
                translated, 0, translated.length, paint, layoutWidth
            ).setTextDirection(TextDirectionHeuristics.RTL)
                .setAlignment(Layout.Alignment.ALIGN_OPPOSITE)
                .setIncludePad(false)
                .setMaxLines(1)
                .build()
            while ((textLayout.lineCount > 1 || textLayout.getLineWidth(0) > layoutWidth) && paint.textSize > 7.5f * scale) {
                paint.textSize -= 0.5f * scale
                textLayout = StaticLayout.Builder.obtain(
                    translated, 0, translated.length, paint, layoutWidth
                ).setTextDirection(TextDirectionHeuristics.RTL)
                    .setAlignment(Layout.Alignment.ALIGN_OPPOSITE)
                    .setIncludePad(false)
                    .setMaxLines(1)
                    .build()
            }
            if (textLayout.lineCount != 1 || textLayout.getLineWidth(0) > layoutWidth || textLayout.height > bitmapHeight) return false
            canvas.save()
            canvas.translate(0f, ((bitmapHeight - textLayout.height) / 2f).coerceAtLeast(0f))
            textLayout.draw(canvas)
            canvas.restore()

            val png = ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "تعذر تجهيز صورة النص العربي." }
                output.toByteArray()
            }
            val image = PDImageXObject.createFromByteArray(document, png, "Arabic replacement")
            PDPageContentStream(
                document,
                document.getPage(pageIndex),
                PDPageContentStream.AppendMode.APPEND,
                true,
                true
            ).use { stream ->
                stream.drawImage(image, rectX - 1f, rectY, rectWidth, rectHeight)
            }
            true
        } catch (_: Exception) {
            false
        } finally {
            overlay?.recycle()
        }
    }

    /** Downloads a Tesseract language model once, using a temporary file so an interrupted
     * download can never be mistaken for a complete traineddata file on the next launch.
     */
    private fun ensureRemoteFile(destination: File, remoteUrl: String) {
        // Traineddata files are multi-megabyte binary assets. A tiny HTML error page or
        // interrupted response must never be accepted as a valid cached language model.
        if (destination.isFile && destination.length() >= 1_000_000L) return
        if (destination.exists() && !destination.delete()) {
            error("تعذر حذف ملف OCR غير مكتمل: ${destination.name}")
        }

        destination.parentFile?.let { parent ->
            check(parent.exists() || parent.mkdirs()) { "تعذر إنشاء مجلد ملفات OCR: ${parent.absolutePath}" }
        }
        val temporary = File(destination.parentFile, destination.name + ".download")
        if (temporary.exists()) temporary.delete()

        val connection = (URL(remoteUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 60000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "SmartPDFPairer/1.3.3")
        }
        try {
            val status = connection.responseCode
            check(status == HttpURLConnection.HTTP_OK) {
                "تعذر تنزيل ملف OCR (${destination.name}): HTTP $status"
            }
            val expectedLength = connection.contentLengthLong
            check(expectedLength <= 0L || expectedLength <= 80L * 1024L * 1024L) {
                "حجم ملف OCR غير متوقع: ${destination.name}"
            }
            var downloaded = 0L
            connection.inputStream.use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        check(downloaded <= 80L * 1024L * 1024L) {
                            "تجاوز تنزيل OCR الحد المسموح: ${destination.name}"
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
            check(temporary.isFile && temporary.length() >= 1_000_000L) {
                "ملف OCR الذي تم تنزيله صغير أو غير مكتمل: ${destination.name}"
            }
            check(expectedLength <= 0L || downloaded == expectedLength) {
                "انقطع تنزيل ملف OCR قبل اكتماله: ${destination.name}"
            }
            if (destination.exists() && !destination.delete()) {
                error("تعذر استبدال ملف OCR القديم: ${destination.name}")
            }
            check(temporary.renameTo(destination)) {
                "تعذر حفظ ملف OCR بعد تنزيله: ${destination.name}"
            }
        } finally {
            connection.disconnect()
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun createOcrIfNeeded(context: Context): TessBaseAPI {
        val root = File(context.filesDir, "tesseract")
        val data = File(root, "tessdata")
        check(data.exists() || data.mkdirs()) { "تعذر إنشاء مجلد بيانات OCR." }
        ensureRemoteFile(File(data, "eng.traineddata"), MODEL_BASE + "eng.traineddata")
        ensureRemoteFile(File(data, "ara.traineddata"), MODEL_BASE + "ara.traineddata")
        return TessBaseAPI().also { api ->
            if (!api.init(root.absolutePath, "ara+eng")) {
                api.end()
                error("فشل Tesseract في تهيئة اللغتين العربية والإنجليزية. تحقق من ملفات traineddata.")
            }
            api.setVariable("preserve_interword_spaces", "1")
        }
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

    /**
     * Returns a sampled solid background color around an OCR/text box only when the
     * surrounding pixels are sufficiently uniform. This supports pale/color panels
     * in diagrams while refusing textured photos and complex artwork.
     */
    private fun sampleFlatBackgroundColor(bitmap: Bitmap, box: IntArray): Int? {
        val left = box[0].coerceAtLeast(0)
        val top = box[1].coerceAtLeast(0)
        val right = box[2].coerceAtMost(bitmap.width - 1)
        val bottom = box[3].coerceAtMost(bitmap.height - 1)
        if (right <= left || bottom <= top) return null
        val margin = 5
        val colors = ArrayList<Int>(96)
        val x1 = (left - margin).coerceAtLeast(0)
        val x2 = (right + margin).coerceAtMost(bitmap.width - 1)
        val y1 = (top - margin).coerceAtLeast(0)
        val y2 = (bottom + margin).coerceAtMost(bitmap.height - 1)
        val stepX = max(1, (x2 - x1) / 24)
        val stepY = max(1, (y2 - y1) / 12)
        for (x in x1..x2 step stepX) {
            for (y in y1..y2 step stepY) {
                // Sample the outer ring only, never the glyphs inside the OCR box.
                if (x in left..right && y in top..bottom) continue
                colors.add(bitmap.getPixel(x, y))
            }
        }
        if (colors.size < 8) return null
        val meanR = colors.map { (it shr 16) and 0xff }.average()
        val meanG = colors.map { (it shr 8) and 0xff }.average()
        val meanB = colors.map { it and 0xff }.average()
        val deviation = colors.map { color ->
            (kotlin.math.abs(((color shr 16) and 0xff) - meanR) +
             kotlin.math.abs(((color shr 8) and 0xff) - meanG) +
             kotlin.math.abs((color and 0xff) - meanB)) / 3.0
        }.average()
        if (deviation > 17.0) return null
        return android.graphics.Color.rgb(meanR.toInt().coerceIn(0, 255),
            meanG.toInt().coerceIn(0, 255), meanB.toInt().coerceIn(0, 255))
    }

    private fun hasSafeBackground(bitmap: Bitmap, box: IntArray): Boolean {
        if (sampleFlatBackgroundColor(bitmap, box) != null) return true
        val patch = createSmoothBackgroundPatch(bitmap, box) ?: return false
        patch.recycle()
        return true
    }

    /**
     * Conservative local inpainting for smooth backgrounds only. A bilinear surface is
     * estimated from the four outer corners; if sampled perimeter pixels do not agree
     * with that surface, the region is rejected (photos, textures, edges and diagrams).
     */
    private fun createSmoothBackgroundPatch(bitmap: Bitmap, box: IntArray): Bitmap? {
        val left = (box[0] - 2).coerceAtLeast(0)
        val top = (box[1] - 2).coerceAtLeast(0)
        val right = (box[2] + 2).coerceAtMost(bitmap.width - 1)
        val bottom = (box[3] + 2).coerceAtMost(bitmap.height - 1)
        val width = right - left + 1
        val height = bottom - top + 1
        if (width < 8 || height < 8 || width > 1200 || height > 600) return null
        fun rgb(c: Int, channel: Int) = when (channel) {
            0 -> android.graphics.Color.red(c).toDouble()
            1 -> android.graphics.Color.green(c).toDouble()
            else -> android.graphics.Color.blue(c).toDouble()
        }
        val c00 = bitmap.getPixel(left, top)
        val c10 = bitmap.getPixel(right, top)
        val c01 = bitmap.getPixel(left, bottom)
        val c11 = bitmap.getPixel(right, bottom)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        var errorSum = 0.0
        var samples = 0
        var maxError = 0.0
        for (y in 0 until height) {
            val v = y.toDouble() / (height - 1).coerceAtLeast(1)
            for (x in 0 until width) {
                val u = x.toDouble() / (width - 1).coerceAtLeast(1)
                val channels = DoubleArray(3)
                for (ch in 0..2) {
                    val topMix = rgb(c00, ch) * (1 - u) + rgb(c10, ch) * u
                    val bottomMix = rgb(c01, ch) * (1 - u) + rgb(c11, ch) * u
                    channels[ch] = topMix * (1 - v) + bottomMix * v
                }
                val estimated = android.graphics.Color.rgb(
                    channels[0].toInt().coerceIn(0, 255),
                    channels[1].toInt().coerceIn(0, 255),
                    channels[2].toInt().coerceIn(0, 255)
                )
                // Validate only the outer ring, where original glyphs are absent.
                val onRing = x < 2 || y < 2 || x >= width - 2 || y >= height - 2
                if (onRing) {
                    val actual = bitmap.getPixel(left + x, top + y)
                    val err = (kotlin.math.abs(android.graphics.Color.red(actual) - android.graphics.Color.red(estimated)) +
                        kotlin.math.abs(android.graphics.Color.green(actual) - android.graphics.Color.green(estimated)) +
                        kotlin.math.abs(android.graphics.Color.blue(actual) - android.graphics.Color.blue(estimated))) / 3.0
                    errorSum += err
                    samples++
                    if (err > maxError) maxError = err
                }
                out.setPixel(x, y, estimated)
            }
        }
        val meanError = if (samples == 0) 999.0 else errorSum / samples
        if (meanError > 12.0 || maxError > 42.0) {
            out.recycle()
            return null
        }
        return out
    }

    /** Percentage of visibly different sample cells between two rendered page thumbnails. */
    private fun visualDifferencePercent(first: Bitmap, second: Bitmap): Int {
        val columns = 28
        val rows = 36
        var different = 0
        var total = 0
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val x1 = ((column + 0.5f) * first.width / columns).toInt().coerceIn(0, first.width - 1)
                val y1 = ((row + 0.5f) * first.height / rows).toInt().coerceIn(0, first.height - 1)
                val x2 = ((column + 0.5f) * second.width / columns).toInt().coerceIn(0, second.width - 1)
                val y2 = ((row + 0.5f) * second.height / rows).toInt().coerceIn(0, second.height - 1)
                val a = first.getPixel(x1, y1)
                val b = second.getPixel(x2, y2)
                val delta = kotlin.math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) +
                    kotlin.math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) +
                    kotlin.math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b))
                if (delta > 72) different++
                total++
            }
        }
        return if (total == 0) 0 else (different * 100f / total).toInt()
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
