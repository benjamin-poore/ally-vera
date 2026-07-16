package com.allyvera.ui.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

object DebugManager {
    private val _screenshots = MutableStateFlow<List<ScreenshotItem>>(emptyList())
    val screenshots: StateFlow<List<ScreenshotItem>> = _screenshots.asStateFlow()

    fun addScreenshot(file: File) {
        _screenshots.value = _screenshots.value + ScreenshotItem(name = file.name, file = file)
    }

}
