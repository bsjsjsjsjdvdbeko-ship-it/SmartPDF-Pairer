package com.smartpdf.pairer

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/** Lightweight, read-only page comparison. Original PDFs are copied to cache and never modified. */
class PdfComparePreviewActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ORIGINAL_URI = "original_uri"
        const val EXTRA_ARABIC_URI = "arabic_uri"
        const val EXTRA_ORIGINAL_NAME = "original_name"
        const val EXTRA_ARABIC_NAME = "arabic_name"
        const val EXTRA_REVIEW_MODE = "review_mode"
        const val EXTRA_ACCEPTED = "review_accepted"
        const val EXTRA_MODIFIED_PAGES = "modified_pages"
        const val EXTRA_APPROVED_PAGES = "approved_pages"
    }

    private lateinit var originalImage: ImageView
    private lateinit var arabicImage: ImageView
    private lateinit var pageLabel: TextView
    private lateinit var loading: ProgressBar
    private lateinit var previous: Button
    private lateinit var next: Button
    private lateinit var approveCurrentPage: CheckBox
    private val modifiedPages = linkedSetOf<Int>()
    private val approvedPages = linkedSetOf<Int>()
    private val worker = Executors.newSingleThreadExecutor()
    private var originalRenderer: PdfRenderer? = null
    private var arabicRenderer: PdfRenderer? = null
    private var originalPfd: ParcelFileDescriptor? = null
    private var arabicPfd: ParcelFileDescriptor? = null
    private var pageIndex = 0
    private var totalPages = 0
    private var originalFile: File? = null
    private var arabicFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_compare_preview)
        originalImage = findViewById(R.id.originalPreview)
        arabicImage = findViewById(R.id.arabicPreview)
        pageLabel = findViewById(R.id.previewPageLabel)
        loading = findViewById(R.id.previewLoading)
        previous = findViewById(R.id.previousPage)
        next = findViewById(R.id.nextPage)
        approveCurrentPage = findViewById(R.id.approveCurrentPage)
        intent.getIntArrayExtra(EXTRA_MODIFIED_PAGES)?.forEach { modifiedPages.add(it) }
        approvedPages.addAll(modifiedPages)
        approveCurrentPage.setOnCheckedChangeListener { _, checked ->
            if (pageIndex in modifiedPages) {
                if (checked) approvedPages.add(pageIndex) else approvedPages.remove(pageIndex)
            }
        }
        val reviewMode = intent.getBooleanExtra(EXTRA_REVIEW_MODE, false)
        val reviewActions = findViewById<View>(R.id.reviewActions)
        reviewActions.visibility = if (reviewMode) View.VISIBLE else View.GONE
        if (reviewMode) {
            findViewById<Button>(R.id.acceptChanges).setOnClickListener {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_ACCEPTED, true)
                    .putExtra(EXTRA_APPROVED_PAGES, approvedPages.toIntArray()))
                finish()
            }
            findViewById<Button>(R.id.rejectChanges).setOnClickListener {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_ACCEPTED, false)
                    .putExtra(EXTRA_APPROVED_PAGES, intArrayOf()))
                finish()
            }
            pageLabel.text = "حدد الصفحات التي توافق على تحسيناتها؛ الصفحات غير المحددة ستبقى من ملف الترجمة الأصلي."
        }
        findViewById<TextView>(R.id.originalPreviewTitle).text =
            intent.getStringExtra(EXTRA_ORIGINAL_NAME) ?: "Original PDF"
        findViewById<TextView>(R.id.arabicPreviewTitle).text =
            intent.getStringExtra(EXTRA_ARABIC_NAME) ?: "Arabic PDF"
        previous.setOnClickListener { showPage(pageIndex - 1) }
        next.setOnClickListener { showPage(pageIndex + 1) }
        loadDocuments()
    }

    private fun loadDocuments() {
        val originalUri = intent.getStringExtra(EXTRA_ORIGINAL_URI)?.let(Uri::parse)
        val arabicUri = intent.getStringExtra(EXTRA_ARABIC_URI)?.let(Uri::parse)
        if (originalUri == null || arabicUri == null) {
            Toast.makeText(this, "لم يتم تمرير الملفين إلى شاشة المعاينة.", Toast.LENGTH_LONG).show()
            finish(); return
        }
        loading.visibility = View.VISIBLE
        worker.execute {
            try {
                val original = copyToCache(originalUri, "compare_original.pdf")
                val arabic = copyToCache(arabicUri, "compare_arabic.pdf")
                val originalDescriptor = ParcelFileDescriptor.open(original, ParcelFileDescriptor.MODE_READ_ONLY)
                val arabicDescriptor = ParcelFileDescriptor.open(arabic, ParcelFileDescriptor.MODE_READ_ONLY)
                val originalPdf = PdfRenderer(originalDescriptor)
                val arabicPdf = PdfRenderer(arabicDescriptor)
                val pages = minOf(originalPdf.pageCount, arabicPdf.pageCount)
                require(pages > 0) { "لا توجد صفحات قابلة للمعاينة." }
                runOnUiThread {
                    originalFile = original; arabicFile = arabic
                    originalPfd = originalDescriptor; arabicPfd = arabicDescriptor
                    originalRenderer = originalPdf; arabicRenderer = arabicPdf
                    totalPages = pages
                    loading.visibility = View.GONE
                    showPage(0)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    loading.visibility = View.GONE
                    pageLabel.text = "تعذرت المعاينة: ${error.message ?: error.javaClass.simpleName}"
                    Toast.makeText(this, "تعذر فتح أحد ملفي PDF للمعاينة.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun copyToCache(uri: Uri, name: String): File {
        val target = File(cacheDir, name)
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "تعذر قراءة الملف المحدد." }
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        require(target.length() > 0L) { "ملف PDF فارغ." }
        return target
    }

    private fun showPage(index: Int) {
        if (index !in 0 until totalPages) return
        pageIndex = index
        approveCurrentPage.isEnabled = index in modifiedPages
        approveCurrentPage.visibility = if (intent.getBooleanExtra(EXTRA_REVIEW_MODE, false)) View.VISIBLE else View.GONE
        approveCurrentPage.setOnCheckedChangeListener(null)
        approveCurrentPage.isChecked = index in approvedPages
        approveCurrentPage.text = if (index in modifiedPages) "اعتماد تحسينات الصفحة ${index + 1}" else "لا توجد تعديلات آلية في هذه الصفحة"
        approveCurrentPage.setOnCheckedChangeListener { _, checked ->
            if (pageIndex in modifiedPages) {
                if (checked) approvedPages.add(pageIndex) else approvedPages.remove(pageIndex)
            }
        }
        loading.visibility = View.VISIBLE
        previous.isEnabled = index > 0
        next.isEnabled = index < totalPages - 1
        pageLabel.text = "جاري عرض الصفحة ${index + 1} من $totalPages..."
        worker.execute {
            try {
                val originalBitmap = renderPage(originalRenderer, index)
                val arabicBitmap = renderPage(arabicRenderer, index)
                runOnUiThread {
                    originalImage.setImageBitmap(originalBitmap)
                    arabicImage.setImageBitmap(arabicBitmap)
                    pageLabel.text = "الصفحة ${index + 1} من $totalPages — مقارنة جنبًا إلى جنب"
                    loading.visibility = View.GONE
                }
            } catch (error: Exception) {
                runOnUiThread {
                    loading.visibility = View.GONE
                    pageLabel.text = "فشل عرض الصفحة: ${error.message ?: error.javaClass.simpleName}"
                }
            }
        }
    }

    private fun renderPage(renderer: PdfRenderer?, index: Int): Bitmap {
        val pdf = requireNotNull(renderer) { "محرك PDF غير جاهز." }
        synchronized(pdf) {
            pdf.openPage(index).use { page ->
                val maxWidth = resources.displayMetrics.widthPixels.coerceIn(480, 1600)
                val scale = (maxWidth.toFloat() / page.width).coerceAtMost(2.0f)
                val width = (page.width * scale).toInt().coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bitmap
            }
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        try { originalRenderer?.close() } catch (_: Exception) {}
        try { arabicRenderer?.close() } catch (_: Exception) {}
        try { originalPfd?.close() } catch (_: Exception) {}
        try { arabicPfd?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}
