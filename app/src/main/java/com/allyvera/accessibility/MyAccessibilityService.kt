package com.allyvera.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class MyAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var screenshotJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Service connected")
        startPeriodicScreenshots()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.d(TAG, "Service destroyed")
    }

    private fun startPeriodicScreenshots() {
        screenshotJob?.cancel()
        screenshotJob = serviceScope.launch {
            while (isActive) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    takeScreenshotNow()
                } else {
                    Log.w(TAG, "Screenshots require API 34+")
                }
                delay(15_000) // 15 seconds
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun takeScreenshotNow() = suspendCancellableCoroutine<Unit> { continuation ->
        try {
            takeScreenshot(
                0,                     // displayId (primary)
                mainExecutor,          // executor
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        processScreenshot(screenshotResult)
                        continuation.resume(Unit)
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "Screenshot failed with error code: $errorCode")
                        continuation.resume(Unit)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error taking screenshot", e)
            continuation.resume(Unit)
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun processScreenshot(screenshotResult: ScreenshotResult) {
        val hardwareBuffer = screenshotResult.hardwareBuffer
        if (hardwareBuffer != null) {
            val bitmap = hardwareBuffer.toBitmap()
            hardwareBuffer.close() // free native memory
            if (bitmap != null) {
                val savedPath = saveBitmap(bitmap)
                Log.d(TAG, "Saved to $savedPath")
            } else {
                Log.e(TAG, "Failed to convert hardware buffer to bitmap")
            }
        } else {
            Log.e(TAG, "HardwareBuffer is null")
        }
    }

    /**
     * Converts a HardwareBuffer to a Bitmap (available from API 26+).
     */
    private fun HardwareBuffer.toBitmap(): Bitmap? {
        return try {
            Bitmap.wrapHardwareBuffer(this, null)
        } catch (e: Exception) {
            null
        }
    }

    private fun saveBitmap(bitmap: Bitmap): String {
        val dir = File(filesDir, "screenshots")
        if (!dir.exists()) dir.mkdirs()
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        val filename = "screenshot_${dateFormat.format(Date())}.jpg"
        val file = File(dir, filename)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        }
        return file.absolutePath
    }

    companion object {
        private const val TAG = "MyAccessibilityService"
    }
}