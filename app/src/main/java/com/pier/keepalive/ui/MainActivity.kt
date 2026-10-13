package com.pier.keepalive.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.pier.keepalive.R
import com.pier.keepalive.service.BlackoutOverlayService
import com.pier.keepalive.service.HostAccessibilityService
import com.pier.keepalive.service.KeepAliveService
import com.pier.keepalive.util.SafetyRecoveryManager
import com.pier.keepalive.util.SystemUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var btnQuickUltraBlackout: MaterialButton
    private lateinit var tvBlackoutStatus: TextView
    private lateinit var tvDimPercentValue: TextView
    private lateinit var sliderDimPercent: Slider
    private lateinit var switchTouchPassthrough: MaterialSwitch
    private lateinit var switchPowerKeyRestore: MaterialSwitch
    private lateinit var switchKeepAlive: MaterialSwitch
    private lateinit var tvTailscaleStatus: TextView
    private lateinit var tvRustdeskStatus: TextView
    private lateinit var tvLogs: TextView
    private lateinit var btnGrantOverlay: Button
    private lateinit var btnGrantWriteSettings: Button
    private lateinit var btnIgnoreBatteryOpt: Button
    private lateinit var btnGrantAccessibility: Button
    private lateinit var btnLaunchTailscale: Button
    private lateinit var btnLaunchRustdesk: Button

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            appendLog(if (granted) "通知权限已授予，后台常驻通知通道就绪。"
                      else "警告：未授予通知权限，部分系统可能静默清理前台服务。")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
            SafetyRecoveryManager.init(this)
            initViews()
            loadPreferences()
            setupListeners()
            checkNotificationPermission()
            startStatusMonitor()
            appendLog("应用启动成功，初始化完成。")
        } catch (e: Exception) {
            Log.e(TAG, "onCreate fatal error: ${e.message}", e)
            appendLogIfReady("启动异常: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            updateUiState()
        } catch (e: Exception) {
            Log.e(TAG, "onResume error: ${e.message}", e)
        }
    }

    private fun initViews() {
        btnQuickUltraBlackout = findViewById(R.id.btnQuickUltraBlackout)
        tvBlackoutStatus = findViewById(R.id.tvBlackoutStatus)
        tvDimPercentValue = findViewById(R.id.tvDimPercentValue)
        sliderDimPercent = findViewById(R.id.sliderDimPercent)
        switchTouchPassthrough = findViewById(R.id.switchTouchPassthrough)
        switchPowerKeyRestore = findViewById(R.id.switchPowerKeyRestore)
        switchKeepAlive = findViewById(R.id.switchKeepAlive)
        tvTailscaleStatus = findViewById(R.id.tvTailscaleStatus)
        tvRustdeskStatus = findViewById(R.id.tvRustdeskStatus)
        tvLogs = findViewById(R.id.tvLogs)
        btnGrantOverlay = findViewById(R.id.btnGrantOverlay)
        btnGrantWriteSettings = findViewById(R.id.btnGrantWriteSettings)
        btnIgnoreBatteryOpt = findViewById(R.id.btnIgnoreBatteryOpt)
        btnGrantAccessibility = findViewById(R.id.btnGrantAccessibility)
        btnLaunchTailscale = findViewById(R.id.btnLaunchTailscale)
        btnLaunchRustdesk = findViewById(R.id.btnLaunchRustdesk)
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences(BlackoutOverlayService.PREFS_NAME, Context.MODE_PRIVATE)
        switchPowerKeyRestore.isChecked = prefs.getBoolean(BlackoutOverlayService.KEY_POWER_BUTTON_RESTORE, true)
        switchTouchPassthrough.isChecked = prefs.getBoolean(BlackoutOverlayService.KEY_TOUCH_PASSTHROUGH, true)

        val alphaPercent = prefs.getInt(BlackoutOverlayService.KEY_DIM_ALPHA_PERCENT, 93).coerceIn(80, 99)
        sliderDimPercent.value = alphaPercent.toFloat()
        updateDimPercentLabel(alphaPercent)
    }

    private fun updateDimPercentLabel(percent: Int) {
        val tip = when {
            percent <= 88 -> "浅度灰黑 (电脑端极亮，手机微光)"
            percent in 89..94 -> "顶级灰黑 (黄金平衡！手机近乎息屏，电脑端看视频极佳)"
            else -> "极限深黑 (耗电逼近断电，电脑端画面略暗)"
        }
        tvDimPercentValue.text = "当前滤光深度：${percent}% ($tip)"
    }

    private fun setupListeners() {
        btnQuickUltraBlackout.setOnClickListener {
            try {
                if (!SystemUtil.canDrawOverlays(this)) {
                    Toast.makeText(this, "需开启悬浮窗权限才能展示省电层！", Toast.LENGTH_SHORT).show()
                    SystemUtil.requestOverlayPermission(this)
                    appendLog("拦截：缺少悬浮窗权限，已跳转系统设置页。")
                    return@setOnClickListener
                }

                if (BlackoutOverlayService.isOverlayActive) {
                    SafetyRecoveryManager.executeEmergencyRestore(this, "用户点击UI解除")
                    appendLog("已手动退出顶级灰黑模式，屏幕恢复正常。")
                } else {
                    if (!KeepAliveService.isDaemonRunning) {
                        val serviceIntent = Intent(this, KeepAliveService::class.java).apply {
                            action = KeepAliveService.ACTION_START
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(serviceIntent)
                        } else {
                            startService(serviceIntent)
                        }
                        switchKeepAlive.isChecked = true
                        appendLog("联动启动：网络锁与 CPU 常驻守护服务已同时激活。")
                    }

                    BlackoutOverlayService.startBlackout(this)
                    val alpha = sliderDimPercent.value.toInt()
                    appendLog("【顶级灰黑】已激活：硬件背光死锁为1，滤光深度=${alpha}%，触控穿透开。按电源键即可恢复！")
                }
                updateUiState()
            } catch (e: Exception) {
                Log.e(TAG, "btnQuickUltraBlackout error: ${e.message}", e)
                appendLog("操作异常: ${e.message}")
            }
        }

        sliderDimPercent.addOnChangeListener { _, value, fromUser ->
            val percent = value.toInt()
            updateDimPercentLabel(percent)
            if (fromUser) {
                try {
                    val prefs = getSharedPreferences(BlackoutOverlayService.PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit().putInt(BlackoutOverlayService.KEY_DIM_ALPHA_PERCENT, percent).apply()
                    if (BlackoutOverlayService.isOverlayActive) {
                        BlackoutOverlayService.stopBlackout(this)
                        BlackoutOverlayService.startBlackout(this)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "slider change error: ${e.message}", e)
                }
            }
        }

        switchTouchPassthrough.setOnCheckedChangeListener { _, isChecked ->
            try {
                val prefs = getSharedPreferences(BlackoutOverlayService.PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putBoolean(BlackoutOverlayService.KEY_TOUCH_PASSTHROUGH, isChecked).apply()
                appendLog("设置更新：触控全穿透 (快手/抖音刷视频) -> $isChecked")
                if (BlackoutOverlayService.isOverlayActive) {
                    BlackoutOverlayService.stopBlackout(this)
                    BlackoutOverlayService.startBlackout(this)
                }
            } catch (e: Exception) {
                Log.e(TAG, "switchTouchPassthrough error: ${e.message}", e)
            }
        }

        switchPowerKeyRestore.setOnCheckedChangeListener { _, isChecked ->
            try {
                val prefs = getSharedPreferences(BlackoutOverlayService.PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putBoolean(BlackoutOverlayService.KEY_POWER_BUTTON_RESTORE, isChecked).apply()
                appendLog("设置更新：按电源键恢复状态 -> $isChecked")
            } catch (e: Exception) {
                Log.e(TAG, "switchPowerKeyRestore error: ${e.message}", e)
            }
        }

        switchKeepAlive.setOnCheckedChangeListener { _, isChecked ->
            try {
                if (isChecked) {
                    val serviceIntent = Intent(this, KeepAliveService::class.java).apply {
                        action = KeepAliveService.ACTION_START
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                    appendLog("后台守护进程启动：持有 CPU WakeLock 与高性能 WiFi 锁。")
                } else {
                    val serviceIntent = Intent(this, KeepAliveService::class.java).apply {
                        action = KeepAliveService.ACTION_STOP
                    }
                    startService(serviceIntent)
                    appendLog("后台守护进程已关闭。")
                }
            } catch (e: Exception) {
                Log.e(TAG, "switchKeepAlive error: ${e.message}", e)
            }
        }

        btnGrantOverlay.setOnClickListener {
            try {
                SystemUtil.requestOverlayPermission(this)
                appendLog("打开悬浮窗授权页。")
            } catch (e: Exception) {
                Log.e(TAG, "btnGrantOverlay error: ${e.message}", e)
            }
        }

        btnGrantWriteSettings.setOnClickListener {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                    appendLog("打开修改系统设置权限页 (用于将硬件背光压至刻度1)。")
                }
            } catch (e: Exception) {
                Log.e(TAG, "btnGrantWriteSettings error: ${e.message}", e)
            }
        }

        btnIgnoreBatteryOpt.setOnClickListener {
            try {
                SystemUtil.requestIgnoreBatteryOptimizations(this)
                appendLog("申请加入电池优化白名单 (Bypass Doze)。")
            } catch (e: Exception) {
                Log.e(TAG, "btnIgnoreBatteryOpt error: ${e.message}", e)
            }
        }

        btnGrantAccessibility.setOnClickListener {
            try {
                SystemUtil.openAccessibilitySettings(this)
                appendLog("打开无障碍设置页 (授权后可捕获电源键与自动同意录屏)。")
            } catch (e: Exception) {
                Log.e(TAG, "btnGrantAccessibility error: ${e.message}", e)
            }
        }

        btnLaunchTailscale.setOnClickListener {
            try {
                if (SystemUtil.launchPackage(this, SystemUtil.PACKAGE_TAILSCALE)) {
                    appendLog("启动 Tailscale。")
                } else {
                    appendLog("错误：未找到 Tailscale 安装包。")
                }
            } catch (e: Exception) {
                Log.e(TAG, "btnLaunchTailscale error: ${e.message}", e)
            }
        }

        btnLaunchRustdesk.setOnClickListener {
            try {
                if (SystemUtil.launchPackage(this, SystemUtil.PACKAGE_RUSTDESK)) {
                    appendLog("启动 RustDesk。")
                } else {
                    appendLog("错误：未找到 RustDesk 安装包。")
                }
            } catch (e: Exception) {
                Log.e(TAG, "btnLaunchRustdesk error: ${e.message}", e)
            }
        }
    }

    private fun checkNotificationPermission() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkNotificationPermission skipped: ${e.message}")
        }
    }

    private fun startStatusMonitor() {
        lifecycleScope.launch {
            while (isActive) {
                try {
                    updateUiState()
                } catch (e: Exception) {
                    Log.e(TAG, "status monitor tick error: ${e.message}", e)
                }
                delay(2500L)
            }
        }
    }

    private fun updateUiState() {
        val isBlackoutActive = BlackoutOverlayService.isOverlayActive
        if (isBlackoutActive) {
            btnQuickUltraBlackout.text = "退出顶级灰黑 (点此或按电源键)"
            btnQuickUltraBlackout.setBackgroundColor(Color.parseColor("#FF5252"))
            tvBlackoutStatus.text = "当前状态：【顶级灰黑省电中】触控全穿透，快手/抖音正常推流"
            tvBlackoutStatus.setTextColor(Color.parseColor("#FF5252"))
        } else {
            btnQuickUltraBlackout.text = "进入顶级灰黑 (按电源键恢复)"
            btnQuickUltraBlackout.setBackgroundColor(Color.parseColor("#00E676"))
            tvBlackoutStatus.text = "当前状态：屏幕正常点亮"
            tvBlackoutStatus.setTextColor(Color.parseColor("#B0B0B0"))
        }

        if (switchKeepAlive.isChecked != KeepAliveService.isDaemonRunning) {
            switchKeepAlive.isChecked = KeepAliveService.isDaemonRunning
        }

        val tailscaleRunning = SystemUtil.isAppRunning(this, SystemUtil.PACKAGE_TAILSCALE)
        val tailscaleInstalled = SystemUtil.isPackageInstalled(this, SystemUtil.PACKAGE_TAILSCALE)
        when {
            tailscaleRunning -> {
                tvTailscaleStatus.text = "运行中 (活跃)"
                tvTailscaleStatus.setTextColor(Color.parseColor("#00E676"))
            }
            tailscaleInstalled -> {
                tvTailscaleStatus.text = "已安装 (就绪)"
                tvTailscaleStatus.setTextColor(Color.parseColor("#FF9100"))
            }
            else -> {
                tvTailscaleStatus.text = "未安装"
                tvTailscaleStatus.setTextColor(Color.parseColor("#FF5252"))
            }
        }

        val rustdeskRunning = SystemUtil.isAppRunning(this, SystemUtil.PACKAGE_RUSTDESK)
        val rustdeskInstalled = SystemUtil.isPackageInstalled(this, SystemUtil.PACKAGE_RUSTDESK)
        when {
            rustdeskRunning -> {
                tvRustdeskStatus.text = "运行中 (活跃)"
                tvRustdeskStatus.setTextColor(Color.parseColor("#00E676"))
            }
            rustdeskInstalled -> {
                tvRustdeskStatus.text = "已安装 (就绪)"
                tvRustdeskStatus.setTextColor(Color.parseColor("#FF9100"))
            }
            else -> {
                tvRustdeskStatus.text = "未安装"
                tvRustdeskStatus.setTextColor(Color.parseColor("#FF5252"))
            }
        }

        val overlayAllowed = SystemUtil.canDrawOverlays(this)
        btnGrantOverlay.text = if (overlayAllowed) "1. 悬浮窗权限 (已授予 ✓)" else "1. 授予悬浮窗权限 (极暗滤光必备)"
        btnGrantOverlay.setTextColor(if (overlayAllowed) Color.parseColor("#00E676") else Color.WHITE)

        val writeSettingsAllowed = Settings.System.canWrite(this)
        btnGrantWriteSettings.text = if (writeSettingsAllowed) "2. 修改系统设置 (已授予 ✓)" else "2. 授予修改系统设置 (压死硬件背光必备)"
        btnGrantWriteSettings.setTextColor(if (writeSettingsAllowed) Color.parseColor("#00E676") else Color.WHITE)

        val batteryIgnored = SystemUtil.isIgnoringBatteryOptimizations(this)
        btnIgnoreBatteryOpt.text = if (batteryIgnored) "3. 电池白名单 (已加入 ✓)" else "3. 忽略电池优化 (Doze 防休眠)"
        btnIgnoreBatteryOpt.setTextColor(if (batteryIgnored) Color.parseColor("#00E676") else Color.WHITE)

        val a11yActive = SystemUtil.isAccessibilityServiceEnabled(this, HostAccessibilityService::class.java)
        btnGrantAccessibility.text = if (a11yActive) "4. 无障碍服务 (已启用 ✓)" else "4. 开启无障碍服务 (自动同意录屏+电源键监听)"
        btnGrantAccessibility.setTextColor(if (a11yActive) Color.parseColor("#00E676") else Color.WHITE)
    }

    private fun appendLog(msg: String) {
        try {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            tvLogs.append("[$time] $msg\n")
        } catch (e: Exception) {
            Log.e(TAG, "appendLog error", e)
        }
    }

    private fun appendLogIfReady(msg: String) {
        try {
            tvLogs?.append(msg + "\n")
        } catch (_: Exception) {
        }
    }
}
