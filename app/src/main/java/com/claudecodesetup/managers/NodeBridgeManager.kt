package com.claudecodesetup.managers

import android.content.Context
import android.util.Log
import com.janeasystems.nodejs_mobile_android.NodeJsMobile
import org.json.JSONObject
import java.io.File
import java.net.Socket

/**
 * Manages the embedded Node.js bridge (replaces Termux-based BridgeManager).
 *
 * The bridge is a Node.js script (assets/nodejs-project/bridge.js) that:
 *   - On first run: installs @anthropic-ai/claude-code via npm (~50 MB download)
 *   - After install: opens TCP port 8083; each connection gets its own claude process
 *
 * Config is passed via a JSON file in filesDir so provider changes propagate
 * to new sessions without restarting Node.js.
 */
class NodeBridgeManager(private val context: Context) {

    companion object {
        const val BRIDGE_PORT = 8083
        const val BRIDGE_HOST = "127.0.0.1"
        private const val TAG = "NodeBridgeManager"

        private const val CONFIG_FILE  = "bridge_config.json"
        const val SETUP_LOG_FILE       = "setup.log"
        const val SETUP_DONE_FILE      = "setup_done"
        const val SETUP_FAILED_FILE    = "setup_failed"
    }

    // ─── Bridge reachability ──────────────────────────────────────────────────

    fun isBridgeReachable(): Boolean = try {
        Socket(BRIDGE_HOST, BRIDGE_PORT).use { true }
    } catch (_: Exception) { false }

    fun openSession(): Socket? = try {
        Socket(BRIDGE_HOST, BRIDGE_PORT)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to open session socket", e)
        null
    }

    // ─── Setup helpers ────────────────────────────────────────────────────────

    fun isSetupDone(): Boolean =
        File(context.filesDir, SETUP_DONE_FILE).exists() && isBridgeReachable()

    fun isSetupFailed(): Boolean =
        File(context.filesDir, SETUP_FAILED_FILE).exists()

    fun clearSetupFailedFlag() =
        File(context.filesDir, SETUP_FAILED_FILE).delete()

    /** Read all lines written so far by bridge.js during npm install. */
    fun readSetupLog(): String = try {
        File(context.filesDir, SETUP_LOG_FILE).readText()
    } catch (_: Exception) { "" }

    // ─── Start Node.js ────────────────────────────────────────────────────────

    /**
     * Write provider config to disk and (re)start Node.js if not already running.
     * Safe to call multiple times — NodeJsMobile ignores duplicate start calls.
     */
    fun startBridge(mode: String, apiKey: String, modelId: String, baseUrl: String) {
        writeConfig(mode, apiKey, modelId, baseUrl)
        startNodeEngine()
    }

    /**
     * Called on first launch (setup screen). Clears any previous failure marker,
     * writes an empty config (provider chosen later), and starts Node.js so
     * bridge.js can run the npm install.
     */
    fun startSetup() {
        clearSetupFailedFlag()
        File(context.filesDir, SETUP_DONE_FILE).delete()
        // Clear previous log so the UI starts fresh
        try { File(context.filesDir, SETUP_LOG_FILE).writeText("") } catch (_: Exception) {}
        startNodeEngine()
    }

    // ─── Private ──────────────────────────────────────────────────────────────

    private fun startNodeEngine() {
        try {
            NodeJsMobile.startNodeWithArguments(
                arrayOf("node", "bridge.js", context.filesDir.absolutePath)
            )
            Log.i(TAG, "NodeJsMobile started (or already running)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Node.js engine", e)
        }
    }

    private fun writeConfig(mode: String, apiKey: String, modelId: String, baseUrl: String) {
        val authToken = if (mode == "proxy") "freecc" else ""
        val effectiveBaseUrl = when (mode) {
            "proxy" -> "http://127.0.0.1:8082"
            else    -> baseUrl
        }
        val json = JSONObject().apply {
            put("mode",      mode)
            put("apiKey",    apiKey)
            put("modelId",   modelId)
            put("baseUrl",   effectiveBaseUrl)
            put("authToken", authToken)
        }
        try {
            File(context.filesDir, CONFIG_FILE).writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Could not write bridge config", e)
        }
    }
}
