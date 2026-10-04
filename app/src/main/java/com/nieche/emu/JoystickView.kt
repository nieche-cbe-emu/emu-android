package com.nieche.emu

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

/**
 * 虚拟摇杆。替代原来的方向键，手感更接近游戏手柄。
 *
 * 底盘内画一个可拖拽的摇杆帽，根据偏移方向判定上下左右。
 * 死区内不触发方向，避免手指稍微一动就飘。拖拽超出底盘范围的部分
 * 会被限制在最大半径上，和实体摇杆一样。
 *
 * 只输出单方向（取偏移量较大的轴），因为功能机游戏不用对角。
 */
class JoystickView(ctx: Context) : View(ctx) {

    /** 当前激活的方向集合（空集 = 松开） */
    var onDirection: ((Set<String>) -> Unit)? = null

    // ---- 画笔 ----
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

    // ---- 几何状态 ----
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
        // 底盘
        c.drawCircle(cx, cy, baseR, basePaint)
        c.drawCircle(cx, cy, baseR, rimPaint)
        // 四个方向箭头
        val ad = baseR * 0.68f; val sz = baseR * 0.14f
        arrow(c, cx, cy - ad, sz,   0f, "up"    in active)
        arrow(c, cx, cy + ad, sz, 180f, "down"  in active)
        arrow(c, cx - ad, cy, sz, 270f, "left"  in active)
        arrow(c, cx + ad, cy, sz,  90f, "right" in active)
        // 摇杆帽
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

    /**
     * 根据偏移判定方向。只取主轴（|dx| 和 |dy| 谁大就是谁的方向），
     * 功能机游戏不需要对角输入。
     */
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
