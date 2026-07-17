package com.allyvera.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import com.allyvera.frame.FrameCache
import com.allyvera.ui.debug.DebugManager
import com.allyvera.ui.debug.DebugScreenshotItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Analysis layer. Runs Yahoo OpenNSFW on the whole screen AND on 4 quadrant slices, taking the
 * MAX nsfw across all 5 — maximizing coverage/recall (a sidebar ad or footer banner gets its own
 * slice). At a 15s capture cadence the ~5 inferences are negligible (~60ms/frame).
 *
 * Model: Yahoo OpenNSFW (binary safe/nsfw). Input is always 224x224, BGR, mean-subtracted
 * [104,117,123]. Whole-frame is letterboxed (aspect-preserving, no distortion); each quadrant is
 * scaled to 224x224.
 */
object ScreenshotProcessor {

    private const val TAG = "ScreenshotProcessor"
    private const val SCREENSHOT_DIR = "screenshots"
    private const val MODEL_SIZE = 224
    private const val MODEL_FILENAME = "nsfw2.tflite"
    private const val NUM_CLASSES = 2   // [safe, nsfw]

    private const val MEAN_B = 104f
    private const val MEAN_G = 117f
    private const val MEAN_R = 123f

    private const val CPU_CURRENT_A = 0.5   // 500 mA estimate for battery drain

    private var interpreter: Interpreter? = null
    private val interpreterMutex = Mutex()

    private suspend fun getInterpreter(context: Context): Interpreter {
        interpreter?.let { return it }
        return interpreterMutex.withLock {
            interpreter ?: run {
                val modelBuffer = loadModelFile(context)
                val options = Interpreter.Options().apply { setNumThreads(4) }
                Interpreter(modelBuffer, options).also {
                    interpreter = it
                    Log.i(TAG, "TFLite interpreter loaded (CPU): $MODEL_FILENAME")
                }
            }
        }
    }

    private fun loadModelFile(context: Context): ByteBuffer {
        return context.assets.open(MODEL_FILENAME).use { input ->
            val bytes = input.readBytes()
            ByteBuffer.allocateDirect(bytes.size).apply {
                order(ByteOrder.nativeOrder())
                put(bytes)
                rewind()
            }
        }
    }

    fun release() = runBlockingRelease()

    private suspend fun releaseSuspended() = interpreterMutex.withLock {
        interpreter?.close()
        interpreter = null
    }

    private fun runBlockingRelease() {
        kotlinx.coroutines.runBlocking { releaseSuspended() }
    }

    /** Run a single bitmap through the model; returns the raw NSFW score (0..1). */
    private suspend fun inferScore(modelInput: Bitmap, interp: Interpreter): Float =
        withContext(Dispatchers.Default) {
            val input = bitmapToInput(modelInput)
            val output = Array(1) { FloatArray(NUM_CLASSES) }
            interpreterMutex.withLock { interp.run(input, output) }
            output[0][1]   // [safe, nsfw] -> nsfw
        }

    // ------------------------------------------------------------------
    // Preprocessing
    // ------------------------------------------------------------------

    /** Letterbox the whole screenshot into a 224x224 square (aspect-preserving, no distortion). */
    private fun preprocessWhole(bitmap: Bitmap): Bitmap {
        val (w, h) = bitmap.width to bitmap.height
        val scale = MODEL_SIZE.toFloat() / maxOf(w, h)
        val tw = (w * scale).toInt().coerceAtLeast(1)
        val th = (h * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, tw, th, true)
        val square = Bitmap.createBitmap(MODEL_SIZE, MODEL_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(square)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(scaled, (MODEL_SIZE - tw) / 2f, (MODEL_SIZE - th) / 2f, null)
        scaled.recycle()
        return square
    }

    /** 4 equal quadrants of the SCREEN, each scaled to 224x224 (true spatial coverage). */
    private fun preprocessQuadrants(bitmap: Bitmap): List<Bitmap> {
        val halfW = bitmap.width / 2
        val halfH = bitmap.height / 2
        val quads = listOf(
            Quad(0, 0, halfW, halfH),
            Quad(halfW, 0, bitmap.width - halfW, halfH),
            Quad(0, halfH, halfW, bitmap.height - halfH),
            Quad(halfW, halfH, bitmap.width - halfW, bitmap.height - halfH)
        )
        return quads.map { (x, y, w, h) ->
            val patch = Bitmap.createBitmap(bitmap, x, y, w, h)
            val scaled = Bitmap.createScaledBitmap(patch, MODEL_SIZE, MODEL_SIZE, true)
            patch.recycle()
            scaled
        }
    }

    private data class Quad(val x: Int, val y: Int, val w: Int, val h: Int)

    // ------------------------------------------------------------------
    // Input tensor: BGR + mean subtract [104,117,123]
    // ------------------------------------------------------------------

    private fun bitmapToInput(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(MODEL_SIZE * MODEL_SIZE * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())
        val pixels = IntArray(MODEL_SIZE * MODEL_SIZE)
        bitmap.getPixels(pixels, 0, MODEL_SIZE, 0, 0, MODEL_SIZE, MODEL_SIZE)
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            buffer.putFloat((b - MEAN_B))
            buffer.putFloat((g - MEAN_G))
            buffer.putFloat((r - MEAN_R))
        }
        buffer.rewind()
        return buffer
    }

    private fun ensureSoftwareBitmap(bitmap: Bitmap): Bitmap {
        if (bitmap.config != Bitmap.Config.HARDWARE) return bitmap
        return bitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: error("Unable to copy HARDWARE bitmap to software")
    }

    // ------------------------------------------------------------------
    // Main processing
    // ------------------------------------------------------------------

    suspend fun process(context: Context, framePath: String) {
        withContext(Dispatchers.IO) {
            val bitmap = BitmapFactory.decodeFile(framePath)
            if (bitmap == null) {
                Log.e(TAG, "Unable to decode cached frame $framePath")
                FrameCache.delete(framePath)
                return@withContext
            }
            try {
                processBitmap(context, bitmap)
            } finally {
                FrameCache.delete(framePath)
            }
        }
    }

    private suspend fun processBitmap(context: Context, bitmap: Bitmap) {
        val dir = File(context.filesDir, SCREENSHOT_DIR)
        if (!dir.exists()) dir.mkdirs()
        val timestamp = System.currentTimeMillis()
        val filename = "screenshot_$timestamp.jpg"
        val file = File(dir, filename)
        val modelInputFile = File(dir, "model_input_$timestamp.jpg")
        val tileDir = File(dir, "tiles_$timestamp")
        if (!tileDir.exists()) tileDir.mkdirs()

        val software = ensureSoftwareBitmap(bitmap)

        // Save full-resolution screenshot
        FileOutputStream(file).use { out -> software.compress(Bitmap.CompressFormat.JPEG, 80, out) }

        // Whole-frame (letterboxed) input, saved for debug
        val whole = preprocessWhole(software)
        FileOutputStream(modelInputFile).use { out -> whole.compress(Bitmap.CompressFormat.JPEG, 95, out) }

        val interp = getInterpreter(context)
        val totalStartNanos = SystemClock.elapsedRealtimeNanos()

        // Always run whole-frame + 4 quadrants, take the max across all 5.
        val wholeNsfw = inferScore(whole, interp)
        val quadrants = preprocessQuadrants(software)
        val tileFiles = mutableListOf<File>()
        val tileScores = mutableListOf<Float>()
        var finalNsfw = wholeNsfw
        quadrants.forEachIndexed { index, quad ->
            val tileFile = File(tileDir, "tile_$index.jpg")
            FileOutputStream(tileFile).use { out -> quad.compress(Bitmap.CompressFormat.JPEG, 95, out) }
            tileFiles.add(tileFile)
            val s = inferScore(quad, interp)
            tileScores.add(s)
            finalNsfw = maxOf(finalNsfw, s)
            quad.recycle()
        }

        val totalDurationNanos = SystemClock.elapsedRealtimeNanos() - totalStartNanos
        val totalDurationMs = totalDurationNanos / 1_000_000.0
        val estimatedBatteryMah = (CPU_CURRENT_A * totalDurationMs) / 3600.0
        val result = NsfwResult(nsfw = finalNsfw)

        Log.i(TAG, "🔬 Inference stats for $filename")
        Log.i(TAG, "   Source: ${software.width}x${software.height} -> whole(letterbox) + 4 quadrants, max-aggregate")
        Log.i(TAG, "   Whole=%.3f  Quads=%s".format(wholeNsfw, tileScores.joinToString(", ") { "%.3f".format(it) }))
        Log.i(TAG, "   Final NSFW=%.3f  Safe=%.3f  (%s)".format(result.nsfw, result.safe, result.classification))
        Log.i(TAG, "   Total time: %.1f ms".format(totalDurationMs))
        Log.i(TAG, "   Estimated battery drain: ~%.3f mAh".format(estimatedBatteryMah))

        whole.recycle()
        if (software !== bitmap) software.recycle()
        bitmap.recycle()

        DebugManager.addScreenshot(
            DebugScreenshotItem(
                name = filename,
                file = file,
                modelInputFile = modelInputFile,
                scores = result,
                tiled = true,
                wholeFrameNsfw = wholeNsfw,
                tileFiles = tileFiles,
                tileScores = tileScores,
                totalTimeMs = totalDurationMs,
                estimatedBatteryMah = estimatedBatteryMah
            )
        )
    }
}
