package com.allyvera.frame

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide bus that frame sensors publish to. Sensors acquire a frame and emit it here;
 * they have no knowledge of what happens to it downstream. The processing layer is the only
 * subscriber.
 *
 * extraBufferCapacity keeps a slow consumer from blocking a sensor, and drops (rather than
 * buffers unbounded) when saturated. Frames carry only a cache file path (the bitmap is already
 * persisted by the sensor), so nothing async crosses the boundary that could leak.
 */
object FrameBus {

    private val _frames = MutableSharedFlow<CapturedFrame>(
        replay = 0,
        extraBufferCapacity = 2
    )

    val frames: SharedFlow<CapturedFrame> = _frames.asSharedFlow()

    fun emit(frame: CapturedFrame): Boolean = _frames.tryEmit(frame)
}
