package com.smartpdf.pairer.smart

import java.text.Normalizer

/**
 * Hidden, conservative Arabic extraction/RTL integrity audit.
 * This class never rewrites PDF text. It normalizes only a temporary analysis copy and
 * reports evidence that extraction may be damaged, visually ordered, or poorly mapped.
 */
internal object ArabicTextIntegrityAuditor {
    enum class Severity { INFO, REVIEW, HIGH }

    data class Finding(
        val severity: Severity,
        val code: String,
        val message: String,
        val excerpt: String
    )

    data class PageResult(
        val findings: List<Finding>,
        val arabicLines: Int,
        val inspectedLines: Int
    )

    private val arabicChars = Regex("[\\u0600-\\u06FF\\u0750-\\u077F\\u08A0-\\u08FF]")
    private val replacementOrPrivate = Regex("[\\uFFFD\\uE000-\\uF8FF]")
    private val presentationForms = Regex("[\\uFB50-\\uFDFF\\uFE70-\\uFEFF]")
    private val bidiControls = Regex("[\\u061C\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]")
    private val arabicTokens = Regex("[\\u0621-\\u064A\\u0660-\\u0669\\u06F0-\\u06F9]+")

    fun audit(lines: List<String>): PageResult {
        val findings = ArrayList<Finding>()
        var arabicLines = 0
        lines.forEach { raw ->
            val text = raw.trim()
            if (text.isEmpty() || !arabicChars.containsMatchIn(text)) return@forEach
            arabicLines++

            val badMappings = replacementOrPrivate.findAll(text).count()
            if (badMappings > 0) {
                findings += Finding(
                    Severity.HIGH, "UNMAPPED_GLYPH",
                    "رموز استبدال أو محارف خاصة داخل نص عربي؛ قد يكون ترميز الخط أو خريطة ToUnicode معطوباً.",
                    excerpt(text)
                )
            }

            val presentationCount = presentationForms.findAll(text).count()
            if (presentationCount > 0) {
                findings += Finding(
                    Severity.REVIEW, "PRESENTATION_FORMS",
                    "النص يحتوي أشكال عرض عربية بدلاً من الحروف الأساسية؛ يلزم التحقق من قابلية النسخ بعد التطبيع.",
                    excerpt(text)
                )
            }

            val controls = bidiControls.findAll(text).count()
            if (controls > 0) {
                val isolatesOpen = text.count { it == '\u2066' || it == '\u2067' || it == '\u2068' }
                val isolatesClose = text.count { it == '\u2069' }
                val embeddingsOpen = text.count { it == '\u202A' || it == '\u202B' || it == '\u202D' || it == '\u202E' }
                val embeddingsClose = text.count { it == '\u202C' }
                val unbalanced = isolatesOpen != isolatesClose || embeddingsOpen != embeddingsClose
                findings += Finding(
                    if (unbalanced) Severity.REVIEW else Severity.INFO,
                    if (unbalanced) "UNBALANCED_BIDI_CONTROLS" else "BIDI_CONTROLS",
                    if (unbalanced) "علامات اتجاه النص RTL/LTR غير متوازنة؛ قد يتغير ترتيب النسخ أو العرض." else "توجد علامات تحكم بالاتجاه؛ يجب الحفاظ عليها عند إعادة بناء النص.",
                    excerpt(text)
                )
            }

            // A token beginning with taa marbuta/alef maqsura is uncommon in Arabic.
            // Several such tokens in one line can indicate character-order reversal,
            // but this is only a review signal and is never auto-corrected.
            val tokens = arabicTokens.findAll(text).map { it.value }.toList()
            val suspiciousStarts = tokens.count { it.firstOrNull() == '\u0629' || it.firstOrNull() == '\u0649' }
            if (tokens.size >= 3 && suspiciousStarts >= 2) {
                findings += Finding(
                    Severity.REVIEW, "POSSIBLE_RTL_ORDER_CORRUPTION",
                    "بداية عدة كلمات بحروف نادراً ما تبدأ بها الكلمة العربية؛ احتمال ترتيب حروف/كلمات معكوس عند الاستخراج، ويحتاج مقارنة بصرية.",
                    excerpt(text)
                )
            }

            val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
            if (normalized != text && presentationCount == 0) {
                // NFKC can change compatibility characters; report only if Arabic content changes.
                val beforeArabic = arabicChars.findAll(text).count()
                val afterArabic = arabicChars.findAll(normalized).count()
                if (beforeArabic != afterArabic) {
                    findings += Finding(
                        Severity.REVIEW, "UNICODE_NORMALIZATION_CHANGE",
                        "تطبيع Unicode يغيّر عدد محارف العربية؛ يلزم التحقق من النص المستخرج قبل اعتماد النسخ.",
                        excerpt(text)
                    )
                }
            }
        }
        return PageResult(findings.distinctBy { it.code + it.excerpt }, arabicLines, lines.size)
    }

    private fun excerpt(value: String): String = value.replace(Regex("\\s+"), " ").take(150)
}
