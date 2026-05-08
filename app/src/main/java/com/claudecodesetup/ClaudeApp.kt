package com.claudecodesetup

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.managers.NodeBridgeManager

class ClaudeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        // Start Node.js engine as early as possible so the bridge is warm
        // by the time the user reaches the terminal. This is a no-op if
        // Node.js is already running (NodeJsMobile ignores duplicate starts).
        val prefs = AppPreferences(this)
        if (prefs.isNodeSetupComplete()) {
            NodeBridgeManager(this).startBridge(
                prefs.getLoginMode(),
                prefs.getApiKey(),
                prefs.getModelId(),
                prefs.getBaseUrl()
            )
        }
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        NotificationChannel(
            CHANNEL_RUNNING,
            "Claude Code Running",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while Claude Code is active"
            setShowBadge(false)
        }.also { manager.createNotificationChannel(it) }

        NotificationChannel(
            CHANNEL_SETUP,
            "Setup Progress",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "First-time setup notifications"
        }.also { manager.createNotificationChannel(it) }
    }

    companion object {
        const val CHANNEL_RUNNING = "claude_running"
        const val CHANNEL_SETUP   = "claude_setup"
    }
}
