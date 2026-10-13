package com.pier.keepalive.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.pier.keepalive.service.BlackoutOverlayService

/**
 * SafetyRecoveryManager:
 * Comprehensive multi-tiered fail-safe engine to ensure the device NEVER
 * becomes an unresponsive black brick.
 *
 * Tier 1: Hardware Power Button / Volume Keys
 * Tier 2: USB Charger Plug-in Instant Recovery
 * Tier 3: Process Crash / UncaughtException Auto-Restore
 * Tier 4: Out-of-band ADB Emergency Broadcast
 * Tier 5: Cold Boot Fail-safe (Never auto-black on boot)
 */
object SafetyRecoveryManager {

    private const val TAG = "SafetyRecovery"
    const val ACTION_EMERGENCY_RESTORE = "com.pier.keepalive.EMERGENCY_RESTORE"

    private var emergencyReceiver: BroadcastReceiver? = null
    private var isCrashHandlerInstalled = false

    /**
     * Initializes global fail-safe hooks:
     * 1. UncaughtExceptionHandler (recovers screen if app crashes)
     * 2. Emergency Broadcast & Charger Plug-in Receiver
     */
    fun init(context: Context) {
        installCrashHandler(context.applicationContext)
        registerEmergencyReceiver(context.applicationContext)
    }

    private fun installCrashHandler(appContext: Context) {
        if (isCrashHandlerInstalled) return
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "FATAL CRASH detected! Executing emergency screen restore...", throwable)
            try {
                executeEmergencyRestore(appContext, "Crash Guardian")
            } catch (e: Exception) {
                Log.e(TAG, "Emergency restore in crash handler failed", e)
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
        isCrashHandlerInstalled = true
        Log.i(TAG, "Crash guardian installed.")
    }

    private fun registerEmergencyReceiver(context: Context) {
        if (emergencyReceiver != null) return

        val filter = IntentFilter().apply {
            addAction(ACTION_EMERGENCY_RESTORE)
            addAction(Intent.ACTION_POWER_CONNECTED) // 插上充电线瞬间唤醒屏幕！
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }

        emergencyReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                Log.w(TAG, "Safety trigger fired: $action")
                when (action) {
                    ACTION_EMERGENCY_RESTORE -> {
                        executeEmergencyRestore(context, "ADB Emergency Broadcast")
                    }
                    Intent.ACTION_POWER_CONNECTED -> {
                        if (BlackoutOverlayService.isOverlayActive || DisplayPowerHelper.isPhysicalScreenOff) {
                            executeEmergencyRestore(context, "USB Charger Connected")
                        }
                    }
                }
            }
        }

        // Android 13+ (API 33+) 兼容性修复：指定 RECEIVER_EXPORTED 以接收来自 ADB shell 的广播
        ContextCompat.registerReceiver(
            context,
            emergencyReceiver!!,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        Log.i(TAG, "Emergency receiver registered with RECEIVER_EXPORTED.")
    }

    /**
     * Volume Key Safety Escape:
     * If user presses Volume Up/Down while in blackout, triggers restore.
     */
    fun handleKeyEvent(keyCode: Int, action: Int, context: Context): Boolean {
        if (action == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (BlackoutOverlayService.isOverlayActive || DisplayPowerHelper.isPhysicalScreenOff) {
                    Log.i(TAG, "Volume key escape hatch triggered.")
                    executeEmergencyRestore(context, "Volume Key Escape")
                    return true
                }
            }
        }
        return false
    }

    /**
     * Global Emergency Restore Core:
     * Guaranteed cleanup across all display controllers.
     */
    fun executeEmergencyRestore(context: Context, reason: String) {
        Log.w(TAG, ">>> EXECUTING EMERGENCY SCREEN RESTORE <<< Reason: $reason")

        // 1. Terminate Blackout Overlay Service
        BlackoutOverlayService.stopBlackout(context)

        // 2. Power on physical display via SurfaceControl
        DisplayPowerHelper.turnOnPhysicalDisplay(context)

        // 3. Fallback: restore root hardware backlight if root was touched
        RootUtil.restoreHardwareBacklight()

        // 4. Force wake up the screen via PowerManager
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        @Suppress("DEPRECATION")
        val wakeLock = pm?.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "RemoteKeepAlive::EmergencyWake"
        )
        wakeLock?.acquire(3000L) // 3 seconds full wake
    }
}
