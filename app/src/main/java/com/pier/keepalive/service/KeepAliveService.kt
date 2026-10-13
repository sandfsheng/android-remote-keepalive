package com.pier.keepalive.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pier.keepalive.R
import com.pier.keepalive.ui.MainActivity
import com.pier.keepalive.util.SystemUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class KeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var watchdogJob: Job? = null

    companion object {
        private const val TAG = "KeepAliveService"
        private const val CHANNEL_ID = "channel_keepalive_daemon"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.pier.keepalive.ACTION_START_KEEPALIVE"
        const val ACTION_STOP = "com.pier.keepalive.ACTION_STOP_KEEPALIVE"

        @Volatile
        var isDaemonRunning: Boolean = false
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireLocks()
        registerNetworkMonitor()
        startWatchdog()
        isDaemonRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("Daemon active | Network & CPU locked"))
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_keepalive_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_keepalive_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, KeepAliveService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Remote Host Daemon Running")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setOngoing(true)
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Daemon", pendingStop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun acquireLocks() {
        // CPU Partial WakeLock: Keep CPU at base clock, never enter deep sleep
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (wakeLock == null && pm != null) {
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "RemoteKeepAlive::CpuLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "Acquired PARTIAL_WAKE_LOCK")
        }

        // WiFi High Performance Lock: Prevent Wi-Fi radio power-save DTIM sleep
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiLock == null && wm != null) {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm.createWifiLock(mode, "RemoteKeepAlive::HighPerfWifi").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "Acquired WIFI_MODE_FULL_HIGH_PERF / LOW_LATENCY")
        }

        // Multicast Lock for local discovery
        if (multicastLock == null && wm != null) {
            multicastLock = wm.createMulticastLock("RemoteKeepAlive::MulticastLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            wakeLock = null
            if (wifiLock?.isHeld == true) wifiLock?.release()
            wifiLock = null
            if (multicastLock?.isHeld == true) multicastLock?.release()
            multicastLock = null
            Log.d(TAG, "Released all hardware locks")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing locks", e)
        }
    }

    private fun registerNetworkMonitor() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val builder = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Active network available: $network")
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "Network connection dropped: $network. Monitoring recovery...")
            }
        }

        connectivityManager?.registerNetworkCallback(builder.build(), networkCallback!!)
    }

    private fun unregisterNetworkMonitor() {
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering network callback", e)
            }
        }
    }

    /**
     * Watchdog loop: Checks Tailscale and RustDesk execution status every 20 seconds.
     * Relaunches them if terminated by OEM battery management.
     */
    private fun startWatchdog() {
        watchdogJob = serviceScope.launch {
            while (isActive) {
                val tailscaleAlive = SystemUtil.isAppRunning(applicationContext, SystemUtil.PACKAGE_TAILSCALE)
                val rustdeskAlive = SystemUtil.isAppRunning(applicationContext, SystemUtil.PACKAGE_RUSTDESK)

                Log.d(TAG, "Watchdog status: Tailscale=$tailscaleAlive, RustDesk=$rustdeskAlive")

                if (!tailscaleAlive && SystemUtil.isPackageInstalled(applicationContext, SystemUtil.PACKAGE_TAILSCALE)) {
                    Log.w(TAG, "Tailscale stopped. Reviving via launch intent...")
                    SystemUtil.launchPackage(applicationContext, SystemUtil.PACKAGE_TAILSCALE)
                }

                if (!rustdeskAlive && SystemUtil.isPackageInstalled(applicationContext, SystemUtil.PACKAGE_RUSTDESK)) {
                    Log.w(TAG, "RustDesk stopped. Reviving via launch intent...")
                    SystemUtil.launchPackage(applicationContext, SystemUtil.PACKAGE_RUSTDESK)
                }

                delay(20000L) // 20s poll
            }
        }
    }

    override fun onDestroy() {
        watchdogJob?.cancel()
        releaseLocks()
        unregisterNetworkMonitor()
        isDaemonRunning = false
        super.onDestroy()
    }
}
