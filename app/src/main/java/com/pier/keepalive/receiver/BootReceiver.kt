package com.pier.keepalive.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.pier.keepalive.service.KeepAliveService
import com.pier.keepalive.util.SystemUtil

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "Device booted with action: $action. Initializing Remote KeepAlive Daemon...")

        if (Intent.ACTION_BOOT_COMPLETED == action || "android.intent.action.QUICKBOOT_POWERON" == action) {
            val serviceIntent = Intent(context, KeepAliveService::class.java).apply {
                this.action = KeepAliveService.ACTION_START
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            // Bring up Tailscale and RustDesk on cold boot
            if (SystemUtil.isPackageInstalled(context, SystemUtil.PACKAGE_TAILSCALE)) {
                SystemUtil.launchPackage(context, SystemUtil.PACKAGE_TAILSCALE)
            }
            if (SystemUtil.isPackageInstalled(context, SystemUtil.PACKAGE_RUSTDESK)) {
                SystemUtil.launchPackage(context, SystemUtil.PACKAGE_RUSTDESK)
            }
        }
    }
}
