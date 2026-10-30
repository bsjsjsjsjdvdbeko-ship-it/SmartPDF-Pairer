# SmartPDF Pairer — V1.3.0 Smart Merge

Android application for creating side-by-side bilingual PDF files.

## Main actions

1. **Normal merge** — imports the selected original and translated PDFs side by side. This path calls only `PdfPairer`; it does not invoke OCR or translation and does not alter either source file.
2. **Smart merge** — scans the translation-side PDF for English text that remains visible, prefers native PDF text extraction, and falls back to Tesseract OCR/hOCR when extraction is empty or corrupted (and on pages containing image XObjects). Clearly detected English-only regions are translated to Arabic and overlaid at their detected position when the background is sufficiently white and the replacement fits safely. The original files remain unchanged. The result is then paired with the original PDF and a separate UTF-8 report is offered.
3. **OCR report** — retains the previous OCR text-report action as a separate utility.

## Smart merge safety rules

- Arabic text is not sent to the English-to-Arabic translator.
- Existing Arabic content is preserved.
- OCR regions without usable hOCR coordinates, low-confidence OCR, non-white/image backgrounds, or translations that do not fit are not overwritten; they are listed in the report for review.
- Translation is attempted only for English-only text regions still present in the translation PDF.
- A paragraph that is completely absent from the translation PDF cannot be reliably identified as missing by simple text extraction alone. The report states this limitation; this version does not claim semantic proof that every source paragraph has an Arabic counterpart.
- The same-page mapping currently requires equal page counts. Different page counts stop the smart operation rather than guessing page correspondence.
- Translation and first-time OCR/font model downloads require internet access. OCR models and the Arabic font are cached in app-private storage.

## Technical architecture

- `PdfPairer.kt`: original, independent side-by-side PDF composition path.
- `smart/SmartTranslationRepair.kt`: smart translation-side inspection, positioned PDF text extraction, OCR/hOCR fallback, conservative in-place overlay, and report generation.
- `ocr/OcrReportGenerator.kt`: original standalone OCR report feature.
- `MainActivity.kt`: separate normal and smart actions, progress, output save, and smart report save.

## Build

GitHub Actions runs `testDebugUnitTest` and `assembleDebug` using JDK 17, Gradle 8.13, and Android SDK 36. The APK is uploaded only after the build job succeeds.

No successful Android build or physical-device runtime test is claimed by this source package until GitHub Actions completes successfully and the generated APK is tested on a device.
