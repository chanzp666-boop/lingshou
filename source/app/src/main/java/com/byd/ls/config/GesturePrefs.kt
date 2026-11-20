package com.byd.ls.config

import android.app.Application
import android.content.Context
import android.content.SharedPreferences

/**
 * 全部设置项的唯一数据源（SharedPreferences）。
 *
 * 实时同步机制：
 *  - 界面写入 → SharedPreferences 立即落盘（apply）
 *  - GlobalGestureService 通过 [registerListener] 监听同一个 SP 文件，
 *    回调里直接 applyConfig() 刷新悬浮条宽度 / 手势阈值，无需重启服务。
 *
 * 注意：SP 不跨进程，只有主进程读它；:guard 守护进程不需要配置。
 */
object GesturePrefs {

    private const val FILE = "ls_gesture_prefs"

    const val KEY_ENABLED = "gesture_enabled"
    /** 双指空调开关（beta6）：与全屏手势服务同生命周期 */
    const val KEY_AC_ENABLED = "ac_enabled"
    const val KEY_EDGE_LEFT_DP = "edge_width_left_dp"
    const val KEY_EDGE_RIGHT_DP = "edge_width_right_dp"
    const val KEY_EDGE_BOTTOM_DP = "edge_width_bottom_dp"
    const val KEY_SENSITIVITY = "sensitivity"
    const val KEY_DEBUG_EDGE = "debug_edge_visible"
    const val KEY_GRANT_DONE = "one_click_grant_done"
    /** 日志开关（1.2.22 新增，默认关）：关掉后 LsLog 只在 ERROR 级落盘 */
    const val KEY_LOG_ENABLED = "log_enabled"
    /** BYD 诊断日志开关（默认关）：打开 = tag 级别 S 压制，关闭 = 属性置空；重启后按此偏好重放 */
    const val KEY_BYD_LOG_DISABLED = "byd_log_disabled"
    /** 仪表助理模块开关（合并阶段新增，默认关）：关掉时不拉起仪表卡片与相关组件 */
    const val KEY_CLUSTER_ENABLED = "cluster_enabled"
    /** 仪表屏"全屏导航优先"开关（默认关） */
    const val KEY_YIELD_NAV = "cluster_yield_nav"
    /** 1.4.137：开机把仪表投屏打到 16 档（全屏地图 + 顶部横条导航卡），默认开 */
    const val KEY_PROJECTION_FULL = "cluster_projection_full"

    // ==== 1.4.59：仪表卡片元素开关 + 像素级微调（用户口述需求）====
    const val KEY_SHOW_OIL = "card_show_oil"        // 本次油耗
    const val KEY_SHOW_ELEC = "card_show_elec"      // 本次电耗
    const val KEY_SHOW_TOTAL = "card_show_total"    // 综合油耗
    const val KEY_SHOW_TYRE = "card_show_tyre"      // 胎压
    const val KEY_SHOW_MUSIC = "card_show_music"    // 音乐卡片
    const val KEY_FUEL_DY = "card_fuel_dy"          // 油耗行上下（2px 步进）
    const val KEY_TYRE_DX = "card_tyre_dx"
    const val KEY_TYRE_DY = "card_tyre_dy"
    const val KEY_MUSIC_DX = "card_music_dx"
    const val KEY_MUSIC_DY = "card_music_dy"
    const val KEY_MUSIC_SMALL_DX = "card_music_small_dx"
    const val KEY_MUSIC_SMALL_DY = "card_music_small_dy"
    // 1.4.160：ADAS 状态（保电/云辇）独立微调，右对齐基准不变
    const val KEY_ADAS_DX = "card_adas_dx"
    const val KEY_ADAS_DY = "card_adas_dy"

    /** 边缘宽度范围，按需求限制 10dp ~ 50dp */
    const val MIN_EDGE_DP = 6
    /** 上限放开到 50dp：条宽由用户在界面上自己定，代码不再替他压窄 */
    const val MAX_EDGE_DP = 50
    const val DEFAULT_EDGE_DP = 20

    /** 灵敏度 0(最迟钝) ~ 100(最灵敏) */
    const val MIN_SENSITIVITY = 0
    const val MAX_SENSITIVITY = 100
    const val DEFAULT_SENSITIVITY = 50

    private lateinit var appCtx: Context
    val sp: SharedPreferences get() = appCtx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        // 1.4.163：当前位置已写进绘制基准，旧偏移一次性归零；之后用户再调仍可正常累加
        val p = sp
        if (!p.getBoolean("card_pos_zeroed_1_4_163", false)) {
            p.edit()
                .putInt(KEY_FUEL_DY, 0)
                .putInt(KEY_TYRE_DX, 0).putInt(KEY_TYRE_DY, 0)
                .putInt(KEY_MUSIC_DX, 0).putInt(KEY_MUSIC_DY, 0)
                .putInt(KEY_MUSIC_SMALL_DX, 0).putInt(KEY_MUSIC_SMALL_DY, 0)
                .putInt(KEY_ADAS_DX, 0).putInt(KEY_ADAS_DY, 0)
                .putBoolean("card_pos_zeroed_1_4_163", true)
                .apply()
        }
        // 1.4.164：小窗当前视觉位置（旧偏移 0/4px）已写进 SMALL_Y_BIG_CARD=402；这里只归零小窗偏移，
        // 不动用户后来调过的油耗/大窗/ADAS/胎压。
        if (!p.getBoolean("card_small_zeroed_1_4_164", false)) {
            p.edit()
                .putInt(KEY_MUSIC_SMALL_DX, 0).putInt(KEY_MUSIC_SMALL_DY, 0)
                .putBoolean("card_small_zeroed_1_4_164", true)
                .apply()
        }
    }

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(v) { sp.edit().putBoolean(KEY_ENABLED, v).apply() }

    /** 双指空调：默认关，必须在全屏手势开启时才有意义 */
    var acEnabled: Boolean
        get() = sp.getBoolean(KEY_AC_ENABLED, false)
        set(v) { sp.edit().putBoolean(KEY_AC_ENABLED, v).apply() }

    // ==== 1.4.59：卡片元素开关与偏移（1.4.163：当前位置已吸收进绘制基准，偏移默认 0）====
    var showOil: Boolean
        get() = sp.getBoolean(KEY_SHOW_OIL, true)
        set(v) { sp.edit().putBoolean(KEY_SHOW_OIL, v).apply() }
    var showElec: Boolean
        get() = sp.getBoolean(KEY_SHOW_ELEC, true)
        set(v) { sp.edit().putBoolean(KEY_SHOW_ELEC, v).apply() }
    var showTotal: Boolean
        get() = sp.getBoolean(KEY_SHOW_TOTAL, true)
        set(v) { sp.edit().putBoolean(KEY_SHOW_TOTAL, v).apply() }
    var showTyre: Boolean
        get() = sp.getBoolean(KEY_SHOW_TYRE, true)
        set(v) { sp.edit().putBoolean(KEY_SHOW_TYRE, v).apply() }
    var showMusic: Boolean
        get() = sp.getBoolean(KEY_SHOW_MUSIC, true)
        set(v) { sp.edit().putBoolean(KEY_SHOW_MUSIC, v).apply() }

    var fuelDy: Int
        get() = sp.getInt(KEY_FUEL_DY, 0)
        set(v) { sp.edit().putInt(KEY_FUEL_DY, v).apply() }
    var tyreDx: Int
        get() = sp.getInt(KEY_TYRE_DX, 0)
        set(v) { sp.edit().putInt(KEY_TYRE_DX, v).apply() }
    var tyreDy: Int
        get() = sp.getInt(KEY_TYRE_DY, 0)
        set(v) { sp.edit().putInt(KEY_TYRE_DY, v).apply() }
    var musicDx: Int
        get() = sp.getInt(KEY_MUSIC_DX, 0)
        set(v) { sp.edit().putInt(KEY_MUSIC_DX, v).apply() }
    var musicDy: Int
        get() = sp.getInt(KEY_MUSIC_DY, 0)
        set(v) { sp.edit().putInt(KEY_MUSIC_DY, v).apply() }
    // 1.4.72：小窗与大窗位置**互相独立**
    var musicSmallDx: Int
        get() = sp.getInt(KEY_MUSIC_SMALL_DX, 0)
        set(v) { sp.edit().putInt(KEY_MUSIC_SMALL_DX, v).apply() }
    var musicSmallDy: Int
        get() = sp.getInt(KEY_MUSIC_SMALL_DY, 0)
        set(v) { sp.edit().putInt(KEY_MUSIC_SMALL_DY, v).apply() }
    var adasDx: Int
        get() = sp.getInt(KEY_ADAS_DX, 0)
        set(v) { sp.edit().putInt(KEY_ADAS_DX, v).apply() }
    var adasDy: Int
        get() = sp.getInt(KEY_ADAS_DY, 0)
        set(v) { sp.edit().putInt(KEY_ADAS_DY, v).apply() }

    /** 左 / 右 / 底三条边缘宽度各自独立，范围 10dp ~ 50dp */
    private fun readEdge(key: String): Int =
        sp.getInt(key, DEFAULT_EDGE_DP).coerceIn(MIN_EDGE_DP, MAX_EDGE_DP)

    private fun writeEdge(key: String, v: Int) {
        sp.edit().putInt(key, v.coerceIn(MIN_EDGE_DP, MAX_EDGE_DP)).apply()
    }

    var edgeLeftDp: Int
        get() = readEdge(KEY_EDGE_LEFT_DP)
        set(v) = writeEdge(KEY_EDGE_LEFT_DP, v)

    var edgeRightDp: Int
        get() = readEdge(KEY_EDGE_RIGHT_DP)
        set(v) = writeEdge(KEY_EDGE_RIGHT_DP, v)

    var edgeBottomDp: Int
        get() = readEdge(KEY_EDGE_BOTTOM_DP)
        set(v) = writeEdge(KEY_EDGE_BOTTOM_DP, v)

    var sensitivity: Int
        get() = sp.getInt(KEY_SENSITIVITY, DEFAULT_SENSITIVITY).coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)
        set(v) { sp.edit().putInt(KEY_SENSITIVITY, v.coerceIn(MIN_SENSITIVITY, MAX_SENSITIVITY)).apply() }

    var debugEdgeVisible: Boolean
        get() = sp.getBoolean(KEY_DEBUG_EDGE, false)
        set(v) { sp.edit().putBoolean(KEY_DEBUG_EDGE, v).apply() }

    var grantDone: Boolean
        get() = sp.getBoolean(KEY_GRANT_DONE, false)
        set(v) { sp.edit().putBoolean(KEY_GRANT_DONE, v).apply() }

    /** 日志开关：默认关（现场要求减少 CPU 损耗） */
    var logEnabled: Boolean
        get() = sp.getBoolean(KEY_LOG_ENABLED, false)
        set(v) {
            sp.edit().putBoolean(KEY_LOG_ENABLED, v).apply()
            com.byd.ls.util.LsLog.enabled = v
            // 合并后仪表模块与本应用同进程同一个开关：一处拨动，两边同时生效
            com.byd.ls.cluster.cfg.ClusterDebug.setMemory(v)
        }

    /**
     * BYD 诊断日志开关：true = 已压制 9 个 BYD CAN 事件 tag。
     * 默认 false（不主动改系统）；true 时开机在 ADB 连上后幂等重放 off。
     */
    var bydLogDisabled: Boolean
        get() = sp.getBoolean(KEY_BYD_LOG_DISABLED, false)
        set(v) { sp.edit().putBoolean(KEY_BYD_LOG_DISABLED, v).apply() }

    /** 仪表助理模块开关：默认关。默认值即"合并未启用"，不影响任何既有行为 */
    var clusterEnabled: Boolean
        get() = sp.getBoolean(KEY_CLUSTER_ENABLED, false)
        set(v) { sp.edit().putBoolean(KEY_CLUSTER_ENABLED, v).apply() }

    /**
     * 仪表屏"全屏导航优先"：开启后，检测到 display 1 上是高德在跑时卡片让位。
     *
     * 默认**关**。1.2.41 默认开启导致开机时卡片不拉（高德比我们先起，判据把
     * "高德在仪表跑导航卡片"误当成"全屏导航占用"），现场表现就是"开机没卡片"。
     */
    var clusterYieldToNav: Boolean
        get() = sp.getBoolean(KEY_YIELD_NAV, false)
        set(v) { sp.edit().putBoolean(KEY_YIELD_NAV, v).apply() }

    /**
     * 1.4.137：开机自启时把仪表投屏档位打到 16（全屏地图 + 顶部横条导航卡）。
     *
     * 默认**开**。档位不持久（重启、方向盘/中控切页、卡片重建都会打回 17），
     * 所以每次自启信号都补发一次；重复发 16 无副作用（实测不闪屏不报错）。
     * 关掉后灵手完全不碰仪表投屏，方向盘换页后维持原样。
     */
    var projectionFullOnBoot: Boolean
        get() = sp.getBoolean(KEY_PROJECTION_FULL, true)
        set(v) { sp.edit().putBoolean(KEY_PROJECTION_FULL, v).apply() }


    /** 开机自启：默认开。关掉后只有手动打开 App 才会拉起服务 */
    // ---------------------------------------------------------------------
    //  跨进程的开关标记
    //
    //  SharedPreferences 不是多进程安全的：:guard 守护进程拿到的永远是它自己首次
    //  加载时的快照，主进程把开关关掉后它感知不到，会一直把手势服务拉回来。
    //  所以另写一个小标记文件，守护进程每轮巡检直接读磁盘。
    // ---------------------------------------------------------------------
    private const val ENABLED_FLAG_FILE = "ls_enabled.flag"

    /** 主进程在开关变化时调用 */
    fun writeEnabledFlag(ctx: Context) {
        runCatching {
            java.io.File(ctx.filesDir, ENABLED_FLAG_FILE).writeText(if (enabled) "1" else "0")
        }
    }

    /** 其它进程（:guard）用这个判断，读不到文件时退回本进程的 SP 值 */
    fun enabledFromDisk(ctx: Context): Boolean = runCatching {
        val f = java.io.File(ctx.filesDir, ENABLED_FLAG_FILE)
        if (f.exists()) f.readText().trim() == "1" else enabled
    }.getOrElse { enabled }

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp.registerOnSharedPreferenceChangeListener(l)
    }

    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {
        sp.unregisterOnSharedPreferenceChangeListener(l)
    }

    /**
     * 灵敏度 → 真实判定阈值的映射。
     *
     * triggerDp：手指需要移动多远才算一次有效手势（越小越灵敏）。
     * 不区分速度 / 停留：底部上滑只要越过位移阈值就回桌面。
     *
     * 灵敏度 0   → 48dp（迟钝）
     * 灵敏度 50  → 34dp（默认，1.1.1 从 40dp 下调，解决"要划很远才返回"）
     * 灵敏度 100 → 20dp（灵敏，轻扫即触发）
     */
    data class Thresholds(val triggerPx: Float)

    fun thresholds(density: Float): Thresholds {
        val t = (sensitivity - MIN_SENSITIVITY).toFloat() / (MAX_SENSITIVITY - MIN_SENSITIVITY)
        // 更跟手：36 → 12dp（原来 48 → 20）。阈值只决定"多长算触发"，
        // 真正的跟手靠 1.2.15 的速度触发（距离到 55% + 速度够快就提前返回）。
        val triggerDp = 36f - 24f * t
        return Thresholds(triggerDp * density)
    }
}
