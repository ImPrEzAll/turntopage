package com.turntopage.turner

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 内置阅读测试页：任何来源（音量键 / 边缘热区 / 悬浮球 / 眼动 / 眨眼 / 空中手势）
 * 注入的滑动与点击都会落在 BookPageView 上，从而实现端到端自测。
 */
class ReaderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SIMULATE_GAZE = "simulate_gaze"
        const val EXTRA_SIMULATE_HAND = "simulate_hand"
        private const val STATE_PAGE = "reader_page"
        private const val PAPER = 0xFFFBF5E9.toInt()
        private const val PAPER_DARK = 0xFFF1E4CE.toInt()
        private const val INK = 0xFF2B2620.toInt()
    }

    private lateinit var pager: BookPageView
    private lateinit var pageInfo: TextView
    private lateinit var hint: TextView
    private lateinit var modeBadge: TextView
    private val ui = Handler(Looper.getMainLooper())

    private val modeTicker = object : Runnable {
        override fun run() {
            updateTrackingBadge()
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = PAPER_DARK
        window.navigationBarColor = PAPER_DARK
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PAPER)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(PAPER_DARK)
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        val back = TextView(this).apply {
            text = "‹ 返回"
            textSize = 14f
            setTextColor(0xFF6B5B45.toInt())
            setPadding(0, dp(6), dp(12), dp(6))
            setOnClickListener { finish() }
        }
        val title = TextView(this).apply {
            text = "阅读测试 · ${Novel.title}"
            textSize = 15f
            setTextColor(INK)
            typeface = Typeface.create("serif", Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        modeBadge = TextView(this).apply {
            textSize = 10.5f
            setTextColor(0xFF6B5B45.toInt())
            gravity = Gravity.END
            setPadding(dp(8), dp(3), dp(8), dp(3))
        }
        top.addView(back)
        top.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(modeBadge, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        if (BuildConfig.DEBUG) {
            val sim = TextView(this).apply {
                text = "模拟识别"
                textSize = 10.5f
                setTextColor(0xFF8A5A2B.toInt())
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { showSimulationDialog() }
            }
            top.addView(sim)
        }
        root.addView(top, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        pager = BookPageView(this).apply {
            chapter = Novel.title
            pages = Novel.pages
            onPageChanged = { index, total -> updatePageInfo(index, total) }
        }
        root.addView(pager, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PAPER_DARK)
            setPadding(dp(18), dp(8), dp(18), dp(12))
        }
        pageInfo = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF5E5140.toInt())
            typeface = Typeface.DEFAULT_BOLD
        }
        hint = TextView(this).apply {
            text = "滑动 / 两侧点击 / 音量键 / 边缘热区 / 悬浮球 / 眼动 / 空中手势"
            textSize = 10.5f
            setTextColor(0xFF84735C.toInt())
            setPadding(0, dp(3), 0, 0)
        }
        bottom.addView(pageInfo)
        bottom.addView(hint)
        root.addView(bottom, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updatePageInfo(0, Novel.pages.size)
        savedInstanceState?.let { pager.currentIndex = it.getInt(STATE_PAGE, 0) }
        updateTrackingBadge()

        if (savedInstanceState == null && BuildConfig.DEBUG &&
            intent.getBooleanExtra(EXTRA_SIMULATE_GAZE, false)
        ) {
            ui.postDelayed({ runSimulatedGaze() }, 1500)
        }
        if (savedInstanceState == null && BuildConfig.DEBUG && intent.hasExtra(EXTRA_SIMULATE_HAND)) {
            val kind = intent.getIntExtra(EXTRA_SIMULATE_HAND, 0)
            ui.postDelayed({ runSimulatedHand(kind) }, 1500)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_PAGE, pager.currentIndex)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        updateTrackingBadge()
        ui.removeCallbacks(modeTicker)
        ui.post(modeTicker)
    }

    override fun onPause() {
        ui.removeCallbacks(modeTicker)
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun showSimulationDialog() {
        val items = arrayOf(
            "眼动：向右看 1 秒（下一页）",
            "手势：向左挥（下一页）",
            "手势：向右挥（上一页）",
            "手势：拇指食指捏合",
        )
        AlertDialog.Builder(this)
            .setTitle("模拟识别结果（调试）")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> runSimulatedGaze()
                    1 -> runSimulatedHand(0)
                    2 -> runSimulatedHand(2)
                    else -> runSimulatedHand(1)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun runSimulatedGaze() {
        if (!EyeTrackingService.running) {
            toast("请先在主页开启眼动翻页")
            return
        }
        if (EyeTrackingService.simulateGaze(1)) {
            toast("模拟“向右看 1 秒”")
        } else {
            toast("当前是空中手势模式，请先切回眼动")
        }
    }

    private fun runSimulatedHand(kind: Int) {
        if (!EyeTrackingService.running || !EyeTrackingService.handMode) {
            toast("请先在主页开启空中手势翻页")
            return
        }
        val name = when (kind) {
            1 -> "捏合"
            2 -> "向右挥（上一页）"
            else -> "向左挥（下一页）"
        }
        if (EyeTrackingService.simulateHand(kind)) {
            toast("模拟“$name”")
        } else {
            toast("手势引擎当前不可用")
        }
    }

    private fun updatePageInfo(index: Int, total: Int) {
        pageInfo.text = "第 ${index + 1} / $total 页"
    }

    private fun updateTrackingBadge() {
        if (!EyeTrackingService.running) {
            modeBadge.text = "识别 关"
            modeBadge.setTextColor(0xFF94836B.toInt())
            return
        }
        if (EyeTrackingService.handMode) {
            val h = TurnBus.handState.value
            modeBadge.setTextColor(0xFFB15A00.toInt())
            val since = SystemClock.uptimeMillis() - h.lastGestureAt
            modeBadge.text = when {
                h.lastGesture.isNotEmpty() && since in 0L..2500L -> "手势 · ${h.lastGesture}"
                !h.hasHand -> "手势 · 找手 ${h.fps.roundToInt()}fps"
                h.pinchRatio in 0.01f..0.60f -> "手势 · 捏合 ${"%.2f".format(h.pinchRatio)}"
                else -> "手势 · ${h.fps.roundToInt()}fps"
            }
            return
        }
        val g = TurnBus.gazeState.value
        modeBadge.setTextColor(0xFF2E7D52.toInt())
        modeBadge.text = when {
            g.calibrating -> "眼动 · 校准中"
            !g.hasFace -> "眼动 · 找脸 ${g.fps.roundToInt()}fps"
            g.dwellProgress > 0.01f -> "注视 ${(g.dwellProgress * 100).roundToInt()}% · ${g.fps.roundToInt()}fps"
            else -> "眼动 · ${"%.2f".format(g.gazeX)} · ${g.fps.roundToInt()}fps"
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}

/** 极简但真实可翻的阅读页：支持拖拽、快滑、左右点击与无障碍注入的触摸手势。 */
class BookPageView(context: Context) : View(context) {

    var chapter: String = ""
    var pages: List<String> = emptyList()
        set(value) {
            field = value
            current = 0
            target = -1
            drag = 0f
            layouts = emptyList()
            layoutWidth = 0
            invalidate()
            onPageChanged?.invoke(0, value.size)
        }
    var onPageChanged: ((index: Int, total: Int) -> Unit)? = null

    private var current = 0
    private var target = -1
    private var drag = 0f
    private var layouts: List<StaticLayout> = emptyList()
    private var layoutWidth = 0
    private var animator: ValueAnimator? = null

    /** 旋转、调整窗口后保持当前页。 */
    var currentIndex: Int
        get() = current
        set(value) {
            if (pages.isEmpty()) return
            current = value.coerceIn(0, pages.lastIndex)
            target = -1
            drag = 0f
            invalidate()
            onPageChanged?.invoke(current, pages.size)
        }

    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private var moved = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    /** 平板横屏时正文最多 760dp 宽，避免一行字太长。 */
    private val contentMaxWidth = dp(760f)

    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF332D25.toInt()
        textSize = dp(19.5f)
        typeface = Typeface.create("serif", Typeface.NORMAL)
    }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7A6244.toInt()
        textSize = dp(12f)
        letterSpacing = 0.08f
        typeface = Typeface.create("serif", Typeface.BOLD)
    }
    private val footPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9A876A.toInt()
        textSize = dp(10.5f)
        typeface = Typeface.create("serif", Typeface.NORMAL)
    }
    private val rulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x338F7652
        strokeWidth = dp(0.8f)
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shadowShader: Shader? = null

    init {
        isClickable = true
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildLayouts()
        shadowShader = LinearGradient(0f, 0f, dp(18f), 0f, 0x40000000, 0x00000000, Shader.TileMode.CLAMP)
        shadowPaint.shader = shadowShader
    }

    private fun contentWidth(): Float =
        minOf((width - dp(56f)).coerceAtLeast(dp(120f)), contentMaxWidth)

    private fun contentLeft(): Float = (width - contentWidth()) / 2f

    private fun rebuildLayouts() {
        if (width <= 0 || pages.isEmpty()) return
        layoutWidth = contentWidth().toInt()
        val availableH = (height - dp(124f)).toInt()
        if (layoutWidth <= 0 || availableH <= 0) return

        // 横屏平板正文限宽；手机横屏高度很矮时自动缩字号，避免一页文字被裁掉。
        var size = dp(19.5f)
        val minSize = dp(12f)
        while (true) {
            bodyPaint.textSize = size
            layouts = pages.map { text ->
                StaticLayout.Builder.obtain(text, 0, text.length, bodyPaint, layoutWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(size * 0.4f, 1f)
                    .setIncludePad(false)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                    .build()
            }
            val maxH = layouts.maxOfOrNull { it.height } ?: 0
            if (maxH <= availableH || size <= minSize) break
            size -= dp(0.5f)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        bgPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0xFFFBF7EE.toInt(), 0xFFF6EDDD.toInt()),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        bgPaint.shader = null

        if (layouts.size != pages.size) rebuildLayouts()
        if (layouts.isEmpty()) {
            drawPage(canvas, 0, 0f)
            return
        }

        if (target >= 0 && drag != 0f) {
            val dir = if (target > current) 1 else -1
            drawPage(canvas, current, drag)
            drawPage(canvas, target, drag + dir * w)
            val edgeX = if (drag < 0) w + drag else drag
            canvas.save()
            canvas.translate(edgeX - dp(18f), 0f)
            canvas.drawRect(0f, 0f, dp(18f), h, shadowPaint)
            canvas.restore()
            if (drag < 0) {
                canvas.save()
                canvas.translate(w + drag, 0f)
                canvas.drawRect(0f, 0f, dp(18f), h, shadowPaint)
                canvas.restore()
            }
        } else {
            drawPage(canvas, current, 0f)
        }
    }

    private fun drawPage(canvas: Canvas, index: Int, dx: Float) {
        if (index !in layouts.indices) return
        canvas.save()
        canvas.translate(dx, 0f)
        val left = contentLeft()
        val right = left + contentWidth()
        val padTop = dp(20f)

        canvas.drawText(chapter, left, padTop + titlePaint.textSize, titlePaint)
        val ruleY = padTop + titlePaint.textSize + dp(10f)
        canvas.drawLine(left, ruleY, right, ruleY, rulePaint)

        val bodyTop = ruleY + dp(22f)
        canvas.clipRect(left, bodyTop, right, height.toFloat() - dp(54f))
        canvas.translate(left, bodyTop)
        layouts[index].draw(canvas)
        canvas.restore()

        val label = "${index + 1} / ${pages.size}"
        val x = right - footPaint.measureText(label)
        canvas.drawText(label, x, height.toFloat() - dp(24f), footPaint)
        canvas.drawText(chapter, left, height.toFloat() - dp(24f), footPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (pages.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                animator = null
                downX = event.x
                downY = event.y
                drag = 0f
                target = -1
                tracking = true
                moved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return true
                val dx = event.x - downX
                val dy = event.y - downY
                if (!moved && abs(dx) > touchSlop && abs(dx) > abs(dy)) moved = true
                if (moved) {
                    val dir = if (dx < 0) 1 else -1
                    val candidate = current + dir
                    target = if (candidate in pages.indices) candidate else -1
                    var offset = dx
                    if (target < 0) offset *= 0.18f
                    drag = offset.coerceIn(-width.toFloat(), width.toFloat())
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!tracking) return true
                tracking = false
                if (!moved) {
                    handleTap(event.x)
                } else if (target >= 0 && abs(drag) > width * 0.12f) {
                    settleTo(target)
                } else {
                    springBack()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                tracking = false
                springBack()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleTap(x: Float) {
        when {
            x < width * 0.3f -> turn(-1)
            x > width * 0.7f -> turn(1)
        }
    }

    private fun turn(dir: Int) {
        val next = current + dir
        if (next !in pages.indices) return
        target = next
        settleTo(next)
    }

    private fun settleTo(index: Int) {
        val dir = if (index > current) 1 else -1
        animate(drag, -dir * width.toFloat(), index)
    }

    private fun springBack() {
        if (drag == 0f) {
            target = -1
            invalidate()
            return
        }
        animate(drag, 0f, null)
    }

    private fun animate(from: Float, to: Float, endIndex: Int?) {
        animator?.cancel()
        val a = ValueAnimator.ofFloat(from, to).setDuration(180L)
        a.addUpdateListener {
            drag = it.animatedValue as Float
            invalidate()
        }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                animator = null
                if (endIndex != null) {
                    current = endIndex
                    onPageChanged?.invoke(current, pages.size)
                }
                target = -1
                drag = 0f
                invalidate()
            }
        })
        animator = a
        a.start()
    }
}

/** 内置测试用原创短篇，避免依赖任何外部阅读 App 即可验收。 */
object Novel {
    const val title = "纸灯渡"
    val pages: List<String> = listOf(
        "江面起雾的时候，摆渡人把一盏纸灯挂在船头。灯不亮，只泛出淡淡的橘色，像一粒被水汽包住的星。渡口没有名字，来坐船的人也不问去处，只把一枚铜钱投进旧木匣，说一声“去对岸”。摆渡人便撑开篙，船身轻轻一荡，雾就合拢了。",
        "阿澪第一次来的时候，怀里抱着一只空鸟笼。她说要去对岸找一个人。摆渡人点头，把纸灯往风里挪了挪，说：“河上规矩，灯灭之前，不要回头。”她答应了，却在船到江心时听见笼子里传来扑翅声，于是忍不住回头看了一眼。身后没有岸，只有一盏更小的灯，正顺着水波向她漂来。",
        "“那是你放不下的东西。”摆渡人的声音从雾里传来。阿澪低头，发现鸟笼里不知何时多了一张字条，上面是她自己的笔迹：别等了。她想不起来何时写过，只觉得胸口像被水浸过的纸，慢慢软下去。船头的灯晃了晃，河面浮起许多旧屋檐的影子，有她童年的巷口，也有一盏从未点亮过的门灯。",
        "摆渡人递来一碗温茶：“喝了就忘了。”阿澪没有接，她问他：“对岸真的有人在等我吗？”摆渡人笑了笑，说自己也不知道。他在这条河上撑了很多年船，只有一次靠了岸。那天岸上没有人，只有一排空椅子，面向江面摆着。他坐了一会儿，天就亮了，醒来时船还在江心，篙上多了一道浅浅的指痕。",
        "江心的雾越来越薄。阿澪看见远处浮着一座小城，青瓦白墙，窗里点着灯。她忽然明白，那盏向她漂来的小灯不是别人的，而是她每年除夕放在河边的愿灯。灯上写着名字，字迹被水泡得模糊，却还是能认出来。她伸手去捞，灯没有散，反而温温地贴住了她的掌心。",
        "船靠岸时，天边露出一线鱼肚白。摆渡人把纸灯取下来，递给她：“到地方了。”她问：“船费呢？”他指指那只空鸟笼：“把等字留下就好。”阿澪把笼门打开，风从里面穿过去，带着一声轻响。她踏上岸，没有回头。身后的水声慢慢低下去，雾重新合上，渡口像从没存在过一样。",
        "很多年后，阿澪也成了一个习惯在夜里点灯的人。她在窗台放一盏小小的纸灯，不写愿望，只让它亮着。偶尔有晚归的人从楼下经过，会看到光从窗缝里漏出来，像一条没有名字的河。有人说，河上有个摆渡人，专渡不肯回家的人。阿澪听见了，只笑笑，又给灯添了一点油。"
    )
}
