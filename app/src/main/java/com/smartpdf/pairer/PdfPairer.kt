package com.smartpdf.pairer

import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.util.Matrix
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Keeps the successful PDFBox LayerUtility composition path. File-based methods are used
 * by the app to avoid loading entire books into memory; byte-array overloads remain for
 * compatibility with the successful #16 source API.
 */
object PdfPairer {
    fun pageCount(input: InputStream): Int = PDDocument.load(input).use { it.numberOfPages }

    fun pageCount(file: File): Int = PDDocument.load(file).use { it.numberOfPages }

    fun pageCount(bytes: ByteArray): Int =
        PDDocument.load(ByteArrayInputStream(bytes)).use { it.numberOfPages }

    fun build(
        originalFile: File,
        arabicFile: File,
        outputFile: File,
        arabicLeft: Boolean,
        onProgress: (completedPages: Int, totalPages: Int) -> Unit = { _, _ -> }
    ) {
        require(originalFile.isFile && originalFile.length() > 0L) { "ملف PDF الأصلي غير موجود أو فارغ." }
        require(arabicFile.isFile && arabicFile.length() > 0L) { "ملف PDF العربي غير موجود أو فارغ." }
        PDDocument.load(originalFile).use { original ->
            PDDocument.load(arabicFile).use { arabic ->
                PDDocument().use { output ->
                    buildInto(original, arabic, output, arabicLeft, onProgress)
                    outputFile.parentFile?.mkdirs()
                    output.save(outputFile)
                }
            }
        }
        require(outputFile.isFile && outputFile.length() > 0L) { "لم يتم إنشاء ملف PDF صالح." }
    }

    fun build(originalBytes: ByteArray, arabicBytes: ByteArray, arabicLeft: Boolean): ByteArray {
        PDDocument.load(ByteArrayInputStream(originalBytes)).use { original ->
            PDDocument.load(ByteArrayInputStream(arabicBytes)).use { arabic ->
                ByteArrayOutputStream().use { result ->
                    PDDocument().use { output ->
                        buildInto(original, arabic, output, arabicLeft) { _, _ -> }
                        output.save(result)
                    }
                    return result.toByteArray()
                }
            }
        }
    }

    private fun buildInto(
        original: PDDocument,
        arabic: PDDocument,
        output: PDDocument,
        arabicLeft: Boolean,
        onProgress: (Int, Int) -> Unit
    ) {
        require(original.numberOfPages == arabic.numberOfPages) {
            "عدد صفحات الملفين يجب أن يكون متساويًا. الأصلي=${original.numberOfPages}، العربي=${arabic.numberOfPages}."
        }
        require(original.numberOfPages > 0) { "ملفات PDF لا تحتوي على صفحات." }

        val originalLayer = LayerUtility(output)
        val arabicLayer = LayerUtility(output)
        val total = original.numberOfPages
        for (index in 0 until total) {
            val originalPage = original.getPage(index)
            val arabicPage = arabic.getPage(index)
            val originalWidth = originalPage.mediaBox.width.toDouble()
            val originalHeight = originalPage.mediaBox.height.toDouble()
            val arabicWidth = arabicPage.mediaBox.width.toDouble()
            val arabicHeight = arabicPage.mediaBox.height.toDouble()
            require(originalWidth > 0 && originalHeight > 0 && arabicWidth > 0 && arabicHeight > 0) {
                "أبعاد صفحة PDF غير صالحة عند الصفحة ${index + 1}."
            }

            val slotWidth = maxOf(originalWidth, arabicWidth)
            val slotHeight = maxOf(originalHeight, arabicHeight)
            val outputPage = PDPage(PDRectangle((slotWidth * 2).toFloat(), slotHeight.toFloat()))
            output.addPage(outputPage)
            val arabicForm = arabicLayer.importPageAsForm(arabic, index)
            val originalForm = originalLayer.importPageAsForm(original, index)

            PDPageContentStream(
                output, outputPage, PDPageContentStream.AppendMode.APPEND, true, true
            ).use { stream ->
                if (arabicLeft) {
                    draw(stream, arabicForm, arabicWidth, arabicHeight, 0.0, slotWidth, slotHeight)
                    draw(stream, originalForm, originalWidth, originalHeight, slotWidth, slotWidth, slotHeight)
                } else {
                    draw(stream, originalForm, originalWidth, originalHeight, 0.0, slotWidth, slotHeight)
                    draw(stream, arabicForm, arabicWidth, arabicHeight, slotWidth, slotWidth, slotHeight)
                }
            }
            onProgress(index + 1, total)
        }
    }

    private fun draw(
        stream: PDPageContentStream,
        form: PDFormXObject,
        sourceWidth: Double,
        sourceHeight: Double,
        x0: Double,
        slotWidth: Double,
        slotHeight: Double
    ) {
        val scale = minOf(slotWidth / sourceWidth, slotHeight / sourceHeight)
        val x = x0 + (slotWidth - sourceWidth * scale) / 2.0
        val y = (slotHeight - sourceHeight * scale) / 2.0
        stream.saveGraphicsState()
        stream.transform(Matrix(scale.toFloat(), 0f, 0f, scale.toFloat(), x.toFloat(), y.toFloat()))
        stream.drawForm(form)
        stream.restoreGraphicsState()
    }
}
