package com.allyvera.processing

import android.content.Context
import android.util.Log
import com.allyvera.frame.FrameBus
import com.allyvera.frame.SensorRegistry
import com.allyvera.frame.FrameSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The "brain". Owns the single capture cadence and commands whichever sensor is currently
 * active via [SensorRegistry]. It is the only thing that decides *when* to capture; sensors
 * merely execute [com.allyvera.frame.CaptureController.capture] on command and emit a path.
 * Frames from the bus are handed to [ScreenshotProcessor], which decides what to do with them.
 */
object ProcessingCoordinator {

    private const val TAG = "ProcessingCoordinator"
    private const val CAPTURE_INTERVAL_MS = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)

    fun start(context: Context) {
        if (started.compareAndSet(false, true).not()) return

        // Consumer: turn emitted frame paths into analysis.
        FrameBus.frames
            .onEach { frame ->
                try {
                    ScreenshotProcessor.process(context.applicationContext, frame.path)
                } catch (exception: Exception) {
                    Log.e(TAG, "Failed to process frame from ${frame.source}", exception)
                }
            }
            .launchIn(scope)

        // Clock: command the active sensor on a fixed cadence.
        val appContext = context.applicationContext
        scope.launch {
            while (isActive) {
                val controller = SensorRegistry.activeController
                if (controller != null && DeviceCaptureGate.canCapture(appContext)) {
                    try {
                        controller.capture()
                    } catch (exception: Exception) {
                        Log.e(TAG, "Capture failed for ${controller.source}", exception)
                    }
                }
                delay(CAPTURE_INTERVAL_MS)
            }
        }

        Log.i(TAG, "Coordinator started (commanding ${FrameSource.values().joinToString()})")
    }

    fun stop() {
        scope.cancel()
        ScreenshotProcessor.release()
        started.set(false)
    }
}
