package com.allyvera.frame

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Sensor-side persistence: writes a captured bitmap to the app cache directory and returns the
 * path. Keeping the bitmap on disk (rather than passing the Bitmap across the flow) decouples
 * the async producer from the consumer and removes any Bitmap lifetime/leak risk.
 *
 * The processing layer is responsible for deleting the file once it has been consumed.
 */
object FrameCache {

    private const val TAG = "FrameCache"
    private const val DIR = "frame_cache"

    fun write(context: Context, bitmap: Bitmap, source: FrameSource): String? {
        val dir = File(context.cacheDir, DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            Log.e(TAG, "Unable to create frame cache dir")
            return null
        }

        val timestamp = System.currentTimeMillis()
        val file = File(dir, "frame_${source.name.lowercase()}_$timestamp.jpg")

        return try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            file.absolutePath
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to cache frame from $source", exception)
            null
        }
    }

    fun delete(path: String) {
        try {
            File(path).delete()
        } catch (exception: Exception) {
            Log.w(TAG, "Unable to delete cached frame $path", exception)
        }
    }
}
