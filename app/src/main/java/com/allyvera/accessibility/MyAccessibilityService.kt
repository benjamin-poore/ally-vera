package com.allyvera.accessibility

import com.allyvera.ui.debug.DebugManager
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
                0,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        // Launch a coroutine to process (or use runBlocking if you prefer,
                        // but since we're already inside a suspend function, we can just call
                        // another suspend function directly after we resume the continuation)
                        serviceScope.launch {
                            processScreenshot(screenshotResult)
                            continuation.resume(Unit)
                        }
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
    private suspend fun processScreenshot(screenshotResult: ScreenshotResult) {
        val hardwareBuffer = screenshotResult.hardwareBuffer
        if (hardwareBuffer != null) {
            val bitmap = withContext(Dispatchers.Default) {
                hardwareBuffer.toBitmap()
            }
            hardwareBuffer.close()   // this is fine, HardwareBuffer has close()
            if (bitmap != null) {
                val savedFile = saveBitmap(bitmap)
                DebugManager.addScreenshot(savedFile)
                Log.d(TAG, "Saved to ${savedFile.absolutePath}")
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

    private suspend fun saveBitmap(bitmap: Bitmap): File = withContext(Dispatchers.IO) {
        val dir = File(filesDir, "screenshots")
        if (!dir.exists()) dir.mkdirs()
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        val filename = "screenshot_${dateFormat.format(Date())}.jpg"
        val file = File(dir, filename)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        }
        file   // no return keyword
    }

    companion object {
        private const val TAG = "MyAccessibilityService"
    }
}