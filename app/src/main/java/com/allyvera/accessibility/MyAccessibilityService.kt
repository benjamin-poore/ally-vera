package com.allyvera.accessibility

import com.allyvera.screenshot.ScreenshotSaver
import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.allyvera.screen.MediaProjectionConsentActivity
import kotlin.coroutines.resume

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startPeriodicScreenshots()
        } else {
            startLegacyScreenCapture()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.d(TAG, "Service destroyed")
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun startPeriodicScreenshots() {
        screenshotJob?.cancel()
        screenshotJob = serviceScope.launch {
            while (isActive) {
                takeScreenshotNow()
                delay(15_000) // 15 seconds
            }
        }
    }

    private fun startLegacyScreenCapture() {
        try {
            startActivity(MediaProjectionConsentActivity.createIntent(this))
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to launch MediaProjection consent", exception)
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
        try {
            val bitmap = withContext(Dispatchers.Default) {
                hardwareBuffer.toBitmap()
            }
            if (bitmap != null) {
                val savedFile = ScreenshotSaver.save(this, bitmap)
                Log.d(TAG, "Saved to ${savedFile.absolutePath}")
            }
        } finally {
            hardwareBuffer.close()
        }
    }

    /**
     * Converts a HardwareBuffer to a software Bitmap (ARGB_8888).
     * HARDWARE configs cannot be read with getPixels() for TFLite prep.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun HardwareBuffer.toBitmap(): Bitmap? {
        return try {
            val hardware = Bitmap.wrapHardwareBuffer(this, null) ?: return null
            val software = hardware.copy(Bitmap.Config.ARGB_8888, false)
            hardware.recycle()
            software
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "MyAccessibilityService"
    }
}