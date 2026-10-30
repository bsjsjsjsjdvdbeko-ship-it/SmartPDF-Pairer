package com.smartpdf.pairer

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.pdfbox.multipdf.LayerUtility
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

object PdfPairer {

    fun pageCount(input: InputStream): Int =
        PDDocument.load(input).use { it.numberOfPages }

    fun pageCount(bytes: ByteArray): Int =
        PDDocument.load(ByteArrayInputStream(bytes)).use { it.numberOfPages }

    fun build(
        originalBytes: ByteArray,
        arabicBytes: ByteArray,
        arabicLeft: Boolean
    ): ByteArray {
        val original = PDDocument.load(ByteArrayInputStream(originalBytes))
        val arabic = PDDocument.load(ByteArrayInputStream(arabicBytes))
        val output = PDDocument()

        try {
            require(original.numberOfPages == arabic.numberOfPages) {
                "عدد صفحات الملفين يجب أن يكون متساويًا في V1."
            }

            val originalLayer = LayerUtility(output)
            val arabicLayer = LayerUtility(output)

            for (i in 0 until original.numberOfPages) {
                val op = original.getPage(i)
                val ap = arabic.getPage(i)

                val ow = op.mediaBox.width.toDouble()
                val oh = op.mediaBox.height.toDouble()
                val aw = ap.mediaBox.width.toDouble()
                val ah = ap.mediaBox.height.toDouble()

                val slotW = maxOf(ow, aw)
                val slotH = maxOf(oh, ah)
                val outPage = PDPage(
                    PDRectangle((slotW * 2).toFloat(), slotH.toFloat())
                )
                output.addPage(outPage)

                val arabicForm = arabicLayer.importPageAsForm(arabic, i)
                val originalForm = originalLayer.importPageAsForm(original, i)

                PDPageContentStream(
                    output,
                    outPage,
                    PDPageContentStream.AppendMode.APPEND,
                    true,
                    true
                ).use { cs ->
                    if (arabicLeft) {
                        draw(cs, arabicForm, aw, ah, 0.0, slotW, slotH)
                        draw(cs, originalForm, ow, oh, slotW, slotW, slotH)
                    } else {
                        draw(cs, originalForm, ow, oh, 0.0, slotW, slotH)
                        draw(cs, arabicForm, aw, ah, slotW, slotW, slotH)
                    }
                }
            }

            val result = ByteArrayOutputStream()
            output.save(result)
            return result.toByteArray()
        } finally {
            output.close()
            original.close()
            arabic.close()
        }
    }

    private fun draw(
        cs: PDPageContentStream,
        form: com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject,
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
        cs.transform(
            Matrix(
                scale.toFloat(),
                0f,
                0f,
                scale.toFloat(),
                x.toFloat(),
                y.toFloat()
            )
        )
        cs.drawForm(form)
        cs.restoreGraphicsState()
    }
}
