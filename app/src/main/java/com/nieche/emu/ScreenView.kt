package com.nieche.emu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

class ScreenView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private var bmp: Bitmap? = null
    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val dst = RectF()

    private var userScale = 1f
    private var panX = 0f
    private var panY = 0f
    private var baseScale = 1f
    private var drawX = 0f
    private var drawY = 0f

    private var gesturing = false

    var onTouchGuest: ((Int, Int, String) -> Unit)? = null

    var onZoom: ((Float) -> Unit)? = null

    private var lastFocusX = 0f
    private var lastFocusY = 0f

    private val scaleDetector = ScaleGestureDetector(ctx,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                lastFocusX = d.focusX
                lastFocusY = d.focusY
                return true
            }

            override fun onScale(d: ScaleGestureDetector): Boolean {
                val old = userScale
                userScale = (userScale * d.scaleFactor).coerceIn(0.5f, 6f)
                val k = userScale / old

                panX = d.focusX + (panX - d.focusX) * k
                panY = d.focusY + (panY - d.focusY) * k

                panX += d.focusX - lastFocusX
                panY += d.focusY - lastFocusY
                lastFocusX = d.focusX
                lastFocusY = d.focusY
                onZoom?.invoke(userScale)
                invalidate()
                return true
            }
        })

    fun setFrame(b: Bitmap) {
        bmp = b
        postInvalidateOnAnimation()
    }

    fun clear() {
        bmp = null
        postInvalidateOnAnimation()
    }

    fun resetZoom() {
        userScale = 1f
        panX = 0f
        panY = 0f
        onZoom?.invoke(1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val b = bmp ?: return

        baseScale = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
        val s = baseScale * userScale
        val w = b.width * s
        val h = b.height * s
        drawX = (width - w) / 2 + panX
        drawY = (height - h) / 2 + panY
        dst.set(drawX, drawY, drawX + w, drawY + h)
        canvas.drawBitmap(b, null, dst, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val b = bmp ?: return false
        scaleDetector.onTouchEvent(e)

        if (e.pointerCount > 1) {
            if (!gesturing) {

                gesturing = true
                onTouchGuest?.invoke(0, 0, "up")
            }
            return true
        }
        if (gesturing) {

            if (e.actionMasked == MotionEvent.ACTION_UP ||
                e.actionMasked == MotionEvent.ACTION_CANCEL) gesturing = false
            return true
        }

        val s = baseScale * userScale
        val gx = ((e.x - drawX) / s).toInt().coerceIn(0, b.width - 1)
        val gy = ((e.y - drawY) / s).toInt().coerceIn(0, b.height - 1)
        val state = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> "down"
            MotionEvent.ACTION_MOVE -> "move"
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> "up"
            else -> return false
        }
        onTouchGuest?.invoke(gx, gy, state)
        if (state == "down") performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
