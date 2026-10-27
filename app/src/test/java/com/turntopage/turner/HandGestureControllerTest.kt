package com.turntopage.turner

import org.junit.Assert.assertEquals
import org.junit.Test

class HandGestureControllerTest {

    private class Clock(var t: Long = 0L)

    private fun points(shift: Float, pinch: Boolean): List<HandPoint> {
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

    private fun setup(pinchDir: Int = 1): Triple<HandGestureController, MutableList<Pair<Int, String>>, Clock> {
        val clock = Clock()
        val turns = mutableListOf<Pair<Int, String>>()
        val ctrl = HandGestureController(
            onTurn = { dir, source -> turns.add(dir to source) },
            pinchDirProvider = { pinchDir },
            now = { clock.t },
        )
        return Triple(ctrl, turns, clock)
    }

    private fun feed(ctrl: HandGestureController, clock: Clock, pts: List<HandPoint>) {
        ctrl.onFrame(HandFrame(true, pts, 15f))
        clock.t += 60
    }

    @Test
    fun waveLeft_turnsNextExactlyOnce() {
        val (ctrl, turns, clock) = setup()
        feed(ctrl, clock, points(0.12f, false))
        feed(ctrl, clock, points(0.12f, false))
        for (i in 1..10) {
            val shift = 0.12f + (-0.24f - 0.12f) * i / 10f
            feed(ctrl, clock, points(shift, false))
        }
        repeat(10) { feed(ctrl, clock, points(-0.24f, false)) }
        assertEquals(listOf(1 to "wave"), turns)
    }

    @Test
    fun waveRight_turnsPrevExactlyOnce() {
        val (ctrl, turns, clock) = setup()
        feed(ctrl, clock, points(-0.12f, false))
        feed(ctrl, clock, points(-0.12f, false))
        for (i in 1..10) {
            val shift = -0.12f + (0.24f - -0.12f) * i / 10f
            feed(ctrl, clock, points(shift, false))
        }
        repeat(10) { feed(ctrl, clock, points(0.24f, false)) }
        assertEquals(listOf(-1 to "wave"), turns)
    }

    @Test
    fun pinch_turnsOnceAndWaitsForRelease() {
        val (ctrl, turns, clock) = setup()
        repeat(3) { feed(ctrl, clock, points(0f, false)) }
        repeat(12) { feed(ctrl, clock, points(0f, true)) }
        assertEquals(listOf(1 to "pinch"), turns)
    }

    @Test
    fun singlePinchFrame_doesNotTrigger() {
        val (ctrl, turns, clock) = setup()
        repeat(3) { feed(ctrl, clock, points(0f, false)) }
        feed(ctrl, clock, points(0f, true))
        repeat(5) { feed(ctrl, clock, points(0f, false)) }
        assertEquals(emptyList<Pair<Int, String>>(), turns)
    }

    @Test
    fun sparseSixFpsWave_stillTurnsOnce() {
        val (ctrl, turns, clock) = setup()
        repeat(3) {
            ctrl.onFrame(HandFrame(true, points(0.12f, false), 6f))
            clock.t += 160
        }
        for (shift in listOf(0f, -0.12f, -0.24f, -0.24f, -0.24f)) {
            ctrl.onFrame(HandFrame(true, points(shift, false), 6f))
            clock.t += 160
        }
        assertEquals(listOf(1 to "wave"), turns)
    }

    @Test
    fun pinch_canBeConfiguredToPrev() {
        val (ctrl, turns, clock) = setup(pinchDir = -1)
        repeat(3) { feed(ctrl, clock, points(0f, false)) }
        repeat(8) { feed(ctrl, clock, points(0f, true)) }
        assertEquals(listOf(-1 to "pinch"), turns)
    }
}
