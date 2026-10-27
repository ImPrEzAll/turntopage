package com.turntopage.turner

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import kotlin.math.roundToInt

/**
 * 主界面：单一大按钮按“下一步”引导，两种识别方式用卡片选择，
 * 技术参数收进折叠区，避免一上来就是一堆开关。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private var wideLayout = false
    private var switching = false
    private var pendingStart = 0
    private var selectedHand = true
    private var advancedOpen = false
    private var usageOpen = false
    private var overlayHiddenFor: TurnAccessibilityService? = null
    private var lastPaintKey = ""

    private lateinit var statusDot: View
    private lateinit var heroStatus: TextView
    private lateinit var heroDetail: TextView
    private lateinit var heroHint: TextView
    private lateinit var pillAccess: TextView
    private lateinit var pillCamera: TextView
    private lateinit var primary: TextView
    private lateinit var handCard: ModeCard
    private lateinit var eyeCard: ModeCard
    private lateinit var guideTitle: TextView
    private lateinit var guideBody: TextView
    private lateinit var advancedContent: LinearLayout
    private lateinit var advancedToggle: TextView
    private lateinit var usageContent: LinearLayout
    private lateinit var usageToggle: TextView

    private val ui = Handler(Looper.getMainLooper())

    private val statusTicker = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 800L)
        }
    }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refresh()
            if (cameraGranted()) toast("相机已允许，点“开始”就能用了")
        }

    private val dpf get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        selectedHand = when {
            settings.handEnabled -> true
            settings.eyeEnabled -> false
            else -> true
        }
        window.statusBarColor = PAPER
        window.navigationBarColor = PAPER
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFFFDF9F2.toInt(), 0xFFF5EBD9.toInt()),
            )
        }
        val cfg = resources.configuration
        wideLayout = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                cfg.smallestScreenWidthDp >= 600
        val side = when {
            wideLayout -> 28
            cfg.screenWidthDp > 760 -> ((cfg.screenWidthDp - 700) / 2).coerceAtLeast(20)
            else -> 20
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dp(side),
                dp(if (wideLayout) 12 else 14),
                dp(side),
                dp(if (wideLayout) 28 else 36),
            )
        }
        scroll.addView(root)
        setContentView(scroll)
        buildUi()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        overlayHiddenFor = null
        hideOwnOverlay()
        refresh()
        ui.removeCallbacks(statusTicker)
        ui.post(statusTicker)
    }

    override fun onPause() {
        ui.removeCallbacks(statusTicker)
        if (overlayHiddenFor != null) TurnAccessibilityService.instance?.setOverlayHidden(false)
        overlayHiddenFor = null
        super.onPause()
    }

    /** 自己界面打开时把悬浮球/热区收起来；服务晚于界面连接时由 refresh 补一次。 */
    private fun hideOwnOverlay() {
        val svc = TurnAccessibilityService.instance ?: return
        if (overlayHiddenFor === svc) return
        svc.setOverlayHidden(true)
        overlayHiddenFor = svc
    }

    // ---------------- 状态 ----------------

    private fun serviceEnabled(): Boolean {
        if (TurnAccessibilityService.instance != null) return true
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        val me = ComponentName(this, TurnAccessibilityService::class.java)
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any {
                val si = it.resolveInfo.serviceInfo
                si.packageName == me.packageName && si.name == me.className
            }
    }

    private fun cameraGranted() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    private fun refresh() {
        hideOwnOverlay()
        val running = EyeTrackingService.running
        val runningHand = running && EyeTrackingService.handMode
        if (running && !switching) selectedHand = runningHand
        val activeHand = if (running) runningHand else selectedHand
        val serviceOk = serviceEnabled()
        val camOk = cameraGranted()
        val g = TurnBus.gazeState.value
        val h = TurnBus.handState.value

        when {
            switching -> {
                heroStatus.text = "正在切换识别方式…"
                heroDetail.text = "摄像头和模型重启中，大约需要 1 秒"
            }
            running -> {
                heroStatus.text = if (runningHand) "空中手势识别中" else "眼动识别中"
                heroDetail.text = if (runningHand) {
                    when {
                        !h.hasHand -> "还没看到手 · 把手掌举到镜头前约一臂远"
                        else -> buildString {
                            append("已看到手 · ${h.fps.toInt()}fps")
                            if (h.lastGesture.isNotEmpty()) append(" · 刚刚：${h.lastGesture}")
                        }
                    }
                } else {
                    when {
                        g.calibrating -> "正在校准 · 请看向屏幕中央"
                        !g.hasFace -> "还没看到人脸 · 把脸放进取景框"
                        else -> buildString {
                            append("已看到人脸 · ${g.fps.toInt()}fps")
                            if (g.dwellDir > 0) append(" · 正在看右边")
                            if (g.dwellDir < 0) append(" · 正在看左边")
                        }
                    }
                }
            }
            !serviceOk -> {
                heroStatus.text = "第 1 步 · 开启无障碍服务"
                heroDetail.text = "没有它，翻页动作无法作用到其他 App"
            }
            !camOk -> {
                heroStatus.text = "第 2 步 · 允许使用相机"
                heroDetail.text = "识别只在本机进行，画面不会上传"
            }
            else -> {
                heroStatus.text = "准备就绪"
                heroDetail.text = "当前选择：${if (selectedHand) "空中手势" else "眼动翻页"} · 点下方开始"
            }
        }

        val key = "$running|$runningHand|$serviceOk|$camOk|$switching|$selectedHand|$activeHand"
        if (key != lastPaintKey) {
            lastPaintKey = key
            statusDot.background = circle(
                when {
                    switching -> AMBER
                    running -> OK
                    !serviceOk || !camOk -> WARN
                    else -> OK
                }
            )
            heroHint.text = if (activeHand) {
                "左挥 下一页   ·   右挥 上一页   ·   捏合 ${if (settings.handPinchPrev) "上一页" else "下一页"}"
            } else {
                "看左边缘 上一页   ·   看右边缘 下一页   ·   眨眼 下一页"
            }
            stylePill(pillAccess, "无障碍服务", serviceOk)
            stylePill(pillCamera, "相机权限", camOk)
            paintPrimary(running, serviceOk, camOk)
            paintModeCard(handCard, running, runningHand, !running && selectedHand)
            paintModeCard(eyeCard, running, running && !runningHand, !running && !selectedHand)
            updateGuide(activeHand)
        }
    }

    private fun stylePill(pill: TextView, label: String, ok: Boolean) {
        pill.text = if (ok) "●  $label 已就绪" else "●  $label 待设置"
        pill.setTextColor(if (ok) OK else WARN)
        pill.background = bg(if (ok) OK_BG else WARN_BG, 99)
    }

    private fun paintPrimary(running: Boolean, serviceOk: Boolean, camOk: Boolean) {
        when {
            switching -> {
                primary.text = "正在切换…"
                primary.setTextColor(0xFF8D8578.toInt())
                primary.background = bg(0xFFEDE6D7.toInt(), 18)
                primary.isEnabled = false
                primary.elevation = 0f
            }
            running -> {
                primary.text = "停止识别"
                primary.setTextColor(0xFFA8452F.toInt())
                primary.background = ripple(bg(0xFFFFFFFF.toInt(), 18, 1.5f, 0xFFE5CDBA.toInt()), 18, 0x24A8452F)
                primary.isEnabled = true
                primary.elevation = fdp(1f)
            }
            !serviceOk -> {
                primary.text = "第 1 步 · 打开无障碍服务"
                primary.setTextColor(0xFF241A0B.toInt())
                primary.background = ripple(grad(intArrayOf(AMBER_LIGHT, AMBER), 18), 18, 0x33000000)
                primary.isEnabled = true
                primary.elevation = fdp(2f)
            }
            !camOk -> {
                primary.text = "第 2 步 · 允许使用相机"
                primary.setTextColor(0xFF241A0B.toInt())
                primary.background = ripple(grad(intArrayOf(AMBER_LIGHT, AMBER), 18), 18, 0x33000000)
                primary.isEnabled = true
                primary.elevation = fdp(2f)
            }
            else -> {
                primary.text = "开始 · ${if (selectedHand) "空中手势" else "眼动翻页"}"
                primary.setTextColor(0xFF241A0B.toInt())
                primary.background = ripple(grad(intArrayOf(AMBER_LIGHT, AMBER), 18), 18, 0x33000000)
                primary.isEnabled = true
                primary.elevation = fdp(2f)
            }
        }
    }

    private fun paintModeCard(card: ModeCard, running: Boolean, active: Boolean, selected: Boolean) {
        val activeNow = running && active
        val chosen = activeNow || (!running && selected)
        card.root.background = when {
            activeNow -> grad(intArrayOf(TEAL_2, TEAL), 22)
            chosen -> bg(0xFFFFFFFF.toInt(), 22, 1.5f, AMBER)
            else -> bg(0xFFFFFFFF.toInt(), 22, 1f, LINE)
        }
        card.root.elevation = if (chosen) fdp(3f) else 0f
        card.title.setTextColor(if (activeNow) CREAM else INK)
        card.sub.setTextColor(if (activeNow) 0xFFB4C8C1.toInt() else MUTED)
        card.glyph.setTint(if (activeNow) CREAM else TEAL, AMBER)
        when {
            activeNow -> {
                card.badge.visibility = View.VISIBLE
                card.badge.text = "使用中"
                card.badge.setTextColor(AMBER)
                card.badge.background = bg(0x33E9A94C, 99)
            }
            chosen -> {
                card.badge.visibility = View.VISIBLE
                card.badge.text = "已选择"
                card.badge.setTextColor(0xFF7C4A06.toInt())
                card.badge.background = bg(0xFFF7E4BF.toInt(), 99)
            }
            else -> card.badge.visibility = View.INVISIBLE
        }
    }

    private fun updateGuide(hand: Boolean) {
        if (hand) {
            guideTitle.text = "空中手势这样用"
            guideBody.text = "手掌张开正对前摄，距离约 40–70cm；向左挥翻下一页，向右挥翻上一页，" +
                    "拇指和食指捏合翻${if (settings.handPinchPrev) "上一页" else "下一页"}。\n" +
                    "每次翻页后停半秒再做下一个动作，避免一次连翻好几页。"
        } else {
            guideTitle.text = "眼动这样用"
            guideBody.text = "启动后先看向屏幕中央约半秒，等状态变成“已看到人脸”；" +
                    "随后看屏幕左侧约 1 秒翻上一页，看右侧约 1 秒翻下一页。\n" +
                    "快速眨两下也能翻下一页；戴眼镜同样可用，保持前摄能看清整张脸。"
        }
    }

    // ---------------- 操作 ----------------

    private fun onPrimary() {
        if (switching) return
        if (EyeTrackingService.running) {
            stopAll()
            return
        }
        if (!serviceEnabled()) {
            openAccessibility()
            return
        }
        if (!cameraGranted()) {
            requestPermissions()
            return
        }
        startSelected()
    }

    private fun onModeCard(hand: Boolean) {
        if (switching) return
        val running = EyeTrackingService.running
        if (running && EyeTrackingService.handMode != hand) {
            if (!serviceEnabled()) {
                openAccessibility()
                return
            }
            if (!cameraGranted()) {
                requestPermissions()
                return
            }
            selectedHand = hand
            settings.handEnabled = hand
            settings.eyeEnabled = !hand
            switching = true
            lastPaintKey = ""
            EyeTrackingService.stop(this)
            if (hand) TurnBus.gazeState.value = GazeUiState()
            else TurnBus.handState.value = HandUiState()
            toast(if (hand) "正在切换到空中手势…" else "正在切换到眼动…")
            refresh()
            root.postDelayed({ launchVision() }, 700)
            return
        }
        selectedHand = hand
        lastPaintKey = ""
        refresh()
        if (!running) toast(if (hand) "已选择空中手势，点下方“开始”" else "已选择眼动翻页，点下方“开始”")
    }

    private fun startSelected() {
        settings.handEnabled = selectedHand
        settings.eyeEnabled = !selectedHand
        switching = false
        pendingStart = 0
        try {
            EyeTrackingService.start(this)
            toast(
                if (selectedHand) "手势已启动：手掌对镜头左右挥动或捏合"
                else "眼动已启动：先看向屏幕中央半秒完成校准"
            )
        } catch (t: Throwable) {
            settings.handEnabled = false
            settings.eyeEnabled = false
            toast("启动失败：${t.message}")
        }
        if (TurnAccessibilityService.instance == null) {
            root.postDelayed({
                if (TurnAccessibilityService.instance == null) {
                    toast("无障碍服务未连接，翻页会无效，请重新开关无障碍")
                }
            }, 1500)
        }
        root.postDelayed({ refresh() }, 500)
    }

    private fun stopAll() {
        val wasHand = EyeTrackingService.handMode
        EyeTrackingService.stop(this)
        settings.handEnabled = false
        settings.eyeEnabled = false
        TurnBus.gazeState.value = GazeUiState()
        TurnBus.handState.value = HandUiState()
        selectedHand = wasHand
        lastPaintKey = ""
        toast(if (wasHand) "空中手势已停止" else "眼动已停止")
        root.postDelayed({ refresh() }, 400)
    }

    private fun launchVision() {
        if (EyeTrackingService.running) {
            // stopService 是异步的；旧模式尚未退出时等它一下，避免新启动被吞掉。
            if (pendingStart < 10) {
                pendingStart++
                root.postDelayed({ launchVision() }, 200)
            } else {
                switching = false
                pendingStart = 0
                settings.handEnabled = EyeTrackingService.handMode
                settings.eyeEnabled = !EyeTrackingService.handMode
                toast("模式切换超时，请再点一次")
                lastPaintKey = ""
                refresh()
            }
            return
        }
        switching = false
        pendingStart = 0
        try {
            EyeTrackingService.start(this)
            toast(if (settings.handEnabled) "空中手势已启动" else "眼动已启动")
        } catch (t: Throwable) {
            settings.handEnabled = false
            settings.eyeEnabled = false
            toast("启动失败：${t.message}")
        }
        root.postDelayed({ refresh() }, 500)
    }

    private fun openAccessibility() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        toast("在无障碍列表里找到 Turntopage 并打开")
    }

    private fun requestPermissions() {
        val list = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        permLauncher.launch(list.toTypedArray())
    }

    // ---------------- 界面构建 ----------------

    private fun buildUi() {
        var rightColumn: LinearLayout? = null
        if (wideLayout) {
            // 平板横屏：左边完成设置与启动，右边看用法和高级参数，避免一条超长单栏。
            val columns = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
            }
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            rightColumn = right
            columns.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.08f).apply {
                marginEnd = dp(10)
            })
            columns.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.92f).apply {
                marginStart = dp(10)
            })
            root.addView(columns, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            content = left
        } else {
            content = root
        }

        buildHero()
        buildPills()

        sectionHeader("选择翻页方式", "一次只能开一种")
        val cards = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        handCard = makeModeCard(hand = true)
        eyeCard = makeModeCard(hand = false)
        cards.addView(handCard.root, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(5)
        })
        cards.addView(eyeCard.root, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(5)
        })
        content.addView(cards, linear(top = 0, bottom = 4))

        primary = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            minHeight = dp(56)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { onPrimary() }
        }
        content.addView(primary, linear(top = 12, bottom = 4))

        val reader = TextView(this).apply {
            text = "打开内置阅读页试试"
            gravity = Gravity.CENTER
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(INK)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            isClickable = true
            isFocusable = true
            background = ripple(bg(0xFFFFFFFF.toInt(), 18, 1f, LINE), 18, 0x20132E2A)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, ReaderActivity::class.java))
            }
        }
        content.addView(reader, linear(top = 8, bottom = 4))

        if (wideLayout) content = rightColumn ?: root
        buildGuide()
        buildAdvanced()
        buildUsage()

        root.addView(TextView(this).apply {
            text = "Turntopage v${BuildConfig.VERSION_NAME} · 识别只在本机运行，画面不上传"
            textSize = 10.5f
            setTextColor(0xFF9D968A.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(26), 0, 0)
        })
    }

    private fun buildHero() {
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = grad(intArrayOf(TEAL_2, 0xFF0D2925.toInt()), 28)
            setPadding(dp(20), dp(18), dp(20), dp(18))
            elevation = fdp(4f)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(46), dp(46)))

        val nameBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
        }
        nameBox.addView(TextView(this).apply {
            text = "Turntopage"
            textSize = 23f
            setTextColor(CREAM)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.01f
        })
        nameBox.addView(TextView(this).apply {
            text = "让视线和手势替你翻页"
            textSize = 11.5f
            setTextColor(0xFFA8BDB6.toInt())
            setPadding(0, dp(2), 0, 0)
        })
        top.addView(nameBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        hero.addView(top)

        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(18), 0, 0)
        }
        statusDot = View(this).apply { background = circle(OK) }
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(9), dp(9)))
        heroStatus = TextView(this).apply {
            textSize = 15f
            setTextColor(CREAM)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), 0, 0, 0)
        }
        statusRow.addView(heroStatus)
        hero.addView(statusRow)

        heroDetail = TextView(this).apply {
            textSize = 11.5f
            setTextColor(0xFFB9CCC6.toInt())
            setLineSpacing(dpf * 2, 1f)
            setPadding(0, dp(5), 0, 0)
        }
        hero.addView(heroDetail)

        heroHint = TextView(this).apply {
            textSize = 11f
            setTextColor(0xFF8FA9A2.toInt())
            setLineSpacing(dpf * 2, 1f)
            setPadding(0, dp(10), 0, 0)
        }
        hero.addView(heroHint)

        content.addView(hero, linear(top = 0, bottom = 12))
    }

    private fun buildPills() {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pillAccess = pill("无障碍服务")
        pillCamera = pill("相机权限")
        row.addView(pillAccess, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(5)
        })
        row.addView(pillCamera, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(5)
        })
        pillAccess.setOnClickListener {
            if (serviceEnabled()) toast("无障碍服务已经开启") else openAccessibility()
        }
        pillCamera.setOnClickListener {
            if (cameraGranted()) toast("相机权限已经允许") else requestPermissions()
        }
        content.addView(row, linear(top = 0, bottom = 2))
    }

    private fun pill(label: String): TextView = TextView(this).apply {
        text = "●  $label 待设置"
        textSize = 11.5f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(9), dp(10), dp(9))
        background = bg(WARN_BG, 99)
        foreground = rippleOnly(99)
        isClickable = true
        isFocusable = true
    }

    private fun sectionHeader(title: String, trailing: String? = null) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(22), 0, dp(10))
        }
        row.addView(View(this).apply { background = bg(AMBER, 99) }, LinearLayout.LayoutParams(dp(3), dp(16)).apply {
            marginEnd = dp(8)
        })
        row.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(INK)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (trailing != null) {
            row.addView(TextView(this).apply {
                text = trailing
                textSize = 11f
                setTextColor(MUTED)
            })
        }
        content.addView(row)
    }

    private fun makeModeCard(hand: Boolean): ModeCard {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            isClickable = true
            isFocusable = true
            foreground = rippleOnly(22)
            setOnClickListener { onModeCard(hand) }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val glyph = ModeGlyphView(this, hand).apply { setTint(TEAL, AMBER) }
        top.addView(glyph, LinearLayout.LayoutParams(dp(40), dp(40)))
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val badge = TextView(this).apply {
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(3), dp(7), dp(3))
            visibility = View.INVISIBLE
        }
        top.addView(badge)
        card.addView(top)

        val title = TextView(this).apply {
            text = if (hand) "空中手势" else "眼动翻页"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(INK)
        }
        card.addView(title, linear(top = 10))
        val sub = TextView(this).apply {
            text = if (hand) "挥手 · 捏合" else "注视 · 眨眼"
            textSize = 11.5f
            setTextColor(MUTED)
        }
        card.addView(sub, linear(top = 3))
        return ModeCard(card, glyph, title, sub, badge)
    }

    private fun buildGuide() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(0xFFF7EBD5.toInt(), 22, 1f, 0xFFEBD9B7.toInt())
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(View(this).apply { background = circle(AMBER) }, LinearLayout.LayoutParams(dp(6), dp(6)).apply {
            marginEnd = dp(7)
        })
        guideTitle = TextView(this).apply {
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF5C4317.toInt())
        }
        head.addView(guideTitle)
        card.addView(head)
        guideBody = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF77613A.toInt())
            setLineSpacing(dpf * 3, 1f)
            setPadding(0, dp(7), 0, 0)
        }
        card.addView(guideBody)
        content.addView(card, linear(top = 18, bottom = 2))
    }

    private fun buildAdvanced() {
        val (header, toggle) = collapsibleHeader("高级设置", "翻页动作 · 眼动参数 · 手势")
        advancedToggle = toggle
        content.addView(header, linear(top = 16, bottom = 0))

        advancedContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(0xFFFFFFFF.toInt(), 22, 1f, LINE)
            setPadding(dp(16), dp(6), dp(16), dp(16))
            visibility = View.GONE
        }
        content.addView(advancedContent, linear(top = 8, bottom = 0))
        header.setOnClickListener {
            advancedOpen = !advancedOpen
            advancedContent.visibility = if (advancedOpen) View.VISIBLE else View.GONE
            advancedToggle.text = if (advancedOpen) "收起" else "展开"
        }

        val c = advancedContent
        c.addView(subLabel("翻页动作", top = 10))
        c.addView(rowSwitch("音量键翻页", "音量+ 上一页 / 音量− 下一页", { it.volumeTurn }) { v, b ->
            v.volumeTurn = b
        })
        c.addView(rowSwitch("屏幕边缘热区", "点击屏幕左右窄条翻页", { it.zonesEnabled }) { v, b ->
            v.zonesEnabled = b
            TurnAccessibilityService.instance?.applyZones()
        })
        c.addView(rowSwitch("显示热区提示色", "边缘出现淡色提示条", { it.zoneHint }) { v, b ->
            v.zoneHint = b
            TurnAccessibilityService.instance?.applyZones()
        })
        c.addView(rowSwitch("方向反转", "翻页方向和习惯相反时打开", { it.invertDirection }) { v, b ->
            v.invertDirection = b
        })
        c.addView(segmented(
            listOf("滑动翻页", "点击翻页"),
            if (settings.actionMode == AppSettings.MODE_SWIPE) 0 else 1,
        ) { i -> settings.actionMode = if (i == 0) AppSettings.MODE_SWIPE else AppSettings.MODE_TAP }, linear(top = 8))
        c.addView(seekView("滑动时长", 100f, 600f, 20f, settings.swipeDurationMs.toFloat(),
            { "${it.toInt()} ms" }) { settings.swipeDurationMs = it.toInt() }, linear(top = 10))
        c.addView(seekView("滑动距离", 0.3f, 0.85f, 0.05f, settings.swipeLengthRatio,
            { "${(it * 100).roundToInt()}%" }) { settings.swipeLengthRatio = it }, linear(top = 6))

        c.addView(subLabel("眼动", top = 18))
        c.addView(rowSwitch("双击眨眼翻页", "快速眨两下 = 下一页", { it.doubleBlink }) { v, b ->
            v.doubleBlink = b
        })
        c.addView(rowSwitch("转头模式", "转头代替眼球移动，更省力", { it.useHeadTurn }) { v, b ->
            v.useHeadTurn = b
        })
        c.addView(seekView("触发灵敏度", 0.30f, 0.80f, 0.05f, settings.onThreshold,
            { "%.2f".format(it) }) { settings.onThreshold = it }, linear(top = 10))
        c.addView(seekView("注视翻页等待", 600f, 2500f, 100f, settings.dwellMs.toFloat(),
            { "${it.toInt()} ms" }) { settings.dwellMs = it.toLong() }, linear(top = 6))

        c.addView(subLabel("空中手势", top = 18))
        c.addView(rowSwitch("捏合翻上一页", "默认捏合 = 下一页", { it.handPinchPrev }) { v, b ->
            v.handPinchPrev = b
        })
        c.addView(TextView(this).apply {
            text = "提示：左挥下一页、右挥上一页是固定方向；触发后停半秒可防连翻。"
            textSize = 11f
            setTextColor(MUTED)
            setLineSpacing(dpf * 2, 1f)
            setPadding(0, dp(6), 0, 0)
        })
    }

    private fun buildUsage() {
        val (header, toggle) = collapsibleHeader("使用说明", "30 秒上手 · 隐私")
        usageToggle = toggle
        content.addView(header, linear(top = 10, bottom = 0))

        usageContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(0xFFFFFFFF.toInt(), 22, 1f, LINE)
            setPadding(dp(16), dp(8), dp(16), dp(16))
            visibility = View.GONE
        }
        content.addView(usageContent, linear(top = 8, bottom = 0))
        header.setOnClickListener {
            usageOpen = !usageOpen
            usageContent.visibility = if (usageOpen) View.VISIBLE else View.GONE
            usageToggle.text = if (usageOpen) "收起" else "展开"
        }

        val lines = listOf(
            "1" to "先点大按钮完成无障碍与相机授权，再选方式、点开始。",
            "2" to "悬浮球可以拖动；点开有上一页 / 下一页，面板里可一键暂停识别。",
            "3" to "换方式时直接点另一张模式卡，识别会自动重启，约 1 秒。",
            "4" to "翻页方向反了：到高级设置打开“方向反转”。",
            "5" to "在任意小说 App 里正常阅读即可；内置阅读页用来先试翻几页。",
            "6" to "画面只在本机 MediaPipe 处理，不上传；持续用摄像头会发热耗电。",
        )
        lines.forEach { (num, text) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(9), 0, 0)
            }
            row.addView(TextView(this).apply {
                this.text = num
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(0xFF7C4A06.toInt())
                background = bg(0xFFF7E4BF.toInt(), 99)
            }, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
            row.addView(TextView(this).apply {
                this.text = text
                textSize = 12.5f
                setTextColor(0xFF4E5B56.toInt())
                setLineSpacing(dpf * 3, 1f)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            usageContent.addView(row)
        }
    }

    private fun collapsibleHeader(title: String, subtitle: String): Pair<LinearLayout, TextView> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg(0xFFFFFFFF.toInt(), 20, 1f, LINE)
            setPadding(dp(16), dp(13), dp(16), dp(13))
            isClickable = true
            isFocusable = true
            foreground = rippleOnly(20)
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(this).apply {
            text = title
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(INK)
        })
        box.addView(TextView(this).apply {
            text = subtitle
            textSize = 11f
            setTextColor(MUTED)
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val toggle = TextView(this).apply {
            text = "展开"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF9A6A16.toInt())
            setPadding(dp(10), dp(4), 0, dp(4))
        }
        row.addView(toggle)
        return row to toggle
    }

    private fun subLabel(t: String, top: Int): TextView = TextView(this).apply {
        text = t
        textSize = 11f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(0xFF9A8F7D.toInt())
        letterSpacing = 0.08f
        setPadding(0, dp(top), 0, dp(2))
    }

    private fun rowSwitch(
        title: String,
        desc: String,
        get: (AppSettings) -> Boolean,
        set: (AppSettings, Boolean) -> Unit,
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(this).apply {
            text = title
            textSize = 13.5f
            setTextColor(INK)
            setTypeface(typeface, Typeface.BOLD)
        })
        box.addView(TextView(this).apply {
            text = desc
            textSize = 11f
            setTextColor(MUTED)
            setPadding(0, dp(1), 0, 0)
        })
        row.addView(box, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp(10)
        })
        row.addView(ToggleView(this, get(settings)) { checked -> set(settings, checked) },
            LinearLayout.LayoutParams(dp(46), dp(26)))
        return row
    }

    private fun segmented(labels: List<String>, initial: Int, onChange: (Int) -> Unit): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bg(0xFFF4EEE2.toInt(), 14)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val items = ArrayList<TextView>()
        val paint: (Int) -> Unit = { chosen ->
            items.forEachIndexed { i, tv ->
                if (i == chosen) {
                    tv.background = bg(0xFFFFFFFF.toInt(), 11)
                    tv.setTextColor(INK)
                    tv.setTypeface(Typeface.DEFAULT_BOLD)
                    tv.elevation = fdp(1f)
                } else {
                    tv.background = null
                    tv.setTextColor(MUTED)
                    tv.setTypeface(Typeface.DEFAULT)
                    tv.elevation = 0f
                }
            }
        }
        labels.forEachIndexed { i, label ->
            val tv = TextView(this).apply {
                text = label
                textSize = 12.5f
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
                isClickable = true
                isFocusable = true
                setOnClickListener { paint(i); onChange(i) }
            }
            items.add(tv)
            box.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        paint(initial)
        return box
    }

    private fun seekView(
        title: String,
        lo: Float,
        hi: Float,
        step: Float,
        initial: Float,
        fmt: (Float) -> String,
        onChange: (Float) -> Unit,
    ): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val label = TextView(this).apply {
            text = "$title    ${fmt(initial)}"
            textSize = 12.5f
            setTextColor(INK)
            setPadding(0, dp(2), 0, dp(2))
        }
        box.addView(label)
        box.addView(SeekBar(this).apply {
            max = ((hi - lo) / step).roundToInt()
            progress = ((initial - lo) / step).roundToInt()
            progressTintList = ColorStateList.valueOf(TEAL)
            thumbTintList = ColorStateList.valueOf(AMBER)
            progressBackgroundTintList = ColorStateList.valueOf(0xFFE8E0D1.toInt())
            setPadding(0, 0, 0, 0)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = lo + p * step
                    label.text = "$title    ${fmt(v)}"
                    onChange(v)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }, linear(top = 2))
        return box
    }

    // ---------------- 样式助手 ----------------

    private fun dp(v: Int) = (v * dpf).toInt()

    private fun fdp(v: Float) = v * dpf

    private fun linear(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun bg(color: Int, radiusDp: Int, strokeDp: Float = 0f, strokeColor: Int = Color.TRANSPARENT) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * dpf
            if (strokeDp > 0f) setStroke((strokeDp * dpf).toInt(), strokeColor)
        }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun grad(colors: IntArray, radiusDp: Int) = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, colors
    ).apply {
        cornerRadius = radiusDp * dpf
    }

    private fun ripple(content: GradientDrawable, radiusDp: Int, color: Int): RippleDrawable {
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = radiusDp * dpf
        }
        return RippleDrawable(ColorStateList.valueOf(color), content, mask)
    }

    private fun rippleOnly(radiusDp: Int): RippleDrawable {
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = radiusDp * dpf
        }
        return RippleDrawable(ColorStateList.valueOf(0x20132E2A), null, mask)
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private class ModeCard(
        val root: LinearLayout,
        val glyph: ModeGlyphView,
        val title: TextView,
        val sub: TextView,
        val badge: TextView,
    )

    /** 自绘开关，和整体圆角卡片风格一致，避免系统 Switch 的突兀大圆点。 */
    private class ToggleView(
        context: Context,
        initial: Boolean,
        private val onChange: (Boolean) -> Unit,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val density = resources.displayMetrics.density
        private var checked = initial
        private var anim = if (initial) 1f else 0f

        init {
            isClickable = true
            isFocusable = true
            setOnClickListener {
                checked = !checked
                ValueAnimator.ofFloat(anim, if (checked) 1f else 0f).apply {
                    duration = 160L
                    addUpdateListener {
                        anim = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
                onChange(checked)
            }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = h / 2f
            paint.color = blend(0xFFE6DDCC.toInt(), TEAL, anim)
            canvas.drawRoundRect(0f, 0f, w, h, r, r, paint)

            val inset = 3f * density
            val knobR = (h - inset * 2f) / 2f
            val x = inset + knobR + anim * (w - inset * 2f - knobR * 2f)
            paint.color = blend(0xFFFFFFFF.toInt(), AMBER, anim)
            canvas.drawCircle(x, h / 2f, knobR, paint)
        }

        private fun blend(from: Int, to: Int, t: Float): Int = Color.argb(
            (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t).toInt(),
            (Color.red(from) + (Color.red(to) - Color.red(from)) * t).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * t).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t).toInt(),
        )
    }

    /** 手绘的小图标：手掌或眼睛，随卡片状态换色。 */
    private class ModeGlyphView(context: Context, private val hand: Boolean) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 2.1f * resources.displayMetrics.density
        }
        private val path = Path()
        private var main = TEAL
        private var accent = AMBER

        fun setTint(mainColor: Int, accentColor: Int) {
            if (main == mainColor && accent == accentColor) return
            main = mainColor
            accent = accentColor
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val s = minOf(width, height).toFloat()
            val cx = width / 2f
            val cy = height / 2f
            if (hand) drawHand(canvas, cx, cy, s) else drawEye(canvas, cx, cy, s)
        }

        private fun drawHand(c: Canvas, cx: Float, cy: Float, s: Float) {
            // 实心手掌 + 四指 + 拇指，小尺寸下比细线更清楚。
            paint.style = Paint.Style.FILL
            paint.color = main
            val palmW = s * 0.5f
            val left = cx - palmW / 2
            val right = cx + palmW / 2
            c.drawRoundRect(left, cy - s * 0.02f, right, cy + s * 0.28f,
                s * 0.1f, s * 0.1f, paint)

            val fingerW = s * 0.085f
            val gap = s * 0.045f
            val total = fingerW * 4 + gap * 3
            val startX = cx - total / 2
            val tips = floatArrayOf(0.16f, 0.06f, 0.085f, 0.16f)
            for (i in 0..3) {
                val x = startX + i * (fingerW + gap)
                c.drawRoundRect(x, cy - s * tips[i], x + fingerW, cy + s * 0.12f,
                    fingerW / 2, fingerW / 2, paint)
            }

            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = s * 0.105f
            c.drawLine(left + s * 0.015f, cy + s * 0.16f, left - s * 0.11f, cy + s * 0.04f, paint)
            paint.strokeCap = Paint.Cap.BUTT

            paint.style = Paint.Style.FILL
            paint.color = accent
            c.drawCircle(cx, cy + s * 0.14f, s * 0.035f, paint)
        }

        private fun drawEye(c: Canvas, cx: Float, cy: Float, s: Float) {
            val w = s * 0.64f
            val h = s * 0.38f
            path.reset()
            path.moveTo(cx - w / 2, cy)
            path.quadTo(cx, cy - h, cx + w / 2, cy)
            path.quadTo(cx, cy + h, cx - w / 2, cy)
            path.close()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.1f * resources.displayMetrics.density
            paint.color = main
            c.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            paint.color = accent
            c.drawCircle(cx, cy, s * 0.095f, paint)
            paint.color = main
            c.drawCircle(cx, cy, s * 0.04f, paint)
        }
    }

    private companion object {
        val PAPER = 0xFFFAF5EC.toInt()
        val INK = 0xFF172D28.toInt()
        val TEAL = 0xFF123B36.toInt()
        val TEAL_2 = 0xFF1D5149.toInt()
        val CREAM = 0xFFF7EFE1.toInt()
        val AMBER = 0xFFE9A94C.toInt()
        val AMBER_LIGHT = 0xFFF3BE6A.toInt()
        val MUTED = 0xFF71807A.toInt()
        val LINE = 0xFFE7DECE.toInt()
        val OK = 0xFF2E7D5B.toInt()
        val OK_BG = 0xFFE8F2EC.toInt()
        val WARN = 0xFFB4552F.toInt()
        val WARN_BG = 0xFFFBEBDD.toInt()
    }
}
