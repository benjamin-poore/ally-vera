package com.allyvera.frame

import java.util.concurrent.atomic.AtomicReference

/**
 * Holds the single currently-active capture sensor. A sensor registers itself on start and
 * unregisters on stop. The coordinator reads [active] to command captures, so the brain — not
 * the sensor — owns the capture cadence. Only one sensor is active at a time; whichever started
 * last wins (the legacy MediaProjection path is only used when the API-34 accessibility capture
 * is unavailable).
 */
object SensorRegistry {

    private val active = AtomicReference<CaptureController?>(null)

    fun register(controller: CaptureController) {
        active.set(controller)
    }

    fun unregister(controller: CaptureController) {
        active.compareAndSet(controller, null)
    }

    val activeController: CaptureController? get() = active.get()
}
