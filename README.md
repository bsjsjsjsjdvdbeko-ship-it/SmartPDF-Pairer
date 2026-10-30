# SmartPDF Pairer — Android V1

تطبيق Android حقيقي لاختيار ملف PDF أصلي وملف PDF عربي ثم إنشاء ملف ثنائي اللغة.

## V1

- Android File Picker بدل كتابة مسارات الملفات.
- Arabic LEFT / Original RIGHT افتراضيًا.
- خيار عكس الترتيب.
- Page 1 ↔ Page 1.
- يتطلب تساوي عدد الصفحات.
- إنشاء `Bilingual_Final.pdf`.
- GitHub Actions لبناء APK Debug.

## البناء

ارفع المشروع إلى GitHub ثم:

Actions → Android Build

وسيتم إنشاء `app-debug.apk` كـ Artifact.

## الحالة

**IMPLEMENTED_BUT_UNVERIFIED**

تم إعداد المصدر، لكن لم يتم بناء APK فعليًا في هذه الجلسة ولم يتم اختباره على جهاز Android. يجب اعتبار أول GitHub Actions build مرحلة التحقق الأولى.

## الخطط التالية

V2: Smart Matching عند اختلاف عدد الصفحات.

V3: OCR للـScanned PDFs.
