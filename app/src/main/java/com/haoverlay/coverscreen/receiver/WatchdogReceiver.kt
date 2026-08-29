package com.haoverlay.coverscreen.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.haoverlay.coverscreen.data.storage.SecureConfigManager
import com.haoverlay.coverscreen.service.CoverOverlayService

/**
 * Health watchdog that periodically verifies CoverOverlayService is alive,
 * mitigating aggressive Samsung One UI background killing.
 */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Watchdog ping received")

        val configManager = SecureConfigManager.getInstance(context)
        val settings = configManager.getOverlaySettings()

        if (settings.isServiceEnabled) {
            CoverOverlayService.start(context)
        }

        // Re-schedule next watchdog cycle
        scheduleWatchdog(context)
    }

    companion object {
        private const val TAG = "WatchdogReceiver"
        private const val WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes

        fun scheduleWatchdog(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, WatchdogReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                8821,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val triggerAt = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                } else {
                    alarmManager.set(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to schedule watchdog alarm", e)
            }
        }
    }
}
