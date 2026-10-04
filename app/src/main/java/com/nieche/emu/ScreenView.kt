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

/**
 * 客户机画面。分辨率是平台钉死的 240x400，这里负责放大、居中、双指缩放平移。
 *
 * 单指是游戏触摸，双指是缩放/平移——尼彩原机是触屏机，很多软键和对话框按钮
 * 只吃触摸，所以单指必须原样透传给游戏，不能被手势识别吞掉。
 */
class ScreenView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private var bmp: Bitmap? = null
    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val dst = RectF()

    /** 用户捏出来的缩放倍率，叠加在自动适配的基础倍率上 */
    private var userScale = 1f
    private var panX = 0f
    private var panY = 0f
    private var baseScale = 1f
    private var drawX = 0f
    private var drawY = 0f
    /** 双指期间不给游戏发触摸，否则捏合会被当成一路乱点 */
    private var gesturing = false

    var onTouchGuest: ((Int, Int, String) -> Unit)? = null
    /** 缩放变化时报给外面，用来更新状态栏 */
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
                // 以两指中点为锚点缩放，画面才不会往角落里跑
                panX = d.focusX + (panX - d.focusX) * k
                panY = d.focusY + (panY - d.focusY) * k
                // 中点自己移动的那部分就是平移。缩放和平移在同一个手势里完成，
                // 不用再单独判一次双指拖动。
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

    /** 恢复成自动适配、居中 */
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
        // 基础倍率把画面塞进控件；再乘上用户捏出来的倍率
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
                // 从单指变成双指：先把游戏那边的按下收掉，不然它会一直以为按着
                gesturing = true
                onTouchGuest?.invoke(0, 0, "up")
            }
            return true
        }
        if (gesturing) {
            // 手指减回一根，先不要立刻当成新的游戏触摸——等这一轮抬起
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
