package com.example.trustedinstaller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

class InstallStatusReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_INSTALL_STATUS = "com.example.trustedinstaller.INSTALL_STATUS"
        const val ACTION_UI_EVENT = "com.example.trustedinstaller.UI_EVENT"
        const val EXTRA_UI_MESSAGE = "extra_ui_message"
        const val EXTRA_INSTALLED_PKG = "extra_installed_pkg"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return

        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
        val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }

                confirmIntent?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(it)
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                val msg = "تم تثبيت الحزمة بنجاح ($packageName) وتوثيق المصدر."
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                notifyUi(context, msg, packageName)
            }

            else -> {
                val reasonText = mapErrorStatus(status)
                val fullError = "فشل التثبيت [$reasonText]: ${statusMessage ?: "بدون تفاصيل إضافية"}"
                Toast.makeText(context, fullError, Toast.LENGTH_LONG).show()
                notifyUi(context, fullError, packageName)
            }
        }
    }

    private fun notifyUi(context: Context, message: String, packageName: String?) {
        val uiIntent = Intent(ACTION_UI_EVENT).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_UI_MESSAGE, message)
            putExtra(EXTRA_INSTALLED_PKG, packageName)
        }
        context.sendBroadcast(uiIntent)
    }

    private fun mapErrorStatus(status: Int): String = when (status) {
        PackageInstaller.STATUS_FAILURE_ABORTED -> "تم إلغاء العملية من قِبل المستخدم"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "العملية محظورة من النظام"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "تعارض مع حزمة موجودة (توقيع مختلف أو إصدار أقدم)"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "الحزمة غير متوافقة مع معمارية الجهاز أو إصدار النظام"
        PackageInstaller.STATUS_FAILURE_INVALID -> "ملف APK تالف أو غير صالح"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "مساحة التخزين غير كافية"
        else -> "خطأ عام ($status)"
    }
}
