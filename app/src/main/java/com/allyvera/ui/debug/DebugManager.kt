package com.allyvera.ui.debug

import com.allyvera.processing.NsfwScores
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class DebugScreenshotItem(
    val name: String,
    val file: File,                 // full screenshot
    val modelInputFile: File,       // letterboxed preview (224x224)
    val scores: NsfwScores? = null,
    val patchFiles: List<File> = emptyList(),
    val totalTimeMs: Double = 0.0,
    val perPatchTimesMs: List<Double> = emptyList(),
    val estimatedBatteryMah: Double = 0.0
)

object DebugManager {
    private val _screenshots = MutableStateFlow<List<DebugScreenshotItem>>(emptyList())
    val screenshots: StateFlow<List<DebugScreenshotItem>> = _screenshots.asStateFlow()

    fun addScreenshot(item: DebugScreenshotItem) {
        _screenshots.value = _screenshots.value + item
    }

    fun clear() {
        _screenshots.value = emptyList()
    }
}