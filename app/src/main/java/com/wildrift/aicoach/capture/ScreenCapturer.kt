package com.wildrift.aicoach.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream

data class CapturedFrame(
    val previewBitmap: Bitmap,
    val base64Jpeg: String,
    val width: Int,
    val height: Int
)

class ScreenCapturer(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val onProjectionStopped: () -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var currentWidth: Int = 0
    private var currentHeight: Int = 0
    private var currentDensityDpi: Int = 0
    private var lastValidBitmap: Bitmap? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            release()
            onProjectionStopped()
        }
    }

    init {
        // Android 14 requires registering callback BEFORE createVirtualDisplay
        mediaProjection.registerCallback(projectionCallback, mainHandler)
        initOrResizeVirtualDisplay()
    }

    @Synchronized
    fun initOrResizeVirtualDisplay() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val density = metrics.densityDpi

        if (virtualDisplay == null) {
            currentWidth = width
            currentHeight = height
            currentDensityDpi = density

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "WildRiftAICoachCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                mainHandler
            )
        } else if (width != currentWidth || height != currentHeight) {
            currentWidth = width
            currentHeight = height
            currentDensityDpi = density

            val oldReader = imageReader
            val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageReader = newReader

            virtualDisplay?.resize(width, height, density)
            virtualDisplay?.surface = newReader.surface
            try {
                oldReader?.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Captures the latest screen frame, scales it to [maxDimension] on the longest side,
     * and returns both a preview Bitmap and a Base64-encoded JPEG string.
     */
    @Synchronized
    fun captureSnapshot(maxDimension: Int = 1280): CapturedFrame? {
        initOrResizeVirtualDisplay()
        val reader = imageReader ?: return null

        var acquiredBitmap: Bitmap? = null
        for (attempt in 0 until 6) {
            var image: Image? = null
            try {
                image = reader.acquireLatestImage()
                if (image != null) {
                    acquiredBitmap = imageToBitmap(image)
                    if (acquiredBitmap != null) {
                        lastValidBitmap = acquiredBitmap
                        break
                    }
                }
            } catch (_: Exception) {
            } finally {
                try {
                    image?.close()
                } catch (_: Exception) {
                }
            }
            if (attempt < 5) {
                try {
                    Thread.sleep(45)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }

        val rawBitmap = acquiredBitmap ?: lastValidBitmap ?: return null
        val scaledBitmap = scaleDownBitmap(rawBitmap, maxDimension)

        val baos = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 82, baos)
        val jpegBytes = baos.toByteArray()
        val base64Jpeg = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)

        return CapturedFrame(
            previewBitmap = scaledBitmap,
            base64Jpeg = base64Jpeg,
            width = scaledBitmap.width,
            height = scaledBitmap.height
        )
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val width = image.width
        val height = image.height
        val planes = image.planes
        if (planes.isEmpty()) return null

        val buffer = planes[0].buffer
        buffer.rewind()
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width

        val paddedWidth = width + rowPadding / pixelStride
        val paddedBitmap = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        paddedBitmap.copyPixelsFromBuffer(buffer)

        return if (rowPadding == 0) {
            paddedBitmap
        } else {
            val cropped = Bitmap.createBitmap(paddedBitmap, 0, 0, width, height)
            paddedBitmap.recycle()
            cropped
        }
    }

    private fun scaleDownBitmap(source: Bitmap, maxDimension: Int): Bitmap {
        val w = source.width
        val h = source.height
        val longest = maxOf(w, h)
        if (longest <= maxDimension) {
            return source.copy(Bitmap.Config.ARGB_8888, false)
        }
        val ratio = maxDimension.toFloat() / longest.toFloat()
        val targetW = (w * ratio).toInt().coerceAtLeast(1)
        val targetH = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetW, targetH, true)
    }

    @Synchronized
    fun release() {
        try {
            mediaProjection.unregisterCallback(projectionCallback)
        } catch (_: Exception) {
        }
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null
        try {
            mediaProjection.stop()
        } catch (_: Exception) {
        }
    }
}
