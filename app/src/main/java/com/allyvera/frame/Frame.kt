package com.allyvera.frame

enum class FrameSource { ACCESSIBILITY, MEDIA_PROJECTION }

/**
 * A frame emitted by a sensor. The sensor writes its bitmap to the cache and emits only the
 * file path, so no Bitmap crosses the async boundary (no lifetime coupling, no leak risk).
 * The processing layer owns the cache file and deletes it once consumed.
 */
data class CapturedFrame(
    val path: String,
    val source: FrameSource
)
