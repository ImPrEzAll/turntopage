package com.turntopage.turner

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 跨应用翻页核心：无障碍服务。
 *  - dispatchGesture 向前台应用注入滑动/点击 → 翻页
 *  - 音量键拦截翻页、左右边缘热区、悬浮球（◀ ▶ / 眼动开关 / 设置）
 *  - 眼动/空中手势识别在 EyeTrackingService（前台相机服务）中运行，经 TurnBus 调到这里
 */
class TurnAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "Turn/Svc"
        var instance: TurnAccessibilityService? = null
            private set
    }

    lateinit var settings: AppSettings
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var bubble: BubbleView? = null
    private var panel: LinearLayout? = null
    private var leftZone: ZoneView? = null
    private var rightZone: ZoneView? = null
    private var expanded = false
    private var overlayHidden = false
    private val d get() = resources.displayMetrics.density

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = AppSettings(this)
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        // 必须显式请求按键过滤，否则物理音量键不会进入 onKeyEvent。
        val info = serviceInfo
        if (info != null) {
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            serviceInfo = info
            Log.i(TAG, "key filter requested flags=0x${info.flags.toString(16)}")
        } else {
            Log.w(TAG, "serviceInfo unavailable, volume key filter not requested")
        }
        mainHandler.post {
            applyZones()
            showBubble()
        }
        Log.i(TAG, "connected")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        mainHandler.post {
            removeAllOverlays()
        }
        Log.i(TAG, "destroyed")
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    // ---------------- 翻页动作 ----------------

    /**
     * dir: +1 下一页 / -1 上一页。向前台应用注入手势。
     *
     * 关键：手势起点必须避开左右边缘热区，否则注入的触摸会被自己的热区窗口吃掉。
     * 滑动从屏幕 20%/80% 处开始，点击落在 25%/75% 区域，仍在多数阅读 App 的翻页区内。
     * 可从任意线程调用（眼动结果在 MediaPipe 线程回调）。
     */
    fun performTurn(dirRaw: Int, source: String): Boolean {
        if (!::settings.isInitialized) return false
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mainHandler.post { performTurnOnMain(dirRaw, source) }
            return true
        }
        return performTurnOnMain(dirRaw, source)
    }

    private fun performTurnOnMain(dirRaw: Int, source: String): Boolean {
        val d = if (settings.invertDirection) -dirRaw else dirRaw
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()
        val y = h * 0.5f
        val b = GestureDescription.Builder()
        when (settings.actionMode) {
            AppSettings.MODE_TAP -> {
                // 25% / 75%：避开 40dp 热区，同时落在常见阅读 App 的左右点击翻页区。
                val x = if (d > 0) w * 0.75f else w * 0.25f
                val path = Path().apply { moveTo(x, y); lineTo(x, y) }
                b.addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            }
            else -> {
                val len = w * settings.swipeLengthRatio
                val x1 = if (d > 0) w * 0.80f else w * 0.20f
                val rawX2 = if (d > 0) x1 - len else x1 + len
                val x2 = rawX2.coerceIn(w * 0.04f, w * 0.96f)
                val path = Path().apply { moveTo(x1, y); lineTo(x2, y) }
                b.addStroke(
                    GestureDescription.StrokeDescription(path, 0L, settings.swipeDurationMs.toLong())
                )
            }
        }
        val ok = dispatchGesture(b.build(), null, mainHandler)
        Log.i(TAG, "turn dir=$d source=$source dispatched=$ok")
        mainHandler.post { bubble?.flash() }
        return ok
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!::settings.isInitialized || !settings.volumeTurn) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    performTurn(-1, "volume")
                }
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    performTurn(1, "volume")
                }
                true
            }
            else -> false
        }
    }

    // ---------------- 悬浮窗 ----------------

    private fun overlayParams(w: Int, h: Int, x: Int, y: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }

    private fun showBubble() {
        val w = wm ?: return
        val size = (56 * d).toInt()
        val view = BubbleView(this)
        view.onTap = { togglePanel() }
        view.onDrag = { dx, dy -> moveBubble(dx, dy) }
        view.visibility = if (overlayHidden) View.GONE else View.VISIBLE
        bubble = view
        w.addView(view, overlayParams(size, size, settings.bubbleX, settings.bubbleY))
        clampBubble()
    }

    /** 旋转屏幕后把悬浮球拉回新的屏幕范围内。 */
    private fun clampBubble() {
        val b = bubble ?: return
        val p = b.layoutParams as? WindowManager.LayoutParams ?: return
        val dm = resources.displayMetrics
        val size = if (b.width > 0) b.width else (56 * d).toInt()
        p.x = p.x.coerceIn(0, (dm.widthPixels - size).coerceAtLeast(0))
        p.y = p.y.coerceIn(0, (dm.heightPixels - size).coerceAtLeast(0))
        settings.bubbleX = p.x
        settings.bubbleY = p.y
        try {
            wm?.updateViewLayout(b, p)
        } catch (_: Exception) {
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        mainHandler.post {
            hidePanel()
            applyZones()
            clampBubble()
            Log.i(TAG, "configuration changed, overlays reapplied")
        }
    }

    private fun moveBubble(dx: Int, dy: Int) {
        val b = bubble ?: return
        val p = b.layoutParams as WindowManager.LayoutParams
        val dm = resources.displayMetrics
        p.x = (p.x + dx).coerceIn(0, dm.widthPixels - b.width)
        p.y = (p.y + dy).coerceIn(0, dm.heightPixels - b.height)
        settings.bubbleX = p.x
        settings.bubbleY = p.y
        try {
            wm?.updateViewLayout(b, p)
        } catch (_: Exception) {
        }
    }

    fun applyZones() {
        val w = wm ?: return
        leftZone?.let { try { w.removeView(it) } catch (_: Exception) {} }
        rightZone?.let { try { w.removeView(it) } catch (_: Exception) {} }
        leftZone = null; rightZone = null
        if (!::settings.isInitialized || !settings.zonesEnabled) return
        val zw = (settings.zoneWidthDp * d).toInt()
        val h = resources.displayMetrics.heightPixels
        val lz = ZoneView(this, -1).apply {
            onTurn = { performTurn(-1, "zone") }
            visibility = if (overlayHidden) View.GONE else View.VISIBLE
        }
        val rz = ZoneView(this, 1).apply {
            onTurn = { performTurn(1, "zone") }
            visibility = if (overlayHidden) View.GONE else View.VISIBLE
        }
        leftZone = lz; rightZone = rz
        w.addView(lz, overlayParams(zw, h, 0, 0))
        w.addView(rz, overlayParams(zw, h, resources.displayMetrics.widthPixels - zw, 0))
        // 后添加的窗口 Z 序更高：把悬浮球重新压回热区之上
        bubble?.let { b ->
            val bp = b.layoutParams
            try {
                w.removeView(b)
            } catch (_: Exception) {
            }
            try {
                w.addView(b, bp)
            } catch (_: Exception) {
            }
        }
    }

    private fun togglePanel() {
        if (expanded) hidePanel() else showPanel()
    }

    private fun showPanel() {
        val w = wm ?: return
        if (panel != null) return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = pill(0xF2FFFFFF.toInt(), 22 * d)
            setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
        }
        row.addView(panelBtn("◀") { performTurn(-1, "panel") })
        row.addView(panelBtn("▶") { performTurn(1, "panel") })
        val trackLabel = when {
            EyeTrackingService.running && EyeTrackingService.handMode -> "手·开"
            EyeTrackingService.running -> "眼·开"
            else -> "识别·关"
        }
        row.addView(panelBtn(trackLabel) { toggleTracking() })
        row.addView(panelBtn("⚙") { openMain() })
        row.addView(panelBtn("×") { hidePanel() })
        panel = row
        val bp = bubble?.layoutParams as? WindowManager.LayoutParams
        val lp = overlayParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            bp?.x ?: 0, (bp?.y ?: 0) + (64 * d).toInt()
        )
        w.addView(row, lp)
        expanded = true
        row.post {
            val dm = resources.displayMetrics
            val current = row.layoutParams as? WindowManager.LayoutParams ?: return@post
            current.x = current.x.coerceIn(0, (dm.widthPixels - row.width).coerceAtLeast(0))
            current.y = current.y.coerceIn(0, (dm.heightPixels - row.height).coerceAtLeast(0))
            try {
                wm?.updateViewLayout(row, current)
            } catch (_: Exception) {
            }
        }
    }

    private fun hidePanel() {
        panel?.let {
            try {
                wm?.removeView(it)
            } catch (_: Exception) {
            }
        }
        panel = null
        expanded = false
    }

    private fun panelBtn(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(0xFF222222.toInt())
            typeface = Typeface.DEFAULT_BOLD
            background = pill(0xFFEEEEEE.toInt(), 16 * d)
            setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = (5 * d).toInt(); marginEnd = (5 * d).toInt() }
            layoutParams = lp
            setOnClickListener { onClick() }
        }

    private fun pill(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = radius }

    /** 悬浮球面板上的识别开关：只负责关掉当前模式；重新开启时回主页选眼动或手势。 */
    private fun toggleTracking() {
        if (EyeTrackingService.running) {
            val wasHand = EyeTrackingService.handMode
            settings.eyeEnabled = false
            settings.handEnabled = false
            EyeTrackingService.stop(this)
            TurnBus.gazeState.value = GazeUiState()
            TurnBus.handState.value = HandUiState()
            toast(if (wasHand) "空中手势翻页已关闭" else "眼动翻页已关闭")
            // stopService 异步完成，立即重建面板会读到旧的运行状态，直接收起更稳妥。
            hidePanel()
        } else {
            toast("请到主界面选择眼动或空中手势")
            openMain()
        }
    }

    private fun openMain() {
        hidePanel()
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** 打开自家设置页时收起悬浮球和热区，避免挡住新界面、也避免边缘误触。 */
    fun setOverlayHidden(hidden: Boolean) {
        mainHandler.post {
            overlayHidden = hidden
            val visibility = if (hidden) View.GONE else View.VISIBLE
            bubble?.visibility = visibility
            leftZone?.visibility = visibility
            rightZone?.visibility = visibility
            if (hidden) hidePanel()
        }
    }

    private fun removeAllOverlays() {
        val w = wm ?: return
        listOf(bubble, panel, leftZone, rightZone).forEach { v ->
            v?.let { try { w.removeView(it) } catch (_: Exception) {} }
        }
        bubble = null; panel = null; leftZone = null; rightZone = null
        expanded = false
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
