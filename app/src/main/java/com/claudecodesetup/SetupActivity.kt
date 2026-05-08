package com.claudecodesetup

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.databinding.ActivitySetupBinding
import com.claudecodesetup.managers.BridgeManager

class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var prefs: AppPreferences
    private lateinit var bridge: BridgeManager

    private val pollHandler = Handler(Looper.getMainLooper())
    private val stepHandler = Handler(Looper.getMainLooper())
    private var polling = false

    // Holds the full paste command so "Copy again" always works.
    private var pasteCmd = ""

    private val logLines = mutableListOf<String>()

    // delay(ms), progress(0–100), label shown in the log and step indicator.
    private val setupSteps = listOf(
        Triple(0L,       5,  "Waiting for Termux to start..."),
        Triple(8_000L,   15, "Updating Termux packages..."),
        Triple(40_000L,  30, "Installing proot-distro, socat, curl..."),
        Triple(110_000L, 45, "Setting up Ubuntu Linux (~300 MB)..."),
        Triple(310_000L, 65, "Installing Node.js v20 inside Ubuntu..."),
        Triple(490_000L, 80, "Installing Claude Code..."),
        Triple(610_000L, 95, "Starting bridge services...")
    )

    private val pollRunnable = object : Runnable {
        override fun run() {
            Thread {
                val reachable = bridge.isBridgeReachable()
                runOnUiThread {
                    if (reachable) {
                        onBridgeDetected()
                    } else if (polling) {
                        pollHandler.postDelayed(this, POLL_INTERVAL_MS)
                    }
                }
            }.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)
        bridge = BridgeManager(this)

        requestNotificationPermission()
        showInitialState()

        binding.btnStartSetup.setOnClickListener { startSetup() }
        binding.btnRetry.setOnClickListener { startSetup() }
        binding.btnContinue.setOnClickListener { proceedToNext() }
        binding.btnCopyCmd.setOnClickListener { copyPasteCommand() }
    }

    override fun onResume() {
        super.onResume()
        if (binding.layoutWaiting.visibility == View.VISIBLE) {
            startPolling()
        }
    }

    override fun onPause() {
        super.onPause()
        stopPolling()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPolling()
        stopSteps()
    }

    // ─── States ──────────────────────────────────────────────────────────────

    private fun showInitialState() {
        binding.layoutInitial.visibility = View.VISIBLE
        binding.layoutWaiting.visibility = View.GONE
        binding.layoutSuccess.visibility = View.GONE
        binding.layoutError.visibility = View.GONE
    }

    private fun showWaitingState() {
        binding.layoutInitial.visibility = View.GONE
        binding.layoutWaiting.visibility = View.VISIBLE
        binding.layoutSuccess.visibility = View.GONE
        binding.layoutError.visibility = View.GONE
        binding.progressSetup.progress = 0
        binding.tvCurrentStep.text = "Waiting for Termux..."
        logLines.clear()
        binding.tvTaskLog.text = ""
    }

    private fun showSuccessState() {
        stopPolling()
        stopSteps()
        binding.layoutInitial.visibility = View.GONE
        binding.layoutWaiting.visibility = View.GONE
        binding.layoutSuccess.visibility = View.VISIBLE
        binding.layoutError.visibility = View.GONE
    }

    private fun showErrorState(msg: String) {
        stopPolling()
        stopSteps()
        binding.layoutWaiting.visibility = View.GONE
        binding.layoutError.visibility = View.VISIBLE
        binding.tvErrorMsg.text = msg
    }

    // ─── Setup flow ───────────────────────────────────────────────────────────

    private fun startSetup() {
        binding.layoutError.visibility = View.GONE
        showWaitingState()

        try {
            val scriptContent = assets.open("setup.sh").bufferedReader().readText()

            // Build the full one-liner and copy to clipboard FIRST.
            // This works regardless of whether allow-external-apps is set in Termux.
            pasteCmd = bridge.buildSetupPasteCommand(scriptContent)
            copyToClipboard(pasteCmd, showToast = false)

            // Open Termux so the user sees the terminal immediately.
            bridge.openTermuxForSetup()

            // Also try via RunCommandService — succeeds silently if allow-external-apps
            // is already enabled (e.g. after the first manual paste sets it).
            bridge.tryRunSetupViaIntent(scriptContent)

            scheduleSteps()
            startPolling()
        } catch (e: Exception) {
            showErrorState("Could not start setup: ${e.message}")
        }
    }

    // ─── Step progression ────────────────────────────────────────────────────

    private fun scheduleSteps() {
        stopSteps()
        for ((delayMs, progress, message) in setupSteps) {
            stepHandler.postDelayed({ advanceStep(progress, message) }, delayMs)
        }
    }

    private fun advanceStep(progress: Int, message: String) {
        if (binding.layoutWaiting.visibility != View.VISIBLE) return
        binding.progressSetup.progress = progress
        binding.tvCurrentStep.text = message
        logLines.add(message)
        binding.tvTaskLog.text = logLines.joinToString("\n")
        binding.scrollTaskLog.post { binding.scrollTaskLog.fullScroll(View.FOCUS_DOWN) }
    }

    private fun stopSteps() {
        stepHandler.removeCallbacksAndMessages(null)
    }

    // ─── Polling ─────────────────────────────────────────────────────────────

    private fun startPolling() {
        if (polling) return
        polling = true
        pollHandler.post(pollRunnable)
    }

    private fun stopPolling() {
        polling = false
        pollHandler.removeCallbacks(pollRunnable)
    }

    private fun onBridgeDetected() {
        stopPolling()
        stopSteps()
        binding.progressSetup.progress = 100
        logLines.add("Setup complete!")
        binding.tvTaskLog.text = logLines.joinToString("\n")
        binding.scrollTaskLog.post { binding.scrollTaskLog.fullScroll(View.FOCUS_DOWN) }
        prefs.setTermuxSetupComplete(true)
        showSuccessState()
    }

    private fun proceedToNext() {
        startActivity(Intent(this, LoginFlowActivity::class.java))
        finish()
    }

    // ─── Clipboard ───────────────────────────────────────────────────────────

    private fun copyPasteCommand() {
        if (pasteCmd.isEmpty()) return
        copyToClipboard(pasteCmd, showToast = true)
    }

    private fun copyToClipboard(text: String, showToast: Boolean) {
        val cb = getSystemService(ClipboardManager::class.java)
        cb.setPrimaryClip(ClipData.newPlainText("claudecode-setup", text))
        if (showToast) {
            Toast.makeText(this, "Command copied — paste it in Termux", Toast.LENGTH_SHORT).show()
        }
    }

    // ─── Notification permission ──────────────────────────────────────────────

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS), 42)
            }
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 5_000L
    }
}
