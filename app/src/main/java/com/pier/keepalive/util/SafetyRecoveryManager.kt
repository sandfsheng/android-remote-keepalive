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
 * Multi-tiered fail-safe engine to ensure the device never becomes a brick.
 *
 * - Tier 1: Power button / Volume keys
 * - Tier 2: USB charger plug-in
 * - Tier 3: UncaughtException auto-restore
 * - Tier 4: ADB emergency broadcast
 */
object SafetyRecoveryManager {

    private const val TAG = "SafetyRecovery"
    const val ACTION_EMERGENCY_RESTORE = "com.pier.keepalive.EMERGENCY_RESTORE"

    private var emergencyReceiver: BroadcastReceiver? = null
    private var isCrashHandlerInstalled = false

    fun init(context: Context) {
        try {
            installCrashHandler(context.applicationContext)
            registerEmergencyReceiver(context.applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init SafetyRecoveryManager: ${e.message}")
        }
    }

    private fun installCrashHandler(appContext: Context) {
        if (isCrashHandlerInstalled) return
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "FATAL CRASH detected. Executing emergency screen restore...", throwable)
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
            addAction(Intent.ACTION_POWER_CONNECTED)
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

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.registerReceiver(
                    context,
                    emergencyReceiver!!,
                    filter,
                    ContextCompat.RECEIVER_EXPORTED
                )
            } else {
                context.registerReceiver(emergencyReceiver, filter)
            }
            Log.i(TAG, "Emergency receiver registered.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register emergency receiver: ${e.message}")
        }
    }

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

    fun executeEmergencyRestore(context: Context, reason: String) {
        Log.w(TAG, ">>> EXECUTING EMERGENCY SCREEN RESTORE <<< Reason: $reason")
        try {
            BlackoutOverlayService.stopBlackout(context)
            DisplayPowerHelper.turnOnPhysicalDisplay(context)
            RootUtil.restoreHardwareBacklight()

            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = pm?.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "RemoteKeepAlive::EmergencyWake"
            )
            wakeLock?.acquire(3000L)
        } catch (e: Exception) {
            Log.e(TAG, "Emergency restore failed", e)
        }
    }
}
