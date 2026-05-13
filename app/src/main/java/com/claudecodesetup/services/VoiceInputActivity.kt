package com.claudecodesetup.services

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent

class VoiceInputActivity : Activity() {

    companion object {
        private const val REQ_CODE = 2001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Claude…")
        }
        startActivityForResult(intent, REQ_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_CODE && resultCode == RESULT_OK) {
            val text = data?.getStringArrayListExtra(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull() ?: ""
            if (text.isNotBlank()) {
                sendBroadcast(Intent(FloatingOverlayService.ACTION_VOICE_RESULT)
                    .setPackage(packageName)
                    .putExtra("text", text))
            }
        }
        finish()
    }
}
