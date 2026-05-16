package com.claudecodesetup.workers

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.claudecodesetup.ClaudeApp
import com.claudecodesetup.R
import com.claudecodesetup.TerminalActivity
import com.claudecodesetup.services.ClaudeService

class ScheduledPromptWorker(
    private val context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val prompt = inputData.getString(KEY_PROMPT) ?: return Result.success()
        val promptId = inputData.getString(KEY_PROMPT_ID) ?: ""

        // If ClaudeService is running, send the prompt directly to the active session
        val service = ClaudeService.instance
        if (service != null) {
            service.sendInput(prompt + "\n")
            return Result.success()
        }

        // Otherwise show a notification so user can tap to open terminal with the prompt
        showPromptNotification(prompt, promptId)
        return Result.success()
    }

    private fun showPromptNotification(prompt: String, promptId: String) {
        val openIntent = Intent(context, TerminalActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(TerminalActivity.EXTRA_SCHEDULED_PROMPT, prompt)
        }
        val pi = PendingIntent.getActivity(
            context, promptId.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, ClaudeApp.CHANNEL_RESPONSE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Scheduled prompt")
            .setContentText(prompt.take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText(prompt))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(3000 + promptId.hashCode() % 100, notif)
    }

    companion object {
        const val KEY_PROMPT    = "prompt"
        const val KEY_PROMPT_ID = "prompt_id"
    }
}
