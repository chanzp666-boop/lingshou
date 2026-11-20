package com.byd.ls.service

import android.view.MotionEvent
import com.byd.ls.config.GesturePrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 手势判定回归：覆盖 1.1.1 调过的阈值、斜向判定、抬手补判与底部上滑。
 * 这些参数直接决定"跟不跟手"，必须锁死。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class GestureRecognizerTest {

    private val thresholdPx = 100f

    private fun recognizer(onGesture: (GestureRecognizer.Gesture) -> Unit) =
        GestureRecognizer(1f, onGesture).apply {
            thresholds = GesturePrefs.Thresholds(thresholdPx)
        }

    private fun event(action: Int, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(0L, 0L, action, x, y, 0)

    @Test
    fun `left edge horizontal swipe fires back once`() {
        val fired = mutableListOf<GestureRecognizer.Gesture>()
        val r = recognizer { fired += it }
        r.onTouch(GestureRecognizer.Edge.LEFT, event(MotionEvent.ACTION_DOWN, 10f, 500f))
        r.onTouch(GestureRecognizer.Edge.LEFT, event(MotionEvent.ACTION_MOVE, 130f, 505f))
        r.onTouch(GestureRecognizer.Edge.LEFT, event(MotionEvent.ACTION_MOVE, 260f, 510f))
        r.onTouch(GestureRecognizer.Edge.LEFT, event(MotionEvent.ACTION_UP, 260f, 510f))
        assertEquals(listOf(GestureRecognizer.Gesture.BACK), fired)
    }

    @Test
    fun `right edge swipe left fires back`() {
        val fired = mutableListOf<GestureRecognizer.Gesture>()
        val r = recognizer { fired += it }
        r.onTouch(GestureRecognizer.Edge.RIGHT, event(MotionEvent.ACTION_DOWN, 2400f, 500f))
        r.onTouch(GestureRecognizer.Edge.RIGHT, event(MotionEvent.ACTION_MOVE, 2250f, 510f))
        r.onTouch(GestureRecognizer.Edge.RIGHT, event(MotionEvent.ACTION_UP, 2250f, 510f))
        assertEquals(listOf(GestureRecognizer.Gesture.BACK), fired)
    }

    @Test
    fun `diagonal swipe still triggers back after dominance relaxed`() {
        val fired = mutableListOf<GestureRecognizer.Gesture>()
        val r = recognizer { fired += it }
        r.onTouch(GestureRecognizer.Edge.LEFT, event(MotionEvent.ACTION_DOWN, 10f, 500f))
        // dx=150 dy=80：1.1.0 的 2:1 判定会漏掉，1.1.1 的 1.25:1 应该触发
        r.onTouch(GestureRecognizer.Edge.LEFT, event(MotionEvent.ACTION_MOVE, 160f, 580f))
        assertEquals(1, fired.size)
    }

    @Test
    fun `vertical drag on side edge does not fire back`() {
        val fired = mutableListOf<GestureRecognizer.Gesture>()
        val r = recognizer { fired += it }
        r.onTouch(GestureRecognizer.Edge.LEFT, event(MotionEvent.ACTION_DOWN, 10f, 300f))
        r.onTouch(GestureRecognizer.Edge.LEFT, event(MotionEvent.ACTION_MOVE, 60f, 700f))
        r.onTouch(GestureRecognizer.Edge.LEFT, event(MotionEvent.ACTION_UP, 60f, 700f))
        assertEquals(emptyList<GestureRecognizer.Gesture>(), fired)
    }

    @Test
    fun `swipe below threshold does nothing`() {
        val fired = mutableListOf<GestureRecognizer.Gesture>()
        val r = recognizer { fired += it }
        r.onTouch(GestureRecognizer.Edge.LEFT, event(MotionEvent.ACTION_DOWN, 10f, 500f))
        r.onTouch(GestureRecognizer.Edge.LEFT, event(MotionEvent.ACTION_MOVE, 60f, 500f))
        r.onTouch(GestureRecognizer.Edge.LEFT, event(MotionEvent.ACTION_UP, 60f, 500f))
        assertEquals(0, fired.size)
    }

    @Test
    fun `fast flick that only shows up on UP still fires back`() {
        val fired = mutableListOf<GestureRecognizer.Gesture>()
        val r = recognizer { fired += it }
        r.onTouch(GestureRecognizer.Edge.LEFT, event(MotionEvent.ACTION_DOWN, 10f, 500f))
        // 没有 MOVE 采样，直接 UP
        r.onTouch(GestureRecognizer.Edge.LEFT, event(MotionEvent.ACTION_UP, 200f, 505f))
        assertEquals(listOf(GestureRecognizer.Gesture.BACK), fired)
    }

    @Test
    fun `cancelled touch past threshold does not fire`() {
        val fired = mutableListOf<GestureRecognizer.Gesture>()
        val r = recognizer { fired += it }
        r.onTouch(GestureRecognizer.Edge.LEFT, event(MotionEvent.ACTION_DOWN, 10f, 500f))
        // 没有 MOVE，直接 CANCEL 且位移已过阈值：旧实现会把 CANCEL 当 UP 触发
        r.onTouch(GestureRecognizer.Edge.LEFT, event(MotionEvent.ACTION_CANCEL, 200f, 500f))
        assertEquals(0, fired.size)
    }

    @Test
    fun `bottom swipe up fires home`() {
        val fired = mutableListOf<GestureRecognizer.Gesture>()
        val r = recognizer { fired += it }
        r.onTouch(GestureRecognizer.Edge.BOTTOM, event(MotionEvent.ACTION_DOWN, 1200f, 720f))
        r.onTouch(GestureRecognizer.Edge.BOTTOM, event(MotionEvent.ACTION_MOVE, 1200f, 560f))
        assertEquals(listOf(GestureRecognizer.Gesture.HOME), fired)
    }

    @Test
    fun `bottom edge is paused while keyboard visible`() {
        val fired = mutableListOf<GestureRecognizer.Gesture>()
        val r = recognizer { fired += it }
        r.bottomPaused = true
        r.onTouch(GestureRecognizer.Edge.BOTTOM, event(MotionEvent.ACTION_DOWN, 1200f, 720f))
        r.onTouch(GestureRecognizer.Edge.BOTTOM, event(MotionEvent.ACTION_MOVE, 1200f, 500f))
        assertEquals(0, fired.size)
    }

    @Test
    fun `down outside then move does not fire for other edge`() {
        val fired = mutableListOf<GestureRecognizer.Gesture>()
        val r = recognizer { fired += it }
        // 右边缘起手向右滑（朝屏幕外）不应触发
        r.onTouch(GestureRecognizer.Edge.RIGHT, event(MotionEvent.ACTION_DOWN, 2400f, 500f))
        r.onTouch(GestureRecognizer.Edge.RIGHT, event(MotionEvent.ACTION_MOVE, 2550f, 500f))
        assertNull(fired.firstOrNull())
    }
}
