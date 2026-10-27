package com.turntopage.turner

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

/**
 * MediaPipe FaceLandmarker 封装（模型打包在 assets，离线运行，无需谷歌服务）。
 * 从 478 个关键点推导：眼球水平视线（blendshapes eyeLookIn/Out）、眨眼幅度、头部偏转。
 */
class FaceLandmarkerHelper(private val onGaze: (GazeFrame) -> Unit) {

    private var landmarker: FaceLandmarker? = null
    private var ts = 0L
    private var lastResultAt = 0L
    private var lastLogAt = 0L
    private var fps = 0f
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    fun setup(context: Context): Boolean = try {
        val base = BaseOptions.builder().setModelAssetPath("face_landmarker.task").build()
        val opts = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setOutputFaceBlendshapes(true)
            .setOutputFacialTransformationMatrixes(false)
            .setResultListener { result, _ ->
                busy.set(false)
                handleResult(result)
            }
            .setErrorListener { e ->
                busy.set(false)
                Log.e(TAG, "mediapipe error", e)
            }
            .build()
        landmarker = FaceLandmarker.createFromOptions(context, opts)
        Log.i(TAG, "FaceLandmarker ready")
        true
    } catch (t: Throwable) {
        Log.e(TAG, "FaceLandmarker init failed", t)
        false
    }

    fun detectAsync(bitmap: Bitmap) {
        val l = landmarker ?: return
        // LIVE_STREAM 同一时刻只接受一帧，避免 Android 16 上堆积导致 Task is busy。
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

    private fun handleResult(result: FaceLandmarkerResult) {
        val now = SystemClock.uptimeMillis()
        val dt = now - lastResultAt
        lastResultAt = now
        if (dt in 1..2000) {
            fps = if (fps == 0f) 1000f / dt else fps * 0.9f + (1000f / dt) * 0.1f
        }
        val faces = result.faceLandmarks()
        if (faces.isEmpty()) {
            if (now - lastLogAt > 3000) {
                lastLogAt = now
                Log.i(TAG, "face=0 fps=${fps.toInt()}")
            }
            onGaze(GazeFrame(false, 0f, 0f, 0f, 0f, fps))
            return
        }

        var lookR = 0f
        var lookL = 0f
        var blinkL = 0f
        var blinkR = 0f
        val bs = result.faceBlendshapes()
        if (bs.isPresent) {
            for (c in bs.get()[0]) when (c.categoryName()) {
                "eyeLookOutRight" -> lookR += c.score()
                "eyeLookInLeft" -> lookR += c.score()
                "eyeLookOutLeft" -> lookL += c.score()
                "eyeLookInRight" -> lookL += c.score()
                "eyeBlinkLeft" -> blinkL = c.score()
                "eyeBlinkRight" -> blinkR = c.score()
            }
        }

        // 头部偏转代理：鼻尖(1)相对左右脸颊(234/454)的偏移，>0 表示头转向用户自己的右侧
        val pts = faces[0]
        var yawDeg = 0f
        if (pts.size > 454) {
            val nose = pts[1]
            val left = pts[234]
            val right = pts[454]
            val faceW = right.x() - left.x()
            if (faceW > 1e-4f) {
                val raw = ((right.x() - nose.x()) - (nose.x() - left.x())) / faceW
                yawDeg = raw * 45f
            }
        }

        val gazeX = ((lookR - lookL) * 2.0f).coerceIn(-1f, 1f)
        if (now - lastLogAt > 3000) {
            lastLogAt = now
            Log.i(
                TAG,
                "face=1 fps=${fps.toInt()} gazeX=%.2f yaw=%.1f blink=%.2f/%.2f"
                    .format(gazeX, yawDeg, blinkL, blinkR)
            )
        }
        onGaze(GazeFrame(true, gazeX, yawDeg, blinkL, blinkR, fps))
    }

    companion object {
        private const val TAG = "Turn/Face"
    }
}
