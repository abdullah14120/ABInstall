package com.android.trustedinstaller

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

class ApkSessionInstaller(private val context: Context) {

    fun installApk(apkUri: Uri) {
        val packageInstaller = context.packageManager.packageInstaller

        // إعداد معاملات الجلسة لتثبيت كامل
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            // تسجيل حزمة التطبيق الحالي كمصدر التثبيت الرسمي
            setInstallerPackageName(context.packageName)

            // تحديد سبب التثبيت كإجراء مباشر ومعتمد من المستخدم
            setInstallReason(PackageManager.INSTALL_REASON_USER)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // تحديد أن التثبيت يتطلب تفاعل المستخدم المباشر (معيار أمان Android 12+)
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
        }

        // إنشاء الجلسة وكتابة ملف الـ APK بداخلها
        val sessionId = packageInstaller.createSession(params)
        packageInstaller.openSession(sessionId).use { session ->
            context.contentResolver.openInputStream(apkUri)?.use { inputStream ->
                session.openWrite("package_install_session", 0, -1).use { outputStream ->
                    inputStream.copyTo(outputStream)
                    // التأكد من تفريغ البيانات والتحقق من سلامتها على القرص
                    session.fsync(outputStream)
                }
            } ?: throw IllegalStateException("تعذر فتح ملف APK من المسار المحدد")

            // إعداد PendingIntent لاستقبال حالة التثبيت في BroadcastReceiver
            val intent = Intent(context, InstallStatusReceiver::class.java).apply {
                action = InstallStatusReceiver.ACTION_INSTALL_STATUS
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                intent,
                flags
            )

            // إرسال الجلسة إلى النظام للتنفيذ
            session.commit(pendingIntent.intentSender)
        }
    }
}
