package com.pier.keepalive.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
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
        try {
            SafetyRecoveryManager.init(this)
        } catch (e: Exception) {
            Log.e(TAG, "SafetyRecoveryManager init failed: ${e.message}")
        }
        Log.i(TAG, "Host Accessibility Service connected.")
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        try {
            if (event == null) return super.onKeyEvent(event)

            val isBlackActive = BlackoutOverlayService.isOverlayActive || DisplayPowerHelper.isPhysicalScreenOff
            if (!isBlackActive) return super.onKeyEvent(event)

            if (event.keyCode == KeyEvent.KEYCODE_POWER && event.action == KeyEvent.ACTION_DOWN) {
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val powerRestoreEnabled = prefs.getBoolean(KEY_POWER_BUTTON_RESTORE, true)

                if (powerRestoreEnabled) {
                    Log.i(TAG, "Hardware POWER button pressed. Triggering emergency restore.")
                    SafetyRecoveryManager.executeEmergencyRestore(this, "Hardware Power Key")
                    return true
                }
            }

            if (SafetyRecoveryManager.handleKeyEvent(event.keyCode, event.action, this)) {
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "onKeyEvent error: ${e.message}", e)
        }
        return super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return
            val rootNode = rootInActiveWindow ?: return
            autoConfirmMediaProjection(rootNode)
            autoConfirmBatteryDialogs(rootNode)
        } catch (e: Exception) {
            Log.e(TAG, "onAccessibilityEvent error: ${e.message}", e)
        }
    }

    private fun autoConfirmMediaProjection(root: AccessibilityNodeInfo) {
        val targetTexts = listOf("Start now", "立即开始", "允许", "START NOW", "Allow", "确定", "OK")
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
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val dm = resources.displayMetrics
                val startX = (dm.widthPixels / 2).toFloat()
                val startY = (dm.heightPixels * 0.8).toFloat()
                val endY = (dm.heightPixels * 0.2).toFloat()

                val swipePath = Path().apply {
                    moveTo(startX, startY)
                    lineTo(startX, endY)
                }

                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(swipePath, 0, 300))
                    .build()

                dispatchGesture(gesture, null, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "performUnlockSwipe error: ${e.message}", e)
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
