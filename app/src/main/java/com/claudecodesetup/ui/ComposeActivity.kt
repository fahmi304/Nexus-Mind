package com.claudecodesetup.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*

class ComposeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppRoot()
        }
    }
}

@Composable
private fun AppRoot() {
    var screen by remember { mutableStateOf("key") }
    var storedKey by remember { mutableStateOf("") }

    when (screen) {
        "key" -> ApiKeyScreen(
            onSuccess = { key ->
                storedKey = key
                screen = "picker"
            }
        )
        "picker" -> ModelPickerScreen(
            apiKey = storedKey,
            onBack = { screen = "key" }
        )
    }
}
