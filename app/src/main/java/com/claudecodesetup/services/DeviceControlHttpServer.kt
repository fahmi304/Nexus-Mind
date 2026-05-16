package com.claudecodesetup.services

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Minimal HTTP server on port 8081 that exposes DeviceControlService methods
 * to bridge.js via POST /device with a JSON body.
 *
 * Request:  { "action": "read_screen" | "tap" | "type_text" | "open_app" | "screenshot",
 *             "x": <float>, "y": <float>, "text": <str>, "package": <str> }
 * Response: { "result": <string>, "error": <boolean> }
 */
class DeviceControlHttpServer {

    companion object {
        private const val TAG  = "DCHttpServer"
        const val PORT         = 8081
    }

    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        Thread({
            try {
                serverSocket = ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))
                Log.i(TAG, "Device control server listening on $PORT")
                while (running) {
                    val client = try { serverSocket!!.accept() } catch (_: Exception) { break }
                    Thread { handleClient(client) }.start()
                }
            } catch (e: Exception) {
                if (running) Log.e(TAG, "Server error", e)
            }
        }, "DCHttpServer").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 10000
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))

            // Read HTTP request headers
            var contentLength = 0
            var line = reader.readLine()
            while (!line.isNullOrEmpty()) {
                if (line.lowercase().startsWith("content-length:"))
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                line = reader.readLine()
            }

            // Read body
            val bodyChars = CharArray(contentLength)
            reader.read(bodyChars, 0, contentLength)
            val body = String(bodyChars)

            val response = try {
                val req = JSONObject(body)
                processRequest(req)
            } catch (e: Exception) {
                JSONObject().put("result", "Parse error: ${e.message}").put("error", true).toString()
            }

            val httpResponse = ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${response.toByteArray().size}\r\n" +
                "Connection: close\r\n\r\n" +
                response)
            client.getOutputStream().write(httpResponse.toByteArray())
            client.getOutputStream().flush()
        } catch (e: Exception) {
            Log.e(TAG, "Client handling error", e)
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun processRequest(req: JSONObject): String {
        val svc = DeviceControlService.instance
        if (svc == null) {
            return JSONObject()
                .put("result", "Device Control not enabled.\nGo to Android Settings → Accessibility → Claude Screen & Device Control → ON")
                .put("error", true).toString()
        }

        return when (val action = req.optString("action")) {
            "read_screen" -> {
                val text = svc.readScreen()
                JSONObject().put("result", text).put("error", false).toString()
            }
            "tap" -> {
                val x = req.optDouble("x", 0.0).toFloat()
                val y = req.optDouble("y", 0.0).toFloat()
                var result = ""
                val latch = java.util.concurrent.CountDownLatch(1)
                svc.tap(x, y) { ok ->
                    result = if (ok) "Tapped at ($x, $y)" else "Tap failed — gesture dispatch error"
                    latch.countDown()
                }
                latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                JSONObject().put("result", result).put("error", result.contains("failed")).toString()
            }
            "type_text" -> {
                val text = req.optString("text")
                val ok = svc.typeText(text)
                val msg = if (ok) "Typed: $text" else "Type failed — no focused input field"
                JSONObject().put("result", msg).put("error", !ok).toString()
            }
            "open_app" -> {
                val pkg = req.optString("package")
                val ok = svc.openApp(pkg)
                val msg = if (ok) "Opened $pkg" else "Could not open $pkg — package not found"
                JSONObject().put("result", msg).put("error", !ok).toString()
            }
            "screenshot" -> {
                var result = ""
                val latch = java.util.concurrent.CountDownLatch(1)
                svc.takeScreenshot { path ->
                    result = path ?: "Screenshot failed"
                    latch.countDown()
                }
                latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
                val isError = result == "Screenshot failed"
                JSONObject().put("result", result).put("error", isError).toString()
            }
            else -> JSONObject().put("result", "Unknown action: $action").put("error", true).toString()
        }
    }
}
