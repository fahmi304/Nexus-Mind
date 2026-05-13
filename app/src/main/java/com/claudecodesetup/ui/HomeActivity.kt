package com.claudecodesetup.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.claudecodesetup.R
import com.claudecodesetup.SettingsActivity
import com.claudecodesetup.TerminalActivity
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.services.FloatingOverlayService

class HomeActivity : ComponentActivity() {

    private lateinit var prefs: AppPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPreferences(this)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        setContent {
            var overlayActive by remember { mutableStateOf(prefs.getOverlayEnabled()) }

            HomeScreen(
                appName = getString(R.string.app_name),
                onChatBox = {
                    if (prefs.isProviderConfigured()) startActivity(Intent(this, TerminalActivity::class.java))
                    else startActivity(Intent(this, ComposeActivity::class.java))
                },
                onTesting = {
                    if (prefs.isProviderConfigured()) startActivity(Intent(this, ModelTestActivity::class.java))
                    else startActivity(Intent(this, ComposeActivity::class.java))
                },
                onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                onProjects = { startActivity(Intent(this, ProjectManagerActivity::class.java)) },
                overlayActive = overlayActive,
                onOverlay = {
                    if (overlayActive) {
                        stopOverlay()
                        overlayActive = false
                    } else {
                        startOverlay()
                        overlayActive = prefs.getOverlayEnabled()
                    }
                }
            )
        }
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this,
                "Grant 'Display over other apps' permission, then tap Overlay again",
                Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            return
        }
        prefs.setOverlayEnabled(true)
        startForegroundService(Intent(this, FloatingOverlayService::class.java))
        Toast.makeText(this, "Overlay started — check your notification bar", Toast.LENGTH_SHORT).show()
    }

    private fun stopOverlay() {
        prefs.setOverlayEnabled(false)
        startService(Intent(this, FloatingOverlayService::class.java)
            .setAction(FloatingOverlayService.ACTION_STOP))
    }
}
