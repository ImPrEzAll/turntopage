package com.turntopage.turner

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.sqrt

/**
 * MediaPipe HandLandmarker 封装（模型离线打包在 assets）。
 * 用 21 个手部关键点做自定义手势；x 轴翻转成“照镜子”方向，方便判断用户左右挥手。
 */
class HandLandmarkerHelper(private val onHand: (HandFrame) -> Unit) {

    private var landmarker: HandLandmarker? = null
    private var ts = 0L
    private var lastResultAt = 0L
    private var lastLogAt = 0L
    private var fps = 0f
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    fun setup(context: Context): Boolean = try {
        val base = BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build()
        val opts = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ ->
                busy.set(false)
                handleResult(result)
            }
            .setErrorListener { e ->
                busy.set(false)
                Log.e(TAG, "mediapipe error", e)
            }
            .build()
        landmarker = HandLandmarker.createFromOptions(context, opts)
        Log.i(TAG, "HandLandmarker ready")
        true
    } catch (t: Throwable) {
        Log.e(TAG, "HandLandmarker init failed", t)
        false
    }

    fun detectAsync(bitmap: Bitmap) {
        val l = landmarker ?: return
        if (!busy.compareAndSet(false, true)) return
        val now = SystemClock.uptimeMillis()
        ts = if (now > ts) now else ts + 1
        val img: MPImage = BitmapImageBuilder(bitmap).build()
        try {
            l.detectAsync(img, ts)
        } catch (t: Throwable) {
            busy.set(false)
            Log.e(TAG, "detectAsync failed", t)
        }
    }

    fun close() {
        try {
            landmarker?.close()
        } catch (_: Throwable) {
        }
        landmarker = null
    }

    private fun handleResult(result: HandLandmarkerResult) {
        val now = SystemClock.uptimeMillis()
        val dt = now - lastResultAt
        lastResultAt = now
        if (dt in 1..2000) {
            fps = if (fps == 0f) 1000f / dt else fps * 0.9f + (1000f / dt) * 0.1f
        }
        val hands = result.landmarks()
        if (hands.isEmpty()) {
            if (now - lastLogAt > 3000) {
                lastLogAt = now
                Log.i(TAG, "hand=0 fps=${fps.toInt()}")
            }
            onHand(HandFrame(false, emptyList(), fps))
            return
        }

        val pts = hands[0].map { HandPoint(1f - it.x(), it.y()) }
        if (now - lastLogAt > 3000) {
            lastLogAt = now
            val palmX = (pts[0].x + pts[5].x + pts[9].x + pts[13].x + pts[17].x) / 5f
            val scale = dist(pts[0], pts[9]).coerceAtLeast(0.05f)
            val pinch = dist(pts[4], pts[8]) / scale
            Log.i(
                TAG,
                "hand=1 fps=${fps.toInt()} palmX=%.2f pinch=%.2f"
                    .format(palmX, pinch)
            )
        }
        onHand(HandFrame(true, pts, fps))
    }

    private fun dist(a: HandPoint, b: HandPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private companion object {
        const val TAG = "Turn/Hand"
    }
}
