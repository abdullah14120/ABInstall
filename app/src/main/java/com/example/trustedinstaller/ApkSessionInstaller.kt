package com.example.trustedinstaller

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ApkSessionInstaller(private val context: Context) {

    /**
     * تثبيت ملف APK واحد أو مجموعة ملفات (Split APKs) داخل جلسة رسمية واحدة
     * مع تسجيل اسم حزمة هذا التطبيق كمصدر التثبيت الموثوق.
     */
    suspend fun installApks(
        apkUris: List<Uri>,
        onProgress: (String) -> Unit
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            require(apkUris.isNotEmpty()) { "يرجى اختيار ملف APK واحد على الأقل" }

            val packageInstaller = context.packageManager.packageInstaller

            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                // تسجيل اسم حزمة التطبيق الحالي كمصدر التثبيت الرسمي
                setInstallerPackageName(context.packageName)

                // تحديد سبب التثبيت كإجراء مباشر ومعتمد من المستخدم
                setInstallReason(PackageManager.INSTALL_REASON_USER)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
            }

            val sessionId = packageInstaller.createSession(params)

            packageInstaller.openSession(sessionId).use { session ->
                apkUris.forEachIndexed { index, uri ->
                    val entryName = "split_pkg_$index.apk"
                    withContext(Dispatchers.Main) {
                        onProgress("جاري كتابة الملف (${index + 1}/${apkUris.size}) داخل جلسة النظام...")
                    }

                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        session.openWrite(entryName, 0, -1).use { outputStream ->
                            inputStream.copyTo(outputStream, bufferSize = 64 * 1024)
                            session.fsync(outputStream)
                        }
                    } ?: throw IllegalStateException("تعذر قراءة الملف: $uri")
                }

                withContext(Dispatchers.Main) {
                    onProgress("بانتظار تأكيد النظام والمستخدم...")
                }

                val callbackIntent = Intent(context, InstallStatusReceiver::class.java).apply {
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
                    callbackIntent,
                    flags
                )

                session.commit(pendingIntent.intentSender)
            }

            sessionId
        }
    }
}
