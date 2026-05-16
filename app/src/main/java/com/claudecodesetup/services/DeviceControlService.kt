package com.claudecodesetup.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class DeviceControlService : AccessibilityService() {

    companion object {
        private const val TAG = "DeviceControlService"
        var instance: DeviceControlService? = null

        fun isAvailable() = instance != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    }

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "DeviceControlService connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /**
     * Silently captures the screen using AccessibilityService API (Android 11+).
     */
    fun takeScreenshot(onDone: (path: String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onDone(null)
            return
        }
        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            Executors.newSingleThreadExecutor(),
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    result.hardwareBuffer.close()
                    if (bitmap == null) { onDone(null); return }
                    try {
                        val out = File(filesDir, "overlay_screenshot.jpg")
                        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                        bitmap.recycle()
                        Handler(Looper.getMainLooper()).post { onDone(out.absolutePath) }
                    } catch (e: Exception) {
                        Log.e(TAG, "screenshot save failed", e)
                        Handler(Looper.getMainLooper()).post { onDone(null) }
                    }
                }
                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "takeScreenshot failed, errorCode=$errorCode")
                    Handler(Looper.getMainLooper()).post { onDone(null) }
                }
            }
        )
    }

    /** Walk accessibility tree and return readable text representation. */
    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "Screen not accessible (enable Accessibility Service)"
        val sb = StringBuilder()
        try { walkNode(root, sb, 0) } catch(_: Exception) {}
        root.recycle()
        return sb.toString().take(8000).ifEmpty { "(screen appears empty or inaccessible)" }
    }

    private fun walkNode(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val label = text.ifEmpty { desc }
        if (label.isNotEmpty() && label.length < 500) {
            val cls = node.className?.toString()?.substringAfterLast('.') ?: ""
            sb.append("  ".repeat(depth.coerceAtMost(6)))
            if (cls.isNotEmpty() && cls != "View") sb.append("[$cls] ")
            sb.append(label).append('\n')
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try { walkNode(child, sb, depth + 1) } catch(_: Exception) {}
            child.recycle()
        }
    }

    /** Dispatch a tap gesture at (x, y) screen coordinates. */
    fun tap(x: Float, y: Float, onDone: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) { onDone(false); return }
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription) { onDone(true) }
            override fun onCancelled(g: GestureDescription) { onDone(false) }
        }, null)
    }

    /** Type text into the currently focused input field. */
    fun typeText(text: String): Boolean {
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return try {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args).also { node.recycle() }
        } catch(_: Exception) { node.recycle(); false }
    }

    /** Launch an app by package name. */
    fun openApp(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch(e: Exception) {
            Log.e(TAG, "openApp $packageName failed", e)
            false
        }
    }
}
