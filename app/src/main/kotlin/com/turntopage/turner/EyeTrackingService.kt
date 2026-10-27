package com.turntopage.turner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import android.util.Size
import java.util.concurrent.Executors

/**
 * 摄像头识别前台服务（camera 类型），两种模式二选一：
 *  - 眼动：FaceLandmarker → GazeController（注视边缘 / 双击眨眼 / 转头）
 *  - 空中手势：HandLandmarker → HandGestureController（手掌左右挥 / 拇指食指捏合）
 * 只做图像分析，无预览界面；息屏时暂停取帧。两种模型不同时加载，避免老机器过热。
 */
class EyeTrackingService : LifecycleService() {

    companion object {
        private const val TAG = "Turn/Vision"
        private const val CHANNEL_ID = "vision_tracking"
        private const val NOTIF_ID = 42

        @Volatile
        var running = false
            private set

        /** 当前服务是否运行在空中手势模式；供悬浮球与阅读页显示状态。 */
        @Volatile
        var handMode = false
            private set

        @Volatile
        private var current: EyeTrackingService? = null

        /** 调试/验收用：模拟一段“先看中间，再持续向右看”的视线序列。 */
        fun simulateGaze(dir: Int): Boolean {
            val s = current ?: return false
            if (handMode) return false
            s.startGazeSimulation(dir)
            return true
        }

        /** 调试/验收用：0=向左挥（下一页），1=捏合，2=向右挥（上一页）。 */
        fun simulateHand(kind: Int): Boolean {
            val s = current ?: return false
            if (!handMode) return false
            s.startHandSimulation(kind)
            return true
        }

        fun start(context: Context) {
            val i = Intent(context, EyeTrackingService::class.java)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EyeTrackingService::class.java))
        }
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var faceHelper: FaceLandmarkerHelper? = null
    private var handHelper: HandLandmarkerHelper? = null
    private var gaze: GazeController? = null
    private var handCtrl: HandGestureController? = null
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var bound = false
    private var screenOn = true
    private var lastAnalyzedAt = 0L
    private var lastLoggedRotation = -1
    private var appliedRotation = -1
    private var rotationSettleUntil = 0L

    /** 不依赖 Activity 重建：每 400ms 查一次屏幕朝向，正反横屏来回转也能跟上。 */
    private val rotationWatcher = object : Runnable {
        override fun run() {
            syncDisplayRotation()
            ui.postDelayed(this, 400L)
        }
    }
    @Volatile
    private var simulating = false
    private var simGeneration = 0
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    unbindCamera()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    bindCamera()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        current = this
        val s = AppSettings(this)
        val hand = s.handEnabled && !s.eyeEnabled
        handMode = hand
        appliedRotation = displayRotation()
        startForegroundWithCamera(hand)
        ui.post(rotationWatcher)
        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        if (hand) {
            TurnBus.handState.value = HandUiState(enabled = true)
            val ctrl = HandGestureController(
                onTurn = { dir, source -> TurnBus.requestTurn(dir, source) },
                onState = { st -> TurnBus.handState.value = st },
                pinchDirProvider = { if (s.handPinchPrev) -1 else 1 },
                now = SystemClock::uptimeMillis,
            )
            handCtrl = ctrl
            val h = HandLandmarkerHelper { frame -> ctrl.onFrame(frame) }
            handHelper = h
            if (!h.setup(this)) {
                Toast.makeText(this, "手势引擎初始化失败", Toast.LENGTH_LONG).show()
                s.handEnabled = false
                stopSelf()
                return
            }
            Log.i(TAG, "hand service started")
        } else {
            gaze = GazeController(s) { dir, source -> TurnBus.requestTurn(dir, source) }
            val f = FaceLandmarkerHelper { frame -> gaze?.onFrame(frame) }
            faceHelper = f
            if (!f.setup(this)) {
                Toast.makeText(this, "眼动引擎初始化失败", Toast.LENGTH_LONG).show()
                s.eyeEnabled = false
                stopSelf()
                return
            }
            Log.i(TAG, "eye service started")
        }
        if (screenOn) bindCamera()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        running = false
        handMode = false
        current = null
        simGeneration++
        simulating = false
        ui.removeCallbacks(rotationWatcher)
        ui.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Throwable) {
        }
        unbindCamera()
        faceHelper?.close()
        faceHelper = null
        handHelper?.close()
        handHelper = null
        gaze = null
        handCtrl = null
        TurnBus.gazeState.value = GazeUiState()
        TurnBus.handState.value = HandUiState()
        Log.i(TAG, "camera service stopped")
        super.onDestroy()
    }

    private fun startForegroundWithCamera(hand: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "摄像头翻页", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (hand) "空中手势翻页运行中" else "眼动翻页运行中"
        val text = if (hand) "手掌左右挥动翻页；拇指食指捏合翻下一页。点按此处管理"
        else "看向屏幕右/左边缘约 1 秒翻页；点按此处管理"
        val n: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    @android.annotation.SuppressLint("RestrictedApi")
    private fun bindCamera() {
        if (bound) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val sel = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(480, 640),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()
                appliedRotation = displayRotation()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(sel)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    // 横屏 / 平板：让 CameraX 按当前屏幕方向输出，位图旋转后 x 轴始终是屏幕横向，
                    // 眼动与挥手判断不会因为设备横过来而变成上下。
                    .setTargetRotation(appliedRotation)
                    .build()
                this@EyeTrackingService.analysis = analysis
                analysis.setAnalyzer(executor) { proxy ->
                    try {
                        processFrame(proxy)
                    } finally {
                        proxy.close()
                    }
                }
                p.unbindAll()
                p.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
                bound = true
                Log.i(TAG, "front camera bound, hand=$handMode")
            } catch (t: Throwable) {
                Log.e(TAG, "camera bind failed", t)
                Toast.makeText(this, "相机启动失败: ${t.message}", Toast.LENGTH_LONG).show()
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun unbindCamera() {
        try {
            provider?.unbindAll()
        } catch (_: Throwable) {
        }
        bound = false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 服务比 Activity 活得久；旋转时直接更新分析目标的朝向，无需重启摄像头。
        syncDisplayRotation()
    }

    /** 屏幕朝向变了就更新 CameraX 目标；短暂跳过几帧，避免半帧旧方向触发误判。 */
    private fun syncDisplayRotation() {
        val rot = displayRotation()
        if (rot == appliedRotation) return
        appliedRotation = rot
        analysis?.targetRotation = rot
        lastLoggedRotation = -1
        rotationSettleUntil = SystemClock.uptimeMillis() + 300L
        if (!simulating) {
            handCtrl?.reset()
            gaze?.resetCalibration()
        }
        Log.i(TAG, "display rotation -> $rot, camera target updated")
    }

    /** 当前屏幕旋转（0/90/180/270），用于 CameraX 目标朝向。 */
    private fun displayRotation(): Int = try {
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    } catch (_: Throwable) {
        Surface.ROTATION_0
    }

    private fun processFrame(proxy: ImageProxy) {
        // 眼动 20fps / 手势 15fps 足够；老机器上明显降低发热与耗电。
        val now = SystemClock.uptimeMillis()
        val interval = if (handMode) 66L else 50L
        if (simulating || now < rotationSettleUntil || now - lastAnalyzedAt < interval) return
        lastAnalyzedAt = now
        val bmp0 = try {
            proxy.toBitmap()
        } catch (t: Throwable) {
            Log.e(TAG, "toBitmap failed", t)
            return
        }
        val rot = proxy.imageInfo.rotationDegrees
        if (rot != lastLoggedRotation) {
            lastLoggedRotation = rot
            Log.i(TAG, "frame rotation=$rot display=${displayRotation()} raw=${bmp0.width}x${bmp0.height}")
        }
        val bmp: Bitmap = if (rot != 0) {
            Bitmap.createBitmap(
                bmp0, 0, 0, bmp0.width, bmp0.height,
                Matrix().apply { postRotate(rot.toFloat()) }, true
            )
        } else bmp0
        if (handMode) handHelper?.detectAsync(bmp) else faceHelper?.detectAsync(bmp)
    }

    /** 仅用于调试：绕过相机，向状态机灌入“中 → 右/左”的合成视线。 */
    private fun startGazeSimulation(dir: Int) {
        val g = gaze ?: return
        val gen = ++simGeneration
        simulating = true
        val start = SystemClock.uptimeMillis()
        g.resetCalibration()
        Log.i(TAG, "simulate gaze dir=$dir")
        val runnable = object : Runnable {
            override fun run() {
                if (gen != simGeneration || !running) {
                    simulating = false
                    return
                }
                val elapsed = SystemClock.uptimeMillis() - start
                val active = elapsed >= 700L
                val gx = if (active) dir.toFloat() else 0f
                val yaw = if (active) dir * 22f else 0f
                g.onFrame(GazeFrame(true, gx, yaw, 0f, 0f, 16f))
                if (elapsed < 3000L) {
                    ui.postDelayed(this, 60L)
                } else {
                    simulating = false
                    g.resetCalibration()
                    Log.i(TAG, "simulation finished")
                }
            }
        }
        ui.post(runnable)
    }

    /** 仅用于调试：绕过相机，喂入合成手部关键点，验收挥手/捏合 → 翻页全链路。 */
    private fun startHandSimulation(kind: Int) {
        val ctrl = handCtrl ?: return
        val gen = ++simGeneration
        simulating = true
        ctrl.reset()
        val start = SystemClock.uptimeMillis()
        val name = when (kind) {
            1 -> "pinch"
            2 -> "wave-right"
            else -> "wave-left"
        }
        Log.i(TAG, "simulate hand $name")
        val runnable = object : Runnable {
            override fun run() {
                if (gen != simGeneration || !running) {
                    simulating = false
                    return
                }
                val elapsed = SystemClock.uptimeMillis() - start
                when (kind) {
                    1 -> {
                        // 0~120ms 张手，180~480ms 捏合，540ms 后张开。
                        val pinch = elapsed in 180L..480L
                        ctrl.onFrame(HandFrame(true, handPoints(0f, pinch), 15f))
                    }
                    else -> {
                        // 0~120ms 停住，120~540ms 快速横向挥动，之后停住。
                        val startShift = if (kind == 2) -0.12f else 0.12f
                        val endShift = if (kind == 2) 0.24f else -0.24f
                        val t = ((elapsed - 120L) / 420f).coerceIn(0f, 1f)
                        val shift = startShift + (endShift - startShift) * t
                        ctrl.onFrame(HandFrame(true, handPoints(shift, false), 15f))
                    }
                }
                if (elapsed < 1200L) {
                    ui.postDelayed(this, 60L)
                } else {
                    simulating = false
                    ctrl.onFrame(HandFrame(false, emptyList(), 15f))
                    Log.i(TAG, "hand simulation finished")
                }
            }
        }
        ui.post(runnable)
    }

    /** 21 个合成关键点（已镜像）；pinch=true 时把拇指尖移到食指尖附近。 */
    private fun handPoints(shift: Float, pinch: Boolean): List<HandPoint> {
        val base = arrayListOf(
            HandPoint(0.50f, 0.90f),
            HandPoint(0.43f, 0.86f),
            HandPoint(0.35f, 0.80f),
            HandPoint(0.29f, 0.72f),
            HandPoint(0.24f, 0.64f),
            HandPoint(0.40f, 0.62f),
            HandPoint(0.36f, 0.48f),
            HandPoint(0.33f, 0.38f),
            HandPoint(0.30f, 0.28f),
            HandPoint(0.50f, 0.60f),
            HandPoint(0.50f, 0.44f),
            HandPoint(0.50f, 0.33f),
            HandPoint(0.50f, 0.23f),
            HandPoint(0.60f, 0.62f),
            HandPoint(0.64f, 0.47f),
            HandPoint(0.67f, 0.36f),
            HandPoint(0.70f, 0.26f),
            HandPoint(0.70f, 0.68f),
            HandPoint(0.76f, 0.56f),
            HandPoint(0.80f, 0.47f),
            HandPoint(0.83f, 0.40f),
        )
        if (pinch) {
            base[2] = HandPoint(0.36f, 0.52f)
            base[3] = HandPoint(0.32f, 0.40f)
            base[4] = HandPoint(0.31f, 0.29f)
        }
        return base.map { HandPoint((it.x + shift).coerceIn(0.01f, 0.99f), it.y) }
    }
}
