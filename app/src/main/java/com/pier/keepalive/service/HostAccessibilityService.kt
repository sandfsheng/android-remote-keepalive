package com.pier.keepalive.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.pier.keepalive.util.DisplayPowerHelper
import com.pier.keepalive.util.SafetyRecoveryManager

class HostAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "HostAccessibility"
        const val PREFS_NAME = "remote_keepalive_prefs"
        const val KEY_POWER_BUTTON_RESTORE = "key_power_button_restore"

        @Volatile
        var isConnected: Boolean = false
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isConnected = true
        SafetyRecoveryManager.init(this)
        Log.i(TAG, "Host Accessibility Service connected. Multi-tier key safety guard ready.")
    }

    /**
     * Non-root Hardware Key Fail-Safe Interceptor:
     * 1. KEYCODE_POWER: Instantly restores normal display state.
     * 2. KEYCODE_VOLUME_UP / DOWN: Secondary physical bypass if power button conflicts.
     */
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return super.onKeyEvent(event)

        val isBlackActive = BlackoutOverlayService.isOverlayActive || DisplayPowerHelper.isPhysicalScreenOff
        if (!isBlackActive) return super.onKeyEvent(event)

        // 1. 电源键主解除通道
        if (event.keyCode == KeyEvent.KEYCODE_POWER && event.action == KeyEvent.ACTION_DOWN) {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val powerRestoreEnabled = prefs.getBoolean(KEY_POWER_BUTTON_RESTORE, true)

            if (powerRestoreEnabled) {
                Log.i(TAG, "Hardware POWER button pressed. Triggering emergency restore.")
                SafetyRecoveryManager.executeEmergencyRestore(this, "Hardware Power Key")
                return true // 拦截事件，避免系统灭屏动作
            }
        }

        // 2. 音量键硬解除旁路（音量+ 或 音量-）
        if (SafetyRecoveryManager.handleKeyEvent(event.keyCode, event.action, this)) {
            return true
        }

        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val rootNode = rootInActiveWindow ?: return

        // 自动点击 RustDesk 录屏弹窗授权
        autoConfirmMediaProjection(rootNode)

        // 自动跳过电池优化弹窗
        autoConfirmBatteryDialogs(rootNode)
    }

    private fun autoConfirmMediaProjection(root: AccessibilityNodeInfo) {
        val targetTexts = listOf(
            "Start now",
            "立即开始",
            "允许",
            "START NOW",
            "Allow",
            "确定",
            "OK"
        )

        for (text in targetTexts) {
            val nodes = root.findAccessibilityNodeInfosByText(text)
            for (node in nodes) {
                if (node.isClickable) {
                    Log.i(TAG, "Auto-clicking screen capture approval button: $text")
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return
                } else {
                    node.parent?.let { parent ->
                        if (parent.isClickable) {
                            Log.i(TAG, "Auto-clicking parent of approval button: $text")
                            parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            return
                        }
                    }
                }
            }
        }
    }

    private fun autoConfirmBatteryDialogs(root: AccessibilityNodeInfo) {
        val batteryKeywords = listOf("Allow", "允许", "无限制", "Unrestricted")
        for (kw in batteryKeywords) {
            val nodes = root.findAccessibilityNodeInfosByText(kw)
            for (node in nodes) {
                if (node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return
                }
            }
        }
    }

    fun performUnlockSwipe() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val displayMetrics = resources.displayMetrics
            val startX = (displayMetrics.widthPixels / 2).toFloat()
            val startY = (displayMetrics.heightPixels * 0.8).toFloat()
            val endY = (displayMetrics.heightPixels * 0.2).toFloat()

            val swipePath = Path().apply {
                moveTo(startX, startY)
                lineTo(startX, endY)
            }

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(swipePath, 0, 300))
                .build()

            dispatchGesture(gesture, null, null)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Host Accessibility Service interrupted.")
    }

    override fun onDestroy() {
        isConnected = false
        super.onDestroy()
    }
}
