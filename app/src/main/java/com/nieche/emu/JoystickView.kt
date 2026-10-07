package com.nieche.emu

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

class JoystickView(ctx: Context) : View(ctx) {

    var onDirection: ((Set<String>) -> Unit)? = null

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2A2A2E.toInt(); style = Paint.Style.FILL
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3A3A40.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val stickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5A5A60.toInt(); style = Paint.Style.FILL
    }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4A90D9.toInt(); style = Paint.Style.FILL
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6A6A70.toInt(); style = Paint.Style.FILL
    }
    private val activeArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4A90D9.toInt(); style = Paint.Style.FILL
    }

    private var cx = 0f
    private var cy = 0f
    private var baseR = 0f
    private var stickR = 0f
    private var stickX = 0f
    private var stickY = 0f
    private var touching = false
    private val active = mutableSetOf<String>()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        cx = w / 2f; cy = h / 2f
        baseR = minOf(w, h) / 2f * 0.82f
        stickR = baseR * 0.38f
        stickX = cx; stickY = cy
    }

    override fun onDraw(c: Canvas) {

        c.drawCircle(cx, cy, baseR, basePaint)
        c.drawCircle(cx, cy, baseR, rimPaint)

        val ad = baseR * 0.68f; val sz = baseR * 0.14f
        arrow(c, cx, cy - ad, sz,   0f, "up"    in active)
        arrow(c, cx, cy + ad, sz, 180f, "down"  in active)
        arrow(c, cx - ad, cy, sz, 270f, "left"  in active)
        arrow(c, cx + ad, cy, sz,  90f, "right" in active)

        c.drawCircle(stickX, stickY, stickR,
            if (touching) activePaint else stickPaint)
    }

    private fun arrow(c: Canvas, x: Float, y: Float, s: Float, rot: Float, on: Boolean) {
        val p = if (on) activeArrowPaint else arrowPaint
        val path = Path().apply {
            moveTo(0f, -s); lineTo(s * 0.7f, s * 0.5f)
            lineTo(-s * 0.7f, s * 0.5f); close()
        }
        c.save(); c.translate(x, y); c.rotate(rot)
        c.drawPath(path, p); c.restore()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                touching = true
                val dx = e.x - cx; val dy = e.y - cy
                val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                val max = baseR - stickR
                if (dist <= max) { stickX = e.x; stickY = e.y }
                else { stickX = cx + dx / dist * max; stickY = cy + dy / dist * max }
                updateDirs(dx, dy, dist)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                stickX = cx; stickY = cy
                active.clear()
                onDirection?.invoke(emptySet())
                invalidate()
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun updateDirs(dx: Float, dy: Float, dist: Float) {
        val dead = baseR * 0.22f
        val dirs = mutableSetOf<String>()
        if (dist > dead) {
            if (Math.abs(dx) > Math.abs(dy)) {
                dirs.add(if (dx > 0) "right" else "left")
            } else {
                dirs.add(if (dy > 0) "down" else "up")
            }
        }
        if (dirs != active) {
            active.clear(); active.addAll(dirs)
            onDirection?.invoke(dirs.toSet())
        }
    }
}
