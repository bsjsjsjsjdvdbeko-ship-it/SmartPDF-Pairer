package com.smartpdf.pairer

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.InputStream

/**
 * PDF pairing operations. File-based APIs avoid loading complete books into ByteArrays.
 * Uses standard PDFBox importPage method, avoiding LayerUtility (not available in pdfbox-android).
 */
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
        require(originalFile.isFile && originalFile.length() > 0L) {
            "ملف PDF الأصلي غير موجود أو فارغ."
        }
        require(arabicFile.isFile && arabicFile.length() > 0L) {
            "ملف PDF العربي غير موجود أو فارغ."
        }

        PDDocument.load(originalFile).use { original ->
            PDDocument.load(arabicFile).use { arabic ->
                require(original.numberOfPages == arabic.numberOfPages) {
                    "عدد صفحات الملفين يجب أن يكون متساويًا في V1."
                }
                require(original.numberOfPages > 0) { "ملفات PDF لا تحتوي على صفحات." }

                PDDocument().use { output ->
                    val total = original.numberOfPages

                    for (i in 0 until total) {
                        val originalPage = original.getPage(i)
                        val arabicPage = arabic.getPage(i)

                        val originalWidth = originalPage.mediaBox.width.toDouble()
                        val originalHeight = originalPage.mediaBox.height.toDouble()
                        val arabicWidth = arabicPage.mediaBox.width.toDouble()
                        val arabicHeight = arabicPage.mediaBox.height.toDouble()

                        val slotWidth = maxOf(originalWidth, arabicWidth)
                        val slotHeight = maxOf(originalHeight, arabicHeight)
                        val outputPage = PDPage(
                            PDRectangle((slotWidth * 2).toFloat(), slotHeight.toFloat())
                        )
                        output.addPage(outputPage)

                        // استيراد الصفحات ككائنات XObject (الطريقة المتوافقة مع أندرويد)
                        val arabicForm = output.importPage(arabicPage)
                        val originalForm = output.importPage(originalPage)

                        PDPageContentStream(
                            output,
                            outputPage,
                            PDPageContentStream.AppendMode.APPEND,
                            true,
                            true
                        ).use { stream ->
                            if (arabicLeft) {
                                draw(stream, arabicForm, arabicWidth, arabicHeight, 0.0, slotWidth, slotHeight)
                                draw(stream, originalForm, originalWidth, originalHeight, slotWidth, slotWidth, slotHeight)
                            } else {
                                draw(stream, originalForm, originalWidth, originalHeight, 0.0, slotWidth, slotHeight)
                                draw(stream, arabicForm, arabicWidth, arabicHeight, slotWidth, slotWidth, slotHeight)
                            }
                        }
                        onProgress(i + 1, total)
                    }

                    outputFile.parentFile?.mkdirs()
                    output.save(outputFile)
                }
            }
        }
    }

    private fun draw(
        stream: PDPageContentStream,
        form: com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject,
        sourceWidth: Double,
        sourceHeight: Double,
        x0: Double,
        slotWidth: Double,
        slotHeight: Double
    ) {
        require(sourceWidth > 0.0 && sourceHeight > 0.0) {
            "أبعاد إحدى صفحات PDF غير صالحة."
        }
        val scale = minOf(slotWidth / sourceWidth, slotHeight / sourceHeight)
        val x = x0 + (slotWidth - sourceWidth * scale) / 2.0
        val y = (slotHeight - sourceHeight * scale) / 2.0

        stream.saveGraphicsState()
        stream.transform(
            Matrix(
                scale.toFloat(), 0f, 0f, scale.toFloat(),
                x.toFloat(), y.toFloat()
            )
        )
        stream.drawForm(form)
        stream.restoreGraphicsState()
    }
}
