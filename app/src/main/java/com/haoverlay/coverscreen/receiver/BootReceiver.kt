package com.haoverlay.coverscreen.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.haoverlay.coverscreen.data.storage.SecureConfigManager
import com.haoverlay.coverscreen.service.CoverOverlayService

/**
 * Automatically starts the overlay service upon device boot or app update if enabled.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "BootReceiver received action: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON") {

            val configManager = SecureConfigManager.getInstance(context)
            val settings = configManager.getOverlaySettings()

            if (settings.autoStartOnBoot && settings.isServiceEnabled) {
                Log.i(TAG, "Auto-starting CoverOverlayService on boot...")
                CoverOverlayService.start(context)
            }

            // Schedule watchdog alarm for reliability
            WatchdogReceiver.scheduleWatchdog(context)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
