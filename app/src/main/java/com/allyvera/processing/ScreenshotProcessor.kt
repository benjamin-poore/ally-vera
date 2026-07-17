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
import kotlin.math.exp
import kotlin.math.min

/**
 * Analysis layer. Owns the TFLite interpreter and the per-frame work: saving the full
 * screenshot, building the letterboxed preview, tiling + saving patches, running inference,
 * collecting timing/battery stats, and publishing a [DebugScreenshotItem] to [DebugManager].
 *
 * This is the only layer that touches the model and debug bookkeeping. Sensors never call it
 * directly — frames arrive through the [com.allyvera.frame.FrameBus] via the coordinator.
 */
object ScreenshotProcessor {

    private const val TAG = "ScreenshotProcessor"
    private const val SCREENSHOT_DIR = "screenshots"
    private const val MODEL_SIZE = 224
    private const val MODEL_FILENAME = "nsfw_mobilenetv2.tflite"
    private const val NUM_CLASSES = 5

    // Grid size: 2x2 = 4 patches
    private const val GRID_COLS = 2
    private const val GRID_ROWS = 2

    // Estimated CPU current draw (amperes) for battery drain estimate
    private const val CPU_CURRENT_A = 0.5   // 500 mA

    private var interpreter: Interpreter? = null
    // Single mutex guards the interpreter reference AND all interpreter.run calls, so release()
    // cannot close the interpreter while a frame is mid-inference.
    private val interpreterMutex = Mutex()

    // ------------------------------------------------------------------
    // Interpreter lifecycle
    // ------------------------------------------------------------------

    private suspend fun getInterpreter(context: Context): Interpreter {
        interpreter?.let { return it }
        return interpreterMutex.withLock {
            interpreter ?: run {
                val modelBuffer = loadModelFile(context)
                val options = Interpreter.Options().apply { setNumThreads(4) }
                Interpreter(modelBuffer, options).also {
                    interpreter = it
                    Log.i(TAG, "TFLite interpreter loaded (CPU)")
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

    fun release() {
        // Hold the mutex so we never close while a run() is in flight on another thread.
        runBlockingRelease()
    }

    private suspend fun releaseSuspended() = interpreterMutex.withLock {
        interpreter?.close()
        interpreter = null
    }

    private fun runBlockingRelease() {
        // release() may be called from a non-suspend context (service onDestroy). Since the
        // coordinator's scope is being cancelled, no new runs will start; waiting on the lock
        // here is safe and brief.
        kotlinx.coroutines.runBlocking { releaseSuspended() }
    }

    // ------------------------------------------------------------------
    // Core inference engine
    // ------------------------------------------------------------------

    private suspend fun inferSinglePatch(patch: Bitmap, interp: Interpreter): NsfwScores =
        withContext(Dispatchers.Default) {
            val input = bitmapToInput(patch)
            val output = Array(1) { FloatArray(NUM_CLASSES) }
            interpreterMutex.withLock {
                interp.run(input, output)
            }
            val probs = probabilities(output[0])
            NsfwScores(
                drawings = probs[0],
                hentai = probs[1],
                neutral = probs[2],
                porn = probs[3],
                sexy = probs[4]
            )
        }

    // ------------------------------------------------------------------
    // Patch creation (grid tiling)
    // ------------------------------------------------------------------

    private fun createPatches(bitmap: Bitmap): List<Bitmap> {
        val patches = mutableListOf<Bitmap>()
        val cellWidth = bitmap.width / GRID_COLS
        val cellHeight = bitmap.height / GRID_ROWS

        for (row in 0 until GRID_ROWS) {
            for (col in 0 until GRID_COLS) {
                val x = col * cellWidth
                val y = row * cellHeight
                val cropped = Bitmap.createBitmap(bitmap, x, y, cellWidth, cellHeight)
                val scaled = Bitmap.createScaledBitmap(cropped, MODEL_SIZE, MODEL_SIZE, true)
                cropped.recycle()
                patches.add(scaled)
            }
        }
        return patches
    }

    // ------------------------------------------------------------------
    // Letterboxed preview (for debugging, NOT used for inference)
    // ------------------------------------------------------------------

    private fun createLetterboxedPreview(bitmap: Bitmap): Bitmap {
        val targetSize = MODEL_SIZE
        val result = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.BLACK)   // black padding – you can change to average color if needed

        val scaleX = targetSize.toFloat() / bitmap.width
        val scaleY = targetSize.toFloat() / bitmap.height
        val scale = min(scaleX, scaleY)

        val scaledWidth = (bitmap.width * scale).toInt()
        val scaledHeight = (bitmap.height * scale).toInt()
        val left = (targetSize - scaledWidth) / 2
        val top = (targetSize - scaledHeight) / 2

        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
        canvas.drawBitmap(scaledBitmap, left.toFloat(), top.toFloat(), null)
        scaledBitmap.recycle()
        return result
    }

    // ------------------------------------------------------------------
    // Bitmap preprocessing helpers
    // ------------------------------------------------------------------

    private fun bitmapToInput(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(MODEL_SIZE * MODEL_SIZE * 3 * 4)
        buffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(MODEL_SIZE * MODEL_SIZE)
        bitmap.getPixels(pixels, 0, MODEL_SIZE, 0, 0, MODEL_SIZE, MODEL_SIZE)

        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255f)
            buffer.putFloat(((pixel shr 8) and 0xFF) / 255f)
            buffer.putFloat((pixel and 0xFF) / 255f)
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
    // Probability post‑processing
    // ------------------------------------------------------------------

    private fun probabilities(output: FloatArray): FloatArray {
        val sum = output.sum()
        val alreadyProbabilities = output.all { it.isFinite() && it in 0f..1f } &&
                sum in 0.98f..1.02f
        if (alreadyProbabilities) {
            return FloatArray(output.size) { index -> output[index] / sum }
        }

        val max = output.maxOrNull() ?: 0f
        var exponentSum = 0.0
        val exps = DoubleArray(output.size)
        for (i in output.indices) {
            exps[i] = exp((output[i] - max).toDouble())
            exponentSum += exps[i]
        }
        return FloatArray(output.size) { i -> (exps[i] / exponentSum).toFloat() }
    }

    // ------------------------------------------------------------------
    // Main processing (runs inference ONCE and logs timing)
    //
    // Consumes a cached frame file written by a sensor. Takes ownership of the cache file and
    // deletes it once processing is complete (success or failure) so nothing is left behind.
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
                // Always reclaim the sensor's cache file once we're done with it.
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

        // Folder for patches
        val patchDir = File(dir, "patches_$timestamp")
        if (!patchDir.exists()) patchDir.mkdirs()

        val software = ensureSoftwareBitmap(bitmap)

        // 1. Save full-resolution screenshot
        FileOutputStream(file).use { out ->
            software.compress(Bitmap.CompressFormat.JPEG, 80, out)
        }

        // 2. Save letterboxed preview
        val preview = createLetterboxedPreview(software)
        FileOutputStream(modelInputFile).use { out ->
            preview.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        preview.recycle()

        // 3. Prepare patches and run inference
        val patches = createPatches(software)
        val interp = getInterpreter(context)

        var best = NsfwScores()
        val totalStartNanos = SystemClock.elapsedRealtimeNanos()
        val patchDurationsMs = mutableListOf<Double>()
        val patchFiles = mutableListOf<File>()

        for ((index, patch) in patches.withIndex()) {
            // 3a. Save this patch for debugging
            val patchFile = File(patchDir, "patch_${index}_${patch.width}x${patch.height}.jpg")
            FileOutputStream(patchFile).use { out ->
                patch.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            patchFiles.add(patchFile)

            // 3b. Run inference with timing
            val patchStartNanos = SystemClock.elapsedRealtimeNanos()
            val scores = inferSinglePatch(patch, interp)
            val patchDurationNanos = SystemClock.elapsedRealtimeNanos() - patchStartNanos
            val patchDurationMs = patchDurationNanos / 1_000_000.0
            patchDurationsMs.add(patchDurationMs)

            // Aggregate (max per class)
            best = NsfwScores(
                drawings = maxOf(best.drawings, scores.drawings),
                hentai = maxOf(best.hentai, scores.hentai),
                neutral = maxOf(best.neutral, scores.neutral),
                porn = maxOf(best.porn, scores.porn),
                sexy = maxOf(best.sexy, scores.sexy)
            )

            patch.recycle()
        }

        val totalDurationNanos = SystemClock.elapsedRealtimeNanos() - totalStartNanos
        val totalDurationMs = totalDurationNanos / 1_000_000.0
        val estimatedBatteryMah = (CPU_CURRENT_A * totalDurationMs) / 3600.0

        Log.i(TAG, "🔬 Inference stats for $filename")
        Log.i(TAG, "   Patches: ${patches.size}")
        Log.i(TAG, "   Per-patch times (ms): ${patchDurationsMs.joinToString(", ", "[", "]") { "%.1f".format(it) }}")
        Log.i(TAG, "   Total time: %.1f ms".format(totalDurationMs))
        Log.i(TAG, "   Estimated battery drain: ~%.3f mAh".format(estimatedBatteryMah))
        Log.i(TAG, "   Scores: $best")

        // Clean up
        if (software !== bitmap) software.recycle()
        bitmap.recycle()

        // 4. Build debug item and pass to DebugManager
        val debugItem = DebugScreenshotItem(
            name = filename,
            file = file,
            modelInputFile = modelInputFile,
            scores = best,
            patchFiles = patchFiles,
            totalTimeMs = totalDurationMs,
            perPatchTimesMs = patchDurationsMs,
            estimatedBatteryMah = estimatedBatteryMah
        )
        DebugManager.addScreenshot(debugItem)
    }
}
