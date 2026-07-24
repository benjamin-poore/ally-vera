package com.allyvera.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Lite region proposal via EfficientDet-Lite0 (320×320).
 *
 * Input is **letterboxed** (not stretched) so landscape aspect isn't crushed. Landscape
 * frames also get left/right half passes so side-by-side gallery images aren't merged
 * into one box. Boxes are returned in original bitmap coordinates.
 */
object ImageRegionDetector {

    private const val TAG = "ImageRegionDetector"
    private const val MODEL_FILENAME = "efficientdet_lite0.tflite"
    private const val INPUT_SIZE = 320
    private const val SCORE_THRESHOLD = 0.22f
    private const val MAX_REGIONS = 8
    private const val MIN_SIDE_PX = 64
    private const val MIN_AREA_FRAC = 0.006f
    private const val BOX_PAD_FRAC = 0.04f
    private const val IOU_MERGE = 0.45f
    private const val MAX_DET = 25
    /** Landscape half-pass width as a fraction of frame (overlap between halves). */
    private const val LANDSCAPE_HALF_FRAC = 0.58f

    private var interpreter: Interpreter? = null
    private val lock = Any()
    private var inputIsFloat = false

    private data class LetterboxMeta(
        val scale: Float,
        val padX: Float,
        val padY: Float,
        val srcW: Int,
        val srcH: Int,
    )

    fun ensureReady(context: Context) {
        synchronized(lock) {
            if (interpreter != null) return
            val buffer = loadModelFile(context, MODEL_FILENAME)
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                setUseXNNPACK(true)
            }
            val created = Interpreter(buffer, options)
            inputIsFloat = created.getInputTensor(0).dataType().name.contains("FLOAT", ignoreCase = true)
            interpreter = created
            Log.i(
                TAG,
                "Ready: $MODEL_FILENAME in=${created.getInputTensor(0).shape().contentToString()} " +
                    "outs=${created.outputTensorCount} floatInput=$inputIsFloat"
            )
        }
    }

    fun release() {
        synchronized(lock) {
            try {
                interpreter?.close()
            } catch (_: Exception) {
            }
            interpreter = null
        }
    }

    fun detect(context: Context, bitmap: Bitmap): List<Rect> {
        ensureReady(context)
        if (interpreter == null) return emptyList()

        val raw = ArrayList<Pair<Rect, Float>>()
        raw += detectOnBitmap(bitmap, offsetX = 0, offsetY = 0)

        // Landscape: full letterbox pass often collapses a row of thumbs into one hit.
        // Re-scan left/right overlapping halves at higher effective resolution.
        if (bitmap.width > bitmap.height) {
            val halfW = (bitmap.width * LANDSCAPE_HALF_FRAC).toInt().coerceAtLeast(MIN_SIDE_PX)
            val left = Bitmap.createBitmap(bitmap, 0, 0, halfW, bitmap.height)
            val rightX = (bitmap.width - halfW).coerceAtLeast(0)
            val right = Bitmap.createBitmap(bitmap, rightX, 0, bitmap.width - rightX, bitmap.height)
            try {
                raw += detectOnBitmap(left, offsetX = 0, offsetY = 0)
                raw += detectOnBitmap(right, offsetX = rightX, offsetY = 0)
            } finally {
                left.recycle()
                right.recycle()
            }
        }

        val frameW = bitmap.width
        val frameH = bitmap.height
        val minArea = (frameW * frameH * MIN_AREA_FRAC).toInt()
        val filtered = raw.mapNotNull { (rect, score) ->
            val clamped = Rect(
                rect.left.coerceIn(0, frameW),
                rect.top.coerceIn(0, frameH),
                rect.right.coerceIn(0, frameW),
                rect.bottom.coerceIn(0, frameH),
            )
            val w = clamped.width()
            val h = clamped.height()
            if (w < MIN_SIDE_PX || h < MIN_SIDE_PX || w * h < minArea) null
            else clamped to score
        }

        val merged = mergeOverlapping(filtered)
            .sortedByDescending { (rect, score) -> score * rect.width() * rect.height() }
            .take(MAX_REGIONS)
            .map { it.first }

        Log.i(
            TAG,
            "detect: proposals=${raw.size} kept=${merged.size} " +
                "on ${frameW}x${frameH} landscape=${bitmap.width > bitmap.height}"
        )
        merged.forEachIndexed { idx, r ->
            Log.i(TAG, "  region$idx ${r.width()}x${r.height()} @${r.flattenToString()}")
        }
        return merged
    }

    /**
     * Run EfficientDet on [bitmap] (letterboxed into 320²). [offsetX]/[offsetY] shift
     * boxes into the parent screenshot when [bitmap] is a crop.
     */
    private fun detectOnBitmap(
        bitmap: Bitmap,
        offsetX: Int,
        offsetY: Int,
    ): List<Pair<Rect, Float>> {
        val interp = interpreter ?: return emptyList()
        val (letterboxed, meta) = letterboxToInput(bitmap)
        val input = if (inputIsFloat) {
            bitmapToFloatBuffer(letterboxed)
        } else {
            bitmapToByteBuffer(letterboxed)
        }
        letterboxed.recycle()

        val locations = Array(1) { Array(MAX_DET) { FloatArray(4) } }
        val classes = Array(1) { FloatArray(MAX_DET) }
        val scores = Array(1) { FloatArray(MAX_DET) }
        val numDetections = FloatArray(1)
        val outputs = hashMapOf<Int, Any>(
            0 to locations,
            1 to classes,
            2 to scores,
            3 to numDetections,
        )
        if (interp.outputTensorCount != 4) {
            outputs.clear()
            for (i in 0 until interp.outputTensorCount) {
                val shape = interp.getOutputTensor(i).shape()
                when {
                    shape.size == 3 && shape[2] == 4 -> outputs[i] = locations
                    shape.size == 1 || (shape.size == 2 && shape.getOrElse(1) { 1 } == 1) ->
                        outputs[i] = numDetections
                    shape.size == 2 -> {
                        if (!outputs.values.contains(scores)) outputs[i] = scores
                        else outputs[i] = classes
                    }
                }
            }
        }

        synchronized(lock) {
            interp.runForMultipleInputsOutputs(arrayOf(input), outputs)
        }

        val count = numDetections[0].toInt().coerceIn(0, MAX_DET)
        val out = ArrayList<Pair<Rect, Float>>(count)
        for (i in 0 until count) {
            val score = scores[0][i]
            if (score < SCORE_THRESHOLD) continue
            val local = boxFromLetterboxed(
                ymin = locations[0][i][0],
                xmin = locations[0][i][1],
                ymax = locations[0][i][2],
                xmax = locations[0][i][3],
                meta = meta,
            ) ?: continue

            val padX = (local.width() * BOX_PAD_FRAC).toInt()
            val padY = (local.height() * BOX_PAD_FRAC).toInt()
            val padded = Rect(
                (local.left - padX).coerceAtLeast(0),
                (local.top - padY).coerceAtLeast(0),
                (local.right + padX).coerceAtMost(meta.srcW),
                (local.bottom + padY).coerceAtMost(meta.srcH),
            )
            padded.offset(offsetX, offsetY)
            out.add(padded to score)
        }
        return out
    }

    private fun boxFromLetterboxed(
        ymin: Float,
        xmin: Float,
        ymax: Float,
        xmax: Float,
        meta: LetterboxMeta,
    ): Rect? {
        val x0 = xmin * INPUT_SIZE
        val y0 = ymin * INPUT_SIZE
        val x1 = xmax * INPUT_SIZE
        val y1 = ymax * INPUT_SIZE
        val left = ((x0 - meta.padX) / meta.scale).toInt()
        val top = ((y0 - meta.padY) / meta.scale).toInt()
        val right = ((x1 - meta.padX) / meta.scale).toInt()
        val bottom = ((y1 - meta.padY) / meta.scale).toInt()
        if (right <= left || bottom <= top) return null
        val rect = Rect(
            left.coerceIn(0, meta.srcW),
            top.coerceIn(0, meta.srcH),
            right.coerceIn(0, meta.srcW),
            bottom.coerceIn(0, meta.srcH),
        )
        if (rect.width() <= 0 || rect.height() <= 0) return null
        return rect
    }

    private fun letterboxToInput(bitmap: Bitmap): Pair<Bitmap, LetterboxMeta> {
        val w = bitmap.width
        val h = bitmap.height
        val scale = INPUT_SIZE.toFloat() / maxOf(w, h)
        val tw = (w * scale).toInt().coerceAtLeast(1)
        val th = (h * scale).toInt().coerceAtLeast(1)
        val padX = (INPUT_SIZE - tw) / 2f
        val padY = (INPUT_SIZE - th) / 2f
        val scaled = Bitmap.createScaledBitmap(bitmap, tw, th, true)
        val square = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(square)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(scaled, padX, padY, null)
        if (scaled !== bitmap) scaled.recycle()
        return square to LetterboxMeta(scale, padX, padY, w, h)
    }

    private fun mergeOverlapping(items: List<Pair<Rect, Float>>): List<Pair<Rect, Float>> {
        if (items.size <= 1) return items
        val sorted = items.sortedByDescending { it.second }.toMutableList()
        val kept = mutableListOf<Pair<Rect, Float>>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            kept.add(best)
            sorted.removeAll { other -> iou(best.first, other.first) >= IOU_MERGE }
        }
        return kept
    }

    private fun iou(a: Rect, b: Rect): Float {
        val inter = Rect(a)
        if (!inter.intersect(b)) return 0f
        val interArea = inter.width() * inter.height().toFloat()
        val union = a.width() * a.height() + b.width() * b.height() - interArea
        return if (union <= 0f) 0f else interArea / union
    }

    private fun bitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3).order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        for (p in pixels) {
            buf.put(((p shr 16) and 0xFF).toByte())
            buf.put(((p shr 8) and 0xFF).toByte())
            buf.put((p and 0xFF).toByte())
        }
        buf.rewind()
        return buf
    }

    private fun bitmapToFloatBuffer(bitmap: Bitmap): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4).order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        val floats = buf.asFloatBuffer()
        var i = 0
        for (p in pixels) {
            floats.put(i++, ((p shr 16) and 0xFF) / 255f)
            floats.put(i++, ((p shr 8) and 0xFF) / 255f)
            floats.put(i++, (p and 0xFF) / 255f)
        }
        buf.rewind()
        return buf
    }

    private fun loadModelFile(context: Context, filename: String): MappedByteBuffer {
        return context.assets.openFd(filename).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
    }
}
