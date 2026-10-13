package com.pier.keepalive.util

import android.content.Context
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.lang.reflect.Method

/**
 * DisplayPowerHelper:
 * Controls physical display panel power mode (POWER_MODE_OFF / POWER_MODE_ON)
 * without triggering system lockscreen or stopping SurfaceFlinger VirtualDisplay.
 *
 * This allows the physical screen to be 100% pitch black while RustDesk's
 * MediaProjection screen capture continues receiving full-color desktop frames!
 */
object DisplayPowerHelper {

    private const val TAG = "DisplayPowerHelper"

    // SurfaceControl power modes
    private const val POWER_MODE_OFF = 0
    private const val POWER_MODE_NORMAL = 2

    private var displayToken: IBinder? = null
    private var setDisplayPowerModeMethod: Method? = null

    @Volatile
    var isPhysicalScreenOff: Boolean = false
        private set

    init {
        initHiddenApi()
    }

    private fun initHiddenApi() {
        try {
            val surfaceControlClass = Class.forName("android.view.SurfaceControl")
            val getInternalDisplayTokenMethod = surfaceControlClass.getMethod("getInternalDisplayToken")
            displayToken = getInternalDisplayTokenMethod.invoke(null) as? IBinder

            setDisplayPowerModeMethod = surfaceControlClass.getMethod(
                "setDisplayPowerMode",
                IBinder::class.java,
                Int::class.javaPrimitiveType
            )
            Log.i(TAG, "Initialized SurfaceControl hidden API successfully.")
        } catch (e: Exception) {
            Log.w(TAG, "Reflection init for SurfaceControl failed: ${e.message}")
        }
    }

    /**
     * Shuts down physical display hardware panel completely.
     * Android framework continues rendering, so RustDesk stream is NOT affected.
     */
    fun turnOffPhysicalDisplay(context: Context): Boolean {
        // Method A: Direct reflection (Works on Android 7-9 or if app has shell/system UID)
        try {
            if (displayToken != null && setDisplayPowerModeMethod != null) {
                setDisplayPowerModeMethod?.invoke(null, displayToken, POWER_MODE_OFF)
                isPhysicalScreenOff = true
                Log.i(TAG, "Physical display turned OFF via SurfaceControl reflection.")
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct SurfaceControl invoke failed (requires Shell UID / Shizuku): ${e.message}")
        }

        // Method B: Shizuku / Shell execution (for unrooted Android 7.0+ devices)
        // Shizuku provides Shell (UID 2000) which has ACCESS_SURFACE_FLINGER permission
        val shellCmd = "service call SurfaceFlinger 1006 i32 0" // Fallback SurfaceFlinger command
        val res = RootUtil.executeRootCommand(shellCmd)
        if (res.first == 0) {
            isPhysicalScreenOff = true
            return true
        }

        return false
    }

    /**
     * Restores physical display hardware panel immediately back to full visibility.
     */
    fun turnOnPhysicalDisplay(context: Context): Boolean {
        try {
            if (displayToken != null && setDisplayPowerModeMethod != null) {
                setDisplayPowerModeMethod?.invoke(null, displayToken, POWER_MODE_NORMAL)
                isPhysicalScreenOff = false
                Log.i(TAG, "Physical display turned ON via SurfaceControl.")
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct restore invoke failed: ${e.message}")
        }

        val shellCmd = "service call SurfaceFlinger 1006 i32 2"
        val res = RootUtil.executeRootCommand(shellCmd)
        if (res.first == 0) {
            isPhysicalScreenOff = false
            return true
        }

        return false
    }
}
