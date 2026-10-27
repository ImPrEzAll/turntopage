package com.turntopage.turner

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow

/** 进程内总线：摄像头识别服务 → 无障碍服务 的翻页请求与状态共享。 */
object TurnBus {
    val gazeState = MutableStateFlow(GazeUiState())
    val handState = MutableStateFlow(HandUiState())

    fun requestTurn(dir: Int, source: String) {
        val s = TurnAccessibilityService.instance
        if (s == null) {
            Log.w("TurnBus", "accessibility not connected, drop turn($dir) from $source")
            return
        }
        s.performTurn(dir, source)
    }
}
