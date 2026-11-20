package com.byd.ls.ac

/**
 * 两指手势状态机（纯逻辑，可单测）。自手指 0.1 实车验收版原样移植（beta6）。
 *
 * DiLink 5 手感：
 *  · 第一次触发后，档位边界逐次增加（96/198/306/420px），模拟阻尼；
 *  · 同一手势锁定主轴，但方向不锁死：左滑后可以右滑，上滑后可以下滑；
 *  · 反向要越过当前档位边界并带回差，避免边界抖动；
 *  · 单次手势最多 5 档，避免长滑失控。
 *
 * 1 指/3 指不参与；左右=风量，上下=温度（上滑加温）。
 */
class GestureEngine(
    private val thresholdPx: Float = 96f,
    private val resistancePx: Float = 6f,
    private val maxSteps: Int = 5,
    private val cooldownMs: Long = 0L,
    private val axisRatio: Float = 1.6f
) {

    enum class Kind { WIND, TEMP }

    data class Step(val kind: Kind, val dir: Int)

    private var active = false
    private var originX = 0f
    private var originY = 0f
    private var axis: Kind? = null
    private var stepIndex = 0
    private var lastStepAt = 0L

    /** 当前手势已锁定的轴；未锁定为 null。 */
    val axisKind: Kind? get() = axis

    companion object {
        /** 反向回差：手指回退超过这么多像素才认反向档，避免边界来回抖。 */
        private const val REVERSE_HYSTERESIS_PX = 12f
    }

    fun reset() {
        active = false
        originX = 0f
        originY = 0f
        axis = null
        stepIndex = 0
        lastStepAt = 0L
    }

    /**
     * 输入一次触摸采样。
     * @param count 当前手指数量
     * @param cx/cy 触点质心（像素）
     * @param atMs 采样时间（毫秒，单调递增）
     */
    fun onSample(count: Int, cx: Float, cy: Float, atMs: Long): Step? {
        if (count != 2) {
            reset()
            return null
        }
        if (!active) {
            active = true
            originX = cx
            originY = cy
            axis = null
            stepIndex = 0
            lastStepAt = 0L
            return null
        }

        val dx = cx - originX
        val dy = cy - originY
        val ax = kotlin.math.abs(dx)
        val ay = kotlin.math.abs(dy)

        if (axis == null) {
            axis = when {
                ax >= thresholdPx && ax >= ay * axisRatio -> Kind.WIND
                ay >= thresholdPx && ay >= ax * axisRatio -> Kind.TEMP
                else -> null
            }
            if (axis == null) return null
        }

        val signed = if (axis == Kind.WIND) dx else dy

        if (lastStepAt > 0L && cooldownMs > 0L && atMs - lastStepAt < cooldownMs) {
            return null
        }

        val absStep = kotlin.math.abs(stepIndex)
        if (absStep >= maxSteps) {
            // 到达正向/反向极限后，仍允许回拖，所以这里只在继续向外时挡住。
            if (stepIndex > 0 && signed > boundaryFor(absStep)) return null
            if (stepIndex < 0 && signed < -boundaryFor(absStep)) return null
        }

        val geometricDir = if (stepIndex >= 0) {
            when {
                signed >= boundaryFor(absStep + 1) -> 1
                stepIndex > 0 && signed <= boundaryFor(absStep) - REVERSE_HYSTERESIS_PX -> -1
                stepIndex == 0 && signed <= -boundaryFor(1) -> -1
                else -> 0
            }
        } else {
            when {
                signed <= -boundaryFor(absStep + 1) -> -1
                signed >= -boundaryFor(absStep) + REVERSE_HYSTERESIS_PX -> 1
                else -> 0
            }
        }

        if (geometricDir == 0) return null

        stepIndex += geometricDir
        lastStepAt = atMs
        // 温度是上滑加温，几何方向与业务方向相反。
        val logicalDir = if (axis == Kind.TEMP) -geometricDir else geometricDir
        return Step(axis!!, logicalDir)
    }

    /** 第 n 档边界的累计位移：1 档 96，2 档 198，3 档 306，4 档 420… */
    private fun boundaryFor(step: Int): Float {
        var total = 0f
        for (i in 0 until step) total += thresholdPx + i * resistancePx
        return total
    }
}