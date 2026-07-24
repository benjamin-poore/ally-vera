package com.allyvera.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
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
 * Cascade:
 * 1) EfficientDet-Lite0 finds image-like regions on the screenshot (cheap, 320²).
 * 2) Each region is cropped, letterboxed to 256², and classified with SigLIP2.
 * No strip / 3×3 path — only detector crops (full-frame letterbox if none found).
 */
object ScreenshotProcessor {

    private const val TAG = "ScreenshotProcessor"
    private const val SCREENSHOT_DIR = "screenshots"
    private const val MODEL_FILENAME = "model_dynamic_range.tflite"
    private const val MODEL_DISPLAY_NAME = "Int8"
    private const val ACCELERATOR = "CPU/XNNPack"
    private const val MODEL_SIZE = 256
    private const val TOP_OFFSET_PX = 100
    private const val BOTTOM_OFFSET_PX = 130
    private const val NUM_CLASSES = 5
    private const val DEBUG_JPEG_QUALITY = 35
    private const val CPU_CURRENT_A = 0.5
    private const val INPUT_FLOATS = MODEL_SIZE * MODEL_SIZE * 3
    private const val NUM_THREADS = 6

    private var interpreter: Interpreter? = null
    @Volatile private var loadedAccelerator: String = "—"
    private val engineMutex = Mutex()

    private val pixelScratch = IntArray(MODEL_SIZE * MODEL_SIZE)
    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(INPUT_FLOATS * 4).order(ByteOrder.nativeOrder())
    private val outputScratch = Array(1) { FloatArray(NUM_CLASSES) }

    val currentAccelerator: String get() = loadedAccelerator

    private suspend fun ensureInterpreter(context: Context): Interpreter {
        interpreter?.let { return it }
        return engineMutex.withLock {
            interpreter?.let { return@withLock it }
            val buffer = loadModelFile(context, MODEL_FILENAME)
            val options = Interpreter.Options().apply {
                setNumThreads(NUM_THREADS)
                setUseXNNPACK(true)
            }
            val created = Interpreter(buffer, options)
            interpreter = created
            loadedAccelerator = ACCELERATOR
            Log.i(TAG, "Engine ready: file=$MODEL_FILENAME accelerator=$ACCELERATOR threads=$NUM_THREADS")
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
        ImageRegionDetector.release()
    }

    suspend fun reloadModel() = engineMutex.withLock {
        closeInterpreterLocked()
        loadedAccelerator = "—"
    }

    private fun inferScoresLocked(modelInput: Bitmap, interp: Interpreter): ClassScores {
        fillInputBuffer(modelInput)
        interp.run(inputBuffer, outputScratch)
        return ClassScores.fromLogits(outputScratch[0])
    }

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

    /** Content area with system bars removed. */
    private fun contentCrop(bitmap: Bitmap): Bitmap {
        val topOffset = TOP_OFFSET_PX.coerceAtMost(bitmap.height / 4)
        val bottomOffset = BOTTOM_OFFSET_PX.coerceAtMost(bitmap.height / 4)
        val top = topOffset
        val bottom = (bitmap.height - bottomOffset).coerceAtLeast(top + 1)
        return Bitmap.createBitmap(bitmap, 0, top, bitmap.width, bottom - top)
    }

    private fun cropToBounds(bitmap: Bitmap, bounds: Rect): Bitmap? {
        val frame = Rect(0, 0, bitmap.width, bitmap.height)
        val clipped = Rect(bounds)
        if (!clipped.intersect(frame)) return null
        if (clipped.width() <= 0 || clipped.height() <= 0) return null
        return Bitmap.createBitmap(bitmap, clipped.left, clipped.top, clipped.width(), clipped.height())
    }

    private fun fillInputBuffer(bitmap: Bitmap) {
        bitmap.getPixels(pixelScratch, 0, MODEL_SIZE, 0, 0, MODEL_SIZE, MODEL_SIZE)
        inputBuffer.rewind()
        val floats = inputBuffer.asFloatBuffer()
        var i = 0
        for (pixel in pixelScratch) {
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f
            floats.put(i++, (r - 0.5f) / 0.5f)
            floats.put(i++, (g - 0.5f) / 0.5f)
            floats.put(i++, (b - 0.5f) / 0.5f)
        }
        inputBuffer.rewind()
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

    suspend fun process(context: Context, framePath: String) {
        withContext(Dispatchers.Default) {
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
        val content = contentCrop(software)

        val interp = ensureInterpreter(context)
        val accelerator = loadedAccelerator
        val totalStartNanos = SystemClock.elapsedRealtimeNanos()

        val detectStart = SystemClock.elapsedRealtimeNanos()
        val regions = ImageRegionDetector.detect(context, content)
        val detectMs = (SystemClock.elapsedRealtimeNanos() - detectStart) / 1_000_000.0

        data class CellResult(
            val label: String,
            val cell: Bitmap,
            val scores: ClassScores,
            val timeMs: Double,
            val outFile: File,
        )

        val viewsToRun = ArrayList<Pair<String, Bitmap>>()
        if (regions.isEmpty()) {
            Log.i(TAG, "No detector regions — full-frame letterbox fallback")
            viewsToRun.add("Full" to letterboxToSquare(content))
        } else {
            regions.forEachIndexed { index, bounds ->
                val crop = cropToBounds(content, bounds) ?: return@forEachIndexed
                val letterboxed = letterboxToSquare(crop)
                crop.recycle()
                viewsToRun.add("R$index" to letterboxed)
            }
        }
        content.recycle()

        val cellResults = ArrayList<CellResult>(viewsToRun.size)
        var aggregate = ClassScores()
        var inferMsTotal = 0.0

        engineMutex.withLock {
            viewsToRun.forEachIndexed { index, (label, cell) ->
                val t0 = SystemClock.elapsedRealtimeNanos()
                val scores = inferScoresLocked(cell, interp)
                val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000.0
                inferMsTotal += ms
                aggregate = aggregate.maxWith(scores)
                cellResults.add(
                    CellResult(label, cell, scores, ms, File(tileDir, "view_$index.jpg"))
                )
            }
        }

        val ioStart = SystemClock.elapsedRealtimeNanos()
        saveJpeg(software, file)
        val views = cellResults.map { cell ->
            saveJpeg(cell.cell, cell.outFile)
            cell.cell.recycle()
            DebugViewResult(cell.label, cell.outFile, cell.scores, cell.timeMs)
        }
        val ioMs = (SystemClock.elapsedRealtimeNanos() - ioStart) / 1_000_000.0

        val totalDurationMs = (SystemClock.elapsedRealtimeNanos() - totalStartNanos) / 1_000_000.0
        val estimatedBatteryMah = (CPU_CURRENT_A * totalDurationMs) / 3600.0
        val result = NsfwResult(scores = aggregate)

        Log.i(TAG, "🔬 Inference stats for $filename ($MODEL_DISPLAY_NAME / $accelerator)")
        Log.i(
            TAG,
            "   Source: ${software.width}x${software.height} -> detect=${regions.size} region(s) " +
                "detect=${"%.0f".format(detectMs)}ms infer=${"%.0f".format(inferMsTotal)}ms " +
                "io=${"%.0f".format(ioMs)}ms total=${"%.0f".format(totalDurationMs)}ms"
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
        Log.i(TAG, "   ~%.3f mAh".format(estimatedBatteryMah))

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
