package com.pier.keepalive.util

import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

object RootUtil {

    private const val TAG = "RootUtil"
    private var cachedBacklightPath: String? = null
    private var originalBrightness: String? = null

    fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readLine()
            process.waitFor()
            output != null && output.contains("uid=0")
        } catch (e: Exception) {
            Log.d(TAG, "Root check failed: ${e.message}")
            false
        }
    }

    fun executeRootCommand(command: String): Pair<Int, String> {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val sb = java.lang.StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line).append("\n")
            }
            val exitCode = process.waitFor()
            Pair(exitCode, sb.toString().trim())
        } catch (e: Exception) {
            Log.e(TAG, "Execute root command failed: $command", e)
            Pair(-1, e.message ?: "Unknown error")
        }
    }

    /**
     * Resolves physical display backlight sysfs path dynamically across different SoCs:
     * Qualcomm, MediaTek, Exynos, Tensor.
     */
    private fun findBacklightPath(): String? {
        if (cachedBacklightPath != null) return cachedBacklightPath

        val candidates = listOf(
            "/sys/class/backlight/panel0-backlight/brightness",
            "/sys/class/backlight/sprd_backlight/brightness",
            "/sys/class/leds/lcd-backlight/brightness",
            "/sys/class/backlight/s6e3ha2/brightness",
            "/sys/class/backlight/s6e8aa0/brightness"
        )

        for (path in candidates) {
            val result = executeRootCommand("[ -f $path ] && echo 1 || echo 0")
            if (result.second == "1") {
                cachedBacklightPath = path
                return path
            }
        }

        // Generic fallback search via find
        val searchResult = executeRootCommand("find /sys/class/backlight /sys/class/leds -name brightness 2>/dev/null | head -n 1")
        if (searchResult.first == 0 && searchResult.second.isNotEmpty()) {
            cachedBacklightPath = searchResult.second
            return searchResult.second
        }

        return null
    }

    /**
     * Shuts down physical display backlight to 0 while keeping SurfaceFlinger
     * and VirtualDisplay rendering intact. Saves 100% of display power on LCD/OLED.
     */
    fun setHardwareBacklightOff(): Boolean {
        val path = findBacklightPath() ?: return false
        try {
            if (originalBrightness == null) {
                val current = executeRootCommand("cat $path")
                if (current.first == 0 && current.second.isNotEmpty()) {
                    originalBrightness = current.second
                }
            }
            val result = executeRootCommand("echo 0 > $path")
            return result.first == 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cut hardware backlight", e)
            return false
        }
    }

    /**
     * Restores physical display backlight back to normal.
     */
    fun restoreHardwareBacklight(): Boolean {
        val path = findBacklightPath() ?: return false
        val target = originalBrightness ?: "128"
        val result = executeRootCommand("echo $target > $path")
        return result.first == 0
    }

    /**
     * Limits display refresh rate to 30Hz or 60Hz to reduce GPU compositor load
     * during headless remote control.
     */
    fun setLowRefreshRate(): Boolean {
        val res1 = executeRootCommand("settings put system peak_refresh_rate 30.0")
        val res2 = executeRootCommand("settings put system min_refresh_rate 30.0")
        return res1.first == 0 && res2.first == 0
    }

    fun restoreRefreshRate(): Boolean {
        val res1 = executeRootCommand("settings delete system peak_refresh_rate")
        val res2 = executeRootCommand("settings delete system min_refresh_rate")
        return res1.first == 0 && res2.first == 0
    }
}
