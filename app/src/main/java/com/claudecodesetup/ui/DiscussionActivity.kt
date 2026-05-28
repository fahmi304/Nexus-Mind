package com.claudecodesetup.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.discussion.DiscussionOrchestrator

class DiscussionActivity : ComponentActivity() {

    private val orchestrator by lazy { DiscussionOrchestrator(lifecycleScope) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = AppPreferences(this)
        setContent {
            DiscussionScreen(
                prefs = prefs,
                orchestrator = orchestrator,
                onExit = { finish() },
            )
        }
    }

    override fun onDestroy() {
        orchestrator.stop()
        super.onDestroy()
    }
}
