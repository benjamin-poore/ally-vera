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
import com.allyvera.ui.debug.DebugViewResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * SigLIP2-x256 over full frame + center square + 3 overlapping strips (30%).
 *
 * Runs model_dynamic_range.tflite on CPU/XNNPack via Interpreter.
 */
object ScreenshotProcessor {

    private const val TAG = "ScreenshotProcessor"
    private const val SCREENSHOT_DIR = "screenshots"
    private const val MODEL_FILENAME = "model_dynamic_range.tflite"
    private const val MODEL_DISPLAY_NAME = "Int8"
    private const val ACCELERATOR = "CPU/XNNPack"
    private const val MODEL_SIZE = 256
    private const val NUM_CLASSES = 5
    private const val STRIP_COUNT = 3
    private const val STRIP_OVERLAP = 0.30f
    private const val DEBUG_JPEG_QUALITY = 40
    private const val CPU_CURRENT_A = 0.5
    private const val INPUT_FLOATS = MODEL_SIZE * MODEL_SIZE * 3

    private var interpreter: Interpreter? = null
    @Volatile private var loadedAccelerator: String = "—"
    private val engineMutex = Mutex()

    val currentAccelerator: String get() = loadedAccelerator

    private suspend fun ensureInterpreter(context: Context): Interpreter {
        interpreter?.let { return it }
        return engineMutex.withLock {
            interpreter?.let { return@withLock it }
            val buffer = loadModelFile(context, MODEL_FILENAME)
            val options = Interpreter.Options().apply { setNumThreads(4) }
            val created = Interpreter(buffer, options)
            interpreter = created
            loadedAccelerator = ACCELERATOR
            Log.i(TAG, "Engine ready: file=$MODEL_FILENAME accelerator=$ACCELERATOR")
            created
        }
    }

    private fun closeInterpreterLocked() {
        try {
            interpreter?.close()
        } catch (error: Exception) {
            Log.w(TAG, "Error closing interpreter", error)
        }
        interpreter = null
    }

    private fun loadModelFile(context: Context, filename: String): MappedByteBuffer {
        return context.assets.openFd(filename).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    }

    fun release() {
        kotlinx.coroutines.runBlocking {
            engineMutex.withLock {
                closeInterpreterLocked()
                loadedAccelerator = "—"
            }
        }
    }

    suspend fun reloadModel() = engineMutex.withLock {
        closeInterpreterLocked()
        loadedAccelerator = "—"
    }

    private suspend fun inferScores(modelInput: Bitmap, interp: Interpreter): ClassScores =
        withContext(Dispatchers.Default) {
            engineMutex.withLock {
                val input = bitmapToFloatBuffer(modelInput)
                val output = Array(1) { FloatArray(NUM_CLASSES) }
                interp.run(input, output)
                ClassScores.fromLogits(output[0])
            }
        }

    // ------------------------------------------------------------------
    // Preprocessing
    // ------------------------------------------------------------------

    private fun letterboxToSquare(bitmap: Bitmap, size: Int = MODEL_SIZE): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w == size && h == size) {
            return bitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: error("Unable to copy ${size}x${size} bitmap")
        }
        val scale = size.toFloat() / maxOf(w, h)
        val tw = (w * scale).toInt().coerceAtLeast(1)
        val th = (h * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, tw, th, true)
        val square = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(square)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(scaled, (size - tw) / 2f, (size - th) / 2f, null)
        if (scaled !== bitmap) scaled.recycle()
        return square
    }

    private fun cropCenterSquare(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val side = minOf(w, h)
        val x = ((w - side) / 2).coerceAtLeast(0)
        val y = ((h - side) / 2).coerceAtLeast(0)
        return Bitmap.createBitmap(bitmap, x, y, side, side)
    }

    private fun cropStrips(bitmap: Bitmap): List<Pair<String, Bitmap>> {
        val w = bitmap.width
        val h = bitmap.height
        val landscape = w > h
        val dim = if (landscape) w else h
        val denom = STRIP_COUNT - (STRIP_COUNT - 1) * STRIP_OVERLAP
        val stripLen = (dim / denom).toInt().coerceIn(1, dim)
        val step = ((1f - STRIP_OVERLAP) * stripLen).toInt().coerceAtLeast(1)
        val starts = listOf(0, step, (dim - stripLen).coerceAtLeast(step))
        val labels = if (landscape) {
            listOf("Left", "Center", "Right")
        } else {
            listOf("Top", "Center", "Bottom")
        }
        return starts.mapIndexed { index, start ->
            val length = minOf(stripLen, dim - start)
            val patch = if (landscape) {
                Bitmap.createBitmap(bitmap, start, 0, length, h)
            } else {
                Bitmap.createBitmap(bitmap, 0, start, w, length)
            }
            labels[index] to patch
        }
    }

    private suspend fun runView(
        label: String,
        modelInput: Bitmap,
        outFile: File,
        interp: Interpreter,
        views: MutableList<DebugViewResult>,
    ): ClassScores {
        saveJpeg(modelInput, outFile)
        val t0 = SystemClock.elapsedRealtimeNanos()
        val scores = inferScores(modelInput, interp)
        val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000.0
        views.add(DebugViewResult(label, outFile, scores, ms))
        return scores
    }

    private fun fillFloatArray(bitmap: Bitmap, out: FloatArray) {
        val pixels = IntArray(MODEL_SIZE * MODEL_SIZE)
        bitmap.getPixels(pixels, 0, MODEL_SIZE, 0, 0, MODEL_SIZE, MODEL_SIZE)
        var i = 0
        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f
            out[i++] = (r - 0.5f) / 0.5f
            out[i++] = (g - 0.5f) / 0.5f
            out[i++] = (b - 0.5f) / 0.5f
        }
    }

    private fun bitmapToFloatBuffer(bitmap: Bitmap): ByteBuffer {
        val floats = FloatArray(INPUT_FLOATS)
        fillFloatArray(bitmap, floats)
        return ByteBuffer.allocateDirect(INPUT_FLOATS * 4).order(ByteOrder.nativeOrder()).apply {
            asFloatBuffer().put(floats)
            rewind()
        }
    }

    private fun ensureSoftwareBitmap(bitmap: Bitmap): Bitmap {
        if (bitmap.config != Bitmap.Config.HARDWARE) return bitmap
        return bitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: error("Unable to copy HARDWARE bitmap to software")
    }

    private fun saveJpeg(bitmap: Bitmap, file: File, quality: Int = DEBUG_JPEG_QUALITY) {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
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
                Log.i(TAG, "Processing frame $framePath")
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
        val tileDir = File(dir, "tiles_$timestamp")
        if (!tileDir.exists()) tileDir.mkdirs()

        val software = ensureSoftwareBitmap(bitmap)
        saveJpeg(software, file)

        val interp = ensureInterpreter(context)
        val accelerator = loadedAccelerator
        val totalStartNanos = SystemClock.elapsedRealtimeNanos()

        val views = mutableListOf<DebugViewResult>()
        var aggregate = ClassScores()

        val wholeLetterboxed = letterboxToSquare(software)
        aggregate = runView("Full", wholeLetterboxed, File(tileDir, "full.jpg"), interp, views)
        if (wholeLetterboxed !== software) wholeLetterboxed.recycle()

        val centerSq = cropCenterSquare(software)
        val centerLetterboxed = letterboxToSquare(centerSq)
        if (centerLetterboxed !== centerSq) centerSq.recycle()
        aggregate = aggregate.maxWith(
            runView("Square", centerLetterboxed, File(tileDir, "center_square.jpg"), interp, views)
        )
        centerLetterboxed.recycle()

        cropStrips(software).forEachIndexed { index, (label, patch) ->
            val letterboxed = letterboxToSquare(patch)
            patch.recycle()
            aggregate = aggregate.maxWith(
                runView(label, letterboxed, File(tileDir, "strip_$index.jpg"), interp, views)
            )
            letterboxed.recycle()
        }

        val totalDurationMs = (SystemClock.elapsedRealtimeNanos() - totalStartNanos) / 1_000_000.0
        val estimatedBatteryMah = (CPU_CURRENT_A * totalDurationMs) / 3600.0
        val result = NsfwResult(scores = aggregate)

        Log.i(TAG, "🔬 Inference stats for $filename ($MODEL_DISPLAY_NAME / $accelerator)")
        Log.i(
            TAG,
            "   Source: ${software.width}x${software.height} -> full + center-square + 3 strips @${(STRIP_OVERLAP * 100).toInt()}% overlap"
        )
        views.forEach { v ->
            Log.i(
                TAG,
                "   ${v.label}: top=${v.scores.topLabel} ${"%.3f".format(v.scores.topScore)}  ${"%.1f".format(v.timeMs)} ms"
            )
        }
        Log.i(
            TAG,
            "   Aggregate top=${aggregate.topLabel} ${"%.3f".format(aggregate.topScore)}  (${result.classification})"
        )
        Log.i(TAG, "   Total time: %.1f ms  ~%.3f mAh".format(totalDurationMs, estimatedBatteryMah))

        if (software !== bitmap) software.recycle()
        bitmap.recycle()

        DebugManager.addScreenshot(
            DebugScreenshotItem(
                name = filename,
                file = file,
                views = views,
                scores = result,
                modelName = "$MODEL_DISPLAY_NAME [$MODEL_FILENAME]",
                accelerator = accelerator,
                totalTimeMs = totalDurationMs,
                estimatedBatteryMah = estimatedBatteryMah,
            )
        )
    }
}
