package com.byd.ls.ui

import android.content.res.Configuration
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.byd.ls.cluster.data.Card
import com.byd.ls.cluster.data.LyricTune
import com.byd.ls.cluster.data.MediaListener
import com.byd.ls.cluster.ui.StyleEditor
import com.byd.ls.R
import com.byd.ls.config.GesturePrefs
import com.byd.ls.databinding.ActivityMainBinding
import com.byd.ls.guard.DaemonGuard
import com.byd.ls.service.A11yHealer
import com.byd.ls.service.BootJobService
import com.byd.ls.service.ClusterCardKeeper
import com.byd.ls.service.LsAccessibilityService
import com.byd.ls.service.GlobalGestureService
import com.byd.ls.service.GuardService
import com.byd.ls.shell.AdbAutoAuth
import com.byd.ls.shell.AdbManager
import com.byd.ls.shell.PrivilegeBootstrap
import com.byd.ls.shell.ShellManager
import com.byd.ls.receiver.HeartbeatReceiver
import com.byd.ls.remote.RemoteAgent
import com.byd.ls.remote.RemoteConfig
import com.byd.ls.util.LsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置界面。
 *
 * 产品目标只有五条：全屏手势导航、边缘宽度/灵敏度可调、ADB 自授权、开机自启、无障碍保活与自愈。
 * 界面只承载这五条对应的最小控件集（1.2.22 按现场要求做过一次收敛）：
 *  · 已删除：按键链路测试、开机自动恢复开关、日志预览/刷新/清空、黑名单摘要、
 *    「打开车机 ADB 设置」、看门狗脚本按钮、ADB 授权详情页入口。
 *  · 保留：手势总开关、调试描边、三边宽度、灵敏度、一键启动/修复、运行状态、日志开关。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    /** 程序化设置控件状态时抑制监听回调，避免自激 */
    private var suppress = false
    /** BYD 诊断日志开关执行中：防止连点与状态回写打架 */
    private var bydBusy = false
    /** 记录当前是否为夜间，uiMode 变化时据此重建界面换色。 */
    private var lastNight = false

    // =====================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GesturePrefs.init(application)
        LsLog.init(application)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        lastNight = isNight()
        applySystemBars()
        // 版本号直接显示在副标题上：现场只需看一眼首页就知道装的是哪一版、测的是哪一版。
        val vName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty()
        b.tvSubtitle.text = if (vName.isEmpty()) "全屏手势导航" else "全屏手势导航 · v$vName"

        bindCoreSwitch()
        bindAcSwitch()
        bindEdgeWidths()
        bindSensitivity()
        bindGrant()
        bindLog()
        bindBydLog()
        bindCluster()
        GesturePrefs.writeCardFlag(application)
        bindActions()
        bindRemoteDebug()

        refreshStatus()
        runCatching { installTabs() }
    }


    // =====================================================================
    //  日 / 夜跟随车机系统：uiMode 在 configChanges 里，需要主动重建才会重新取颜色
    // =====================================================================
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val night = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (night != lastNight) {
            lastNight = night
            recreate()
        }
    }

    private fun isNight(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun applySystemBars() {
        val night = isNight()
        window.statusBarColor = uiColor(R.color.bg_root)
        window.navigationBarColor = uiColor(R.color.bg_root)
        var flags = window.decorView.systemUiVisibility
        flags = if (night) {
            flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        } else {
            flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        window.decorView.systemUiVisibility = flags
    }

    // =====================================================================
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun uiColor(id: Int): Int = getColor(id)

    // 1.4.59：底部两个标签（手势 / 仪表）+ 仪表元素开关与 2px 微调
    // 不改 activity_main.xml（两列布局已实车调过），运行时重排：
    //   · 手势页 = 原布局去掉「仪表卡片控制」那几张卡
    //   · 仪表页 = 程序化面板（开关 + 微调）+ 原卡片控制卡（整卡搬过去，监听不动）
    // =====================================================================
    private fun installTabs() {
        val content = findViewById<android.view.ViewGroup>(android.R.id.content)
        val original = content.getChildAt(0) as android.view.View
        val columns = (original as android.view.ViewGroup).getChildAt(0) as android.view.ViewGroup
        // 1.4.152：底部导航栏是 64dp 覆盖层；手势页内容滚到底时，最后一张卡要停在栏上方 40px，
        // 不能贴住/钻到栏下面。列容器自身加 paddingBottom（不是 ScrollView 的 padding，
        // 这样 clipToPadding=false 也不会把卡片画进 padding 区）。
        columns.setPadding(columns.paddingLeft, columns.paddingTop, columns.paddingRight,
            dp(64) + 40)

        /** 从控件往上走，直到「卡的父容器是列、列的父容器是两列根」→ 返回那张卡 */
        fun cardOf(id: Int): android.view.View? {
            var v: android.view.View? = original.findViewById(id) ?: return null
            while (v != null) {
                val p = v.parent as? android.view.ViewGroup ?: return null
                if (p.parent === columns) return v
                v = p
            }
            return null
        }

        content.removeView(original)
        val frame = android.widget.FrameLayout(this)
        val scroll = android.widget.ScrollView(this)
        scroll.setBackgroundColor(uiColor(R.color.bg_root))
        scroll.isFillViewport = true
        val col = android.widget.LinearLayout(this)
        col.orientation = android.widget.LinearLayout.VERTICAL
        // 1.4.152：仪表页同样给 64dp 导航栏 + 40px 呼吸区；原来 96dp 只剩约 48px，视觉上仍贴。
        col.setPadding(dp(36), dp(36), dp(36), dp(64) + 40)
        scroll.addView(col)

        // 1.4.145：仪表页第一张卡 = 原「仪表卡片」控制卡（启动/停止/元素样式/歌词校准）。
        // 注意：必须取「卡」本身，不能从 btn_card_start 一路走到 columns（那样拿到的是整列，
        // 会被下面的安全检查判成 risky，卡就一直留在手势页）。
        val cand = cardOf(R.id.btn_card_start)
        val risky = listOf(R.id.btn_activate, R.id.tv_grant_hint, R.id.switch_enable)
            .any { id -> cand?.findViewById<android.view.View>(id) != null }
        if (cand != null && !risky) {
            (cand.parent as? android.view.ViewGroup)?.removeView(cand)
        }

        // 1.4.146：四张卡都按手势页的半宽卡排，两列向下：
        //   左列：仪表卡片、油耗；右列：音乐、胎压。高度更均衡，也没有全宽卡打断节奏。
        val grid = android.widget.LinearLayout(this)
        grid.orientation = android.widget.LinearLayout.HORIZONTAL
        val left = android.widget.LinearLayout(this)
        left.orientation = android.widget.LinearLayout.VERTICAL
        val right = android.widget.LinearLayout(this)
        right.orientation = android.widget.LinearLayout.VERTICAL
        grid.addView(left, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
        grid.addView(android.view.View(this), android.widget.LinearLayout.LayoutParams(dp(20), 1))
        grid.addView(right, android.widget.LinearLayout.LayoutParams(0, -2, 1f))

        if (cand != null && !risky) left.addView(cand)

        val fuel = cardBox()
        fuel.addView(sectionTitle("油耗卡片"))
        // 1.4.161：元素设置提到最上（按钮在上、小字在下），三个显示开关连排
        fuel.addView(elementRow("油耗元素（标题/数值/单位 的颜色·大小·粗细·描边）",
            arrayOf("cap", "num", "unit")))
        fuel.addView(switchRow("综合油耗", GesturePrefs.showTotal) { GesturePrefs.showTotal = it })
        fuel.addView(switchRow("本次油耗", GesturePrefs.showOil) { GesturePrefs.showOil = it })
        fuel.addView(switchRow("本次电耗", GesturePrefs.showElec) { GesturePrefs.showElec = it })
        fuel.addView(nudgeRow("油耗栏上下移动（每次 4px）",
            onUp = { GesturePrefs.fuelDy -= 4 }, onDown = { GesturePrefs.fuelDy += 4 },
            label = { "上下偏移 " + GesturePrefs.fuelDy + " px" }))
        right.addView(fuel)

        val tyre = cardBox()
        // 1.4.161：开关并入标题栏，不再显示「显示胎压」副标题
        tyre.addView(sectionTitleSwitch("胎压卡片", GesturePrefs.showTyre) { GesturePrefs.showTyre = it })
        tyre.addView(elementRow("胎压/ADAS 状态（颜色·大小·粗细·描边）",
            arrayOf("tnum", "tunit", "adas")))
        tyre.addView(nudge4Row("保电/云辇状态位置（每次 4px）",
            onL = { GesturePrefs.adasDx -= 4 }, onR = { GesturePrefs.adasDx += 4 },
            onU = { GesturePrefs.adasDy -= 4 }, onD = { GesturePrefs.adasDy += 4 },
            label = { "左右 " + GesturePrefs.adasDx + " px · 上下 " + GesturePrefs.adasDy + " px" }))
        tyre.addView(nudge4Row("胎压位置（每次 4px）",
            onL = { GesturePrefs.tyreDx -= 4 }, onR = { GesturePrefs.tyreDx += 4 },
            onU = { GesturePrefs.tyreDy -= 4 }, onD = { GesturePrefs.tyreDy += 4 },
            label = { "左右 " + GesturePrefs.tyreDx + " px · 上下 " + GesturePrefs.tyreDy + " px" }))

        val music = cardBox()
        // 1.4.161：改名「音乐卡片」，开关并入标题栏，不再显示「显示音乐窗口」副标题
        music.addView(sectionTitleSwitch("音乐卡片", GesturePrefs.showMusic) { GesturePrefs.showMusic = it })
        music.addView(elementRow("音乐元素（曲名/歌手/歌词 的颜色·大小·粗细·描边）",
            arrayOf("title", "artist", "lcur", "lprev", "lfar")))
        music.addView(nudge4Row("音乐卡片位置（每次 4px）",
            onL = { GesturePrefs.musicDx -= 4 }, onR = { GesturePrefs.musicDx += 4 },
            onU = { GesturePrefs.musicDy -= 4 }, onD = { GesturePrefs.musicDy += 4 },
            label = { "左右 " + GesturePrefs.musicDx + " px · 上下 " + GesturePrefs.musicDy + " px" }))
        music.addView(nudge4Row("音乐小窗位置（每次 4px，与大窗独立）",
            onL = { GesturePrefs.musicSmallDx -= 4 }, onR = { GesturePrefs.musicSmallDx += 4 },
            onU = { GesturePrefs.musicSmallDy -= 4 }, onD = { GesturePrefs.musicSmallDy += 4 },
            label = { "左右 " + GesturePrefs.musicSmallDx + " px · 上下 " + GesturePrefs.musicSmallDy + " px" }))
        left.addView(music)
        right.addView(tyre)

        col.addView(grid)

        frame.addView(original, android.view.ViewGroup.LayoutParams(-1, -1))
        frame.addView(scroll, android.view.ViewGroup.LayoutParams(-1, -1))
        scroll.visibility = android.view.View.GONE

        val bar = android.widget.LinearLayout(this)
        bar.orientation = android.widget.LinearLayout.HORIZONTAL
        bar.setBackgroundColor(uiColor(R.color.bg_tab_bar))
        val lp = android.widget.FrameLayout.LayoutParams(-1, dp(64))
        lp.gravity = android.view.Gravity.BOTTOM
        frame.addView(bar, lp)
        val tab1 = tabButton("手势")
        val tab2 = tabButton("仪表")
        bar.addView(tab1, android.widget.LinearLayout.LayoutParams(0, -1, 1f))
        bar.addView(tab2, android.widget.LinearLayout.LayoutParams(0, -1, 1f))
        fun select(instrument: Boolean) {
            scroll.visibility = if (instrument) android.view.View.VISIBLE else android.view.View.GONE
            original.visibility = if (instrument) android.view.View.GONE else android.view.View.VISIBLE
            tab1.setTextColor(if (instrument) uiColor(R.color.text_secondary) else uiColor(R.color.text_primary))
            tab2.setTextColor(if (instrument) uiColor(R.color.text_primary) else uiColor(R.color.text_secondary))
        }
        tab1.setOnClickListener { select(false) }
        tab2.setOnClickListener { select(true) }
        select(false)

        (original as android.view.ViewGroup).clipToPadding = false
        content.addView(frame)
    }

    private fun tabButton(text: String): android.widget.TextView {
        val tv = android.widget.TextView(this)
        tv.text = text
        tv.gravity = android.view.Gravity.CENTER
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
        tv.setTextColor(uiColor(R.color.text_primary))
        tv.isClickable = true
        return tv
    }

    /** 1.4.60：一栏一个「元素设置」按钮，只编辑该栏自己的元素 */
    private fun elementRow(title: String, elements: Array<String>): android.view.View {
        val box = android.widget.LinearLayout(this)
        box.orientation = android.widget.LinearLayout.VERTICAL
        val bt = android.widget.Button(this)
        bt.text = "元素设置"
        bt.setBackgroundResource(com.byd.ls.R.drawable.bg_button_primary)
        bt.setTextColor(0xFFFFFFFF.toInt())
        bt.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
        bt.setOnClickListener { runCatching { com.byd.ls.cluster.ui.StyleEditor(this, elements).show() } }
        box.addView(bt, android.widget.LinearLayout.LayoutParams(dp(148), dp(52)))
        val tv = android.widget.TextView(this)
        tv.text = title
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        tv.setTextColor(uiColor(R.color.text_secondary))
        tv.setPadding(0, dp(8), 0, 0)
        box.addView(tv)
        box.setPadding(0, dp(8), 0, dp(14))
        return box
    }

    /** 1.4.63：仪表页也用手势页那套卡片样式（bg_card + card_padding + card_gap） */
    private fun cardBox(): android.widget.LinearLayout {
        val box = android.widget.LinearLayout(this, null, 0, com.byd.ls.R.style.Card)
        box.orientation = android.widget.LinearLayout.VERTICAL
        // 程序化创建的卡不吃 style 里的 layout_marginBottom，显式补 20dp 卡间距
        box.layoutParams = android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(20) }
        return box
    }

    /** 1.4.66：与「仪表卡片」那张卡同款标题（1.4.151：26→24sp，拉开与正文的层级） */
    private fun sectionTitle(t: String): android.widget.TextView {
        val tv = android.widget.TextView(this)
        tv.text = t
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 24f)
        tv.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD))
        tv.setTextColor(uiColor(R.color.text_primary))
        tv.setPadding(0, 0, 0, dp(10))
        return tv
    }

    /** 1.4.161：卡片标题栏右侧带开关，替代单独的“显示xxx”小标题 */
    private fun sectionTitleSwitch(t: String, checked: Boolean, onChange: (Boolean) -> Unit): android.view.View {
        val row = android.widget.LinearLayout(this)
        row.orientation = android.widget.LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        row.setPadding(0, 0, 0, dp(10))
        val tv = android.widget.TextView(this)
        tv.text = t
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 24f)
        tv.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD))
        tv.setTextColor(uiColor(R.color.text_primary))
        row.addView(tv, android.widget.LinearLayout.LayoutParams(0, -1, 1f))
        val sw = android.widget.Switch(this)
        sw.isChecked = checked
        sw.setOnCheckedChangeListener { _, v ->
            onChange(v)
            GesturePrefs.writeCardFlag(this)
        }
        row.addView(sw)
        return row
    }

    /** 1.4.66：卡片副标题（暗色小字，与仪表卡片一致） */
    private fun subTitle(t: String): android.widget.TextView {
        val tv = android.widget.TextView(this)
        tv.text = t
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
        tv.setTextColor(uiColor(R.color.text_secondary))
        tv.setPadding(0, 0, 0, dp(8))
        return tv
    }

    private fun switchRow(name: String, checked: Boolean, onChange: (Boolean) -> Unit): android.view.View {
        val row = android.widget.LinearLayout(this)
        row.orientation = android.widget.LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        row.minimumHeight = dp(56)
        row.setPadding(0, dp(8), 0, dp(8))
        val tv = android.widget.TextView(this)
        tv.text = name
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
        tv.setTextColor(uiColor(R.color.text_primary))
        row.addView(tv, android.widget.LinearLayout.LayoutParams(0, -1, 1f))
        val sw = android.widget.Switch(this)
        sw.isChecked = checked
        sw.setOnCheckedChangeListener { _, v ->
            onChange(v)
            GesturePrefs.writeCardFlag(this)
        }
        row.addView(sw)
        return row
    }

    private fun nudgeRow(title: String, onUp: () -> Unit, onDown: () -> Unit, label: () -> String): android.view.View {
        val box = android.widget.LinearLayout(this)
        box.orientation = android.widget.LinearLayout.VERTICAL
        box.setPadding(0, dp(8), 0, dp(12))
        val tv = android.widget.TextView(this)
        tv.text = title
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
        tv.setTextColor(uiColor(R.color.text_primary))
        box.addView(tv)
        val row = android.widget.LinearLayout(this)
        row.orientation = android.widget.LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(6), 0, 0)
        val state = android.widget.TextView(this)
        state.text = label()
        state.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        state.setTextColor(uiColor(R.color.text_secondary))
        row.addView(state, android.widget.LinearLayout.LayoutParams(0, -1, 1f))
        fun btn(t: String, f: () -> Unit) {
            val bt = android.widget.Button(this)
            bt.text = t
            bt.setBackgroundResource(com.byd.ls.R.drawable.bg_button_ghost)
            bt.setTextColor(uiColor(R.color.text_primary))
            bt.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            bt.setOnClickListener {
                f()
                state.text = label()
                // 1.4.164：同进程直刷仪表卡片，避免等下一次 700ms tick 才看到 4px 变化。
                com.byd.ls.cluster.ui.CardActivity.refreshNow()
            }
            val lp = android.widget.LinearLayout.LayoutParams(dp(64), dp(52))
            lp.marginStart = dp(8)
            row.addView(bt, lp)
        }
        btn("▲", onUp); btn("▼", onDown)
        box.addView(row)
        return box
    }

    private fun nudge4Row(title: String, onL: () -> Unit, onR: () -> Unit, onU: () -> Unit,
                          onD: () -> Unit, label: () -> String): android.view.View {
        val box = android.widget.LinearLayout(this)
        box.orientation = android.widget.LinearLayout.VERTICAL
        box.setPadding(0, dp(8), 0, dp(12))
        val tv = android.widget.TextView(this)
        tv.text = title
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
        tv.setTextColor(uiColor(R.color.text_primary))
        box.addView(tv)
        val state = android.widget.TextView(this)
        state.text = label()
        state.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        state.setTextColor(uiColor(R.color.text_secondary))
        state.setPadding(0, dp(2), 0, dp(8))
        box.addView(state)
        val row = android.widget.LinearLayout(this)
        row.orientation = android.widget.LinearLayout.HORIZONTAL
        fun btn(t: String, f: () -> Unit) {
            val bt = android.widget.Button(this)
            bt.text = t
            bt.setBackgroundResource(com.byd.ls.R.drawable.bg_button_ghost)
            bt.setTextColor(uiColor(R.color.text_primary))
            bt.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            bt.setOnClickListener { f(); state.text = label() }
            val lp = android.widget.LinearLayout.LayoutParams(dp(64), dp(52))
            lp.marginEnd = dp(8)
            row.addView(bt, lp)
        }
        btn("←", onL); btn("→", onR); btn("↑", onU); btn("↓", onD)
        box.addView(row)
        return box
    }

    override fun onResume() {
        super.onResume()
        // 每次回到界面都点检一次无障碍：名单被 ROM 回写抹掉时立刻补回（带 12s 防抖）
        A11yHealer.healAsync(applicationContext, "MainActivity.onResume")
        // 如果名单在但 binding 丢了，前台立即修一次，不等后台 30s
        A11yHealer.repairOnUserOpen(applicationContext)
        // 用户主动打开界面时优先自愈：跳过 10 分钟间隔，但仍受「每次开机最多 2 次」约束
        lifecycleScope.launch {
            AdbAutoAuth.maybeRequestAuthorization(applicationContext, "open", force = true)
        }
        // 回到界面时立刻补手势服务与卡片：后台巡检 20s 一轮、连丢两次才动手，
        // 用户从导航/桌面切回来时等不起，这里直接触发一次（heal 内部有 5s 节流）
        if (GesturePrefs.enabled && !GlobalGestureService.isRunning()) {
            runCatching { GlobalGestureService.start(applicationContext) }
            runCatching { DaemonGuard.start(applicationContext) }
        }
        runCatching { ClusterCardKeeper.heal(applicationContext, "foreground") }
        // 兜底：真重启后 /data/local/tmp 里的脚本还在但进程已没了，打开 App 就补装一次（30s 节流）
        Thread {
            runCatching {
                kotlinx.coroutines.runBlocking {
                    com.byd.ls.shell.WatchdogInstaller.ensure(applicationContext, "onResume")
                }
            }
        }.apply { isDaemon = true; name = "ls-wd"; start() }
        refreshStatus()
        refreshBydLogState()
    }

    // =====================================================================
    //  核心开关
    // =====================================================================
    private fun bindCoreSwitch() {
        suppress = true
        b.switchEnable.isChecked = GesturePrefs.enabled
        b.switchProjectionFull.isChecked = GesturePrefs.projectionFullOnBoot
        b.switchDebugEdge.isChecked = GesturePrefs.debugEdgeVisible
        suppress = false
        updateSwitchHint()

        b.switchEnable.setOnCheckedChangeListener { _, checked ->
            if (suppress) return@setOnCheckedChangeListener
            GesturePrefs.enabled = checked
            GesturePrefs.writeEnabledFlag(this)
            if (checked) {
                if (!Settings.canDrawOverlays(this)) {
                    toast("还没有悬浮窗权限，请先点「一键启动 / 修复」")
                }
                GlobalGestureService.start(this)
                GuardService.start(this)
                DaemonGuard.start(this)
                HeartbeatReceiver.schedule(this)
                A11yHealer.healAsync(applicationContext, "switch-on")
                A11yHealer.repairOnUserOpen(applicationContext)
            } else {
                GlobalGestureService.stop(this)
                GuardService.stop(this)
                DaemonGuard.stop(this)
                HeartbeatReceiver.unschedule(this)
            }
            updateSwitchHint()
            b.switchAc.isEnabled = checked
            refreshStatus()
        }

        // 1.4.138 全屏导航优化：打开 = 上电 16 档 + 开始导航补发；关闭 = 完全走原厂 17 档
        b.switchProjectionFull.setOnCheckedChangeListener { _, checked ->
            if (suppress) return@setOnCheckedChangeListener
            GesturePrefs.projectionFullOnBoot = checked
            com.byd.ls.cluster.data.NavState.setDetectionDisabled(checked)
            if (checked) com.byd.ls.cluster.ClusterProjection.startKeepFull(this)
            else com.byd.ls.cluster.ClusterProjection.stopKeepFull()
            toast(if (checked) "已开启：全屏导航 + 音乐窗恒大窗" else "已关闭：使用原厂投屏方案，音乐窗跟随导航")
        }

        b.switchDebugEdge.setOnCheckedChangeListener { _, checked ->
            if (suppress) return@setOnCheckedChangeListener
            GesturePrefs.debugEdgeVisible = checked
        }

    }

    private fun updateSwitchHint() {
        b.tvSwitchHint.text = if (GesturePrefs.enabled)
            "已开启：左/右边缘滑动=返回，底部上滑=桌面（跟随系统默认桌面）"
        else
            "关闭时不会创建任何悬浮窗，也不驻留前台服务"
    }

    // =====================================================================
    //  双指空调开关（beta6）
    // =====================================================================
    private fun bindAcSwitch() {
        suppress = true
        b.switchAc.isChecked = GesturePrefs.acEnabled
        b.switchAc.isEnabled = GesturePrefs.enabled
        suppress = false

        b.switchAc.setOnCheckedChangeListener { _, checked ->
            if (suppress) return@setOnCheckedChangeListener
            if (checked && !GesturePrefs.enabled) {
                suppress = true
                b.switchAc.isChecked = false
                suppress = false
                toast("请先开启全屏手势")
                return@setOnCheckedChangeListener
            }
            GesturePrefs.acEnabled = checked
            if (checked) {
                runCatching { GlobalGestureService.start(this) }
                toast("双指空调已开启：左右滑=风量，上下滑=温度")
            } else {
                toast("双指空调已关闭")
            }
        }
    }

    // =====================================================================
    //  边缘宽度（10dp ~ 50dp）：左 / 右 / 底三边独立
    // =====================================================================
    private fun bindEdgeWidths() {
        bindEdge(b.seekEdgeLeft, b.tvEdgeLeftValue,
            { GesturePrefs.edgeLeftDp }, { GesturePrefs.edgeLeftDp = it })
        bindEdge(b.seekEdgeRight, b.tvEdgeRightValue,
            { GesturePrefs.edgeRightDp }, { GesturePrefs.edgeRightDp = it })
        bindEdge(b.seekEdgeBottom, b.tvEdgeBottomValue,
            { GesturePrefs.edgeBottomDp }, { GesturePrefs.edgeBottomDp = it })
    }

    private fun bindEdge(
        bar: android.widget.SeekBar,
        value: android.widget.TextView,
        get: () -> Int,
        set: (Int) -> Unit
    ) {
        bar.max = GesturePrefs.MAX_EDGE_DP - GesturePrefs.MIN_EDGE_DP
        bar.progress = get() - GesturePrefs.MIN_EDGE_DP
        value.text = "${get()} dp"

        bar.setOnSeekBarChangeListener(seekListener { progress ->
            val dp = (progress + GesturePrefs.MIN_EDGE_DP)
                .coerceIn(GesturePrefs.MIN_EDGE_DP, GesturePrefs.MAX_EDGE_DP)
            set(dp)        // 实时同步到服务
            value.text = "$dp dp"
        })
    }

    // =====================================================================
    //  灵敏度（0 ~ 100）
    // =====================================================================
    private fun bindSensitivity() {
        b.seekSens.max = GesturePrefs.MAX_SENSITIVITY
        b.seekSens.progress = GesturePrefs.sensitivity
        b.tvSensValue.text = GesturePrefs.sensitivity.toString()
        updateThresholdText()

        b.seekSens.setOnSeekBarChangeListener(seekListener { progress ->
            GesturePrefs.sensitivity = progress   // 实时同步到服务
            b.tvSensValue.text = progress.toString()
            updateThresholdText()
        })
    }

    private fun updateThresholdText() {
        val t = GesturePrefs.thresholds(resources.displayMetrics.density)
        val density = resources.displayMetrics.density
        b.tvThresholdDetail.text = "当前阈值：触发位移 ${fmt(t.triggerPx / density)} dp"
    }

    private fun fmt(v: Float): String = if (v >= 100) v.toInt().toString() else String.format("%.0f", v)

    private inline fun seekListener(crossinline onChange: (Int) -> Unit) =
        object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !suppress) onChange(progress)
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        }

    // =====================================================================
    //  一键启动 / 修复（唯一的授权入口：内置 ADB 自授权）
    // =====================================================================
    private fun bindGrant() {
        b.btnActivate.setOnClickListener { doActivate() }
    }

    @Volatile private var waitingAllow = false
    @Volatile private var oneTapRunning = false
    @Volatile private var needPortShown = false

    private fun doActivate() {
        if (waitingAllow) {
            waitingAllow = false
            b.btnActivate.text = "一键启动 / 修复（推荐）"
            b.btnActivate.isEnabled = true
            toast("已取消等待授权")
            return
        }
        if (oneTapRunning) return
        oneTapRunning = true
        b.btnActivate.isEnabled = false
        b.btnActivate.text = "正在启动…"
        b.tvGrantResult.text = "正在一键启动…"
        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    PrivilegeBootstrap.oneTap(applicationContext) { p ->
                        runOnUiThread {
                            b.tvGrantResult.text = p.text
                            b.btnActivate.text = when {
                                p.waiting -> "取消等待授权"
                                p.stage == PrivilegeBootstrap.Stage.DONE -> "一键启动 / 修复（推荐）"
                                else -> "正在执行…"
                            }
                            b.btnActivate.isEnabled = p.waiting
                            if (p.stage == PrivilegeBootstrap.Stage.WAIT_ALLOW) waitingAllow = true
                            if (p.stage == PrivilegeBootstrap.Stage.NEED_PORT && !needPortShown) {
                                needPortShown = true
                                showNeedPortDialog()
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                PrivilegeBootstrap.Result(false, "异常", listOf("✗ 一键启动 — 异常：${t.message}"))
            } finally {
                oneTapRunning = false
                waitingAllow = false
            }
            b.btnActivate.text = "一键启动 / 修复（推荐）"
            b.btnActivate.isEnabled = true
            b.tvGrantResult.text = result.lines.joinToString("\n")
            b.tvGrantResult.setTextColor(uiColor(if (result.ok) R.color.state_ok else R.color.state_warn))
            toast(if (result.ok) "一键启动完成，请查看运行状态" else "部分步骤失败，请查看结果区")
            refreshStatus()
        }
    }

    private fun showNeedPortDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("未检测到 ADB 端口")
            .setMessage("车机没有打开 USB 调试（127.0.0.1:5555 未监听）。\n\n请到车机 设置→开发者选项 打开 USB 调试，然后回来再点一次「一键启动 / 修复」。")
            .setPositiveButton("知道了", null)
            .show()
    }

    // =====================================================================
    //  日志开关（左列）
    // =====================================================================
    private fun bindLog() {
        suppress = true
        b.switchLog.isChecked = GesturePrefs.logEnabled
        suppress = false

        b.switchLog.setOnCheckedChangeListener { _, checked ->
            if (suppress) return@setOnCheckedChangeListener
            GesturePrefs.logEnabled = checked
            LsLog.enabled = checked
            toast(if (checked) "日志已开启（会增加少量 CPU 占用）" else "日志已关闭")
            refreshStatus()
        }
    }

    // =====================================================================
    //  BYD 诊断日志开关（默认关；打开后开机保持禁用）
    // =====================================================================
    private fun bindBydLog() {
        suppress = true
        b.switchBydLog.isChecked = GesturePrefs.bydLogDisabled
        suppress = false
        b.switchBydLog.setOnCheckedChangeListener { _, checked ->
            if (suppress || bydBusy) return@setOnCheckedChangeListener
            applyBydLog(checked)
        }
    }

    /** 每次回到首页都真实读一次车机状态；不再按 com.byd.logswitch 包判断。 */
    private fun refreshBydLogState() {
        if (bydBusy) return
        lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) {
                runCatching { com.byd.ls.shell.BydLogSwitch.status(applicationContext) }
                    .getOrElse { com.byd.ls.shell.BydLogSwitch.State.UNKNOWN }
            }
            if (bydBusy) return@launch
            b.rowBydLog.visibility = View.VISIBLE
            suppress = true
            b.switchBydLog.isChecked = when (state) {
                com.byd.ls.shell.BydLogSwitch.State.DISABLED -> true
                com.byd.ls.shell.BydLogSwitch.State.ENABLED -> false
                else -> GesturePrefs.bydLogDisabled
            }
            suppress = false
            // 真实状态与本地偏好对齐：开机重放依赖这个偏好；不能只刷 UI。
            when (state) {
                com.byd.ls.shell.BydLogSwitch.State.DISABLED -> GesturePrefs.bydLogDisabled = true
                com.byd.ls.shell.BydLogSwitch.State.ENABLED -> GesturePrefs.bydLogDisabled = false
                else -> Unit
            }
        }
    }

    private fun applyBydLog(disabled: Boolean) {
        if (bydBusy) return
        bydBusy = true
        b.switchBydLog.isEnabled = false
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { com.byd.ls.shell.BydLogSwitch.setDisabled(applicationContext, disabled, 2) }
                    .getOrDefault(false)
            }
            b.switchBydLog.isEnabled = true
            if (ok) {
                GesturePrefs.bydLogDisabled = disabled
                toast(if (disabled) "BYD 诊断日志已禁用，重启后保持" else "BYD 诊断日志已恢复")
            } else {
                suppress = true
                b.switchBydLog.isChecked = !disabled
                suppress = false
                toast("调试通道未连接，无法修改")
            }
            bydBusy = false
            refreshBydLogState()
        }
    }

    // =====================================================================
    //  其它操作（右列）
    // =====================================================================
    private fun bindActions() {
        b.btnRefresh.setOnClickListener { refreshStatus() }
    }

    // =====================================================================
    //  仪表卡片（合并自仪表助理，同一进程）
    // =====================================================================
    private fun bindCluster() {
        b.btnCardStart.setOnClickListener {
            GesturePrefs.clusterEnabled = true
            GesturePrefs.writeCardFlag(this)
            b.tvCardStatus.text = "卡片：正在拉起…"
            lifecycleScope.launch {
                val msg = withContext(Dispatchers.IO) {
                    // 走 restart 而不是 start：单任务 Activity 在但窗口被回收时，start 是空操作
                    runCatching { Card.restart() }.getOrElse { "失败：" + it.message }
                }
                toast(msg)
                refreshCardStatus()
            }
        }

        b.btnCardStop.setOnClickListener {
            GesturePrefs.clusterEnabled = false
            GesturePrefs.writeCardFlag(this)
            val msg = runCatching { Card.stop() }.getOrElse { "失败：" + it.message }
            toast(msg)
            b.tvCardStatus.text = "卡片：已停止"
        }

        b.btnCardMedia.setOnClickListener {
            b.tvCardStatus.text = "媒体：诊断中…"
            lifecycleScope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching { Card.mediaDiagnose() }.getOrElse { "诊断失败：" + it.message }
                }
                b.tvCardStatus.text = text
            }
        }

        bindLyricTune()
        refreshCardStatus()
    }

    // ---------------------------------------------------------------------
    //  歌词时间轴校准
    // ---------------------------------------------------------------------
    private fun bindLyricTune() {
        b.btnLyricMinus500.setOnClickListener { nudgeLyric(-500L) }
        b.btnLyricMinus100.setOnClickListener { nudgeLyric(-100L) }
        b.btnLyricPlus100.setOnClickListener { nudgeLyric(100L) }
        b.btnLyricPlus500.setOnClickListener { nudgeLyric(500L) }
        b.btnLyricReset.setOnClickListener {
            val t = MediaListener.title
            if (t.isEmpty()) { toast("当前没有在播放的曲目"); return@setOnClickListener }
            runCatching { LyricTune.reset(t, MediaListener.artist) }
            toast("已清除「" + t + "」的偏移")
            refreshLyricOffset()
        }
        refreshLyricOffset()
    }

    /**
     * 按当前曲目加减歌词偏移。
     *
     * 写得进 /sdcard 才有意义，所以先看有没有权限；写失败也要给出可见提示，
     * 否则用户按十下没反应会以为按钮坏了。
     */
    private fun nudgeLyric(deltaMs: Long) {
        val t = MediaListener.title
        if (t.isEmpty()) { toast("当前没有在播放的曲目"); return }
        val cur = runCatching { LyricTune.offsetFor(t, MediaListener.artist) }.getOrDefault(0L)
        val next = (cur + deltaMs).coerceIn(LyricTune.MIN_MS, LyricTune.MAX_MS)
        if (next == cur) { toast("已到调节上限（±2 秒）"); return }
        val ok = runCatching { LyricTune.save(t, MediaListener.artist, next); true }.getOrDefault(false)
        toast(
            if (ok) "「" + t + "」歌词偏移 " + fmtOffset(next)
            else "写入失败，检查存储权限"
        )
        refreshLyricOffset()
    }

    private fun refreshLyricOffset() {
        val t = MediaListener.title
        if (t.isEmpty()) {
            b.tvLyricOffset.text = "歌词校准：未在播放"
            return
        }
        val v = runCatching { LyricTune.offsetFor(t, MediaListener.artist) }.getOrDefault(0L)
        val own = runCatching { LyricTune.hasOwn(t, MediaListener.artist) }.getOrDefault(false)
        b.tvLyricOffset.text = "歌词校准：" + fmtOffset(v) +
            (if (own) "（本曲目已校准）" else "（全局默认）") + " · " + t
    }

    private fun fmtOffset(ms: Long): String = (if (ms > 0) "+" else "") + ms + " ms"

    /** 卡片运行状态（要查 shell，必须离开主线程） */
    private fun refreshCardStatus() {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                val alive = runCatching { Card.alive() }.getOrDefault(false)
                val media = runCatching { Card.mediaConnected() }.getOrNull()
                val m = when (media) {
                    true -> "已连接"
                    false -> "未连接"
                    else -> "心跳不可用"
                }
                "卡片：" + (if (alive) "运行中" else "未运行") + " · 媒体：" + m
            }
            b.tvCardStatus.text = text
            refreshLyricOffset()
        }
    }

    // =====================================================================
    //  远程调试（首页开关卡片 + 长按副标题改地址）
    // =====================================================================
    private fun bindRemoteDebug() {
        syncRemoteSwitch()
        b.switchRemote.setOnCheckedChangeListener { _, checked ->
            if (suppress) return@setOnCheckedChangeListener
            RemoteConfig.setEnabled(applicationContext, checked)
            if (checked) {
                RemoteAgent.start(applicationContext)
                toast("远程调试已开启")
            } else {
                RemoteAgent.stop(applicationContext)
                toast("远程调试已关闭")
            }
            refreshStatus()
        }
        b.tvSubtitle.setOnLongClickListener {
            if (com.byd.ls.BuildConfig.PUBLIC_BUILD) return@setOnLongClickListener false
            showRemoteDialog()
            true
        }
        b.tvSubtitle.setOnClickListener {
            if (com.byd.ls.BuildConfig.PUBLIC_BUILD) return@setOnClickListener
            if (RemoteAgent.stateText.startsWith("未配置")) showRemoteDialog()
        }
    }

    private fun syncRemoteSwitch() {
        suppress = true
        b.switchRemote.isChecked = RemoteConfig.enabled(this)
        suppress = false
    }

    private fun showRemoteDialog() {
        if (com.byd.ls.BuildConfig.PUBLIC_BUILD) return
        val ctx = applicationContext
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
        }
        val status = TextView(this).apply {
            text = "状态：${RemoteAgent.stateText}\n设备 ID：${RemoteConfig.deviceId(ctx)}"
            setTextColor(uiColor(R.color.text_tertiary))
            textSize = 12f
        }
        val edit = android.widget.EditText(this).apply {
            setText(RemoteConfig.url(ctx))
            setHint("wss://你的域名/agent")
            textSize = 13f
            setSingleLine(false)
        }
        val editSecret = android.widget.EditText(this).apply {
            setText(RemoteConfig.bootstrap(ctx))
            setHint("注册口令（一般不用改）")
            textSize = 13f
            setSingleLine(false)
        }
        box.addView(status)
        box.addView(edit)
        box.addView(editSecret)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("远程调试")
            .setView(box)
            .setPositiveButton("保存并重连") { _, _ ->
                val url = edit.text.toString().trim()
                val secret = editSecret.text.toString().trim()
                if (!url.startsWith("wss://")) {
                    toast("地址必须以 wss:// 开头")
                    return@setPositiveButton
                }
                RemoteConfig.setUrl(applicationContext, url)
                if (secret.isNotEmpty()) RemoteConfig.setBootstrap(applicationContext, secret)
                RemoteConfig.setEnabled(applicationContext, true)
                syncRemoteSwitch()
                RemoteAgent.stop(applicationContext)
                RemoteAgent.start(applicationContext)
                toast("已保存，2 秒后开始连接")
            }
            .setNegativeButton("取消", null)
            .setNeutralButton(if (RemoteAgent.stateText == "已连接") "断开" else "连接") { _, _ ->
                if (RemoteAgent.stateText == "已连接") {
                    RemoteConfig.setEnabled(applicationContext, false)
                    syncRemoteSwitch()
                    RemoteAgent.stop(applicationContext)
                } else {
                    RemoteConfig.setEnabled(applicationContext, true)
                    syncRemoteSwitch()
                    RemoteAgent.start(applicationContext)
                }
            }
            .show()
    }

    // =====================================================================
    //  状态区
    // =====================================================================
    private fun refreshStatus() {
        b.tvGrantHint.text = ShellManager.hint()
        b.btnActivate.isEnabled = !oneTapRunning || waitingAllow

        // 真值优先：窗口数/绑定/通道来自系统侧采样，进程内字符串只作补充
        val truth = com.byd.ls.service.HealthMonitor.summary()
        b.tvGestureStatus.text = if (truth == "未采样") {
            "手势服务：" + GlobalGestureService.statusText()
        } else {
            "手势真值：" + truth + "\n进程内：" + GlobalGestureService.statusText()
        }
        b.tvLastCmd.text = "动作链路：" + GlobalGestureService.lastBackendText() +
            "\n最近执行命令：" + GlobalGestureService.lastCommandText()
        updateThresholdText()
        renderLocalRows()

        refreshCardStatus()

        // 需要跑 shell 的，异步查
        lifecycleScope.launch {
            val snap = withContext(Dispatchers.IO) {
                runCatching { AdbManager.statusSnapshot(applicationContext) }
                    .getOrElse { LinkedHashMap<String, String>().apply { put("状态查询", "失败：${it.message}") } }
            }
            remoteRows.clear()
            remoteRows.putAll(snap)
            renderRows()
        }
    }

    private val localRows = LinkedHashMap<String, String>()
    private val remoteRows = LinkedHashMap<String, String>()

    /** 先渲染不依赖 shell 的行，避免界面空白 */
    private fun renderLocalRows() {
        localRows.clear()
        localRows["手势总开关"] = if (GesturePrefs.enabled) "已开启" else "已关闭"
        localRows["边缘宽度"] = "左 ${GesturePrefs.edgeLeftDp} · 右 ${GesturePrefs.edgeRightDp} · 下 ${GesturePrefs.edgeBottomDp} dp"
        localRows["灵敏度"] = GesturePrefs.sensitivity.toString()
        localRows["悬浮窗权限"] = if (Settings.canDrawOverlays(this)) "已允许" else "未允许"
        localRows["native 守护进程"] = DaemonGuard.statusText(this)
        localRows["无障碍绑定"] =
            when {
                LsAccessibilityService.isBinderConnected() -> "已绑定"
                // 1.4.6：该车机已判定不绑第三方无障碍（docs/08），别再显示红色"未绑定"误导用户
                A11yHealer.isUnsupported(this) -> "不支持（该车机不绑第三方无障碍）"
                else -> "未绑定"
            }
        localRows["WRITE_SECURE_SETTINGS"] =
            if (A11yHealer.canWriteSecureSettings(this)) "已授予" else "未授予（降级 shell）"
        localRows["开机自启任务"] = if (BootJobService.isScheduled(this)) "已排程" else "未排程"
        localRows["日志"] = if (GesturePrefs.logEnabled) "开启" else "关闭"
        localRows["远程调试"] = RemoteAgent.stateText + "（长按标题可改地址）"

        renderRows()
    }

    private fun renderRows() {
        b.statusContainer.removeAllViews()
        val density = resources.displayMetrics.density
        val map = LinkedHashMap<String, String>().apply { putAll(localRows); putAll(remoteRows) }
        for ((k, v) in map) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
            }
            val label = TextView(this).apply {
                text = k
                textSize = 17f
                setTextColor(uiColor(R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val value = TextView(this).apply {
                text = v
                textSize = 17f
                setTextColor(colorOfValue(v))
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.END
                maxLines = 3
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.35f)
            }
            row.addView(label)
            row.addView(value)
            b.statusContainer.addView(row)
        }
    }

    private fun colorOfValue(v: String): Int {
        val good = listOf("已允许", "已启用", "已授权", "在线", "是", "运行中", "已连接", "已建立", "已开启", "已排程")
        val bad = listOf("未运行", "未允许", "未启用", "未授权", "否", "未连接", "未建立", "已关闭", "失败", "未排程")
        return when {
            good.any { v.contains(it) } -> uiColor(R.color.state_ok)
            bad.any { v.contains(it) } -> uiColor(R.color.state_error)
            else -> uiColor(R.color.text_primary)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private companion object {
        const val TAG = "MainActivity"
    }
}
