package com.byd.ls.ac

import android.content.Context
import com.byd.ls.R
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView

/**
 * DiLink 5 三指空调浮层。
 *
 * 资源尺寸来自 AirConditioning.apk：
 *  · 风量窗口 412x96dp，温度窗口 148x260dp，顶部 y=20dp；
 *  · 背景 #3a4048，8dp 圆角；高亮 56sp，普通 36sp；
 *  · 高亮白 #ffffff，普通 #d9dae1eb。
 * 窗口只做展示，必须 NOT_TOUCHABLE，不能拦截底层 App 触摸。
 */
object AcHud {

    private const val TAG = "AcHud"
    private const val HIDE_MS = 3000L

    private var appCtx: Context? = null
    private var wm: WindowManager? = null
    private var root: FrameLayout? = null
    private var windGroup: LinearLayout? = null
    private var tempGroup: RelativeLayout? = null
    private var windValues: List<TextView> = emptyList()
    private var tempValues: List<TextView> = emptyList()
    private var attached = false

    /** 原厂默认深灰 HUD：固定值，不跟随系统日/夜模式。 */
    private val activeColorValue = Color.WHITE
    private val normalColorValue = Color.parseColor("#D9DAE1EB")
    private val iconColorValue = Color.WHITE

    /** 每次 show 递增；用来阻止上一轮 hide 的结束回调把刚显示的 HUD 再藏回去。 */
    private var hideSeq = 0

    private val handler = Handler(Looper.getMainLooper())
    private val hide = Runnable { hideOnMain() }

    fun init(ctx: Context): Boolean {
        appCtx = ctx.applicationContext
        wm = ctx.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val ready = runCatching { Settings.canDrawOverlays(ctx.applicationContext) }.getOrDefault(false)
        Log.d(TAG, "init canDrawOverlays=$ready")
        // 第一次手势才建视图会在主线程多花几十毫秒；提前建好，三指落下即可显示。
        if (ready) handler.post { ensureViews() }
        return ready
    }

    fun show(kind: GestureEngine.Kind, wind: Int, temp: Int) {
        Log.d(TAG, "show $kind wind=$wind temp=$temp")
        if (!canDraw()) return
        handler.post {
            Log.d(TAG, "show posted")
            hideSeq++
            handler.removeCallbacks(hide)
            ensureViews()
            bind(kind, wind, temp)
            scheduleHide()
        }
    }

    fun scheduleHide(delayMs: Long = HIDE_MS) {
        handler.removeCallbacks(hide)
        handler.postDelayed(hide, delayMs)
    }

    fun hideNow() {
        handler.removeCallbacks(hide)
        handler.post { hideOnMain() }
    }

    private fun hideOnMain() {
        val r = root ?: return
        val seq = hideSeq
        r.animate().cancel()
        r.animate().alpha(0f).setDuration(160).withEndAction {
            if (seq != hideSeq) return@withEndAction
            r.visibility = View.GONE
            r.alpha = 0.90f
        }.start()
    }

    private fun canDraw(): Boolean {
        val ctx = appCtx ?: return false
        val ready = runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false)
        if (!ready) Log.d(TAG, "overlay permission missing")
        return ready
    }

    private fun ensureViews() {
        if (root != null) return
        val ctx = appCtx ?: return
        Log.d(TAG, "ensureViews")
        val frame = FrameLayout(ctx).apply {
            setBackgroundResource(R.drawable.ac_gesture_pop_view_bg)
            alpha = 0.90f
        }

        val wind = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12f), 0, dp(12f), 0)
            visibility = View.GONE
        }
        val icon = ImageView(ctx).apply {
            setImageResource(R.drawable.ac_gesture_wind)
            setColorFilter(iconColorValue, PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        wind.addView(icon, LinearLayout.LayoutParams(dp(24f), dp(24f)).apply {
            rightMargin = dp(12f)
            gravity = Gravity.CENTER_VERTICAL
        })
        windValues = (0..2).map {
            makeText(36f, normalColor()).also { tv ->
                wind.addView(tv, LinearLayout.LayoutParams(dp(80f), LinearLayout.LayoutParams.MATCH_PARENT))
            }
        }
        frame.addView(wind, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val temp = RelativeLayout(ctx).apply { visibility = View.GONE }
        val left = ImageView(ctx).apply {
            setImageResource(R.drawable.ac_gesture_left_arrow)
            setColorFilter(iconColorValue, PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        temp.addView(left, RelativeLayout.LayoutParams(dp(20f), dp(20f)).apply {
            addRule(RelativeLayout.ALIGN_PARENT_LEFT)
            addRule(RelativeLayout.CENTER_VERTICAL)
            leftMargin = dp(6f)
        })
        val right = ImageView(ctx).apply {
            setImageResource(R.drawable.ac_gesture_right_arrow)
            setColorFilter(iconColorValue, PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        temp.addView(right, RelativeLayout.LayoutParams(dp(20f), dp(20f)).apply {
            addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
            addRule(RelativeLayout.CENTER_VERTICAL)
            rightMargin = dp(6f)
        })

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        tempValues = (0..2).map {
            makeText(36f, normalColor()).also { tv ->
                column.addView(tv, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                ))
            }
        }
        temp.addView(column, RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        ).apply {
            leftMargin = dp(24f)
            rightMargin = dp(24f)
            addRule(RelativeLayout.CENTER_IN_PARENT)
        })
        val degree = makeText(30f, normalColor()).apply { text = "°C" }
        temp.addView(degree,
            RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
                addRule(RelativeLayout.ALIGN_PARENT_TOP)
                topMargin = dp(8f)
                rightMargin = dp(10f)
            })
        frame.addView(temp, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        root = frame
        windGroup = wind
        tempGroup = temp
    }

    private fun bind(kind: GestureEngine.Kind, wind: Int, temp: Int) {
        val frame = root ?: return
        val windRow = windGroup ?: return
        val tempColumn = tempGroup ?: return
        val active = activeColor()
        val normal = normalColor()

        frame.setBackgroundResource(R.drawable.ac_gesture_pop_view_bg)

        if (kind == GestureEngine.Kind.WIND) {
            windRow.visibility = View.VISIBLE
            tempColumn.visibility = View.GONE
            val values = listOf(wind - 1, wind, wind + 1)
            windValues.forEachIndexed { index, tv ->
                val value = values[index]
                tv.text = if (value in 1..7) value.toString() else ""
                tv.setTextColor(if (index == 1) active else normal)
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (index == 1) 56f else 36f)
            }
        } else {
            windRow.visibility = View.GONE
            tempColumn.visibility = View.VISIBLE
            // 温度列表从下到上为 Lo、18…32、Hi：上面是高值，下面是低值。
            val values = listOf(temp + 1, temp, temp - 1)
            tempValues.forEachIndexed { index, tv ->
                tv.text = tempLabel(values[index])
                tv.setTextColor(if (index == 1) active else normal)
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (index == 1) 56f else 36f)
            }
        }

        frame.visibility = View.VISIBLE
        frame.animate().cancel()
        frame.alpha = 0.90f
        frame.scaleX = 1f
        frame.scaleY = 1f
        val params = paramsFor(kind)
        Log.d(TAG, "bind $kind ${params.width}x${params.height} attached=$attached wm=${wm != null}")
        val manager = wm
        if (manager == null) {
            Log.e(TAG, "window manager null")
            return
        }
        if (!attached) {
            runCatching { manager.addView(frame, params) }
                .onSuccess {
                    attached = true
                    Log.d(TAG, "addView ok")
                }
                .onFailure { Log.e(TAG, "addView failed", it) }
        } else {
            runCatching { manager.updateViewLayout(frame, params) }
                .onFailure { Log.e(TAG, "updateView failed", it) }
        }
    }

    private fun paramsFor(kind: GestureEngine.Kind): WindowManager.LayoutParams {
        val width = dp(if (kind == GestureEngine.Kind.WIND) 412f else 148f)
        val height = dp(if (kind == GestureEngine.Kind.WIND) 96f else 260f)
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(20f)
            x = 0
        }
    }

    private fun activeColor(): Int = activeColorValue

    private fun normalColor(): Int = normalColorValue

    private fun makeText(sizeSp: Float, color: Int): TextView {
        val ctx = appCtx ?: error("AcHud not initialized")
        return TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = Typeface.DEFAULT
        }
    }

    private fun tempLabel(value: Int): String = when {
        value > 33 -> ""
        value == 33 -> "Hi"
        value < 17 -> ""
        value == 17 -> "Lo"
        else -> value.toString()
    }

    private fun dp(value: Float): Int {
        val ctx = appCtx ?: return value.toInt()
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, ctx.resources.displayMetrics
        ).toInt()
    }
}
