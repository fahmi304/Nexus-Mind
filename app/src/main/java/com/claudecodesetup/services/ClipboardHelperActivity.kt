package com.claudecodesetup.services

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle

class ClipboardHelperActivity : Activity() {

    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        // Don't read clipboard here — window has no focus yet on Android 10+.
        // Wait for onWindowFocusChanged to fire with hasFocus=true.
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true
        val cm   = getSystemService(ClipboardManager::class.java)
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        val action = if (!text.isNullOrBlank()) FloatingOverlayService.ACTION_CLIPBOARD_READY
                     else                       FloatingOverlayService.ACTION_CLIPBOARD_EMPTY
        sendBroadcast(
            Intent(action).setPackage(packageName).apply {
                if (!text.isNullOrBlank()) putExtra("text", text)
            }
        )
        finish()
    }
}
