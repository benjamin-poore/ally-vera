package com.allyvera.ui.debug

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow

class DebugViewModel : ViewModel() {
    val screenshots: StateFlow<List<ScreenshotItem>> = DebugManager.screenshots
}
