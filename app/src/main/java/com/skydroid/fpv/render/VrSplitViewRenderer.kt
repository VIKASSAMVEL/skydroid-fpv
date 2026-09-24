package com.skydroid.fpv.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * Renders stereoscopic split-screen dual view for smartphone FPV goggles.
 * Synchronously projects the incoming UVC video feed to both left and right eye surfaces.
 */
class VrSplitViewRenderer(
    leftSurface: SurfaceView,
    rightSurface: SurfaceView
) {
    private val leftHolder: SurfaceHolder = leftSurface.holder
    private val rightHolder: SurfaceHolder = rightSurface.holder

    private var isLeftAvailable = false
    private var isRightAvailable = false

    private val srcRect = Rect()
    private val leftDestRect = Rect()
    private val rightDestRect = Rect()
    private val paint = Paint().apply {
        isFilterBitmap = true
    }

    init {
        leftHolder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { isLeftAvailable = true }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) { isLeftAvailable = true }
            override fun surfaceDestroyed(holder: SurfaceHolder) { isLeftAvailable = false }
        })

        rightHolder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { isRightAvailable = true }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) { isRightAvailable = true }
            override fun surfaceDestroyed(holder: SurfaceHolder) { isRightAvailable = false }
        })
    }

    fun renderVrFrame(bitmap: Bitmap) {
        srcRect.set(0, 0, bitmap.width, bitmap.height)

        // Render left eye
        if (isLeftAvailable) {
            renderToHolder(leftHolder, bitmap, leftDestRect)
        }

        // Render right eye
        if (isRightAvailable) {
            renderToHolder(rightHolder, bitmap, rightDestRect)
        }
    }

    private fun renderToHolder(holder: SurfaceHolder, bitmap: Bitmap, dest: Rect) {
        var canvas: Canvas? = null
        try {
            canvas = holder.lockCanvas()
            if (canvas != null) {
                canvas.drawColor(Color.BLACK)
                // Center-fit with 4:3 ratio inside each eye viewport
                val aspect = 4f / 3f
                val targetW = (canvas.height * aspect).toInt()
                val left = (canvas.width - targetW) / 2
                dest.set(left, 0, left + targetW, canvas.height)

                canvas.drawBitmap(bitmap, srcRect, dest, paint)
            }
        } catch (_: Exception) {
        } finally {
            if (canvas != null) {
                try {
                    holder.unlockCanvasAndPost(canvas)
                } catch (_: Exception) {}
            }
        }
    }
}
