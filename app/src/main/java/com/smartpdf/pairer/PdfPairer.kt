package com.smartpdf.pairer

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.InputStream

object PdfPairer {

    fun pageCount(input: InputStream): Int =
        PDDocument.load(input).use { it.numberOfPages }

    fun pageCount(file: File): Int =
        PDDocument.load(file).use { it.numberOfPages }

    fun build(
        originalFile: File,
        arabicFile: File,
        outputFile: File,
        arabicLeft: Boolean,
        onProgress: (completedPages: Int, totalPages: Int) -> Unit = { _, _ -> }
    ) {
        require(originalFile.isFile && originalFile.length() > 0L) { "ملف PDF الأصلي غير موجود." }
        require(arabicFile.isFile && arabicFile.length() > 0L) { "ملف PDF العربي غير موجود." }

        PDDocument.load(originalFile).use { originalDoc ->
            PDDocument.load(arabicFile).use { arabicDoc ->
                require(originalDoc.numberOfPages == arabicDoc.numberOfPages) {
                    "عدد صفحات الملفين يجب أن يكون متساويًا."
                }

                PDDocument().use { outputDoc ->
                    val total = originalDoc.numberOfPages
                    for (i in 0 until total) {
                        val originalPage = originalDoc.getPage(i)
                        val arabicPage = arabicDoc.getPage(i)

                        val ow = originalPage.mediaBox.width.toDouble()
                        val oh = originalPage.mediaBox.height.toDouble()
                        val aw = arabicPage.mediaBox.width.toDouble()
                        val ah = arabicPage.mediaBox.height.toDouble()

                        val slotW = maxOf(ow, aw)
                        val slotH = maxOf(oh, ah)
                        
                        val outPage = PDPage(PDRectangle((slotW * 2).toFloat(), slotH.toFloat()))
                        outputDoc.addPage(outPage)

                        // استيراد الصفحات بشكل صحيح باستخدام الوثيقة الأصلية
                        val arabicForm = outputDoc.importPage(arabicPage)
                        val originalForm = outputDoc.importPage(originalPage)

                        PDPageContentStream(outputDoc, outPage).use { cs ->
                            if (arabicLeft) {
                                draw(cs, arabicForm, aw, ah, 0.0, slotW, slotH)
                                draw(cs, originalForm, ow, oh, slotW, slotW, slotH)
                            } else {
                                draw(cs, originalForm, ow, oh, 0.0, slotW, slotH)
                                draw(cs, arabicForm, aw, ah, slotW, slotW, slotH)
                            }
                        }
                        onProgress(i + 1, total)
                    }
                    outputFile.parentFile?.mkdirs()
                    outputDoc.save(outputFile)
                }
            }
        }
    }

    private fun draw(
        cs: PDPageContentStream,
        form: PDFormXObject,
        sourceW: Double,
        sourceH: Double,
        x0: Double,
        slotW: Double,
        slotH: Double
    ) {
        val scale = minOf(slotW / sourceW, slotH / sourceH)
        val x = x0 + (slotW - sourceW * scale) / 2.0
        val y = (slotH - sourceH * scale) / 2.0

        cs.saveGraphicsState()
        cs.transform(Matrix(scale.toFloat(), 0f, 0f, scale.toFloat(), x.toFloat(), y.toFloat()))
        cs.drawForm(form)
        cs.restoreGraphicsState()
    }
}
