package com.pier.keepalive.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pier.keepalive.R
import com.pier.keepalive.ui.MainActivity
import com.pier.keepalive.util.SafetyRecoveryManager

class BlackoutOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var blackoutView: View? = null
    private var lastTapTime: Long = 0L
    private var powerScreenReceiver: BroadcastReceiver? = null
    private var originalSystemBrightness: Int = 128
    private var originalBrightnessMode: Int = Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC

    companion object {
        private const val TAG = "BlackoutOverlayService"
        private const val CHANNEL_ID = "channel_blackout_overlay"
        private const val NOTIFICATION_ID = 1002

        const val ACTION_START = "com.pier.keepalive.ACTION_START_BLACKOUT"
        const val ACTION_STOP = "com.pier.keepalive.ACTION_STOP_BLACKOUT"

        const val PREFS_NAME = "remote_keepalive_prefs"
        const val KEY_POWER_BUTTON_RESTORE = "key_power_button_restore"
        const val KEY_DOUBLE_TAP_RESTORE = "key_double_tap_restore"
        const val KEY_TOUCH_PASSTHROUGH = "key_touch_passthrough"
        const val KEY_DIM_ALPHA_PERCENT = "key_dim_alpha_percent"

        @Volatile
        var isOverlayActive: Boolean = false
            private set

        fun startBlackout(context: Context) {
            try {
                val intent = Intent(context, BlackoutOverlayService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start blackout service: ${e.message}")
            }
        }

        fun stopBlackout(context: Context) {
            try {
                val intent = Intent(context, BlackoutOverlayService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop blackout service: ${e.message}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            createNotificationChannel()
            registerScreenStateReceiver()
            backupAndEnforceSystemBrightness()
        } catch (e: Exception) {
            Log.e(TAG, "onCreate error: ${e.message}", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_STOP -> {
                    removeBlackoutOverlay()
                    restoreSystemBrightness()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                else -> {
                    startForeground(NOTIFICATION_ID, buildNotification())
                    showBlackoutOverlay()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand error: ${e.message}", e)
        }
        return START_STICKY
    }

    private fun backupAndEnforceSystemBrightness() {
        try {
            if (Settings.System.canWrite(this)) {
                originalSystemBrightness = Settings.System.getInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    128
                )
                originalBrightnessMode = Settings.System.getInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                )

                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    1
                )
                Log.i(TAG, "Hardware brightness clamped to floor 1.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clamp system brightness: ${e.message}")
        }
    }

    private fun restoreSystemBrightness() {
        try {
            if (Settings.System.canWrite(this)) {
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    originalSystemBrightness
                )
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    originalBrightnessMode
                )
                Log.i(TAG, "Restored original system brightness: $originalSystemBrightness")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore system brightness: ${e.message}")
        }
    }

    private fun registerScreenStateReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }

        powerScreenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                try {
                    val action = intent?.action ?: return
                    val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val powerRestoreEnabled = prefs.getBoolean(KEY_POWER_BUTTON_RESTORE, true)

                    if (!powerRestoreEnabled || !isOverlayActive) return

                    if (action == Intent.ACTION_SCREEN_OFF) {
                        Log.i(TAG, "SCREEN_OFF caught. SafetyRecoveryManager restoring display...")
                        SafetyRecoveryManager.executeEmergencyRestore(this@BlackoutOverlayService, "Power Button Screen Off")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "screen receiver error: ${e.message}")
                }
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.registerReceiver(
                    this,
                    powerScreenReceiver!!,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
            } else {
                registerReceiver(powerScreenReceiver, filter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register screen state receiver: ${e.message}")
        }
    }

    private fun unregisterScreenStateReceiver() {
        powerScreenReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister receiver: ${e.message}")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_blackout_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_blackout_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, BlackoutOverlayService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("顶级灰黑省电中 (快手/抖音远控运行)")
            .setContentText("硬件背光压至极限，触控全穿透。按电源键即可恢复正常。")
            .setSmallIcon(android.R.drawable.ic_lock_power_off)
            .setOngoing(true)
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "退出省电", pendingStop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showBlackoutOverlay() {
        if (blackoutView != null) return

        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val alphaPercent = prefs.getInt(KEY_DIM_ALPHA_PERCENT, 93).coerceIn(80, 99)
            val touchPassthrough = prefs.getBoolean(KEY_TOUCH_PASSTHROUGH, true)
            val doubleTapEnabled = prefs.getBoolean(KEY_DOUBLE_TAP_RESTORE, true)

            var windowFlags = (
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )

            if (touchPassthrough) {
                windowFlags = windowFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }

            val layoutParams = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                format = PixelFormat.TRANSLUCENT
                gravity = Gravity.TOP or Gravity.START
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
                screenBrightness = 0.001f
                flags = windowFlags
            }

            val alphaValue = (alphaPercent / 100.0 * 255.0).toInt().coerceIn(0, 255)
            val ultraDimColor = Color.argb(alphaValue, 0, 0, 0)

            val overlay = View(this).apply {
                setBackgroundColor(ultraDimColor)

                if (!touchPassthrough && doubleTapEnabled) {
                    setOnTouchListener { _, event ->
                        if (event.action == MotionEvent.ACTION_DOWN) {
                            val screenWidth = resources.displayMetrics.widthPixels
                            val touchX = event.rawX
                            val touchY = event.rawY
                            if (touchX > (screenWidth - 250) && touchY < 250) {
                                val now = System.currentTimeMillis()
                                if (now - lastTapTime < 500) {
                                    stopBlackout(this@BlackoutOverlayService)
                                    return@setOnTouchListener true
                                }
                                lastTapTime = now
                            }
                        }
                        false
                    }
                }
            }

            windowManager?.addView(overlay, layoutParams)
            blackoutView = overlay
            isOverlayActive = true
            Log.i(TAG, "Ultra-dim overlay attached: Alpha=${alphaPercent}%, Passthrough=$touchPassthrough")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach blackout overlay: ${e.message}", e)
        }
    }

    private fun removeBlackoutOverlay() {
        if (blackoutView != null && windowManager != null) {
            try {
                windowManager?.removeView(blackoutView)
                Log.i(TAG, "Ultra-dim overlay detached.")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay: ${e.message}")
            } finally {
                blackoutView = null
                isOverlayActive = false
            }
        }
    }

    override fun onDestroy() {
        try {
            unregisterScreenStateReceiver()
            removeBlackoutOverlay()
            restoreSystemBrightness()
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy error: ${e.message}")
        }
        super.onDestroy()
    }
}
