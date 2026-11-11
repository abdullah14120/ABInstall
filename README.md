# Trusted APK Installer (PackageInstaller Session Project)

مشروع أندرويد متكامل مكتوب بلغة **Kotlin** وواجهة **Material Design 3** لتثبيت ملفات الـ APK والحزم المقسمة (Split APKs) عبر واجهة النظام الرسمية `PackageInstaller.Session`، مع تسجيل هوية التطبيق وتوقيعه الرقمي كمصدر تثبيت رسمي (`InstallSourceInfo`).

## المزايا الرئيسية في المشروع
1. **تثبيت رسمي موثق المصدر (`ApkSessionInstaller.kt`):**
   - يستخدم `SessionParams(MODE_FULL_INSTALL)`.
   - يضبط `setInstallerPackageName(context.packageName)` و `setInstallReason(PackageManager.INSTALL_REASON_USER)`.
   - يدعم تثبيت ملف `APK` منفرد أو عدة ملفات `Split APKs` في جلسة ذرية واحدة.
2. **معالج حالات التثبيت (`InstallStatusReceiver.kt`):**
   - يتعامل مع `STATUS_PENDING_USER_ACTION` لعرض نافذة التأكيد الرسمية للنظام.
   - يحلل أخطاء التثبيت بالتفصيل (تعارض التوقيع، عدم توافق المعمارية، نقص المساحة، إلخ).
3. **فاحص مصدر التثبيت المدمج (`SourceInspector.kt`):**
   - أداة مدمجة داخل التطبيق لفحص أي حزمة مثبتة وعرض:
     - `installingPackageName`
     - `initiatingPackageName`
     - `originatingPackageName`
     - البصمة التشفيرية `SHA-256` لشهادة توقيع المثبّت (`initiatingPackageSigningInfo`).

## طريقة التخصيص لمتجرك الخاص
لتغيير معرف المصدر الذي يظهر للتطبيقات المثبتة إلى معرف متجرك الخاص:
1. افتح الملف `app/build.gradle.kts`.
2. عدّل قيمة `applicationId = "com.example.trustedinstaller"` إلى اسم الحزمة الرسمي الخاص بمتجرك.
3. وقّع التطبيق بشهادتك الرسمية (`Keystore`) عند استخراج نسخة `Release`.
