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


## V1.3.2 — Smart Merge restoration

- Restores the separate Smart Merge and OCR report actions.
- Keeps the PDFBox `LayerUtility` page composition path and the original vector page content.
- Arabic replacement glyphs are shaped by Android Canvas and embedded as a small lossless PNG overlay only for the replacement region; the rest of each PDF page is not rasterized. Text in that replacement overlay is not selectable/searchable.
- OCR uses `tess-two` with Arabic and English trained data.
- The GitHub Actions workflow uses the runner-provided Android SDK and signs debug/release with the configured GitHub Secrets keystore.
- Smart Merge is conservative: it replaces only detected English-only regions on sufficiently light backgrounds and sends uncertain cases to the report. It cannot infer paragraphs completely absent from the translation PDF.


## V1.3.3 — OCR and Smart Merge reliability

- OCR traineddata downloads are cached only after a complete multi-megabyte download; partial files are removed and content-length/maximum-size checks are applied.
- OCR initialization is attempted once per Smart Merge run, and the actual initialization failure is written to the report instead of being silently swallowed and retried on every image page.
- Repeated identical English strings reuse the translation result during the same run, reducing network calls and inconsistent duplicate translations.
- The repaired translation PDF is written to a temporary `.partial` file and promoted only after a non-empty save completes, reducing the chance of leaving a truncated output after a save failure.
- Version name and GitHub artifact name are updated to 1.3.3.

Validation in this package is static (source and archive checks). A Gradle build and device runtime test must still be confirmed by GitHub Actions and device testing.


## V1.4.0 — PDF Compare Preview

- Added a read-only, side-by-side PDF page preview for the original and translated documents.
- Added previous/next page navigation; the preview renders pages with Android `PdfRenderer`.
- Source documents are copied to app cache for preview and are not modified.
- Added the first pass of the navy/purple application theme palette.
- Existing Normal Merge, Smart Merge, OCR reports, LayerUtility, and signing workflow are retained.

The preview is a visual comparison only; it does not perform OCR alignment or detect semantic differences. Build verification must be completed by GitHub Actions.


## V1.5.1 — Conservative Arabic coverage/quality audit

- Adds a per-page reference check for up to 4 extractable English source blocks, pairing each with the nearest Arabic block by vertical position.
- Uses MyMemory output only as a rough reference and normalizes Arabic diacritics and common Alef/Ya/Ta-Marbuta variants before measuring token coverage.
- Automatic replacement is restricted to obvious short/incomplete target fragments (less than 35% of reference token count and less than 12% token coverage), with a verified light background and safe geometry. Low lexical overlap alone never triggers replacement.
- If the layout/background is unsafe, the region is reported for human review. This is a conservative coverage heuristic, not an LLM semantic-quality verdict; fully omitted paragraphs are still not auto-inserted at guessed positions.
- The advanced audit now runs even when no residual English text was detected on the target page.

## V1.5.0 — Two-mode merge and expanded page audit

- Keeps the normal merge as an independent path: it calls `PdfPairer` only and does not run OCR or translation.
- Names the two primary actions clearly: normal merged PDF and advanced compare/repair merge.
- Advanced merge continues to inspect every translation-side page, OCR pages with image XObjects or unreliable native text extraction, and translate detected English-only regions only when placement/background checks pass.
- Detects separately positioned Latin fragments inside mixed Arabic/English PDF text runs, instead of skipping the entire mixed line. The replacement is limited to the fragment's measured bounds and remains subject to the existing white-background and fit checks.
- Adds per-page source-English/target-Arabic extraction counts and conservative coverage warnings to the report. These counts are heuristic indicators only; they do not prove semantic translation quality or prove that a fully omitted paragraph has been restored.
- Images containing English text can be OCR-scanned and their detected text can be translated when hOCR coordinates and safe replacement geometry are available. This does not yet reconstruct arbitrary complex image artwork or guarantee matching an image absent from the source page.
- Full semantic quality scoring and reliable paragraph alignment are not claimed as implemented: the V1.5.1 reference-coverage check is deliberately conservative and cannot judge meaning, style, or factual accuracy. Uncertain content is reported rather than overwritten.

Build/device verification is still required. This archive update does not claim a successful Gradle build or physical-device test.


## V1.5.2 — Optional AI semantic audit and missing-paragraph flags

- Adds an optional OpenAI-compatible Chat Completions endpoint/model configuration for semantic English-to-Arabic review. The app contains no embedded API key; the user's key is encrypted with Android Keystore (AES-GCM) and only HTTPS endpoints are accepted.
- During advanced merge, up to four source text blocks per page are compared with nearby Arabic blocks. When AI is configured, the model judges meaning, omissions and material mistranslations; an AI correction is applied only at confidence >=85% and only when the existing safe-background/geometry checks pass. Otherwise the case is reported for human review.
- If no Arabic block is found near a source block, the report flags a potentially fully omitted paragraph. It is not inserted automatically because page reflow and layout differences make guessed coordinates unsafe.
- Without AI settings, the existing conservative MyMemory reference-coverage heuristic remains as a fallback and is explicitly identified as heuristic.
- AI calls require the user's own compatible API endpoint, model and key, plus internet access. No key or paid service is bundled.

Build/device verification remains pending; this source update has not been represented as a successful APK build or device test.


## V1.5.3 — Visual page and image-text audit

- Adds a low-resolution page-to-page visual difference signal. A strong mismatch or differing image-XObject presence triggers OCR even when native PDF text extraction appears valid.
- When visual artwork differs, OCR can inspect both source and translation pages and flag OCR text visible only on the translation side. English OCR regions enter the existing translation path with hOCR coordinates.
- Replacement overlays can sample a locally uniform colored background, not only white. Textured/complex image backgrounds remain review-only to avoid destroying diagrams or photographs.
- This is conservative image-text extraction and overlay, not full image reconstruction or semantic object matching. Page-count equality is still required.
- Gradle build/device validation remains pending in this environment.

## V1.6.0 — Multimodal image-text audit

- Adds an OpenAI-compatible multimodal page audit for suspicious pages containing images or significant visual differences. The source and translated page are sent together as resized JPEG data URLs.
- The vision model can report missing, untranslated, or materially mistranslated text inside diagrams, screenshots, labels, and illustrations, including a suggested Arabic wording and confidence.
- Vision findings are report-only. The app does not erase or repaint complex image regions from model-generated guesses; precise segmentation and inpainting are not implemented.
- The configured model/provider must support image input. Page content is sent to that configured provider over HTTPS, so users should avoid sending confidential PDFs to providers they do not trust.
- Gradle build and device validation remain pending unless separately completed.


## V1.7.0 — Conservative image-text reconstruction
- Adds OCR-box-based local background reconstruction for image text on flat fills and carefully validated smooth gradients.
- Uses a bilinear color surface estimated from the patch corners and rejects regions whose perimeter does not fit the estimate.
- Keeps OCR confidence and Arabic text-fit checks; complex photos, textures, intersecting lines and diagrams remain report-only/manual review.
- This is not general-purpose image inpainting and does not use AI-invented coordinates.


## V1.8.0 — Review before accepting repairs

The advanced merge now pauses after repair and opens a page-by-page comparison of the original and repaired translation. The user must explicitly accept the repaired translation or reject all automated changes and continue with the untouched original translation. Leaving the review screen without a decision does not approve changes. Normal merge remains unchanged. This is a human-approval stage, not a claim of complex-image AI inpainting; image-edit APIs require a separately configured provider capability. See `STAGE_V1.8.0_AR.md`.


## V1.9.0 — Page-level approval and selective repair

Advanced merge now records exactly which pages received automated overlays. During review, each modified page has an approval checkbox. The final Arabic PDF is assembled by importing approved repaired pages and untouched original-translation pages for all rejected or unmodified pages. This prevents a user from having to accept or reject every repair as one batch. The existing conservative smooth-background patch estimator remains limited to locally smooth surfaces; complex photos/textures are not automatically inpainted. This is page-level approval, not per-OCR-box approval or a claim of generative AI inpainting. Normal merge remains unchanged. See `STAGE_V1.9.0_AR.md`.


## V2.0.0 — Per-region approval

Advanced merge now exposes each successfully rendered repair candidate separately in the review screen, with its source text, proposed Arabic wording, page number, and detection confidence. The user can approve or reject each region independently, including multiple different decisions on the same page. On confirmation, the repair engine re-runs against the untouched Arabic source and applies only approved region IDs; it no longer imports or accepts an entire repaired page. Candidate IDs are based on page, normalized source text, and region position (not remote translation wording) so small wording variations do not invalidate the approval. The ordinary merge path is unchanged. This remains conservative local overlay repair; it is not a general-purpose generative inpainting engine. See `STAGE_V2.0.0_AR.md`.


## V2.1.0 — Hidden Arabic/RTL integrity audit (phase 1)

The advanced merge path now runs a hidden Arabic text integrity audit on both source and translated PDF text extraction. It flags unmapped/private glyphs, Arabic presentation forms, suspicious or unbalanced bidirectional controls, and heuristic signs of reversed Arabic word order. Non-informational findings trigger the existing OCR review path. This first phase is diagnostic and conservative: it does not rewrite Arabic text based on a guess and adds no user-facing settings or technical controls.


## V2.2.0 — Arabic searchable text-layer repair
- Advanced merge compares native extracted Arabic with high-confidence Arabic+English OCR.
- When a damaged text layer is confirmed, the affected page is rebuilt as a lossless rendered page image plus an invisible Unicode-mapped OCR text layer, so the visible page is retained while copy/search can use recognized text.
- Rebuilding is gated by OCR confidence and Arabic token mismatch; low-confidence pages are left unchanged and flagged internally.
- This targeted fallback rasterizes only rebuilt pages (180 DPI in the current OCR path), so vector sharpness/selectability of non-OCR objects on those pages is not preserved.
- Ordinary merge remains unchanged; the advanced flow keeps the simple user-facing controls.

## V2.3.0 — بن راوح Home UI
- Redesigned the Arabic RTL home screen with a branded blue header and the existing app launcher icon.
- Consolidated the two PDF pickers into one visual selection panel.
- Kept only Normal Merge and Smart Merge as prominent home actions.
- Moved page ordering, AI audit settings, OCR report, and optional comparison preview into the settings menu.
- Preserved the existing processing flows and output actions.


## V2.3.1 — Ben Rawah reference-matched home screen

The home screen now follows the approved Arabic visual reference: blue brand header, prominent combined PDF selection flow, side-by-side normal/smart merge cards, green trust banner, and Mohammed footer. Secondary tools remain accessible from settings.


## V2.3.2

واجهة رئيسية أنظف بعلامات PDF أقل، ورسم موحد للملفين، ومعالجة هوامش أشرطة النظام لمنع تغطية تذييل Mohammed.
