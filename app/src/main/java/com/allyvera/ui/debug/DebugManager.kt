package com.allyvera.ui.debug

import com.allyvera.processing.NsfwResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

data class DebugScreenshotItem(
    val name: String,
    val file: File,                 // full screenshot
    val modelInputFile: File,       // the letterboxed 224x224 whole-frame input
    val scores: NsfwResult? = null,
    val tiled: Boolean = false,
    val wholeFrameNsfw: Float = 0f,
    val tileFiles: List<File> = emptyList(),
    val tileScores: List<Float> = emptyList(),
    val totalTimeMs: Double = 0.0,
    val estimatedBatteryMah: Double = 0.0
)

object DebugManager {
    private val _screenshots = MutableStateFlow<List<DebugScreenshotItem>>(emptyList())
    val screenshots: StateFlow<List<DebugScreenshotItem>> = _screenshots.asStateFlow()

    // Guards the read-modify-write on the StateFlow value, which is otherwise not safe under
    // concurrent updates from the processing pool.
    private val lock = Mutex()

    suspend fun addScreenshot(item: DebugScreenshotItem) = lock.withLock {
        _screenshots.value = _screenshots.value + item
    }

    suspend fun clear() = lock.withLock {
        _screenshots.value = emptyList()
    }
}
