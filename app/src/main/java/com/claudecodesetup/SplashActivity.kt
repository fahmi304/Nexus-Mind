package com.claudecodesetup

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.claudecodesetup.data.AppPreferences

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = AppPreferences(this)

        // Handle text shared from other apps (ACTION_SEND)
        val sharedText = if (intent?.action == Intent.ACTION_SEND &&
            intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)
        } else null

        val next: Class<*> = when {
            !prefs.isNodeSetupComplete()  -> SetupActivity::class.java
            !prefs.isProviderConfigured() -> com.claudecodesetup.ui.ComposeActivity::class.java
            else                          -> TerminalActivity::class.java
        }

        val nextIntent = Intent(this, next)
        if (sharedText != null && next == TerminalActivity::class.java) {
            nextIntent.putExtra("shared_text", sharedText)
        }

        startActivity(nextIntent)
        finish()
    }
}
