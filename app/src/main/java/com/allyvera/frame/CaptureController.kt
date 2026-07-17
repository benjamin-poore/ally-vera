package com.allyvera.frame

/**
 * A capture sensor, as seen by the "brain" (coordinator). A sensor does nothing on its own:
 * it only snapshots on command and emits the resulting cache-file path to the bus. It owns no
 * clock and makes no decision about when or why to capture.
 */
interface CaptureController {

    val source: FrameSource

    /** Acquire one frame now, cache it, and emit its path via [FrameBus]. */
    suspend fun capture()
}
