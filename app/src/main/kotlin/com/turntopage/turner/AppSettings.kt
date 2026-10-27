package com.turntopage.turner

import android.content.Context
import android.content.SharedPreferences

/** 全局设置，读写同一 SharedPreferences，服务与界面共享。 */
class AppSettings(context: Context) {
    private val p: SharedPreferences =
        context.applicationContext.getSharedPreferences("turntopage", Context.MODE_PRIVATE)

    var eyeEnabled: Boolean
        get() = p.getBoolean("eyeEnabled", false)
        set(v) = p.edit().putBoolean("eyeEnabled", v).apply()

    /** 空中手势模式；与 eyeEnabled 互斥，同一时间只跑一个摄像头识别器。 */
    var handEnabled: Boolean
        get() = p.getBoolean("handEnabled", false)
        set(v) = p.edit().putBoolean("handEnabled", v).apply()

    /** 捏合手指默认翻下一页；打开后改为上一页。 */
    var handPinchPrev: Boolean
        get() = p.getBoolean("handPinchPrev", false)
        set(v) = p.edit().putBoolean("handPinchPrev", v).apply()

    var useHeadTurn: Boolean
        get() = p.getBoolean("useHeadTurn", false)
        set(v) = p.edit().putBoolean("useHeadTurn", v).apply()

    var doubleBlink: Boolean
        get() = p.getBoolean("doubleBlink", true)
        set(v) = p.edit().putBoolean("doubleBlink", v).apply()

    var volumeTurn: Boolean
        get() = p.getBoolean("volumeTurn", true)
        set(v) = p.edit().putBoolean("volumeTurn", v).apply()

    var zonesEnabled: Boolean
        get() = p.getBoolean("zonesEnabled", true)
        set(v) = p.edit().putBoolean("zonesEnabled", v).apply()

    var zoneHint: Boolean
        get() = p.getBoolean("zoneHint", true)
        set(v) = p.edit().putBoolean("zoneHint", v).apply()

    var invertDirection: Boolean
        get() = p.getBoolean("invertDirection", false)
        set(v) = p.edit().putBoolean("invertDirection", v).apply()

    /** 注视触发阈值 0.30~0.80，越大越难触发 */
    var onThreshold: Float
        get() = p.getFloat("onThreshold", 0.5f)
        set(v) = p.edit().putFloat("onThreshold", v).apply()

    /** 注视多久后翻页（毫秒） */
    var dwellMs: Long
        get() = p.getLong("dwellMs", 1200L)
        set(v) = p.edit().putLong("dwellMs", v).apply()

    /** 滑动手势时长（毫秒） */
    var swipeDurationMs: Int
        get() = p.getInt("swipeDurationMs", 280)
        set(v) = p.edit().putInt("swipeDurationMs", v).apply()

    /** 滑动距离占屏宽比例 */
    var swipeLengthRatio: Float
        get() = p.getFloat("swipeLengthRatio", 0.55f)
        set(v) = p.edit().putFloat("swipeLengthRatio", v).apply()

    var actionMode: Int
        get() = p.getInt("actionMode", MODE_SWIPE)
        set(v) = p.edit().putInt("actionMode", v).apply()

    /** 热区宽度 dp */
    var zoneWidthDp: Int
        get() = p.getInt("zoneWidthDp", 40)
        set(v) = p.edit().putInt("zoneWidthDp", v).apply()

    var bubbleX: Int
        get() = p.getInt("bubbleX", 24)
        set(v) = p.edit().putInt("bubbleX", v).apply()

    var bubbleY: Int
        get() = p.getInt("bubbleY", 240)
        set(v) = p.edit().putInt("bubbleY", v).apply()

    companion object {
        const val MODE_SWIPE = 0
        const val MODE_TAP = 1
    }
}
