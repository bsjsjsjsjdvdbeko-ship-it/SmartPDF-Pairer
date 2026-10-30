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
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import com.smartpdf.pairer.ocr.OcrReportGenerator
import com.smartpdf.pairer.smart.SmartTranslationRepair

class MainActivity : AppCompatActivity() {

    private lateinit var originalName: TextView
    private lateinit var arabicName: TextView
    private lateinit var pageInfo: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var pairButton: Button
    private lateinit var saveButton: Button
    private lateinit var openButton: Button
    private lateinit var arabicLeft: RadioButton
    private lateinit var ocrButton: Button
    private lateinit var smartButton: Button
    private var pendingOcrReport: String? = null
    private var pendingSmartReport: String? = null

    private var originalUri: Uri? = null
    private var arabicUri: Uri? = null
    private var generatedFile: File? = null
    private var savedOutputUri: Uri? = null
    private var isBusy = false
    private var pageCountOriginal: Int? = null
    private var pageCountArabic: Int? = null

    private val executor = Executors.newSingleThreadExecutor()

    companion object {
        private const val PICK_ORIGINAL = 1001
        private const val PICK_ARABIC = 1002
        private const val CREATE_OUTPUT = 1003
        private const val CREATE_OCR_REPORT = 1004
        private const val CREATE_SMART_REPORT = 1005
        private const val OUTPUT_FILE_NAME = "Bilingual_Final.pdf"
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
        saveButton = findViewById<Button>(R.id.saveButton)
        openButton = findViewById<Button>(R.id.openButton)
        arabicLeft = findViewById<RadioButton>(R.id.arabicLeft)
        ocrButton = findViewById(R.id.ocrButton)
        smartButton = findViewById(R.id.smartButton)

        findViewById<Button>(R.id.selectOriginal).setOnClickListener {
            openPdfPicker(PICK_ORIGINAL)
        }
        findViewById<Button>(R.id.selectArabic).setOnClickListener {
            openPdfPicker(PICK_ARABIC)
        }
        pairButton.setOnClickListener { startPairing() }
        smartButton.setOnClickListener { startSmartPairing() }
        saveButton.setOnClickListener { launchSavePicker() }
        openButton.setOnClickListener { openGeneratedPdf() }
        ocrButton.setOnClickListener { startOcrReport() }

        updateActionButtons()
    }

    private fun openPdfPicker(requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, requestCode)
    }

    @Deprecated("Legacy result API retained for broad Android compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) {
            if (requestCode == CREATE_OUTPUT) {
                status.text = "تم إنشاء الملف مؤقتًا. يمكنك حفظه لاحقًا باستخدام زر حفظ / تصدير."
            }
            return
        }
        val uri = data?.data ?: return

        when (requestCode) {
            PICK_ORIGINAL, PICK_ARABIC -> {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                    // Some document providers do not offer persistable permissions.
                }
                generatedFile = null
                savedOutputUri = null
                if (requestCode == PICK_ORIGINAL) {
                    originalUri = uri
                    originalName.text = "Original PDF: ${displayName(uri)}"
                    pageCountOriginal = null
                } else {
                    arabicUri = uri
                    arabicName.text = "Arabic PDF: ${displayName(uri)}"
                    pageCountArabic = null
                }
                updateActionButtons()
                updatePageInfo()
            }

            CREATE_OUTPUT -> saveGeneratedPdf(uri)
            CREATE_OCR_REPORT -> saveOcrReport(uri)
            CREATE_SMART_REPORT -> saveSmartReport(uri)
        }
    }

    private fun updatePageInfo() {
        val original = originalUri
        val arabic = arabicUri
        if (original == null || arabic == null) {
            pageInfo.text = "اختر الملفين أولاً."
            return
        }

        status.text = "جاري فحص الملفين..."
        executor.execute {
            try {
                val originalTemp = File(cacheDir, "inspect_original.pdf")
                val arabicTemp = File(cacheDir, "inspect_arabic.pdf")
                copyUriToFile(original, originalTemp)
                copyUriToFile(arabic, arabicTemp)
                val originalPages = PdfPairer.pageCount(originalTemp)
                val arabicPages = PdfPairer.pageCount(arabicTemp)
                runOnUiThread {
                    pageCountOriginal = originalPages
                    pageCountArabic = arabicPages
                    pageInfo.text = "Original: $originalPages صفحة (${formatSize(originalTemp.length())})\n" +
                        "Arabic: $arabicPages صفحة (${formatSize(arabicTemp.length())})"
                    status.text = if (originalPages == arabicPages) {
                        "✓ الملفان جاهزان للمطابقة صفحة مقابل صفحة."
                    } else {
                        "⚠️ يختلف عدد الصفحات. يلزم حاليًا تساوي عدد الصفحات للمطابقة صفحة مقابل صفحة."
                    }
                    pairButton.isEnabled = originalPages == arabicPages && !isBusy
                    smartButton.isEnabled = originalPages == arabicPages && !isBusy
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "❌ تعذر فحص PDF: ${friendlyError(e)}"
                    pairButton.isEnabled = false
                    smartButton.isEnabled = false
                }
            }
        }
    }

    private fun startPairing() {
        if (isBusy) return
        val original = originalUri
        val arabic = arabicUri
        if (original == null || arabic == null) {
            Toast.makeText(this, "اختر الملفين أولاً.", Toast.LENGTH_SHORT).show()
            return
        }

        val placeArabicOnLeft = arabicLeft.isChecked
        generatedFile = null
        savedOutputUri = null
        updateActionButtons()
        setBusy(true, 0, "جاري تجهيز ملفات PDF...")
        executor.execute {
            try {
                val originalTemp = File(cacheDir, "pair_original.pdf")
                val arabicTemp = File(cacheDir, "pair_arabic.pdf")
                val outputTemp = File(cacheDir, "Bilingual_Final.pdf")

                copyUriToFile(original, originalTemp) { copied, total ->
                    val stage = percent(copied, total)
                    updateProgress(stage * 5 / 100, 100, "نسخ الملف الأصلي: $stage%")
                }
                copyUriToFile(arabic, arabicTemp) { copied, total ->
                    val stage = percent(copied, total)
                    updateProgress(5 + stage * 5 / 100, 100, "نسخ الملف العربي: $stage%")
                }

                val originalPages = PdfPairer.pageCount(originalTemp)
                val arabicPages = PdfPairer.pageCount(arabicTemp)
                require(originalPages == arabicPages) {
                    "عدد الصفحات مختلف: Original=$originalPages, Arabic=$arabicPages. لا يمكن دمجهما في الدمج العادي."
                }
                require(originalPages > 0) { "أحد الملفين لا يحتوي على صفحات." }

                PdfPairer.build(
                    originalTemp,
                    arabicTemp,
                    outputTemp,
                    placeArabicOnLeft
                ) { completed, total ->
                    val value = 10 + (completed * 85 / total)
                    updateProgress(value, 100, "دمج الصفحات: $completed من $total (${value}%)")
                }

                require(outputTemp.isFile && outputTemp.length() > 0L) {
                    "لم يتم إنشاء ملف PDF صالح."
                }
                generatedFile = outputTemp
                runOnUiThread {
                    setBusy(false)
                    progress.progress = 100
                    status.text = "✅ اكتمل إنشاء الملف (${formatSize(outputTemp.length())}).\nاختر مكان حفظه على الهاتف."
                    updateActionButtons()
                    launchSavePicker()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setBusy(false)
                    status.text = "❌ فشل الدمج:\n${friendlyError(e)}"
                    updateActionButtons()
                }
            }
        }
    }

    /** Smart path is intentionally separate; normal pairing never calls OCR or translation. */
    private fun startSmartPairing() {
        if (isBusy) return
        val original = originalUri
        val arabic = arabicUri
        if (original == null || arabic == null) {
            Toast.makeText(this, "اختر الملف الأصلي وملف الترجمة أولاً.", Toast.LENGTH_SHORT).show()
            return
        }

        val placeArabicOnLeft = arabicLeft.isChecked
        generatedFile = null
        savedOutputUri = null
        pendingSmartReport = null
        updateActionButtons()
        setBusy(true, 0, "بدء الفحص الذكي لملف الترجمة...")
        executor.execute {
            try {
                val originalTemp = File(cacheDir, "smart_original.pdf")
                val arabicTemp = File(cacheDir, "smart_arabic_source.pdf")
                val repairedArabic = File(cacheDir, "smart_arabic_repaired.pdf")
                val outputTemp = File(cacheDir, "Bilingual_Smart_Final.pdf")

                copyUriToFile(original, originalTemp) { copied, total ->
                    updateProgress(percent(copied, total) * 4 / 100, 100, "نسخ الملف الأصلي...")
                }
                copyUriToFile(arabic, arabicTemp) { copied, total ->
                    updateProgress(4 + percent(copied, total) * 4 / 100, 100, "نسخ ملف الترجمة...")
                }

                val originalPages = PdfPairer.pageCount(originalTemp)
                val translationPages = PdfPairer.pageCount(arabicTemp)
                require(originalPages == translationPages) {
                    "عدد الصفحات مختلف: الأصلي=$originalPages، الترجمة=$translationPages. لا يمكن إجراء المطابقة الآمنة صفحة بصفحة."
                }

                val repairResult = SmartTranslationRepair.repair(
                    this,
                    originalTemp,
                    arabicTemp,
                    repairedArabic
                ) { completed, total, message ->
                    updateProgress(8 + if (total == 0) 0 else completed * 52 / total, 100, message)
                }
                pendingSmartReport = repairResult.report

                PdfPairer.build(
                    originalTemp,
                    repairedArabic,
                    outputTemp,
                    placeArabicOnLeft
                ) { completed, total ->
                    updateProgress(60 + completed * 38 / total, 100, "دمج الصفحات المحسّنة: $completed من $total")
                }
                require(outputTemp.isFile && outputTemp.length() > 0L) { "لم يتم إنشاء ملف الدمج الذكي." }
                generatedFile = outputTemp
                runOnUiThread {
                    setBusy(false)
                    progress.progress = 100
                    status.text = "اكتمل الدمج الذكي. تمت معالجة ${repairResult.translatedRegions} منطقة، وتحتاج ${repairResult.reviewRegions} منطقة إلى مراجعة.\n" +
                        "سيتم حفظ PDF ثم عرض خيار حفظ التقرير."
                    updateActionButtons()
                    launchSavePicker()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setBusy(false)
                    status.text = "فشل الدمج الذكي: ${friendlyError(e)}"
                    updateActionButtons()
                }
            }
        }
    }

    private fun saveSmartReport(destination: Uri) {
        val report = pendingSmartReport
        if (report.isNullOrBlank()) {
            status.text = "تقرير الدمج الذكي غير متاح."
            return
        }
        try {
            contentResolver.openOutputStream(destination, "wt").use { output ->
                requireNotNull(output) { "تعذر فتح ملف التقرير." }
                output.write(report.toByteArray(Charsets.UTF_8))
                output.flush()
            }
            pendingSmartReport = null
            status.text = "تم حفظ ملف الدمج الذكي وتقرير الفحص بنجاح. راجع التقرير لمعرفة المناطق التي تحتاج تدقيقًا."
        } catch (e: Exception) {
            status.text = "تعذر حفظ تقرير الدمج الذكي: ${friendlyError(e)}"
        }
    }

    private fun startOcrReport() {
        if (isBusy) return
        val sourceUri = arabicUri
        if (sourceUri == null) {
            Toast.makeText(this, "اختر ملف PDF العربي أولاً.", Toast.LENGTH_SHORT).show()
            return
        }
        setBusy(true, 0, "جاري تجهيز OCR العربي والإنجليزي...")
        ocrButton.isEnabled = false
        executor.execute {
            try {
                val input = File(cacheDir, "ocr_source.pdf")
                copyUriToFile(sourceUri, input)
                val report = OcrReportGenerator.generate(this, input) { completed, total, message ->
                    updateProgress(if (total == 0) 0 else completed * 100 / total, 100, message)
                }
                pendingOcrReport = report
                runOnUiThread {
                    setBusy(false)
                    ocrButton.isEnabled = true
                    status.text = "اكتمل OCR. اختر مكان حفظ تقرير النصوص والترجمات. لم يتم تغيير PDF الأصلي."
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TITLE, "SmartPDF_OCR_Arabic_English_Report.txt")
                    }
                    startActivityForResult(intent, CREATE_OCR_REPORT)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setBusy(false)
                    ocrButton.isEnabled = true
                    status.text = "تعذر إكمال OCR: ${friendlyError(e)}"
                }
            }
        }
    }

    private fun saveOcrReport(destination: Uri) {
        val report = pendingOcrReport
        if (report.isNullOrBlank()) {
            status.text = "تقرير OCR غير متاح. أعد العملية."
            return
        }
        try {
            contentResolver.openOutputStream(destination, "wt").use { output ->
                requireNotNull(output) { "تعذر فتح ملف التقرير للحفظ." }
                output.write(report.toByteArray(Charsets.UTF_8))
                output.flush()
            }
            pendingOcrReport = null
            status.text = "تم حفظ تقرير OCR والترجمات بنجاح. ملف PDF الأصلي لم يتغير."
        } catch (e: Exception) {
            status.text = "فشل حفظ تقرير OCR: ${friendlyError(e)}"
        }
    }

    private fun launchSavePicker() {
        val file = generatedFile
        if (file == null || !file.exists()) {
            status.text = "أنشئ ملف PDF أولاً."
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, if (pendingSmartReport != null) "Bilingual_Smart_Final.pdf" else OUTPUT_FILE_NAME)
        }
        startActivityForResult(intent, CREATE_OUTPUT)
    }

    private fun saveGeneratedPdf(destination: Uri) {
        val source = generatedFile
        if (source == null || !source.exists()) {
            status.text = "ملف PDF المؤقت غير موجود. أعد عملية الدمج."
            return
        }
        setBusy(true, 95, "جاري حفظ الملف في المكان الذي اخترته...")
        executor.execute {
            try {
                contentResolver.openOutputStream(destination, "wt").use { output ->
                    requireNotNull(output) { "تعذر فتح مكان الحفظ." }
                    source.inputStream().buffered().use { input ->
                        output.buffered().use { target ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var copied = 0L
                            val total = source.length().coerceAtLeast(1L)
                            var lastReportedPercent = -1
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                target.write(buffer, 0, count)
                                copied += count
                                val currentPercent = percent(copied, total)
                                if (currentPercent != lastReportedPercent) {
                                    val value = 95 + (copied * 5 / total).toInt()
                                    updateProgress(value.coerceAtMost(100), 100, "حفظ PDF: $currentPercent%")
                                    lastReportedPercent = currentPercent
                                }
                            }
                            target.flush()
                        }
                    }
                }
                savedOutputUri = destination
                runOnUiThread {
                    setBusy(false)
                    progress.progress = 100
                    status.text = if (pendingSmartReport != null) {
                        "✅ تم حفظ ملف الدمج الذكي. اختر الآن مكان حفظ تقرير الفحص."
                    } else {
                        "✅ تم حفظ Bilingual_Final.pdf بنجاح.\nيمكنك فتح الملف الآن."
                    }
                    updateActionButtons()
                    Toast.makeText(this, "تم حفظ ملف PDF بنجاح", Toast.LENGTH_LONG).show()
                    if (pendingSmartReport != null) {
                        val reportIntent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TITLE, "SmartPDF_Smart_Merge_Report.txt")
                        }
                        startActivityForResult(reportIntent, CREATE_SMART_REPORT)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setBusy(false)
                    status.text = "❌ تعذر حفظ الملف: ${friendlyError(e)}\nيمكنك المحاولة مرة أخرى."
                    updateActionButtons()
                }
            }
        }
    }

    private fun openGeneratedPdf() {
        try {
            val uri = savedOutputUri ?: generatedFile?.let {
                FileProvider.getUriForFile(this, "$packageName.fileprovider", it)
            }
            if (uri == null) {
                status.text = "لا يوجد ملف جاهز للفتح."
                return
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "فتح ملف PDF"))
        } catch (e: Exception) {
            status.text = "تعذر فتح الملف. تأكد من تثبيت قارئ PDF.\n${friendlyError(e)}"
        }
    }

    private fun copyUriToFile(
        uri: Uri,
        destination: File,
        onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> }
    ) {
        val total = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else -1L
            } else -1L
        } ?: -1L

        contentResolver.openInputStream(uri).use { rawInput ->
            requireNotNull(rawInput) { "تعذر قراءة الملف المحدد." }
            FileOutputStream(destination).buffered().use { output ->
                rawInput.buffered().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    var lastReportedPercent = -1
                    var lastUnknownSizeReport = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        val currentPercent = percent(copied, total)
                        if ((total > 0L && currentPercent != lastReportedPercent) ||
                            (total <= 0L && copied - lastUnknownSizeReport >= 1024L * 1024L)) {
                            onProgress(copied, total)
                            lastReportedPercent = currentPercent
                            lastUnknownSizeReport = copied
                        }
                    }
                    onProgress(copied, total)
                    output.flush()
                }
            }
        }
        require(destination.length() > 0L) { "الملف المحدد فارغ." }
    }

    private fun updateProgress(value: Int, max: Int, message: String) {
        runOnUiThread {
            progress.max = max
            progress.progress = value.coerceIn(0, max)
            status.text = message
        }
    }

    private fun setBusy(busy: Boolean, initialProgress: Int = 0, message: String? = null) {
        isBusy = busy
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        progress.isIndeterminate = false
        progress.max = 100
        progress.progress = initialProgress
        ocrButton.isEnabled = !busy && arabicUri != null
        pairButton.isEnabled = !busy && originalUri != null && arabicUri != null
        smartButton.isEnabled = !busy && originalUri != null && arabicUri != null &&
            (pageCountOriginal == null || pageCountArabic == null || pageCountOriginal == pageCountArabic)
        saveButton.isEnabled = !busy && generatedFile?.exists() == true
        openButton.isEnabled = !busy && (savedOutputUri != null || generatedFile?.exists() == true)
        if (message != null) status.text = message
    }

    private fun updateActionButtons() {
        saveButton.isEnabled = !isBusy && generatedFile?.exists() == true
        openButton.isEnabled = !isBusy && (savedOutputUri != null || generatedFile?.exists() == true)
        ocrButton.isEnabled = !isBusy && arabicUri != null
        pairButton.isEnabled = !isBusy && originalUri != null && arabicUri != null &&
            (pageCountOriginal == null || pageCountArabic == null || pageCountOriginal == pageCountArabic)
        smartButton.isEnabled = !isBusy && originalUri != null && arabicUri != null &&
            (pageCountOriginal == null || pageCountArabic == null || pageCountOriginal == pageCountArabic)
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment ?: "PDF"
    }

    private fun formatSize(bytes: Long): String =
        if (bytes < 1024L) "$bytes B"
        else if (bytes < 1024L * 1024L) "%.1f KB".format(bytes / 1024.0)
        else "%.2f MB".format(bytes / (1024.0 * 1024.0))

    private fun percent(done: Long, total: Long): Int =
        if (total <= 0L) 0 else ((done * 100L) / total).toInt().coerceIn(0, 100)

    private fun friendlyError(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
