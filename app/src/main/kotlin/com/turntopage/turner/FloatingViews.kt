package com.turntopage.turner

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

/** 悬浮球：显示眼动注视进度环或空中手势状态；可拖动，单击展开面板。 */
class BubbleView(context: Context) : View(context) {

    var onTap: (() -> Unit)? = null
    var onDrag: ((Int, Int) -> Unit)? = null

    @Volatile
    private var flashUntil = 0L

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun dp(v: Float) = v * resources.displayMetrics.density

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; lastX = downX; lastY = downY; moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.rawX - lastX).toInt()
                val dy = (e.rawY - lastY).toInt()
                if (moved || abs(e.rawX - downX) > dp(6f) || abs(e.rawY - downY) > dp(6f)) {
                    moved = true
                    onDrag?.invoke(dx, dy)
                }
                lastX = e.rawX; lastY = e.rawY
            }
            MotionEvent.ACTION_UP -> if (!moved) onTap?.invoke()
        }
        return true
    }

    fun flash() {
        flashUntil = SystemClock.uptimeMillis() + 260
        postInvalidate()
    }

    override fun onDraw(c: Canvas) {
        val running = EyeTrackingService.running
        val handOn = running && EyeTrackingService.handMode
        val eyeOn = running && !EyeTrackingService.handMode
        val g = TurnBus.gazeState.value
        val h = TurnBus.handState.value
        val w = width.toFloat()
        val r = w / 2f

        // 底盘
        paint.style = Paint.Style.FILL
        paint.color = if (running) 0xE6121212.toInt() else 0xCCFFFFFF.toInt()
        c.drawCircle(r, r, r - dp(1f), paint)

        // 外圈：眼动绿，手势橙
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = when {
            handOn -> 0xFFFB8C00.toInt()
            eyeOn -> 0xFF43A047.toInt()
            else -> 0xFF909090.toInt()
        }
        c.drawCircle(r, r, r - dp(2.5f), paint)

        if (handOn) {
            // 捏合进度环（0.55 → 0，0.32 → 满）
            if (h.hasHand && h.pinchRatio in 0.01f..0.55f) {
                val p = ((0.55f - h.pinchRatio) / 0.23f).coerceIn(0f, 1f)
                paint.strokeWidth = dp(3.5f)
                paint.strokeCap = Paint.Cap.ROUND
                paint.color = 0xFFFFB74D.toInt()
                c.drawArc(
                    dp(2.5f), dp(2.5f), w - dp(2.5f), w - dp(2.5f),
                    -90f, 360f * p, false, paint
                )
                paint.strokeCap = Paint.Cap.BUTT
            }
            drawHandIcon(c, r, h.hasHand)
            if (!h.hasHand) {
                paint.style = Paint.Style.FILL
                paint.color = 0x66FF5252
                c.drawCircle(r, r, r - dp(6f), paint)
            }
        } else {
            // 注视进度环
            if (g.dwellDir != 0 && g.dwellProgress > 0f) {
                paint.strokeWidth = dp(3.5f)
                paint.color = if (g.dwellDir > 0) 0xFFFF7043.toInt() else 0xFF42A5F5.toInt()
                c.drawArc(dp(2.5f), dp(2.5f), w - dp(2.5f), w - dp(2.5f), -90f, 360f * g.dwellProgress, false, paint)
            }

            // 眼睛图标：瞳孔随视线偏移
            val eyeW = r * 1.25f
            val eyeH = r * 0.78f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2f)
            paint.color = if (eyeOn) 0xFFEEEEEE.toInt() else 0xFF444444.toInt()
            c.drawOval(r - eyeW / 2, r - eyeH / 2, r + eyeW / 2, r + eyeH / 2, paint)
            paint.style = Paint.Style.FILL
            val px = r + g.gazeX * eyeW * 0.26f
            c.drawCircle(px, r, r * 0.17f, paint)

            // 无脸提示
            if (eyeOn && !g.hasFace) {
                paint.color = 0x66FF5252
                c.drawCircle(r, r, r - dp(6f), paint)
            }
        }

        // 翻页闪烁
        val now = SystemClock.uptimeMillis()
        if (now < flashUntil) {
            val a = (flashUntil - now) / 260f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(3f)
            paint.color = Color.argb((a * 200).toInt(), 255, 152, 0)
            c.drawCircle(r, r, r + dp(4f) + (1 - a) * dp(8f), paint)
        }

        // 未运行时也慢速重绘：模式切换或服务重启后能自动换成对应图标，不必等触摸。
        postInvalidateDelayed(if (running) 66L else 250L)
    }

    /** 简笔手掌：四指 + 拇指；检测到收拢时用暖色强调。 */
    private fun drawHandIcon(c: Canvas, r: Float, hasHand: Boolean) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = if (!hasHand) 0xFF888888.toInt()
        else if (TurnBus.handState.value.pinchRatio in 0.01f..0.45f) 0xFFFFB74D.toInt()
        else 0xFFEEEEEE.toInt()

        val palmLeft = r - 0.26f * r
        val palmTop = r - 0.05f * r
        val palmRight = r + 0.26f * r
        val palmBottom = r + 0.42f * r
        c.drawRoundRect(palmLeft, palmTop, palmRight, palmBottom, 0.12f * r, 0.12f * r, paint)

        val fingerX = floatArrayOf(-0.19f, -0.065f, 0.065f, 0.19f)
        val fingerTop = floatArrayOf(-0.48f, -0.56f, -0.52f, -0.40f)
        for (i in 0..3) {
            val x = r + fingerX[i] * r
            c.drawLine(x, r - 0.02f * r, x, r + fingerTop[i] * r, paint)
        }
        // 拇指
        c.drawLine(r - 0.24f * r, r + 0.18f * r, r - 0.48f * r, r + 0.04f * r, paint)
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun abs(v: Float) = kotlin.math.abs(v)
}

/** 边缘热区：点击窄条即向该侧翻页。 */
class ZoneView(context: Context, private val side: Int) : View(context) {

    var onTurn: (() -> Unit)? = null

    private var downAt = 0L
    private var downX = 0f
    private var downY = 0f
    private var hot = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                hot = true
                downAt = SystemClock.uptimeMillis()
                downX = e.rawX; downY = e.rawY
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                hot = false
                invalidate()
                val dt = SystemClock.uptimeMillis() - downAt
                val moved = kotlin.math.abs(e.rawY - downY) > 70 * d ||
                        kotlin.math.abs(e.rawX - downX) > 50 * d
                if (dt < 600 && !moved) onTurn?.invoke()
            }
            MotionEvent.ACTION_CANCEL -> {
                hot = false
                invalidate()
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val s = TurnAccessibilityService.instance?.settings ?: return
        val hint = s.zoneHint
        if (hint || hot) {
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(if (hot) 90 else 26, 255, 152, 0)
            val r = 12 * d
            val left = if (side < 0) 0f else width - 2 * r
            c.drawRoundRect(left, 0f, left + 2 * r, height.toFloat(), r, r, paint)
        }
    }
}
