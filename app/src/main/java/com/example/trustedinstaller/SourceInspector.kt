package com.example.trustedinstaller

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.SigningInfo
import android.os.Build
import java.security.MessageDigest

object SourceInspector {

    // بصمة شهادة الاختبار العامة من جوجل (AOSP TestKey) لكشف التطبيقات المعاد توقيعها بشكل غير رسمي
    private const val AOSP_TEST_KEY_SHA256 =
        "A4:0D:A8:0A:59:D1:70:CA:A9:50:CF:15:C1:8C:45:4D:47:A3:9B:26:98:9D:8B:64:0E:CD:74:5B:A7:1B:F5:DC"

    // قائمة المتاجر العالمية المعتمدة في القوائم البيضاء لخوادم الشركات
    private val KNOWN_OFFICIAL_STORES = mapOf(
        "com.android.vending" to "Google Play Store (متجر جوجل بلاي الرسمي)",
        "com.huawei.appmarket" to "Huawei AppGallery (متجر هواوي الرسمي)",
        "com.sec.android.app.samsungapps" to "Samsung Galaxy Store (متجر سامسونج الرسمي)",
        "com.amazon.venezia" to "Amazon Appstore (متجر أمازون الرسمي)",
        "com.xiaomi.mipicks" to "Xiaomi GetApps (متجر شاومي الرسمي)",
        "com.heytap.market" to "OPPO / Realme App Market",
        "com.bbk.appstore" to "vivo V-Appstore"
    )

    /**
     * فحص شامل لمصدر تثبيت أي حزمة على الجهاز واستخراج التوقيع الرقمي للمثبّت وللتطبيق نفسه
     * ومطابقته مع معايير التحقق الأمني لدى خوادم الشركات (Server Telemetry & Attestation).
     */
    fun inspectPackageSource(context: Context, targetPackage: String): String {
        val pm = context.packageManager
        val cleanPkg = targetPackage.trim()

        if (cleanPkg.isEmpty()) {
            return "يرجى إدخال اسم الحزمة (Package Name) للفحص."
        }

        return try {
            val pkgInfo = getTargetPackageInfo(pm, cleanPkg)
            val appVersion = "${pkgInfo.versionName ?: "N/A"} (${getVersionCodeCompat(pkgInfo)})"
            val targetAppSignature = extractPackageSignaturesSha256(pkgInfo)
            val isTargetSignedWithTestKey = targetAppSignature.contains(AOSP_TEST_KEY_SHA256, ignoreCase = true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val info = pm.getInstallSourceInfo(cleanPkg)
                val initiating = info.initiatingPackageName ?: "غير معروف (null)"
                val installing = info.installingPackageName ?: "غير معروف (null)"
                val originating = info.originatingPackageName ?: "غير مسجل (null)"

                // فحص نوع مصدر التثبيت (Android 13 / API 33+)
                val packageSourceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    mapPackageSourceType(info.packageSource)
                } else {
                    "غير مدعوم في هذا الإصدار (يتطلب Android 13+)"
                }

                // فحص مالك التحديثات الحصري (Android 14 / API 34+)
                val updateOwner = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    info.updateOwnerPackageName ?: "غير محدد (null)"
                } else {
                    "غير مدعوم في هذا الإصدار (يتطلب Android 14+)"
                }

                // استخراج البصمة التشفيرية (SHA-256) لشهادة توقيع المثبّت
                val installerFingerprints = extractSigningInfoSha256(info.initiatingPackageSigningInfo)

                val isInstalledByUs = installing == context.packageName
                val knownStoreLabel = KNOWN_OFFICIAL_STORES[installing]

                buildString {
                    appendLine("📦 الحزمة المفحوصة: $cleanPkg")
                    appendLine("🔢 الإصدار: $appVersion")
                    appendLine("────────────────────────────────")
                    appendLine("🛡️ بيانات مصدر التثبيت (InstallSourceInfo):")
                    appendLine("🔹 الحزمة المثبِّتة (Installing): $installing")
                    appendLine("🔹 الحزمة المبادرة (Initiating): $initiating")
                    appendLine("🔹 المصدر الأصلي (Originating): $originating")
                    appendLine("🔹 تصنيف المصدر (PackageSource): $packageSourceType")
                    appendLine("🔹 مالك التحديثات (UpdateOwner): $updateOwner")
                    appendLine("────────────────────────────────")
                    appendLine("🔑 توقيع المثبِّت (Installer SHA-256):")
                    appendLine(installerFingerprints)
                    appendLine("────────────────────────────────")
                    appendLine("📜 توقيع التطبيق المفحوص (Target APK SHA-256):")
                    appendLine(targetAppSignature)
                    if (isTargetSignedWithTestKey) {
                        appendLine("❌ تحذير: التطبيق موقع بشهادة اختبار عامة (AOSP TestKey)!")
                    } else {
                        appendLine("✅ حالة توقيع التطبيق: شهادة مطور خاصة سليمة.")
                    }
                    appendLine("────────────────────────────────")
                    appendLine("🌐 تقييم الموثوقية لدى خوادم الشركات (Server Verdict):")
                    when {
                        knownStoreLabel != null -> {
                            appendLine("✅ مستوى الثقة: أقصى درجة موثوقية (متجر عالمي معتمد: $knownStoreLabel).")
                        }
                        isInstalledByUs -> {
                            appendLine("✅ مستوى الثقة: مثبّت رسمياً عبر هذا التطبيق (${context.packageName}) وموثق بشهادة التوقيع.")
                        }
                        installing == "غير معروف (null)" || installing.contains("packageinstaller") -> {
                            appendLine("⚠️ مستوى الثقة: منخفض — يظهر كتثبيت يدوي جانبي (Sideloaded / Unknown Source).")
                        }
                        else -> {
                            appendLine("ℹ️ مستوى الثقة: مثبّت عبر حزمة خارجية محددة الهوية ($installing).")
                        }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val legacyInstaller = pm.getInstallerPackageName(cleanPkg) ?: "غير معروف / تثبيت يدوي (null)"
                val isInstalledByUs = legacyInstaller == context.packageName
                val knownStoreLabel = KNOWN_OFFICIAL_STORES[legacyInstaller]

                buildString {
                    appendLine("📦 الحزمة المفحوصة: $cleanPkg")
                    appendLine("🔢 الإصدار: $appVersion")
                    appendLine("────────────────────────────────")
                    appendLine("🔹 الحزمة المثبِّتة (Legacy API): $legacyInstaller")
                    appendLine("────────────────────────────────")
                    appendLine("📜 توقيع التطبيق المفحوص (Target APK SHA-256):")
                    appendLine(targetAppSignature)
                    appendLine("────────────────────────────────")
                    when {
                        knownStoreLabel != null -> {
                            appendLine("✅ النتيجة: مثبّت عبر متجر عالمي معتمد ($knownStoreLabel).")
                        }
                        isInstalledByUs -> {
                            appendLine("✅ النتيجة: تم التثبيت رسمياً عبر هذا التطبيق (${context.packageName}).")
                        }
                        else -> {
                            appendLine("ℹ️ النتيجة: تم التثبيت عبر ($legacyInstaller).")
                        }
                    }
                }
            }
        } catch (e: PackageManager.NameNotFoundException) {
            "❌ الحزمة ($cleanPkg) غير مثبتة حالياً على هذا الجهاز."
        } catch (e: Exception) {
            "⚠️ تعذر فحص الحزمة: ${e.localizedMessage}"
        }
    }

    private fun getTargetPackageInfo(pm: PackageManager, packageName: String): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, flags)
        }
    }

    private fun getVersionCodeCompat(pkgInfo: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pkgInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pkgInfo.versionCode.toLong()
        }
    }

    private fun mapPackageSourceType(source: Int): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return "غير متاح"
        return when (source) {
            PackageInstaller.PACKAGE_SOURCE_STORE -> "متجر تطبيقات رسمي (STORE = 2) ✅"
            PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE -> "ملف محلي (LOCAL_FILE = 3) ⚠️"
            PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE -> "ملف محمّل (DOWNLOADED_FILE = 4) ⚠️"
            PackageInstaller.PACKAGE_SOURCE_OTHER -> "مصدر آخر (OTHER = 1)"
            PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED -> "غير محدد (UNSPECIFIED = 0) ⚠️"
            else -> "قيمة غير معروفة ($source)"
        }
    }

    private fun extractSigningInfoSha256(signingInfo: SigningInfo?): String {
        if (signingInfo == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return "لا يوجد توقيع مرتبط (null)"
        }
        val certs = if (signingInfo.hasMultipleSigners()) {
            signingInfo.apkContentsSigners
        } else {
            signingInfo.signingCertificateHistory
        }
        return certs?.joinToString("\n") { sha256Fingerprint(it.toByteArray()) }
            ?: "غير متاح"
    }

    private fun extractPackageSignaturesSha256(pkgInfo: PackageInfo): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            extractSigningInfoSha256(pkgInfo.signingInfo)
        } else {
            @Suppress("DEPRECATION")
            val sigs = pkgInfo.signatures
            sigs?.joinToString("\n") { sha256Fingerprint(it.toByteArray()) }
                ?: "غير متاح"
        }
    }

    private fun sha256Fingerprint(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString(":") { "%02X".format(it) }
    }
}
