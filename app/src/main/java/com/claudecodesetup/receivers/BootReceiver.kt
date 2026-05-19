package com.claudecodesetup.receivers

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.claudecodesetup.ClaudeApp
import com.claudecodesetup.R
import com.claudecodesetup.TerminalActivity
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.services.FloatingOverlayService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = AppPreferences(context)
        if (!prefs.isNodeSetupComplete() || !prefs.isProviderConfigured()) return

        // Restart the floating overlay if it was enabled before reboot
        if (prefs.getOverlayEnabled() && Settings.canDrawOverlays(context)) {
            context.startForegroundService(
                Intent(context, FloatingOverlayService::class.java)
            )
        }

        val openIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, TerminalActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, ClaudeApp.CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Nexus Mind")
            .setContentText("Tap to resume your session")
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(2001, notification)
    }
}
