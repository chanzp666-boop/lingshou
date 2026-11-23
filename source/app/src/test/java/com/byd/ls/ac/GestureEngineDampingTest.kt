package com.byd.ls.ac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** DiLink 5 风格：首档后逐步增加阻力，不再用 400ms 长冷却。自手指 0.1 验收版移植。 */
class GestureEngineDampingTest {

    private fun engine() = GestureEngine(
        thresholdPx = 96f,
        resistancePx = 6f,
        maxSteps = 5,
        cooldownMs = 80L
    )

    private fun responsiveEngine() = GestureEngine(
        thresholdPx = 96f,
        resistancePx = 6f,
        maxSteps = 5,
        cooldownMs = 0L
    )

    @Test
    fun leftThenRightEmitsOppositeStepInSameGesture() {
        val e = responsiveEngine()
        e.onSample(3, 100f, 100f, 0)
        assertEquals(GestureEngine.Step(GestureEngine.Kind.WIND, -1), e.onSample(3, 4f, 100f, 200))
        assertEquals(GestureEngine.Step(GestureEngine.Kind.WIND, 1), e.onSample(3, 20f, 100f, 400))
    }

    @Test
    fun upThenDownEmitsOppositeStepInSameGesture() {
        val e = responsiveEngine()
        e.onSample(3, 100f, 300f, 0)
        assertEquals(GestureEngine.Step(GestureEngine.Kind.TEMP, 1), e.onSample(3, 100f, 204f, 200))
        assertEquals(GestureEngine.Step(GestureEngine.Kind.TEMP, -1), e.onSample(3, 100f, 220f, 400))
    }

    @Test
    fun smallJitterDoesNotReverseStep() {
        val e = responsiveEngine()
        e.onSample(3, 100f, 100f, 0)
        e.onSample(3, 196f, 100f, 100)
        assertNull(e.onSample(3, 188f, 100f, 300))
    }

    @Test
    fun secondStepNeedsMoreTravelThanFirst() {
        val e = engine()
        e.onSample(3, 100f, 100f, 0)
        assertEquals(GestureEngine.Step(GestureEngine.Kind.WIND, 1), e.onSample(3, 196f, 100f, 100))
        assertNull(e.onSample(3, 297f, 100f, 180))
        assertEquals(GestureEngine.Step(GestureEngine.Kind.WIND, 1), e.onSample(3, 298f, 100f, 260))
    }

    @Test
    fun longGestureIsCappedAtFiveSteps() {
        val e = engine()
        e.onSample(3, 0f, 100f, 0)
        var steps = 0
        for (x in listOf(96f, 198f, 306f, 420f, 540f, 620f)) {
            if (e.onSample(3, x, 100f, (x.toInt() * 10).toLong()) != null) steps++
        }
        assertEquals(5, steps)
    }

    @Test
    fun defaultEngineAllowsFiveSteps() {
        val e = GestureEngine()
        e.onSample(3, 0f, 100f, 0)
        var steps = 0
        for (x in listOf(96f, 198f, 306f, 420f, 540f, 620f)) {
            if (e.onSample(3, x, 100f, (x.toInt() * 10).toLong()) != null) steps++
        }
        assertEquals(5, steps)
    }

    @Test
    fun fifthStepBoundaryIs540Px() {
        val e = engine()
        e.onSample(3, 0f, 100f, 0)
        listOf(96f, 198f, 306f, 420f).forEachIndexed { i, x ->
            e.onSample(3, x, 100f, (i + 1) * 100L)
        }
        assertNull(e.onSample(3, 539f, 100f, 600))
        assertEquals(GestureEngine.Step(GestureEngine.Kind.WIND, 1), e.onSample(3, 540f, 100f, 700))
    }

    @Test
    fun newGestureResetsResistance() {
        val e = engine()
        e.onSample(3, 100f, 100f, 0)
        e.onSample(3, 196f, 100f, 100)
        e.reset()
        e.onSample(3, 100f, 200f, 200)
        assertNull(e.onSample(3, 100f, 160f, 300))
        assertEquals(GestureEngine.Step(GestureEngine.Kind.TEMP, 1), e.onSample(3, 100f, 104f, 400))
    }

    @Test
    fun axisLocksAfterFirstDirection() {
        val e = engine()
        e.onSample(3, 100f, 100f, 0)
        assertEquals(GestureEngine.Step(GestureEngine.Kind.WIND, 1), e.onSample(3, 196f, 100f, 100))
        assertNull(e.onSample(3, 196f, 300f, 300))
    }
}