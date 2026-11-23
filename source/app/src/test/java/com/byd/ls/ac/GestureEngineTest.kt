package com.byd.ls.ac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GestureEngineTest {

    private fun engine() = GestureEngine(thresholdPx = 96f, cooldownMs = 400L)

    @Test
    fun threeFingerRightIsWindPlusOne() {
        val e = engine()
        assertNull(e.onSample(3, 100f, 100f, 0))
        val step = e.onSample(3, 220f, 100f, 120)
        assertEquals(GestureEngine.Step(GestureEngine.Kind.WIND, 1), step)
    }

    @Test
    fun threeFingerLeftIsWindMinusOne() {
        val e = engine()
        e.onSample(3, 300f, 100f, 0)
        val step = e.onSample(3, 180f, 100f, 120)
        assertEquals(GestureEngine.Step(GestureEngine.Kind.WIND, -1), step)
    }

    @Test
    fun threeFingerUpIsTempPlusOne() {
        val e = engine()
        e.onSample(3, 100f, 300f, 0)
        val step = e.onSample(3, 100f, 180f, 120)
        assertEquals(GestureEngine.Step(GestureEngine.Kind.TEMP, 1), step)
    }

    @Test
    fun threeFingerDownIsTempMinusOne() {
        val e = engine()
        e.onSample(3, 100f, 100f, 0)
        val step = e.onSample(3, 100f, 220f, 120)
        assertEquals(GestureEngine.Step(GestureEngine.Kind.TEMP, -1), step)
    }

    @Test
    fun diagonalIsIgnored() {
        val e = engine()
        e.onSample(3, 100f, 100f, 0)
        assertNull(e.onSample(3, 260f, 260f, 120))
    }

    @Test
    fun belowThresholdIsIgnored() {
        val e = engine()
        e.onSample(3, 100f, 100f, 0)
        assertNull(e.onSample(3, 180f, 100f, 120))
    }

    @Test
    fun secondFingerResetsGesture() {
        val e = engine()
        e.onSample(3, 100f, 100f, 0)
        e.onSample(2, 100f, 100f, 50)
        assertNull(e.onSample(3, 300f, 100f, 100))
    }

    @Test
    fun cooldownBlocksImmediateSecondStep() {
        val e = engine()
        e.onSample(3, 100f, 100f, 0)
        assertEquals(GestureEngine.Step(GestureEngine.Kind.WIND, 1), e.onSample(3, 220f, 100f, 100))
        assertNull(e.onSample(3, 340f, 100f, 200))
        assertEquals(GestureEngine.Step(GestureEngine.Kind.WIND, 1), e.onSample(3, 340f, 100f, 600))
    }
}