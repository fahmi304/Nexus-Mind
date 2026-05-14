package com.claudecodesetup.services

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class MediaProjectionActivity : Activity() {

    companion object {
        private const val REQ_CODE = 1001
        private const val TAG      = "MediaProjectionAct"

        // Cached across invocations so the system dialog only appears once per session.
        // Cleared if the projection becomes invalid.
        private var cachedProjection: MediaProjection? = null
    }

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader?        = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)

        val proj = cachedProjection
        if (proj != null) {
            captureWithProjection(proj)
        } else {
            val mgr = getSystemService(MediaProjectionManager::class.java)
            startActivityForResult(mgr.createScreenCaptureIntent(), REQ_CODE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CODE || resultCode != RESULT_OK || data == null) {
            finish(); return
        }
        val mgr  = getSystemService(MediaProjectionManager::class.java)
        val proj = mgr.getMediaProjection(resultCode, data)
        cachedProjection = proj
        captureWithProjection(proj)
    }

    private fun captureWithProjection(proj: MediaProjection) {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(dm)
        val width  = dm.widthPixels
        val height = dm.heightPixels
        val dpi    = dm.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        try {
            virtualDisplay = proj.createVirtualDisplay(
                "OverlayCapture",
                width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )
        } catch (e: Exception) {
            // Cached projection was invalidated — clear it and ask again next time
            Log.w(TAG, "createVirtualDisplay failed, clearing cache: ${e.message}")
            cachedProjection = null
            finish()
            return
        }

        Handler(Looper.getMainLooper()).postDelayed({ captureAndBroadcast(width, height) }, 300)
    }

    private fun captureAndBroadcast(width: Int, height: Int) {
        var image: Image? = null
        try {
            image = imageReader?.acquireLatestImage()
            if (image == null) { finish(); return }

            val planes     = image.planes
            val buffer     = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride  = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width

            val bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)

            val outFile = File(filesDir, "overlay_screenshot.jpg")
            FileOutputStream(outFile).use { fos ->
                cropped.compress(Bitmap.CompressFormat.JPEG, 80, fos)
            }
            bitmap.recycle()
            cropped.recycle()

            sendBroadcast(
                Intent(FloatingOverlayService.ACTION_SCREENSHOT_READY)
                    .setPackage(packageName)
                    .putExtra("path", outFile.absolutePath)
            )
        } catch (e: Exception) {
            Log.e(TAG, "captureAndBroadcast error", e)
        } finally {
            image?.close()
            virtualDisplay?.release()
            virtualDisplay = null
            // Do NOT stop the MediaProjection — keep it alive for next screenshot
            finish()
        }
    }
}
