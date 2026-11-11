package com.example.trustedinstaller

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

object SourceInspector {

    /**
     * فحص مصدر تثبيت أي حزمة على الجهاز واستخراج التوقيع الرقمي للمثبّت
     */
    fun inspectPackageSource(context: Context, targetPackage: String): String {
        val pm = context.packageManager
        val cleanPkg = targetPackage.trim()

        if (cleanPkg.isEmpty()) {
            return "يرجى إدخال اسم الحزمة (Package Name) للفحص."
        }

        return try {
            // التحقق من وجود الحزمة أولاً
            pm.getPackageInfo(cleanPkg, 0)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val info = pm.getInstallSourceInfo(cleanPkg)
                val initiating = info.initiatingPackageName ?: "غير معروف (null)"
                val installing = info.installingPackageName ?: "غير معروف (null)"
                val originating = info.originatingPackageName ?: "غير مسجل (null)"

                val signingInfo = info.initiatingPackageSigningInfo
                val certFingerprints = if (signingInfo != null) {
                    val certs = if (signingInfo.hasMultipleSigners()) {
                        signingInfo.apkContentsSigners
                    } else {
                        signingInfo.signingCertificateHistory
                    }
                    certs?.joinToString("\n") { sha256Fingerprint(it.toByteArray()) }
                        ?: "غير متاح"
                } else {
                    "لا يوجد توقيع مرتبط"
                }

                val isInstalledByUs = installing == context.packageName

                buildString {
                    appendLine("📦 الحزمة المفحوصة: $cleanPkg")
                    appendLine("────────────────────────────────")
                    appendLine("🔹 الحزمة المثبِّتة (Installing): $installing")
                    appendLine("🔹 الحزمة المبادرة (Initiating): $initiating")
                    appendLine("🔹 المصدر الأصلي (Originating): $originating")
                    appendLine("🔹 توقيع المثبِّت (SHA-256):")
                    appendLine(certFingerprints)
                    appendLine("────────────────────────────────")
                    if (isInstalledByUs) {
                        appendLine("✅ النتيجة: تم التثبيت رسمياً عبر هذا التطبيق (${context.packageName}) وموثق بشهادة التوقيع.")
                    } else {
                        appendLine("ℹ️ النتيجة: تم التثبيت عبر مصدر آخر ($installing).")
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val legacyInstaller = pm.getInstallerPackageName(cleanPkg) ?: "غير معروف / تثبيت يدوي (null)"
                buildString {
                    appendLine("📦 الحزمة المفحوصة: $cleanPkg")
                    appendLine("────────────────────────────────")
                    appendLine("🔹 الحزمة المثبِّتة (Legacy API): $legacyInstaller")
                }
            }
        } catch (e: PackageManager.NameNotFoundException) {
            "❌ الحزمة ($cleanPkg) غير مثبتة حالياً على هذا الجهاز."
        } catch (e: Exception) {
            "⚠️ تعذر فحص الحزمة: ${e.localizedMessage}"
        }
    }

    private fun sha256Fingerprint(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString(":") { "%02X".format(it) }
    }
}
