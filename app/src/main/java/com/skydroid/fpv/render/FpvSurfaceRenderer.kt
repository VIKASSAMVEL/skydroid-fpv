package com.skydroid.fpv.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * High-performance low-latency SurfaceView renderer for FPV live feed.
 * Supports aspect ratio scaling (4:3 classic analog, 16:9, and fullscreen stretch).
 */
class FpvSurfaceRenderer(
    private val surfaceView: SurfaceView
) : SurfaceHolder.Callback {

    enum class AspectRatioMode {
        NATIVE_4_3,
        WIDESCREEN_16_9,
        FILL_STRETCH
    }

    private var surfaceHolder: SurfaceHolder = surfaceView.holder
    private var isSurfaceAvailable = false
    private var currentMode = AspectRatioMode.NATIVE_4_3

    private val destRect = Rect()
    private val srcRect = Rect()
    private val paint = Paint().apply {
        isFilterBitmap = true
        isDither = false
    }

    init {
        surfaceHolder.addCallback(this)
    }

    fun setAspectRatioMode(mode: AspectRatioMode) {
        this.currentMode = mode
    }

    fun getAspectRatioMode(): AspectRatioMode = currentMode

    fun renderFrame(bitmap: Bitmap) {
        if (!isSurfaceAvailable) return

        var canvas: Canvas? = null
        try {
            canvas = surfaceHolder.lockCanvas()
            if (canvas != null) {
                // Clear background with deep black
                canvas.drawColor(Color.BLACK)

                srcRect.set(0, 0, bitmap.width, bitmap.height)
                computeDestRect(canvas.width, canvas.height, bitmap.width, bitmap.height)

                canvas.drawBitmap(bitmap, srcRect, destRect, paint)
            }
        } catch (_: Exception) {
            // Ignore surface lock collisions during lifecycle changes
        } finally {
            if (canvas != null) {
                try {
                    surfaceHolder.unlockCanvasAndPost(canvas)
                } catch (_: Exception) {}
            }
        }
    }

    private fun computeDestRect(viewWidth: Int, viewHeight: Int, frameWidth: Int, frameHeight: Int) {
        when (currentMode) {
            AspectRatioMode.FILL_STRETCH -> {
                destRect.set(0, 0, viewWidth, viewHeight)
            }
            AspectRatioMode.NATIVE_4_3 -> {
                val targetAspect = 4f / 3f
                calculateAspectFit(viewWidth, viewHeight, targetAspect)
            }
            AspectRatioMode.WIDESCREEN_16_9 -> {
                val targetAspect = 16f / 9f
                calculateAspectFit(viewWidth, viewHeight, targetAspect)
            }
        }
    }

    private fun calculateAspectFit(viewWidth: Int, viewHeight: Int, targetAspect: Float) {
        val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()
        if (viewAspect > targetAspect) {
            // View is wider than target: pillarbox (black bars on left/right)
            val renderWidth = (viewHeight * targetAspect).toInt()
            val left = (viewWidth - renderWidth) / 2
            destRect.set(left, 0, left + renderWidth, viewHeight)
        } else {
            // View is taller than target: letterbox (black bars on top/bottom)
            val renderHeight = (viewWidth / targetAspect).toInt()
            val top = (viewHeight - renderHeight) / 2
            destRect.set(0, top, viewWidth, top + renderHeight)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        isSurfaceAvailable = true
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        isSurfaceAvailable = true
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        isSurfaceAvailable = false
    }
}
