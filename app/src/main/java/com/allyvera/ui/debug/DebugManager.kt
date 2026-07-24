package com.allyvera.ui.debug

import com.allyvera.processing.ClassScores
import com.allyvera.processing.NsfwResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** One inference view: a detector crop (or full-frame fallback). */
data class DebugViewResult(
    val label: String,
    val file: File,
    val scores: ClassScores,
    val timeMs: Double,
)

data class DebugScreenshotItem(
    val name: String,
    val file: File,
    val views: List<DebugViewResult> = emptyList(),
    val scores: NsfwResult? = null,
    val modelName: String = "",
    val accelerator: String = "",
    val totalTimeMs: Double = 0.0,
    val estimatedBatteryMah: Double = 0.0,
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
