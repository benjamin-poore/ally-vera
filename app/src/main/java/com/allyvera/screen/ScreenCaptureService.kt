package com.allyvera.screen

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.allyvera.MainActivity
import com.allyvera.frame.CapturedFrame
import com.allyvera.frame.CaptureController
import com.allyvera.frame.FrameBus
import com.allyvera.frame.FrameCache
import com.allyvera.frame.FrameSource
import com.allyvera.frame.SensorRegistry
import com.allyvera.processing.DeviceCaptureGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ScreenCaptureService : Service(), CaptureController {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaProjection: MediaProjection? = null
    private var isStopping = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection session stopped")
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        captureHandler?.post {
            updateCaptureDimensions()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData = intent?.projectionResultData()

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            Log.e(TAG, "Missing valid MediaProjection consent result")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (mediaProjection != null) {
            Log.i(TAG, "Screen capture is already running")
            return START_NOT_STICKY
        }

        try {
            startAsForeground()
            startProjection(resultCode, resultData)
            SensorRegistry.register(this)
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to start screen capture", exception)
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        SensorRegistry.unregister(this)
        isStopping = true
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        super.onDestroy()
    }

    private fun startAsForeground() {
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startProjection(resultCode: Int, resultData: Intent) {
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val projection = projectionManager.getMediaProjection(resultCode, resultData)
            ?: error("MediaProjectionManager returned no projection")
        mediaProjection = projection
        projection.registerCallback(projectionCallback, mainHandler)

        val thread = HandlerThread("ScreenCaptureFrames").also { it.start() }
        captureThread = thread
        captureHandler = Handler(thread.looper)

        val metrics = resources.displayMetrics
        val reader = createImageReader(metrics.widthPixels, metrics.heightPixels)
        virtualDisplay = projection.createVirtualDisplay(
            "AllyVeraScreenCapture",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null
        )
    }

    private fun createImageReader(width: Int, height: Int): ImageReader {
        return ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).also { reader ->
            imageReader = reader
        }
    }

    private fun updateCaptureDimensions() {
        val display = virtualDisplay ?: return
        val oldReader = imageReader ?: return
        val metrics = resources.displayMetrics
        if (oldReader.width == metrics.widthPixels && oldReader.height == metrics.heightPixels) {
            return
        }

        val newReader = createImageReader(metrics.widthPixels, metrics.heightPixels)
        display.resize(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        display.surface = newReader.surface
        oldReader.setOnImageAvailableListener(null, null)
        oldReader.close()
        Log.i(TAG, "Capture resized to ${metrics.widthPixels}x${metrics.heightPixels}")
    }

    /**
     * Commanded by the coordinator. Pulls the most recent buffered frame, caches it to a file,
     * and emits the path. The sensor decides nothing about cadence — it just does the work.
     */
    override val source: FrameSource = FrameSource.MEDIA_PROJECTION

    override suspend fun capture() {
        if (isStopping) return
        val reader = imageReader ?: return
        withContext(Dispatchers.IO) {
            // Re-check lock/screen state on the same thread as acquisition. MediaProjection can
            // still deliver frames after lock/screen-off, and a coordinator check can race.
            if (!DeviceCaptureGate.canCapture(this@ScreenCaptureService)) return@withContext
            val image = reader.acquireLatestImage() ?: return@withContext
            try {
                val bitmap = image.toBitmap(image.width, image.height)
                val path = FrameCache.write(this@ScreenCaptureService, bitmap, FrameSource.MEDIA_PROJECTION)
                bitmap.recycle()
                if (path != null) {
                    // If the bus is full the frame would otherwise leak on disk — discard it.
                    if (!FrameBus.emit(CapturedFrame(path, FrameSource.MEDIA_PROJECTION))) {
                        FrameCache.delete(path)
                    }
                }
            } catch (exception: Exception) {
                Log.e(TAG, "Unable to acquire MediaProjection frame", exception)
            } finally {
                image.close()
            }
        }
    }

    private fun Image.toBitmap(width: Int, height: Int): Bitmap {
        val plane = planes.first()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride

        val paddedBitmap = Bitmap.createBitmap(
            paddedWidth,
            height,
            Bitmap.Config.ARGB_8888
        )
        plane.buffer.rewind()
        paddedBitmap.copyPixelsFromBuffer(plane.buffer)
        if (paddedWidth == width) return paddedBitmap

        val croppedBitmap = Bitmap.createBitmap(paddedBitmap, 0, 0, width, height)
        paddedBitmap.recycle()
        return croppedBitmap
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Screen capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Required while legacy screen capture is active"
            }
        )
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Screen capture active")
            .setContentText("Ally Vera is capturing a screenshot every 15 seconds")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun Intent.projectionResultData(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            getParcelableExtra(EXTRA_RESULT_DATA)
        }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"

        fun createStartIntent(
            context: Context,
            resultCode: Int,
            resultData: Intent
        ): Intent = Intent(context, ScreenCaptureService::class.java).apply {
            putExtra(EXTRA_RESULT_CODE, resultCode)
            putExtra(EXTRA_RESULT_DATA, resultData)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }
}
