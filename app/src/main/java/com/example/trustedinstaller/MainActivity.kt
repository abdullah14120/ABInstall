package com.example.trustedinstaller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.trustedinstaller.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sessionInstaller: ApkSessionInstaller

    // اختيار ملف APK واحد
    private val pickSingleApkLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { startSessionInstallation(listOf(it)) }
    }

    // اختيار عدة ملفات APK (لدعم الحزم المقسمة Split APKs)
    private val pickMultipleApksLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            startSessionInstallation(uris)
        }
    }

    private val uiEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == InstallStatusReceiver.ACTION_UI_EVENT) {
                val msg = intent.getStringExtra(InstallStatusReceiver.EXTRA_UI_MESSAGE)
                val pkg = intent.getStringExtra(InstallStatusReceiver.EXTRA_INSTALLED_PKG)

                binding.tvInstallStatus.text = msg ?: ""

                if (!pkg.isNullOrBlank()) {
                    binding.etTargetPackage.setText(pkg)
                    binding.tvInspectResult.text = SourceInspector.inspectPackageSource(
                        this@MainActivity,
                        pkg
                    )
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionInstaller = ApkSessionInstaller(this)

        // عرض معرف المصدر الحالي للتطبيق
        binding.tvCurrentInstallerId.text = "معرف المصدر المسجل: ${packageName}"

        setupListeners()
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatusUi()

        val filter = IntentFilter(InstallStatusReceiver.ACTION_UI_EVENT)
        ContextCompat.registerReceiver(
            this,
            uiEventReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(uiEventReceiver)
    }

    private fun setupListeners() {
        binding.btnGrantPermission.setOnClickListener {
            openInstallPermissionSettings()
        }

        binding.btnPickSingleApk.setOnClickListener {
            if (hasInstallPermission()) {
                pickSingleApkLauncher.launch(arrayOf("application/vnd.android.package-archive", "*/*"))
            } else {
                openInstallPermissionSettings()
            }
        }

        binding.btnPickSplitApks.setOnClickListener {
            if (hasInstallPermission()) {
                pickMultipleApksLauncher.launch(arrayOf("application/vnd.android.package-archive", "*/*"))
            } else {
                openInstallPermissionSettings()
            }
        }

        binding.btnInspectPackage.setOnClickListener {
            val targetPkg = binding.etTargetPackage.text?.toString().orEmpty()
            val report = SourceInspector.inspectPackageSource(this, targetPkg)
            binding.tvInspectResult.text = report
        }

        binding.btnInspectSelf.setOnClickListener {
            binding.etTargetPackage.setText(packageName)
            val report = SourceInspector.inspectPackageSource(this, packageName)
            binding.tvInspectResult.text = report
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { uri ->
                if (hasInstallPermission()) {
                    startSessionInstallation(listOf(uri))
                } else {
                    binding.tvInstallStatus.text = "يرجى منح صلاحية تثبيت الحزم أولاً ثم إعادة المحاولة."
                    openInstallPermissionSettings()
                }
            }
        }
    }

    private fun startSessionInstallation(uris: List<Uri>) {
        lifecycleScope.launch {
            binding.tvInstallStatus.text = "جاري تهيئة جلسة التثبيت..."
            val result = sessionInstaller.installApks(uris) { progressMsg ->
                binding.tvInstallStatus.text = progressMsg
            }

            result.onFailure { error ->
                binding.tvInstallStatus.text = "❌ خطأ أثناء إنشاء الجلسة: ${error.localizedMessage}"
            }
        }
    }

    private fun hasInstallPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    private fun updatePermissionStatusUi() {
        val granted = hasInstallPermission()
        if (granted) {
            binding.tvPermissionStatus.text = "✅ صلاحية تثبيت الحزم: مفعلة وجاهزة"
            binding.btnGrantPermission.text = "إعدادات الصلاحية"
        } else {
            binding.tvPermissionStatus.text = "⚠️ صلاحية تثبيت الحزم: غير مفعلة (مطلوبة للتثبيت)"
            binding.btnGrantPermission.text = "منح صلاحية التثبيت الآن"
        }
    }

    private fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }
}
