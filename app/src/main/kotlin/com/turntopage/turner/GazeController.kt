package com.turntopage.turner

import android.os.SystemClock
import kotlin.math.abs

/** 一帧人脸检测结果。gazeX: -1 看向左 .. +1 看向右（用户自己的左右）。 */
data class GazeFrame(
    val hasFace: Boolean,
    val gazeX: Float,
    val yawDeg: Float,
    val blinkLeft: Float,
    val blinkRight: Float,
    val fps: Float,
)

/** 悬浮球、主页与阅读测试页使用的注视状态。 */
data class GazeUiState(
    val enabled: Boolean = false,
    val hasFace: Boolean = false,
    val gazeX: Float = 0f,
    val dwellDir: Int = 0,
    val dwellProgress: Float = 0f,
    val blinking: Boolean = false,
    val fps: Float = 0f,
    val threshold: Float = 0.5f,
    val calibrating: Boolean = false,
)

/**
 * 眼动翻页状态机：
 *  - 自动居中校准：开始检测的前几帧作为视线零点，消除“看正前方 blendshape 也不为零”的偏差
 *  - 注视翻页：视线持续偏向屏幕左/右超过 dwellMs → 上一页/下一页（带冷却与回中再武装，防连翻）
 *  - 双击眨眼：700ms 内连续两次闭眼 → 下一页
 */
class GazeController(
    private val settings: AppSettings,
    private val onTurn: (dir: Int, source: String) -> Unit,
) {
    private var smooth = 0f
    private var armed = true
    private var dwellAccum = 0L
    private var lastTs = 0L
    private var wasClosed = false
    private var lastCloseTs = 0L
    private var blinkLockUntil = 0L
    private var cooldownUntil = 0L

    private var neutral = 0f
    private var calibrationSum = 0f
    private var calibrationCount = 0
    private var calibrated = false
    private var calibrationMode: Boolean? = null
    private var noFaceSince = 0L

    @Synchronized
    fun onFrame(f: GazeFrame) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastTs == 0L) 16L else (now - lastTs).coerceIn(1L, 200L)
        lastTs = now

        // ---- 双击眨眼 ----
        val closed = f.hasFace && f.blinkLeft > 0.55f && f.blinkRight > 0.55f
        if (closed && !wasClosed) {
            if (settings.doubleBlink && now >= blinkLockUntil && now >= cooldownUntil &&
                (now - lastCloseTs) < 700L
            ) {
                onTurn(1, "blink")
                blinkLockUntil = now + 1600L
                cooldownUntil = now + 1200L
                dwellAccum = 0
                armed = false
            }
            lastCloseTs = now
        }
        wasClosed = closed

        if (!f.hasFace) {
            if (noFaceSince == 0L) noFaceSince = now
            // 离开画面超过 3 秒后重新校准，换姿势/换人后不用重启服务。
            if (now - noFaceSince > 3000L) {
                calibrated = false
                calibrationCount = 0
                calibrationSum = 0f
            }
            smooth *= 0.5f
            dwellAccum = 0
            armed = true
            publish(f, 0, 0f, calibrating = false)
            return
        }
        noFaceSince = 0L

        if (calibrationMode != settings.useHeadTurn) {
            calibrationMode = settings.useHeadTurn
            resetCalibrationInternal()
        }

        val yawNorm = (f.yawDeg / 20f).coerceIn(-1.5f, 1.5f)
        val raw = if (settings.useHeadTurn) {
            yawNorm
        } else {
            (f.gazeX * 1.5f + yawNorm * 0.25f).coerceIn(-1.5f, 1.5f)
        }

        if (!calibrated) {
            calibrationSum += raw
            calibrationCount++
            smooth = 0f
            if (calibrationCount >= 10) {
                neutral = (calibrationSum / calibrationCount).coerceIn(-0.9f, 0.9f)
                calibrated = true
            }
            publish(f, 0, 0f, calibrating = !calibrated)
            return
        }

        val centered = (raw - neutral).coerceIn(-1.5f, 1.5f)
        smooth = smooth * 0.6f + centered * 0.4f

        var dwellDir = 0
        var progress = 0f
        if (!armed) {
            if (abs(smooth) < settings.onThreshold * 0.6f) {
                armed = true
                dwellAccum = 0
            }
        }
        if (armed && now >= cooldownUntil) {
            if (smooth > settings.onThreshold) dwellDir = 1
            else if (smooth < -settings.onThreshold) dwellDir = -1
            if (dwellDir != 0) {
                dwellAccum += dt
                progress = dwellAccum.toFloat() / settings.dwellMs
                if (dwellAccum >= settings.dwellMs) {
                    onTurn(dwellDir, "gaze")
                    armed = false
                    dwellAccum = 0
                    progress = 0f
                    cooldownUntil = now + 1400L
                }
            } else {
                dwellAccum = 0
            }
        }

        // 连续读数明显居中时，极慢地修正零点，抵消坐姿/屏幕角度带来的漂移。
        if (dwellDir == 0 && armed && abs(smooth) < settings.onThreshold * 0.5f) {
            neutral += centered * 0.003f
        }

        publish(f, dwellDir, progress, calibrating = false)
    }

    /** 设置变化或服务重启时调用。 */
    @Synchronized
    fun resetCalibration() {
        resetCalibrationInternal()
        calibrationMode = null
    }

    private fun resetCalibrationInternal() {
        calibrated = false
        calibrationSum = 0f
        calibrationCount = 0
        neutral = 0f
        smooth = 0f
        dwellAccum = 0
        armed = true
    }

    private fun publish(f: GazeFrame, dir: Int, progress: Float, calibrating: Boolean) {
        TurnBus.gazeState.value = GazeUiState(
            enabled = settings.eyeEnabled,
            hasFace = f.hasFace,
            gazeX = smooth,
            dwellDir = dir,
            dwellProgress = progress.coerceIn(0f, 1f),
            blinking = f.blinkLeft > 0.55f && f.blinkRight > 0.55f,
            fps = f.fps,
            threshold = settings.onThreshold,
            calibrating = calibrating,
        )
    }
}
