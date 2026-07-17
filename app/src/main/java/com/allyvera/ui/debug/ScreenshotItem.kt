package com.allyvera.ui.debug

import com.allyvera.screenshot.NsfwScores
import java.io.File

data class ScreenshotItem(
    val name: String,
    val file: File,
    val modelInputFile: File,
    val scores: NsfwScores? = null
)