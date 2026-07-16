package com.allyvera.screenshot

import android.content.Context
import android.graphics.Bitmap
import com.allyvera.ui.debug.DebugManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ScreenshotSaver {

    private const val SCREENSHOT_DIR = "screenshots"

    suspend fun save(context: Context, bitmap: Bitmap): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, SCREENSHOT_DIR)
        if (!dir.exists()) dir.mkdirs()
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        val filename = "screenshot_${dateFormat.format(Date())}.jpg"
        val file = File(dir, filename)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        }
        DebugManager.addScreenshot(file)
        file
    }
}
