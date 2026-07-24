package com.allyvera.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.allyvera.frame.CapturedFrame
import com.allyvera.frame.CaptureController
import com.allyvera.frame.FrameBus
import com.allyvera.frame.FrameCache
import com.allyvera.frame.FrameSource
import com.allyvera.frame.SensorRegistry
import com.allyvera.processing.DeviceCaptureGate
import com.allyvera.screen.MediaProjectionConsentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Dumb sensor. It does not own a clock and never decides when to capture — the coordinator
 * commands [capture] on a fixed cadence. On command it snapshots the screen, caches the bitmap
 * to a file, and emits only the path. All decisions about the data live in the processing layer.
 */
class MyAccessibilityService : AccessibilityService(), CaptureController {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    override val source: FrameSource = FrameSource.ACCESSIBILITY

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Service connected")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            SensorRegistry.register(this)
            Log.i(TAG, "Registered as capture sensor (API 34+)")
        } else {
            startLegacyScreenCapture()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        SensorRegistry.unregister(this)
        serviceScope.cancel()
        Log.i(TAG, "Service destroyed")
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override suspend fun capture() {
        captureFrame()
    }

    private fun startLegacyScreenCapture() {
        try {
            startActivity(MediaProjectionConsentActivity.createIntent(this))
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to launch MediaProjection consent", exception)
        }
    }

    /**
     * Sensor work only: acquire a frame, persist it to cache, and emit the path. The bitmap is
     * recycled once written — the processing layer reads from disk.
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun captureFrame() = suspendCancellableCoroutine<Unit> { continuation ->
        if (!DeviceCaptureGate.canCapture(this)) {
            continuation.resume(Unit)
            return@suspendCancellableCoroutine
        }
        try {
            takeScreenshot(
                0,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        val bitmap = screenshotResult.hardwareBuffer.toBitmap()
                        screenshotResult.hardwareBuffer.close()
                        if (bitmap != null) {
                            serviceScope.launch(Dispatchers.IO) {
                                val path = FrameCache.write(
                                    this@MyAccessibilityService,
                                    bitmap,
                                    FrameSource.ACCESSIBILITY
                                )
                                bitmap.recycle()
                                if (path != null) {
                                    if (!FrameBus.emit(
                                            CapturedFrame(path, FrameSource.ACCESSIBILITY)
                                        )
                                    ) {
                                        FrameCache.delete(path)
                                    }
                                }
                                continuation.resume(Unit)
                            }
                        } else {
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
