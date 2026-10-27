package com.turntopage.turner

import kotlin.math.abs
import kotlin.math.sqrt

/** 归一化手部关键点；x 已按“照镜子”的方向翻转，+x = 用户自己的右侧。 */
data class HandPoint(val x: Float, val y: Float)

/** 一帧手部检测结果。points 为空表示未看到手。 */
data class HandFrame(
    val hasHand: Boolean,
    val points: List<HandPoint>,
    val fps: Float,
)

/** 悬浮球、主页与阅读测试页使用的手势状态。 */
data class HandUiState(
    val enabled: Boolean = false,
    val hasHand: Boolean = false,
    val fps: Float = 0f,
    val lastGesture: String = "",
    val lastGestureAt: Long = 0L,
    val pinchRatio: Float = 0f,
)

/**
 * 空中手势状态机（纯 Kotlin，便于 JVM 单测）：
 *  - 张开手掌左右挥动：向左挥 = 下一页，向右挥 = 上一页（按用户视角，摄像头已镜像）
 *  - 拇指与食指捏合：默认下一页，可通过 pinchDirProvider 改成上一页
 *  - 触发后要求手停下/离开画面再重新武装，避免一次挥手连翻
 */
class HandGestureController(
    private val onTurn: (dir: Int, source: String) -> Unit,
    private val onState: (HandUiState) -> Unit = {},
    private val pinchDirProvider: () -> Int = { 1 },
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private data class Sample(val t: Long, val x: Float, val y: Float, val open: Boolean)

    private val history = ArrayDeque<Sample>()
    private var waveArmed = true
    private var waveLockUntil = 0L
    private var pinched = false
    private var pinchSeeded = false
    private var pinchSmooth = 0f
    private var pinchLowFrames = 0
    private var pinchLockUntil = 0L
    private var noHandSince = 0L
    private var lastFps = 0f
    private var lastGesture = ""
    private var lastGestureAt = 0L

    @Synchronized
    fun onFrame(f: HandFrame) {
        val t = now()
        lastFps = f.fps

        if (!f.hasHand) {
            if (noHandSince == 0L) noHandSince = t
            history.clear()
            pinched = false
            pinchSeeded = false
            pinchSmooth = 0f
            pinchLowFrames = 0
            if (t - noHandSince > 350L) waveArmed = true
            publish(false)
            return
        }
        noHandSince = 0L
        val p = f.points
        if (p.size < 21) {
            publish(false)
            return
        }

        val open = isOpenPalm(p)
        val palmX = (p[0].x + p[5].x + p[9].x + p[13].x + p[17].x) / 5f
        val palmY = (p[0].y + p[5].y + p[9].y + p[13].y + p[17].y) / 5f
        val ratio = pinchRatio(p)
        pinchSmooth = if (!pinchSeeded) {
            pinchSeeded = true
            ratio
        } else {
            pinchSmooth * 0.65f + ratio * 0.35f
        }

        var fired = false

        // ---- 捏合：原始距离连续 2 帧足够近即触发（6fps 下也只需约 0.3 秒）；
        //      必须张到 0.55 以上才重新武装，避免捏住时连翻。 ----
        if (!pinched) {
            pinchLowFrames = if (ratio < 0.36f) pinchLowFrames + 1 else 0
            if (pinchLowFrames >= 2 && t >= pinchLockUntil) {
                val dir = if (pinchDirProvider() < 0) -1 else 1
                onTurn(dir, "pinch")
                pinched = true
                pinchLockUntil = t + 900L
                lastGesture = if (dir > 0) "捏合 · 下一页" else "捏合 · 上一页"
                lastGestureAt = t
                history.clear()
                fired = true
            }
        } else {
            pinchLowFrames = 0
            if (ratio > 0.55f) {
                pinched = false
                history.clear() // 松开捏合的动作不要被误判成挥手
            }
        }

        if (!fired) {
            history.addLast(Sample(t, palmX, palmY, open))
            while (history.size > 2 && t - history.first().t > 650L) {
                history.removeFirst()
            }

            // ---- 挥手：张开手掌在约 0.3 秒内横向移动超过 18% 画幅 ----
            if (open && !pinched && t >= waveLockUntil) {
                val candidates = history.filter {
                    val age = t - it.t
                    it.open && age in 140L..550L
                }
                val ref = candidates.minByOrNull { abs((t - it.t) - 280L) }
                if (ref != null) {
                    val dx = palmX - ref.x
                    val dy = palmY - ref.y
                    if (abs(dx) >= 0.18f && abs(dy) <= 0.16f && waveArmed) {
                        // 镜像坐标：手向左移(x 减小) = 下一页；向右移 = 上一页。
                        val dir = if (dx < 0f) 1 else -1
                        onTurn(dir, "wave")
                        waveArmed = false
                        waveLockUntil = t + 900L
                        lastGesture = if (dir > 0) "挥手 · 下一页" else "挥手 · 上一页"
                        lastGestureAt = t
                        history.clear()
                        fired = true
                    }
                }
            }

            // 手停稳后再允许下一次挥手，防止连续翻页。
            if (!fired && !waveArmed && t >= waveLockUntil) {
                val last = history.lastOrNull()
                val first = history.firstOrNull()
                if (last != null && first != null && t - first.t >= 120L) {
                    val speed = abs(last.x - first.x) * 1000f / (t - first.t)
                    if (speed < 0.35f) waveArmed = true
                } else if (history.size >= 1) {
                    waveArmed = true
                }
            }
        }

        publish(true)
    }

    @Synchronized
    fun reset() {
        history.clear()
        waveArmed = true
        waveLockUntil = 0L
        pinched = false
        pinchSeeded = false
        pinchSmooth = 0f
        pinchLowFrames = 0
        pinchLockUntil = 0L
        noHandSince = 0L
        lastGesture = ""
        lastGestureAt = 0L
        publish(false)
    }

    private fun publish(hasHand: Boolean) {
        onState(
            HandUiState(
                enabled = true,
                hasHand = hasHand,
                fps = lastFps,
                lastGesture = lastGesture,
                lastGestureAt = lastGestureAt,
                pinchRatio = pinchSmooth,
            )
        )
    }

    private fun isOpenPalm(p: List<HandPoint>): Boolean {
        val wrist = p[0]
        var extended = 0
        for ((tip, pip) in FINGERS) {
            if (dist(p[tip], wrist) > dist(p[pip], wrist) * 1.12f) extended++
        }
        return extended >= 3
    }

    /** 拇指尖(4)与食指尖(8)距离，用腕-中指根(0-9)作尺度归一化。 */
    private fun pinchRatio(p: List<HandPoint>): Float {
        val scale = dist(p[0], p[9]).coerceAtLeast(0.05f)
        return (dist(p[4], p[8]) / scale).coerceIn(0f, 3f)
    }

    private fun dist(a: HandPoint, b: HandPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private companion object {
        val FINGERS = listOf(8 to 6, 12 to 10, 16 to 14, 20 to 18)
    }
}
