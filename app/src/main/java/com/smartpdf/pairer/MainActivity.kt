package com.smartpdf.pairer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var originalName: TextView
    private lateinit var arabicName: TextView
    private lateinit var pageInfo: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var pairButton: Button
    private lateinit var arabicLeft: RadioButton

    private var originalUri: Uri? = null
    private var arabicUri: Uri? = null
    private val executor = Executors.newSingleThreadExecutor()

    companion object {
        private const val PICK_ORIGINAL = 1001
        private const val PICK_ARABIC = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        originalName = findViewById<TextView>(R.id.originalName)
        arabicName = findViewById<TextView>(R.id.arabicName)
        pageInfo = findViewById<TextView>(R.id.pageInfo)
        status = findViewById<TextView>(R.id.status)
        progress = findViewById<ProgressBar>(R.id.progress)
        pairButton = findViewById<Button>(R.id.pairButton)
        arabicLeft = findViewById<RadioButton>(R.id.arabicLeft)

        findViewById<Button>(R.id.selectOriginal).setOnClickListener {
            openPdfPicker(PICK_ORIGINAL)
        }
        findViewById<Button>(R.id.selectArabic).setOnClickListener {
            openPdfPicker(PICK_ARABIC)
        }
        pairButton.setOnClickListener { startPairing() }
    }

    private fun openPdfPicker(requestCode: Int) {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/pdf"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            requestCode
        )
    }

    @Deprecated("Legacy API retained for simple broad Android compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return

        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) {
        }

        when (requestCode) {
            PICK_ORIGINAL -> {
                originalUri = uri
                originalName.text = "Original PDF: ${displayName(uri)}"
            }
            PICK_ARABIC -> {
                arabicUri = uri
                arabicName.text = "Arabic PDF: ${displayName(uri)}"
            }
        }
        updatePageInfo()
    }

    private fun updatePageInfo() {
        val o = originalUri ?: run {
            pageInfo.text = "اختر الملفين أولاً."
            return
        }
        val a = arabicUri ?: return

        executor.execute {
            try {
                val op = contentResolver.openInputStream(o).use { PdfPairer.pageCount(requireNotNull(it)) }
                val ap = contentResolver.openInputStream(a).use { PdfPairer.pageCount(requireNotNull(it)) }
                runOnUiThread {
                    pageInfo.text = "Original: $op صفحة\nArabic: $ap صفحة"
                    status.text = if (op == ap) {
                        "✓ جاهز: مطابقة صفحة مقابل صفحة"
                    } else {
                        "⚠️ V1 يتطلب تساوي عدد الصفحات. Smart Matching سيكون في V2."
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "❌ تعذر قراءة PDF: ${e.message}" }
            }
        }
    }

    private fun startPairing() {
        val original = originalUri
        val arabic = arabicUri
        if (original == null || arabic == null) {
            Toast.makeText(this, "اختر الملفين أولاً.", Toast.LENGTH_SHORT).show()
            return
        }

        setBusy(true)
        status.text = "جاري إنشاء الملف..."

        executor.execute {
            try {
                val originalBytes = contentResolver.openInputStream(original).use {
                    requireNotNull(it).readBytes()
                }
                val arabicBytes = contentResolver.openInputStream(arabic).use {
                    requireNotNull(it).readBytes()
                }

                val op = PdfPairer.pageCount(originalBytes)
                val ap = PdfPairer.pageCount(arabicBytes)
                require(op == ap) {
                    "عدد الصفحات مختلف: Original=$op, Arabic=$ap"
                }

                val result = PdfPairer.build(
                    originalBytes,
                    arabicBytes,
                    arabicLeft.isChecked
                )

                val outDir = getExternalFilesDir(null)
                    ?: throw IllegalStateException("تعذر الوصول إلى مساحة التطبيق.")
                val outFile = File(outDir, "Bilingual_Final.pdf")
                outFile.writeBytes(result)

                runOnUiThread {
                    setBusy(false)
                    status.text = "✅ تم إنشاء Bilingual_Final.pdf\n\n${outFile.absolutePath}\n\nV1: العربي يسار والأصلي يمين."
                    Toast.makeText(this, "تم إنشاء الملف بنجاح", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setBusy(false)
                    status.text = "❌ فشل الدمج:\n${e.message}"
                }
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        pairButton.isEnabled = !busy
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) return c.getString(i)
            }
        }
        return uri.lastPathSegment ?: "PDF"
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
