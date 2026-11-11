package com.example.trustedinstaller

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import java.io.File
import java.security.MessageDigest

object SecurityEnvironmentChecker {

    /**
     * 1. فحص نظافة بيئة الجهاز (Device Telemetry) التي تقرأها التطبيقات وترسلها للسيرفر عند تسجيل الدخول
     */
    fun auditDeviceEnvironment(context: Context): String {
        val resolver = context.contentResolver

        // فحص خيارات المطور وتصحيح USB (أهم مؤشرين تراقبهما خوادم الشركات)
        val devOptionsEnabled = Settings.Global.getInt(
            resolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
        ) != 0

        val adbEnabled = Settings.Global.getInt(
            resolver,
            Settings.Global.ADB_ENABLED, 0
        ) != 0

        // فحص وجود اتصال VPN نشط (قد يسبب حظر تسجيل الدخول أو طلب تحقق إضافي)
        val vpnActive = isVpnActive(context)

        // فحص وجود آثار Root شائعة في المسارات القياسية
        val rootIndicators = checkRootFiles()

        // فحص وسم بناء النظام (test-keys تدل على روم معدل أو غير رسمي)
        val buildTags = Build.TAGS ?: ""
        val isOfficialBuild = buildTags.contains("release-keys")

        return buildString {
            appendLine("🛡️ تقرير بصمة البيئة والأمان (Server Telemetry):")
            appendLine("────────────────────────────────")
            appendLine(if (!devOptionsEnabled) "✅ خيارات المطور: مغلقة (آمن)" else "⚠️ خيارات المطور: مفعلة (قد تكتشفها بعض التطبيقات)")
            appendLine(if (!adbEnabled) "✅ تصحيح أخطاء ADB: مغلق (آمن)" else "❌ تصحيح أخطاء ADB: مفعل (يُنصح بإغلاقه قبل تسجيل الدخول)")
            appendLine(if (!vpnActive) "✅ حالة الشبكة: اتصال مباشر بدون VPN" else "⚠️ حالة الشبكة: VPN نشط (قد يغير بصمة الشبكة للسيرفر)")
            appendLine(if (!rootIndicators) "✅ فحص ملفات Root: نظيف" else "❌ فحص ملفات Root: تم رصد مسارات su على النظام")
            appendLine(if (isOfficialBuild) "✅ توقيع روم النظام: رسمي (release-keys)" else "⚠️ توقيع روم النظام: غير قياسي ($buildTags)")
        }
    }

    /**
     * 2. فحص سلامة ملف الـ APK وشهادة توقيعه الأصلية قبل تثبيته
     */
    fun verifyApkFileArchive(context: Context, apkFilePath: String): String {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

        val pkgInfo: PackageInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(apkFilePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apkFilePath, flags)
        }

        if (pkgInfo == null) {
            return "❌ الملف تالف أو ليس حزمة APK صالحة."
        }

        val sigDigest = extractSignatureSha256(pkgInfo)
        // بصمة شهادة الاختبار العامة من جوجل (AOSP TestKey) التي تكشف التطبيقات المعدلة فوراً
        val aospTestKeySha256 = "A4:0D:A8:0A:59:D1:70:CA:A9:50:CF:15:C1:8C:45:4D:47:A3:9B:26:98:9D:8B:64:0E:CD:74:5B:A7:1B:F5:DC"
        val isSignedWithTestKey = sigDigest.equals(aospTestKeySha256, ignoreCase = true)

        return buildString {
            appendLine("🔍 فحص سلامة ملف الـ APK قبل التثبيت:")
            appendLine("🔹 الحزمة: ${pkgInfo.packageName}")
            appendLine("🔹 الإصدار: ${pkgInfo.versionName} (${pkgInfo.longVersionCode})")
            appendLine("🔹 بصمة توقيع المطور (SHA-256):")
            appendLine(sigDigest)
            if (isSignedWithTestKey) {
                appendLine("❌ تحذير أمني: هذا الملف موقع بشهادة اختبار عامة (TestKey)! سيكتشف سيرفر الشركة أنه معدل فوراً.")
            } else {
                appendLine("✅ التوقيع الرقمي: شهادة خاصة سليمة (غير موقع بـ TestKey).")
            }
        }
    }

    private fun extractSignatureSha256(pkgInfo: PackageInfo): String {
        val bytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = pkgInfo.signingInfo
            val certs = if (signingInfo?.hasMultipleSigners() == true) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo?.signingCertificateHistory
            }
            certs?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.signatures?.firstOrNull()?.toByteArray()
        } ?: return "غير متاح"

        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString(":") { "%02X".format(it) }
    }

    private fun isVpnActive(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun checkRootFiles(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su"
        )
        return paths.any { File(it).exists() }
    }
}
