package com.byd.ls.service

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.byd.ls.config.GesturePrefs
import com.byd.ls.shell.ShellManager
import com.byd.ls.util.LsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * 无障碍名单的纯字符串处理。
 *
 * 单独抽出来是为了两件事：
 *  1. 保证 read-modify-write 永远保留车机上已有的其它无障碍服务（KD、迪友等）；
 *  2. 能在 JVM 单测里覆盖，不依赖 Android 运行时。
 */
object A11yList {

    const val PKG = "com.byd.ls"
    const val SERVICE_CLASS = "com.byd.ls.service.LsAccessibilityService"
    const val COMPONENT = "$PKG/$SERVICE_CLASS"

    /**
     * 统一成 packageName/className 全名，用于比较；非法条目返回 null。
     *
     * 为什么必须按组件比而不是按字符串比：AMS 保存 enabled_accessibility_services 时用
     * ComponentName.flattenToString()，类名以包名开头时会缩成短名
     * （com.byd.ls/com.byd.ls.service.X → com.byd.ls/.service.X）。
     * 纯字符串 contains 遇到短名就判 false → 灵手以为名单里没有自己 → 再追加全名 → 同一组件两份。
     */
    private fun keyOf(entry: String?): String? {
        val e = entry?.trim()
        if (e.isNullOrEmpty() || e == "null") return null
        val slash = e.indexOf('/')
        if (slash <= 0 || slash == e.length - 1) return null
        val pkg = e.substring(0, slash)
        val raw = e.substring(slash + 1)
        // 等价于 ComponentName.flattenToString 的展开规则：".Cls" → pkg.Cls；裸类名 → pkg.裸类名。
        // 这里不用 android.content.ComponentName：它在本工程的纯 JVM 单测里是 stub（返回 null），
        // 用了会让 A11yListTest / A11yMergeOrderTest 全部空结果。手写解析同时保留了可测性。
        val cls = when {
            raw.startsWith(".") -> pkg + raw
            !raw.contains('.') -> pkg + "." + raw
            else -> raw
        }
        return pkg + "/" + cls
    }

    /**
     * 按原顺序解析；用组件 key 去重（同一组件的短名/全名只保留第一次出现的那个字符串），
     * 保留原始写法不做改写，避免制造无谓的名单变化。
     *
     * 【安全铁律】解析不出来的条目**必须原样保留**，绝不能 `?: continue` 丢掉：
     * 现场就这么把 com.byd.clusterlab/.CarAccess 从名单里抹掉过（仪表 ClusterLab 直接失效）。
     * 名单是全车机共用的，灵手只允许增删自己那一条，其余字节一律不动。
     * 所以这里对无法归一的条目用「原文」当去重 key，保证它被写回去。
     */
    fun parse(raw: String?): MutableList<String> {
        val out = ArrayList<String>(4)
        if (raw.isNullOrBlank()) return out
        val seen = HashSet<String>(4)
        for (part in raw.split(':')) {
            val v = part.trim()
            if (v.isEmpty() || v == "null") continue
            // 归一成功就按组件去重；失败则用原文做 key（宁可重复也不能丢）
            val k = keyOf(v) ?: ("raw:" + v)
            if (!seen.add(k)) continue
            out.add(v)
        }
        return out
    }

    /** 把自己追加到末尾；已在名单中则原样返回（顺序不变） */
    fun merge(raw: String?, self: String = COMPONENT): String {
        val list = parse(raw)
        val selfKey = keyOf(self) ?: return list.joinToString(":")
        if (list.none { keyOf(it) == selfKey }) list.add(self)
        return list.joinToString(":")
    }

    fun without(raw: String?, self: String = COMPONENT): String {
        val selfKey = keyOf(self) ?: return parse(raw).joinToString(":")
        // 只过滤掉 key 明确等于自己的那条；解析不出来的条目（keyOf == null）一律保留
        return parse(raw).filter { entry ->
            val k = keyOf(entry)
            k == null || k != selfKey
        }.joinToString(":")
    }

    fun contains(raw: String?, self: String = COMPONENT): Boolean {
        val selfKey = keyOf(self) ?: return false
        return parse(raw).any { keyOf(it) == selfKey }
    }
}

/**
 * 无障碍自愈器。
 *
 * 背景：settings put secure enabled_accessibility_services 写入成功 ≠ 服务被 bind。
 * DiLink 4.0 实测有两种脱绑：一种是被 ROM 回写抹掉（补写就够），另一种更麻烦——
 * 名单里还有本服务、enabled=1，但 AMS 既没把它放进 Bound 也没放进 Binding。
 * 后者写同值永远修不好：SettingsProvider 对同值照样 notifyChange，AMS 拿到通知后
 * 发现名单没变就跳过整段重新绑定，绑定状态一次都不会重新评估。
 *
 * 策略：
 *  · 先读当前名单，只增删自己，绝不动别人的项；
 *  · 任何写入前先跟当前值比，值没变就不写（写同值既无效，又会把另一个进程的
 *    observer 打醒，主进程与 :guard 互相触发就是现场每秒刷屏的自激循环）；
 *  · 真脱绑时做一次定向重绑：移除自己→写入→等 1s→精确加回→写入，让 AMS 看到
 *    两次真实的值变化才会重新 bind；最多 2 轮，仍不行退到 5 分钟冷却的硬重置；
 *  · 重绑用 filesDir 下的同一个锁文件做跨进程互斥（主进程和 :guard 都可能触发）；
 *  · App 若已被 pm grant WRITE_SECURE_SETTINGS 授权，直接写 Settings，否则降级
 *    shell(UID 2000) 通道（内置 adblib）；
 *  · 触发点：开机、MainActivity.onResume、:guard 每 60s、授权/激活完成、安装覆盖，
 *    以及 ContentObserver 监听系统设置被外部改写（0.8s 内补回）。
 */
object A11yHealer {

    private const val TAG = "A11yHealer"

    /** 常规触发的最小间隔，避免 onResume 与守护同时打 settings */
    private const val DEBOUNCE_MS = 12_000L
    private const val OBSERVER_THROTTLE_MS = 3_000L

    /** 移除与加回之间的等待：车机实测 1s 以上 AMS 才稳定重新 bind */
    private const val REBIND_GAP_MS = 1_000L
    /** 每一轮重绑后等 bind 的时间 */
    private const val REBIND_WAIT_MS = 1_500L
    /** 定向重绑最多重试轮数 */
    private const val REBIND_ROUNDS = 2
    /** 跨进程重绑冷却：另一进程看到这个时间戳就不会抢着重绑 */
    private const val REBIND_COOLDOWN_MS = 10_000L
    /** 硬重置 0→1 的冷却 */
    private const val HARD_RESET_COOLDOWN_MS = 5 * 60_000L
    /** 硬重置进行中的判据窗口：这段时间内谁都不许把 enabled 抢着置回 1 */
    private const val HARD_RESET_WINDOW_MS = 15_000L

    /**
     * 深度恢复冷却。
     *
     * 原来 20 分钟太长：上电后 a11y 悬空时只能成功一次，之后再悬空就整场恢复不了，
     * 只能等用户手动开 App。现在 force-stop 已降级为第二档（见 deepRecover），
     * 常用路径是无损重绑，风险低，冷却压到 30 秒。
     */
    private const val DEEP_COOLDOWN_MS = 30_000L

    /** 进程刚起来这段时间内禁止中断式恢复；冷启动 a11y 约 14s 自然绑上，60s 仍悬空说明真卡死，允许升级 */
    private const val FORCE_KILL_GUARD_MS = 60_000L
    private const val KEY_DEEP_TS = "deep_recover_ts"
    private const val DEEP_PREFS = "a11y_deep"

    /**
     * 【1.4.6】「已知该车机不支持（第三方）无障碍」标记。
     *
     * 现场实测（docs/08）：本车 AMS 从未真正 bind 我们 —— `Binding services` 段有我们、
     * `Bound services` 段没有、AMS 里连该服务的 ServiceRecord 都没有；另一个第三方
     * GKD 同样悬挂；强启 ×4 / 移除加回 / 置名单首位 / 重启进程 **全部无效**。
     *
     * 在悬挂态下每 5 分钟 force-stop 一次只会杀进程、断特权通道
     * （实测连续两条 shell.exec 就 exit=-1），是纯自伤。
     *
     * 落盘后：常规自愈直接跳过，不再写 settings、不再强启；
     * 只有用户手动点「一键启动 / 修复」才清标记并完整重试一次。
     */
    /**
     * 是否允许强制重启（force-stop + RESTART）。
     *
     * 1.4.6 起默认 **false**：实测在悬挂态下强启 4 次全部失败，只会杀进程、断特权通道。
     * 换新车型排查时可临时置 true 复现旧行为。
     */
    private const val EXP_ALLOW_FORCE_RESTART = false

    private const val UNSUPPORTED_FILE = "a11y_unsupported.flag"

    /**
     * 半绑自愈：用**本地 APK 副本**重装自己（零流量）。
     *
     * 依据 docs/08 §10：本车 AMS 在服务进程被杀后不会清理 `mBindingServices`，
     * 于是「Bound 段有我们、进程内 binder 不连、rebind/settings 重写/force-stop 全无效」。
     * 实测唯一有效且不耗流量的是：把 /data/app 里现成的 base.apk 复制到 /data/local/tmp，
     * 再 `pm install -r -d` 重装同版本——PackageManager 的包变更会让 AMS 重建绑定。
     */
    /**
     * 1.4.87（对齐迪加）：名单里**只追加自己**，绝不删除。
     * 旧实现「移除 2 秒再加回」会把 AMS 刚建立的绑定打断，是半绑的直接原因。
     */
    /** 1.4.88：恢复「移除→加回」——实验（17:47:56）证明这是本车**唯一有效**的重绑手段。
     *  约束：开机 ≥60s、冷却 3 分钟、只在 dumpsysBound=true 且实例为空时调用（1.4.91 放宽）。
     */
    @Volatile private var lastRemoveAddAt = 0L
    suspend fun rebindRemoveAdd(ctx: Context): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        // 1.4.91：开机下限 120s→60s（半绑后 3 分钟内必须有一次重绑机会）
        if (now < 60_000L) return false
        // 1.4.91：冷却 5min→3min（同上；1.4.90 实测半绑后 3 分钟内 0 次重绑）
        if (now - lastRemoveAddAt < 180_000L) return false
        lastRemoveAddAt = now
        if (!ShellManager.isPrivileged()) return false
        val full = readEnabled(ctx)
        if (!A11yList.contains(full)) return false
        val without = A11yList.without(full)
        // 1.4.119：脚本换成 A11ySettingsScript 的四步版（去 → 回 → 补 accessibility_enabled=1 → 读回），
        // 不再「执行成功就返回 true」——本车多次出现 settings 写成功但服务没被 bind。
        val cmd = A11ySettingsScript.command(full, without) + "; " + RESTART_BC
        LsLog.e(TAG, "A11Y_REBIND remove-add 开始 raw=" + full)
        val out = runCatching { ShellManager.exec(ctx, cmd, 20_000) }.getOrNull()
        val verified = out != null && A11ySettingsScript.verify(out.stdout, A11yList.COMPONENT)
        LsLog.e(TAG, "A11Y_REBIND remove-add 读回校验 verified=" + verified
            + (if (verified) "" else " out=" + (out?.stdout ?: "null").take(160)))
        return verified
    }

    suspend fun rebindAddOnlyDeprecated(ctx: Context): Boolean {
        if (!ShellManager.isPrivileged()) return false
        val full = readEnabled(ctx)
        if (A11yList.contains(full)) {
            runCatching { ShellManager.exec(ctx, RESTART_BC, 8_000) }
            return true
        }
        val merged = A11yList.merge(full)
        val cmd = "settings put secure enabled_accessibility_services '" + merged + "'; sleep 1; " + RESTART_BC
        runCatching { ShellManager.exec(ctx, cmd, 12_000) }
        return true
    }

    suspend fun selfReinstallIfHalfBound(ctx: Context): String {
        if (!isMainProcess()) return "跳过（非主进程）"
        if (!ShellManager.isPrivileged()) return "跳过（无 shell 通道）"
        if (isBound()) return "已绑定"
        val bound = runCatching { dumpsysBound(ctx) }.getOrNull()
        if (bound != true) return "跳过（AMS 侧没有 Bound 记录，不属半绑）"
        val f = java.io.File(ctx.filesDir, "a11y_selfreinstall.ts")
        val now = System.currentTimeMillis()
        // 10 分钟冷却：重装会杀进程，不能反复来
        if (f.exists() && now - f.lastModified() < 600_000L) return "跳过（10 分钟内已重装过）"
        runCatching { f.writeText(now.toString()) }

        val base = runCatching {
            ctx.packageManager.getApplicationInfo(ctx.packageName, 0).sourceDir
        }.getOrNull() ?: return "跳过（拿不到 base.apk 路径）"
        val tmp = "/data/local/tmp/ls-self.apk"
        val cp = ShellManager.exec(ctx, "cp $base $tmp", 20_000L)
        if (!cp.success) return "跳过（复制 base.apk 失败：" + cp.stdout.take(60) + "）"

        // 与远程更新同一条经验：日志写 /data/local/tmp；装完 sleep 8 再广播拉起
        val script = "pm install -r -d $tmp > /data/local/tmp/ls-self-install.log 2>&1; " +
            "sleep 8; am broadcast -a com.byd.ls.action.RESTART -p com.byd.ls -f 0x20"
        ShellManager.exec(ctx, script, 90_000L)
        LsLog.w(TAG, "半绑自愈：已下发本地自重装（$base → $tmp）")
        RemoteAuditLog(ctx, "半绑自愈：本地自重装已下发")
        return "已下发本地自重装（零流量）"
    }

    fun unsupportedFile(ctx: Context) = java.io.File(ctx.filesDir, UNSUPPORTED_FILE)

    fun isUnsupported(ctx: Context): Boolean =
        runCatching { unsupportedFile(ctx).exists() }.getOrDefault(false)

    fun markUnsupported(ctx: Context, why: String) {
        runCatching {
            unsupportedFile(ctx).writeText(why + " @" + System.currentTimeMillis())
            LsLog.w(TAG, "标记「无障碍不可用」：$why（此后不再自动强启/重绑）")
        }
    }

    fun clearUnsupported(ctx: Context) {
        runCatching { unsupportedFile(ctx).delete() }
    }

    /** 用户主动触发的修复（repairNow / switch-on）不受「已知不支持」闸门限制 */
    private fun isUserInitiated(reason: String): Boolean =
        reason.startsWith("repairNow") || reason.startsWith("switch-on")

    /**
     * 不弹 UI 的拉起口令。
     *
     * `am start -n com.byd.ls/.ui.MainActivity` 会把主界面直接推到前台（车机实测
     * ActivityRecord launchedFromPackage=com.android.shell，用户在这种时候只想安静
     * 恢复服务）。改发显式广播：RestartReceiver 只 startForegroundService，不碰 Activity。
     * -f 0x20 = FLAG_INCLUDE_STOPPED_PACKAGES，包被 force-stop 置 stopped 后仍能收到。
     */
    private const val RESTART_BC = "am broadcast -a com.byd.ls.action.RESTART -p com.byd.ls -f 0x20"

    // 1.4.93: 休眠唤醒(STR resume)识别与「逼 AMS 重评」参数
    private const val WAKE_PREFS = "a11y_wake"
    private const val KEY_WAKE_DELTA = "wake_delta"
    private const val KEY_FLIP_TS = "flip_ts"
    private const val FLIP_COOLDOWN_MS = 90_000L
    private const val KEY_STALE_CLEAN_TS = "stale_clean_ts"
    // 1.5.0-beta5：看门狗硬恢复请求限流。beta4 现场 22:48:36、22:49:23 连续两次
    // force-stop，间隔仅 47 秒；App 侧先按 10 分钟冷却挡住重复请求。
    private const val KEY_WD_REQ_TS = "wd_req_ts"
    private const val WD_REQ_COOLDOWN_MS = 10 * 60_000L
    // 1.4.97: 5 分钟 -> 2 分钟。实车 23:05 那次 RECOVER 就是被 5 分钟冷却挡下的
    // (日志: A11Y_STALE_CLEAN 冷却中, 跳过)；第一档已改成只停服务不杀进程, 代价低, 冷却可以短。
    private const val STALE_COOLDOWN_MS = 2 * 60_000L


    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    /**
     * 本对象自己起的后台任务句柄。
     *
     * 为什么必须留句柄：这些协程带 30s / 1.5s 延迟跑，宿主进程还活着时它们会一直挂在
     * Dispatchers.IO 上。单测里换一个测试方法就等于换了一份 Context 与 settings 存储，
     * 上一个方法残留的协程照样醒来改名单（现场表现就是同一份用例隔次红、红的还不是同一条）。
     * 留句柄后 [cancelPending] 能把它们一次性掐掉，测试与"重启前收摊"共用同一个入口。
     */
    private val jobs = java.util.concurrent.CopyOnWriteArrayList<Job>()

    /**
     * 本轮进程里"上一次真实重绑"的时刻。
     *
     * 为什么单靠文件冷却不够：阶梯内部会用 force=true 再做一次（硬重置/深恢复），
     * 那会绕过文件冷却——两个 force 挨在一起，就是现场"主进程与 :guard 来回 remove/add"。
     * 这里加一道进程内 3 秒硬闸，force 也过不去。
     */
    @Volatile private var lastRealRebindAt = 0L

    private fun track(job: Job): Job {
        jobs.add(job)
        job.invokeOnCompletion { jobs.remove(job) }
        return job
    }

    /** 取消本对象已排队的后台自愈（测试隔离 / 进程收摊用）。 */
    fun cancelPending() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    @Volatile private var lastRunAt = 0L
    @Volatile private var lastObserverHealAt = 0L
    @Volatile private var lastRawSeen: String? = null
    @Volatile private var lastUserRepairAt = 0L

    @Volatile private var observer: ContentObserver? = null

    /** 最近一次自愈结果，界面直接展示，现场不用翻 logcat */
    @Volatile var lastResult: String = "尚未执行"
        private set

    /** 写入结果：changed=false 表示值已经是目标值，一条都没写 */
    private data class WriteOutcome(val ok: Boolean, val changed: Boolean)

    /** 重绑结果：detail 直接拼进对外返回串，现场一眼能看出走到哪一步 */
    private data class RebindOutcome(val ok: Boolean, val detail: String)

    /**
     * 异步触发（带防抖）。[delayMs] 用于开机后等系统服务就绪。
     */
    /**
     * 1.4.93: 识别「休眠唤醒(STR resume)」。
     *
     * 判据(不需要任何权限): elapsedRealtime() 在休眠期间照走, uptimeMillis() 不走,
     * 两者差值一次跳变 > 15s 就说明刚从 suspend 醒来(冷启动不会跳, 两个计数器同时归零)。
     */
    fun noteWakeSignals(ctx: Context): Boolean {
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val nowUp = android.os.SystemClock.uptimeMillis()
        val delta = nowElapsed - nowUp
        val sp = ctx.applicationContext.getSharedPreferences(WAKE_PREFS, Context.MODE_PRIVATE)
        val last = sp.getLong(KEY_WAKE_DELTA, -1L)
        sp.edit().putLong(KEY_WAKE_DELTA, delta).apply()
        if (last < 0L) return false
        val grow = delta - last
        if (grow > 15_000L) {
            LsLog.w(TAG, "A11Y_RESUME 检测到休眠唤醒(睡了 " + (grow / 1000) + "s)")
            return true
        }
        return false
    }

    /**
     * 1.4.93: 唤醒后「逼 AMS 重建 ServiceRecord」。
     *
     * 背景(交接包 docs/13 10/12 节): 本车休眠唤醒后是 dumpsysBound=true / inProcess=false / hanging=true,
     * disableSelf() 与「名单移除-加回」都无效。AMS 只在 settings 的值真实变化时才重新评估,
     * 所以这里把 accessibility_enabled 做一次 1-0-1 翻转, 并把名单同时去/回一遍。
     *
     * 约束: 唤醒后只赌一次(冷却 90s); 整段丢给 nohup+setsid, 避免被自己的写操作打断。
     */
    fun forceReevaluateAsync(ctx: Context, reason: String) {
        CoroutineScope(Dispatchers.IO).launch { forceReevaluateNow(ctx.applicationContext, reason) }
    }

    private suspend fun forceReevaluateNow(app: Context, reason: String) {
        if (!isMainProcess()) return
        val sp = app.getSharedPreferences(WAKE_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = sp.getLong(KEY_FLIP_TS, 0L)
        if (now - last < FLIP_COOLDOWN_MS) {
            LsLog.i(TAG, "A11Y_FORCE_FLIP 冷却中(<" + (FLIP_COOLDOWN_MS / 1000) + "s), 跳过")
            return
        }
        if (!ShellManager.isPrivileged()) {
            LsLog.w(TAG, "A11Y_FORCE_FLIP 跳过(无 shell 通道)")
            return
        }
        val full = readEnabled(app)
        if (!A11yList.contains(full)) {
            LsLog.w(TAG, "A11Y_FORCE_FLIP 跳过(名单缺本服务)")
            return
        }
        val without = A11yList.without(full)
        sp.edit().putLong(KEY_FLIP_TS, now).apply()
        val q = String(charArrayOf(34.toChar()))
        val script = "settings put secure accessibility_enabled 0; sleep 1; " +
            "settings put secure enabled_accessibility_services " + q + without + q + "; sleep 1; " +
            "settings put secure enabled_accessibility_services " + q + full + q + "; sleep 1; " +
            "settings put secure accessibility_enabled 1; sleep 1; " + RESTART_BC
        val cmd = "nohup setsid sh -c " + String(charArrayOf(39.toChar())) + script + String(charArrayOf(39.toChar())) + " >/dev/null 2>&1 &"
        LsLog.w(TAG, "A11Y_FORCE_FLIP[" + reason + "]: accessibility_enabled 1-0-1 + 名单去/回(逼 AMS 重评)")
        runCatching { ShellManager.exec(app, cmd, 8_000) }
    }

    /**
     * 1.4.94: 查 AMS 里我们 a11y 服务的连接记录是否「死连接/重复连接」。
     *
     * 依据(2026-11-10 22:3x 实车): 休眠唤醒后 dumpsys activity services 里出现
     *   ConnectionRecord{... DEAD com.byd.ls/.service.LsAccessibilityService}
     * 外加两条重复连接(client 全是 system), 于是 dumpsys accessibility 把我们永远挂在
     * Binding services 里: 条目在、开关开、进程活, 但 onServiceConnected 永远不来。
     */
    private suspend fun hasStaleA11yRecords(app: Context): Pair<Boolean, String> {
        val r = runCatching { ShellManager.exec(app, "dumpsys activity services com.byd.ls", 8_000L) }.getOrNull()
            ?: return false to "no-shell"
        val lines = r.stdout.lineSequence()
            .filter { it.contains("ConnectionRecord") && it.contains("LsAccessibilityService") }
            .toList()
        val dead = lines.count { it.contains("DEAD") }
        return (dead > 0 || lines.size > 1) to ("dead=" + dead + " conns=" + lines.size)
    }

    /**
     * 1.4.94: 清掉死连接 —— force-stop 自身(会清 ServiceRecord) -> 8s -> RESTART 广播拉起。
     *
     * 这是 2026-11-10 22:36 实车验证过的唯一有效手段(清理后 12 秒内 verdict 回 ok)。
     * 与 1.4.92「不再无条件 force-stop」不冲突: 只有「悬挂 + 真检测到 DEAD/重复连接」才执行,
     * 且每 5 分钟最多一次。
     */
    private suspend fun cleanStaleNow(app: Context, reason: String) {
        if (!isMainProcess()) return
        if (LsAccessibilityService.isBinderConnected()) return
        val sp = app.getSharedPreferences(WAKE_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - sp.getLong(KEY_STALE_CLEAN_TS, 0L) < STALE_COOLDOWN_MS) {
            LsLog.i(TAG, "A11Y_STALE_CLEAN 冷却中, 跳过")
            return
        }
        if (!ShellManager.isPrivileged()) {
            LsLog.w(TAG, "A11Y_STALE_CLEAN 跳过(无 shell 通道)")
            return
        }
        val (stale, detail) = hasStaleA11yRecords(app)
        if (!stale) {
            LsLog.i(TAG, "A11Y_STALE 无死连接(" + detail + "), 不清理")
            return
        }
        sp.edit().putLong(KEY_STALE_CLEAN_TS, now).apply()

        // 第一档(1.4.96)：只停服务实例, 不杀进程 —— 不碰 adblib 通道, 不制造新死连接。
        LsLog.w(TAG, "A11Y_STALE_CLEAN[" + reason + "] 死/重复连接(" + detail + ") -> 先 am stopservice 停服务")
        runCatching { ShellManager.exec(app, "am stopservice -n com.byd.ls/com.byd.ls.service.LsAccessibilityService", 6_000L) }
        runCatching { ShellManager.exec(app, RESTART_BC, 6_000L) }
        delay(6_000L)
        if (LsAccessibilityService.isBinderConnected()) {
            LsLog.i(TAG, "A11Y_STALE_CLEAN 停服务后已重新绑定")
            return
        }

        // 1.4.101：**把硬恢复交给 shell 看门狗**（UID 2000，独立进程）执行。
        // 原因（2026-11-11 00:4x 实车）：App 自己用 adblib 跑「force-stop 自己」时，
        // 杀进程会掐掉 adblib socket，adbd 对整个会话发 SIGHUP → 后半段 RESTART 广播根本没跑，
        // 于是"每次都只完成了自杀"（1.4.94–1.4.99 的自动恢复全栽在这）。
        // 手动两段式（force-stop → 8s → 广播）实测 10 秒内回 ok；这里只负责写请求文件。
        LsLog.w(TAG, "A11Y_WD_REQ 停服务仍未绑定，请求 shell 看门狗执行硬恢复")
        if (!requestWatchdogHardRecover(app, now, reason)) {
            LsLog.w(TAG, "A11Y_WD_REQ 写入失败或被限流")
        }
    }

    @Volatile private var wakeTriedThisRun = false

    /**
     * 1.5.0-beta5：写看门狗硬恢复请求前先做 10 分钟冷却。
     *
     * beta4 现场 22:48:36 / 22:49:23 连续两次请求，看门狗连续 force-stop；
     * 这里只限流，不改变看门狗侧“先温和重绑、失败才强杀”的顺序。
     * 用户手动点修复（reason=repairNow*）不受自动冷却限制。
     */
    private suspend fun requestWatchdogHardRecover(app: Context, now: Long, reason: String): Boolean {
        if (!reason.startsWith("repairNow")) {
            val sp = app.getSharedPreferences(WAKE_PREFS, Context.MODE_PRIVATE)
            val last = sp.getLong(KEY_WD_REQ_TS, 0L)
            if (now - last < WD_REQ_COOLDOWN_MS) {
                LsLog.i(TAG, "A11Y_WD_REQ[$reason] 冷却中(<10min)，跳过")
                return false
            }
            sp.edit().putLong(KEY_WD_REQ_TS, now).apply()
        }
        return runCatching {
            ShellManager.exec(app, "echo $now > /data/local/tmp/ls-heal.req", 5_000L)
        }.isSuccess
    }

    /**
     * 1.4.102：唤醒恢复改为「只写请求文件」，由 shell 看门狗执行硬恢复。
     *
     * 为什么不再自己做翻转/停服务：
     *  · 2026-11-11 01:20 实测，翻转 + am stopservice 共花 40 秒且全部无效；
     *  · 真正有效的是「force-stop → RESTART」，而它必须由**进程外**执行（1.4.94–1.4.99 全栽在
     *    "自己杀自己时 adblib socket 断、后半段没跑"）。
     *  · 看门狗已在 resume 时主动做这件事（1.4.102），这里只是兜底：万一它没醒/没动手。
     */
    fun wakeRecoveryAsync(ctx: Context, reason: String) {
        synchronized(this) {
            if (wakeTriedThisRun) {
                LsLog.i(TAG, "A11Y_RECOVER[" + reason + "] 本进程已请求过，跳过")
                return
            }
            wakeTriedThisRun = true
        }
        val app = ctx.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val now = System.currentTimeMillis()
            if (requestWatchdogHardRecover(app, now, reason)) {
                LsLog.w(TAG, "A11Y_WD_REQ[" + reason + "] 已请求看门狗执行硬恢复")
            } else {
                LsLog.w(TAG, "A11Y_WD_REQ[" + reason + "] 写入失败或被限流")
            }
        }
    }

    fun healAsync(ctx: Context, reason: String, delayMs: Long = 0L) {
        // 用户把总开关关掉时不要强行把无障碍再拉起来（尊重用户在系统设置里的关闭动作）。
        // 用跨进程标记文件判断：:guard 进程里的 SharedPreferences 可能是旧快照。
        if (!GesturePrefs.enabledFromDisk(ctx)) {
            LsLog.i(TAG, "手势总开关关闭，跳过无障碍自愈($reason)")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastRunAt < DEBOUNCE_MS) return
        lastRunAt = now
        val app = ctx.applicationContext
        track(scope.launch {
            if (delayMs > 0) delay(delayMs)
            runCatching { healInternal(app, reason) }
                .onFailure { LsLog.w(TAG, "自愈异常($reason): ${it.message}") }
        })
    }

    /** 同步执行（授权/激活流程里需要拿到结果再往下走），不受防抖限制 */
    suspend fun healNow(ctx: Context, reason: String): String = mutex.withLock {
        healLocked(ctx.applicationContext, reason)
    }

    /**
     * 进程重启/开机 30 秒后检查一次：名单里有灵手、enabled=1，但 30 秒仍没 bind，
     * 说明系统把 binding 丢了（GKD 的 fixA11yService 同类场景）。
     *
     * 只做一次定向重绑，仍不行再一次 0→1；60 秒文件冷却，绝不周期抖。
     */
    /**
     * 1.4.96: 进程一起来就自检 —— 不再依赖广播/依赖 30s 那条前置条件。
     *
     * 真车事实: 休眠唤醒时进程被杀, 重启后 AMS 既不 bind 也不发 USER_PRESENT;
     * 冷启动 15s 内本来就会自己绑上, 所以「15s 后仍未绑」= 需要一次主动恢复。
     */
    fun scheduleWakeCheck(ctx: Context) {
        if (!isMainProcess()) return
        val app = ctx.applicationContext
        track(scope.launch {
            delay(15_000L)
            if (LsAccessibilityService.isBinderConnected()) return@launch
            LsLog.w(TAG, "A11Y_WAKE_CHECK 进程起来 15s 仍未绑定 -> 启动恢复序列")
            wakeRecoveryAsync(app, "proc-start-15s")
        })
    }

    fun scheduleStaleRepair(ctx: Context) {
        if (!isMainProcess()) return
        val app = ctx.applicationContext
        track(scope.launch {
            delay(30_000L)
            runCatching { mutex.withLock { repairIfStale(app) } }
                .onFailure { LsLog.w(TAG, "stale repair 异常: ${it.message}") }
        })
    }

    private fun staleRepairFile(ctx: Context) = java.io.File(ctx.filesDir, "a11y_stale_repair.ts")

    /** a11y 真正连上后清掉修复冷却，下一次进程重启还能再修 */
    fun onA11yConnected(ctx: Context) {
        runCatching { staleRepairFile(ctx).delete() }
        // 重绑冷却只在“还没修好”时才有意义；已经连上就清掉，下次真出问题立刻能重绑
        runCatching { rebindSlotFile(ctx).delete() }
    }

    private suspend fun repairIfStale(ctx: Context) {
        val raw = readEnabled(ctx)
        // 1.4.96: 放宽 —— 唤醒后系统可能把条目抹掉(dumpsysBound=false), 此时更要修, 不能早退
        if (A11yList.contains(raw) && readEnabledFlag(ctx) != 1) LsLog.w(TAG, "accessibility_enabled != 1，由恢复序列的 1-0-1 翻转负责写回")
        if (isBound()) {
            onA11yConnected(ctx)
            return
        }
        if (!canWriteSecureSettings(ctx) && !ShellManager.isPrivileged()) return

        val f = staleRepairFile(ctx)
        val now = System.currentTimeMillis()
        if (f.exists() && now - f.lastModified() < 60_000L) {
            LsLog.i(TAG, "stale repair 冷却中（<60s），跳过本次")
            return
        }
        runCatching { f.writeText(now.toString()); f.setLastModified(now) }

        // 1.4.87：这里不再立刻重绑，交给 watchdog（60s × 连续 3 次）
        LsLog.w(TAG, "进程重启 30s 后 a11y 仍脱绑 —— 交给 watchdog 三动作，不再立刻重绑")
        // 1.4.93: 先做一次「值真翻转」逼 AMS 重建 ServiceRecord(本车唯一还没试过的手段)
        wakeRecoveryAsync(ctx, "process-restart-30s")
        if (true) return
        val r = rebind(ctx, 1, "stale", force = true)
        if (r.ok) {
            LsLog.i(TAG, "定向重绑后 a11y 已恢复（${r.detail}）")
            onA11yConnected(ctx)
            return
        }
        val hanging = dumpsysStuck(ctx)
        LsLog.w(TAG, "定向重绑后仍未绑定（${r.detail}），悬空=$hanging")
        if (hardResetStuckBinding(ctx)) return
        // 硬重置也无效（AMS 既不 Bound 也不再发 bind）：只能让包重新被系统拉起
        val deep = deepRecover(ctx, "stale")
        LsLog.w(TAG, "深度恢复：$deep")
    }

    /**
     * 用户打开灵手界面 / 打开总开关时的立即修复。
     *
     * 这是“打开一次就该恢复”的入口：不等后台 30 秒，前台直接做一次定向重绑。
     * 20 秒节流，避免用户反复切页导致连续 remove/add。
     */
    fun repairOnUserOpen(ctx: Context) {
        val app = ctx.applicationContext
        if (!GesturePrefs.enabledFromDisk(app)) return
        val now = System.currentTimeMillis()
        if (now - lastUserRepairAt < 20_000L) return
        lastUserRepairAt = now
        track(scope.launch {
            runCatching { repairNow(app) }
                .onFailure { LsLog.w(TAG, "用户打开界面修复异常: ${it.message}") }
        })
    }

    private fun hardResetFile(ctx: Context) = java.io.File(ctx.filesDir, "a11y_hard_reset.ts")

    /** 硬重置是否处在 0→1 的中间态；这段时间里别把 enabled 抢着置回 1 */
    private fun hardResetInFlight(ctx: Context): Boolean {
        val f = hardResetFile(ctx)
        return f.exists() && System.currentTimeMillis() - f.lastModified() < HARD_RESET_WINDOW_MS
    }

    /**
     * 系统 dumpsys 的 Binding services 里已经有灵手，但主进程就是收不到
     * onServiceConnected：这是 binding 卡死，remove/add 清不掉，只能做一次
     * accessibility_enabled 0→1 硬重置。5 分钟文件冷却，绝不反复。
     */
    private suspend fun hardResetStuckBinding(ctx: Context): Boolean {
        if (!canWriteSecureSettings(ctx) && !ShellManager.isPrivileged()) return false
        val f = hardResetFile(ctx)
        val now = System.currentTimeMillis()
        if (f.exists() && now - f.lastModified() < HARD_RESET_COOLDOWN_MS) {
            LsLog.i(TAG, "硬重置冷却中（<5min），跳过")
            return false
        }
        runCatching { f.writeText(now.toString()); f.setLastModified(now) }

        val raw = readEnabled(ctx)
        LsLog.w(TAG, "Binding services 有灵手但主进程未连接：执行一次硬重置（0→1）")
        writeServices(ctx, A11yList.without(raw))
        writeFlag(ctx, 0)
        delay(1_500L)
        writeFlag(ctx, 1)
        delay(1_000L)
        writeServices(ctx, if (A11yList.contains(raw)) raw else A11yList.merge(raw))
        val ok = waitBound(8_000L)
        LsLog.i(TAG, "硬重置结果: bound=$ok")
        if (ok) onA11yConnected(ctx)
        return ok
    }

    /**
     * 用户主动点「一键启动 / 修复」时的同步修复：不受重绑冷却限制。
     *
     * 名单在但不绑定时做真正的 remove→add 重绑（最多 2 轮），仍不绑定再退一次 0→1。
     * force 只跳过冷却，真正的互斥仍由 [withRebindSlot] 的文件锁保证，所以不会出现
     * 用户点一次、两个进程各重绑一次的互踩。
     */
    suspend fun repairNow(ctx: Context): String = mutex.withLock {
        val app = ctx.applicationContext
        // 用户主动点修复：清掉「已知不支持」标记，完整重试一遍（含强启判定）
        clearUnsupported(app)
        val raw = readEnabled(app)
        if (!A11yList.contains(raw)) return@withLock "名单里没有灵手，已交给常规补写"
        if (isBound()) return@withLock "已绑定"
        if (!canWriteSecureSettings(app) && !ShellManager.isPrivileged()) {
            return@withLock "无写入通道；请先手动开启无障碍"
        }
        val boundByDumpsys = dumpsysBound(app)
        val hanging = dumpsysStuck(app)
        LsLog.w(TAG, "用户主动修复：dumpsysBound=$boundByDumpsys · 悬空=$hanging")
        // 悬空态下 remove→add 与 0→1 硬重置都清不掉（真车实测），先直连深度恢复，
        // 成功就不再走后面两轮注定失败的写入。
        if (hanging) {
            val d = deepRecover(app, "repairNow")
            if (waitBound(2_500L) || dumpsysBound(app) == true) {
                return@withLock "深度恢复后已绑定（$d）"
            }
        }
        val r = rebind(app, REBIND_ROUNDS, "repairNow", force = true)
        if (r.ok) {
            onA11yConnected(app)
            return@withLock "定向重绑后已恢复（${r.detail}）"
        }
        if (hardResetStuckBinding(app) && waitBound(1_500L)) {
            return@withLock "enabled 重置后已恢复"
        }
        // 硬重置也救不回来（AMS 既不 Bound 也不再发 bind）：只能让包重新被系统拉起
        val deep = deepRecover(app, "repairNow")
        if (waitBound(2_000L) || dumpsysBound(app) == true) {
            return@withLock "深度恢复后已绑定（$deep）"
        }
        if (boundByDumpsys == true) return@withLock "系统显示已绑定，无需修复"
        "定向重绑 $REBIND_ROUNDS 轮仍未绑定（${r.detail}）；深度恢复=$deep"
    }

    /** 让下一次 healAsync 立即生效（例如用户刚点完一键授权） */
    fun resetDebounce() {
        lastRunAt = 0L
    }

    fun component(): String = A11yList.COMPONENT

    /**
     * 监听系统设置被外部改写。
     *
     * DiLink 实测会在运行一段时间后把第三方写入的无障碍项抹掉；只靠 60s 轮询，
     * 中间最多有 60 秒不可用。挂 ContentObserver 后，名单一被改就在 0.8 秒内补回。
     * 主进程与 :guard 进程各注册一份（两个进程都可能活到最后）。
     */
    fun registerObserver(ctx: Context) {
        // 只有主进程监听：:guard 也注册会各自补写、互相打醒（现场同一秒两条日志）
        if (!isMainProcess()) {
            LsLog.i(TAG, "非主进程，跳过无障碍 observer 注册")
            return
        }
        if (observer != null) return
        val app = ctx.applicationContext
        val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                onSettingsChanged(app)
            }
        }
        runCatching {
            app.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, o
            )
            app.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED), false, o
            )
            observer = o
            LsLog.i(TAG, "已注册无障碍设置变更监听")
        }.onFailure { LsLog.w(TAG, "注册设置监听失败: ${it.message}") }
    }

    private fun onSettingsChanged(ctx: Context) {
        if (!GesturePrefs.enabledFromDisk(ctx)) return
        val rawNow = readEnabled(ctx)
        val flag = readEnabledFlag(ctx)
        // 名单已含本服务且确实绑定着：这就是一次回声（自己或另一进程写回触发的），
        // 什么都不用做，记下值直接退出，绝不因为它再写一次 settings。
        if (A11yList.contains(rawNow) && flag == 1 && boundNow()) {
            lastRawSeen = rawNow
            return
        }
        // 名单没变且仍含本服务时同样忽略，避免自激循环
        if (rawNow == lastRawSeen && A11yList.contains(rawNow) && flag == 1) return
        lastRawSeen = rawNow
        val now = System.currentTimeMillis()
        if (now - lastObserverHealAt < OBSERVER_THROTTLE_MS) return
        lastObserverHealAt = now
        LsLog.d(TAG, "无障碍设置被外部修改，0.8s 后补回")
        track(scope.launch {
            delay(800)
            runCatching { healInternal(ctx, "settings-observer") }
                .onFailure { LsLog.w(TAG, "监听自愈异常: ${it.message}") }
        })
    }

    // =====================================================================
    private suspend fun healInternal(ctx: Context, reason: String): String = mutex.withLock {
        healLocked(ctx, reason)
    }

    /**
     * 自愈主体。所有会写 settings 的路径都在同一把 mutex 下，避免 onResume、
     * :guard 60s、observer 三条触发链在同一个进程里并发 read-modify-write。
     */
    private suspend fun healLocked(ctx: Context, reason: String): String {
        // 【1.4.6 闸门】该车机已判定「不绑第三方无障碍」：常规自愈一律跳过。
        // 只有这样才不会每 5 分钟 force-stop 一次自伤（见 markUnsupported 注释）。
        // 1.4.88：unsupported 标记**不再阻断**自愈（实验证明它会自我锁死）；仅用于状态文案
        if (false && isUnsupported(ctx) && !isUserInitiated(reason)) {
            return "跳过（该车机不绑定第三方无障碍，见 docs/08）"
        }
        // 快速短路：名单里有自己 + enabled=1 + binder 已连接 → 什么都不做，也不打日志。
        // 60s 巡检在正常状态下走的就是这条：省掉 parse/readFlag/拼串/一行日志，全天 1440 次。
        // 注意必须复制 lastRawSeen，否则 observer 的回声判断会失效（会以为名单变了）。
        val rawFast = readEnabled(ctx)
        if (A11yList.contains(rawFast) && readEnabledFlag(ctx) == 1 && isBound()) {
            lastRawSeen = rawFast
            return "已在绑定"
        }

        // 首次触发时 shell 通道可能还没探测过：补一次探测，避免开机自愈被误判为"无通道"
        var privileged = ShellManager.isPrivileged()
        if (!privileged && !canWriteSecureSettings(ctx) && ShellManager.currentChannelId() == "none") {
            privileged = runCatching { ShellManager.reprobe(ctx).isPrivileged() }.getOrDefault(false)
        }

        // 没有任何写入通道时只做一次诊断，不要每 12s 试图写 settings（必然失败且刷日志）
        if (!canWriteSecureSettings(ctx) && !privileged) {
            val result = "原因=$reason · 无写入通道（WRITE_SECURE_SETTINGS 未授予，且无 shell 通道）"
            LsLog.w(TAG, result)
            lastResult = result
            return result
        }

        val rawBefore = readEnabled(ctx)
        lastRawSeen = rawBefore
        val before = A11yList.parse(rawBefore)
        val had = before.contains(A11yList.COMPONENT)
        val resetting = hardResetInFlight(ctx)

        // 守门员：名单里没有灵手才补写。名单里已有灵手时再写同值毫无意义——
        // AMS 收到通知后发现值没变，既不重新评估绑定，还会把另一个进程的 observer
        // 打醒，两个进程互相触发就是现场“1 秒 3 次写”的自激循环。
        var write = WriteOutcome(true, false)
        if (!had) {
            write = writeEnabled(ctx, A11yList.merge(rawBefore))
        } else if (readEnabledFlag(ctx) != 1 && !resetting) {
            write = writeEnabled(ctx, rawBefore)
        } else if (readEnabledFlag(ctx) != 1) {
            LsLog.i(TAG, "硬重置进行中，跳过 enabled 补写")
        }

        var bound = if (had) isBound() else waitBound(1_500L)

        // 名单在、enabled=1，却既不在 Bound 也不在 Binding：只有让 AMS 看到值真实
        // 变化（移除→加回）才会重新 bind。冷却与跨进程互斥都在 rebind 里。
        var deepNote: String? = null
        val rebindNote: String? = if (had && !bound && !resetting && readEnabledFlag(ctx) == 1) {
            // 悬空态（Binding 段有自己、Bound 段没有）：真车实测 remove→add 写 settings 无效，
            // 直接走深度恢复，省掉一轮注定失败的写入与等待。
            if (dumpsysStuck(ctx)) {
                deepNote = deepRecover(ctx, "heal:$reason")
                if (waitBound(2_000L) || dumpsysBound(ctx) == true) bound = true
                "跳过（悬空，直接深度恢复）"
            } else {
            val r = rebind(ctx, 1, "heal:$reason")
            if (r.ok) bound = true
            if (!r.ok) {
                // 移除→加回只对「AMS 还在正常评估」的场景有效。车机实测还有更死的状态：
                // 名单在、enabled=1、AMS 既不 Bound 也不再发 bind（Binding 段为空），
                // 此时任何 settings 变化（移除、加回、enabled 0→1）都不响应，
                // 只有让包重新被系统拉起（force-stop → 写回名单 → RESTART 广播）才恢复。
                deepNote = deepRecover(ctx, "heal:$reason")
                if (waitBound(2_000L)) bound = true
            }
            r.detail
            }
        } else {
            null
        }

        val rawAfter = readEnabled(ctx)
        // 记下自己写回后的值：observer 读到同样的值就知道是回声，不会再触发一轮自愈
        lastRawSeen = rawAfter
        val after = A11yList.parse(rawAfter)
        val result = buildString {
            append("原因=").append(reason)
            append(" · 写入=").append(
                when {
                    !write.ok -> "失败"
                    !write.changed -> "跳过（值未变）"
                    else -> "已处理"
                }
            )
            append(" · 名单前=").append(before.size).append("项")
            append(" · 名单后=").append(after.size).append("项")
            append(" · 含本服务=").append(A11yList.contains(rawAfter))
            append(" · 已绑定=").append(bound)
            if (rebindNote != null) append(" · 重绑=").append(rebindNote)
            if (deepNote != null) append(" · 深度恢复=").append(deepNote)
        }
        // 只有真的写入了（或重绑过）才输出完整名单；"跳过（值未变）"只留一行
        // 注意：rebindNote 形如「跳过（非主进程不做重绑）」时并没有真的动过名单，
        // 不能因为它是非 null 就输出整表（否则 :guard 每分钟刷两行）。
        val rebindHappened = (rebindNote != null && !rebindNote.startsWith("跳过")) ||
            (deepNote != null && !deepNote.startsWith("跳过"))
        // 只在「真的动了名单」或「写入失败」时留痕：
        // 60s 巡检在正常状态下什么都不做，却会每分钟一条，全天 1440 条（O1/O5 的元凶）。
        // :guard 那份进程 binder 状态拿不到，会一直走到这里，所以这条判定必须在这层。
        when {
            write.changed || rebindHappened ->
                LsLog.i(TAG, result + "\n  写前: $rawBefore\n  写后: $rawAfter")
            !write.ok -> LsLog.w(TAG, result)
            else -> Unit   // 稳态：不写、不打日志
        }
        lastResult = result
        return result
    }

    /** 读取 enabled_accessibility_services（普通 App 无需权限即可读） */
    fun readEnabled(ctx: Context): String = try {
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: ""
    } catch (t: Throwable) {
        LsLog.w(TAG, "读取无障碍名单失败: ${t.message}")
        ""
    }

    fun readEnabledFlag(ctx: Context): Int = try {
        Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
    } catch (t: Throwable) { 0 }

    // =====================================================================
    // 定向重绑
    // =====================================================================

    private fun rebindSlotFile(ctx: Context) = java.io.File(ctx.filesDir, "a11y_rebind.lock")

    /**
     * 重绑状态机：移除自己 → 写入 → 等 1s → 精确加回 → 写入 → 校验绑定，最多 [rounds] 轮。
     *
     * 为什么必须 remove 再 add：写同值时 SettingsProvider 照样 notifyChange，AMS 比
     * 较后发现 enabled_accessibility_services 没变化，直接跳过整段重新绑定逻辑；只有
     * 值真的从「含本服务」变成「不含」再变回来，它才会 unbind → bind。
     *
     * 加回时写的是移除前的原始字符串，不是 merge 结果：merge 会把本服务挪到末尾，
     * 既打乱别人服务的顺序，又制造一次没必要的名单变化。
     */
    private suspend fun rebind(
        ctx: Context,
        rounds: Int,
        reason: String,
        force: Boolean = false
    ): RebindOutcome {
        // 只允许主进程做「移除→加回」这种破坏性重绑。
        // :guard 看不到主进程的 binder 状态（mainA11yConnected 会返回 false/过期值），
        // 它一旦误判就会把主进程里真正连着的无障碍拆掉——现场「手势偶发失效、
        // dumpsys 长期停在 Binding services」就是它造成的。
        if (!isMainProcess()) return RebindOutcome(true, "跳过（非主进程不做重绑）")
        if (isBound()) return RebindOutcome(true, "已在绑定")
        if (!canWriteSecureSettings(ctx) && !ShellManager.isPrivileged()) {
            return RebindOutcome(false, "无写入通道")
        }
        val out = withRebindSlot(ctx, force) { rebindLocked(ctx, rounds) }
            ?: return RebindOutcome(false, "跳过（互斥或冷却中）")
        LsLog.w(TAG, "定向重绑[$reason]: ok=${out.ok} · ${out.detail}")
        return out
    }

    private suspend fun rebindLocked(ctx: Context, rounds: Int): RebindOutcome {
        // 崩溃安全：先把原始名单落盘，再动手。任何中断（超时/被杀/异常）之后，
        // 下一次 heal 都会按这个备份把自己补回名单——1.2.34 在真车上就是这里断掉，
        // 把灵手从 enabled_accessibility_services 里删了没加回来，现场表现为"永久掉线"。
        val backup = java.io.File(ctx.filesDir, "a11y_restore.txt")
        runCatching { backup.writeText(readEnabled(ctx)) }
        try {
        var lastDumpsys: Boolean? = null
        for (round in 1..rounds) {
            val base = readEnabled(ctx)
            if (A11yList.contains(base)) {
                LsLog.i(TAG, "重绑第${round}轮：移除本服务→加回")
                if (!writeEnabled(ctx, A11yList.without(base)).ok) {
                    return RebindOutcome(false, "移除写入失败")
                }
                delay(REBIND_GAP_MS)
                if (!writeEnabled(ctx, base).ok) {
                    // 加不回去等于把用户的无障碍彻底弄丢，比不修更糟：立刻补一次
                    LsLog.e(TAG, "重绑加回失败，补写一次防止名单丢失本服务")
                    writeEnabled(ctx, A11yList.merge(base))
                }
            } else {
                // 名单里本来就没本服务：这次写入本身就是值变化，AMS 会重新评估
                LsLog.i(TAG, "重绑第${round}轮：名单缺本服务，直接补写")
                if (!writeEnabled(ctx, A11yList.merge(base)).ok) {
                    return RebindOutcome(false, "补写失败")
                }
            }
            if (verifyBound(ctx)) return RebindOutcome(true, "第${round}轮成功")
            lastDumpsys = dumpsysBound(ctx)
        }
        return RebindOutcome(false, "${rounds}轮后仍未绑定（dumpsys=$lastDumpsys）")
        } finally {
            // 备份还在 = 这轮没有正常收尾：只要名单里缺自己，就按备份补回去
            val saved = runCatching { if (backup.exists()) backup.readText() else null }.getOrNull()
            if (saved != null) {
                runCatching {
                    val cur = readEnabled(ctx)
                    if (!A11yList.contains(cur)) {
                        writeEnabled(ctx, A11yList.merge(cur))
                        LsLog.w(TAG, "重绑中断：已按备份把本服务补回名单")
                    }
                }
                runCatching { backup.delete() }
            }
        }
    }
    /**
     * 深度恢复：Binding 悬空（AMS 发过 bind 却一直没连上）时唯一有效的动作。
     *
     * 车机实测：这种状态下写 settings（哪怕移除再加回）AMS 比较后发现值没变，
     * 直接跳过重新绑定，只有让包重新被系统拉起才恢复；而 force-stop 会把包置为
     * stopped（系统从此不再 bind 它），所以必须紧跟一次拉起动作把它叫回来。
     * 三步连成一条命令交给 adbd 执行：本进程被 force-stop 掉也不影响已经 fork 的
     * sh 把剩下的步骤跑完。
     *
     * 拉起动作一律用 [RESTART_BC] 而不是 `am start -n .../MainActivity`：后者会把
     * 主界面弹到用户脸上（1.2.26 真车实测，ActivityRecord 的
     * launchedFromPackage=com.android.shell，休眠唤醒与断电重启各复现一次）。
     */
    private suspend fun deepRecover(ctx: Context, reason: String): String {
        if (!isMainProcess()) return "跳过（非主进程）"
        val now = System.currentTimeMillis()
        val sp = ctx.applicationContext.getSharedPreferences(DEEP_PREFS, Context.MODE_PRIVATE)
        val last = sp.getLong(KEY_DEEP_TS, 0L)
        if (now - last < DEEP_COOLDOWN_MS) {
            return "跳过（冷却 ${(DEEP_COOLDOWN_MS - (now - last)) / 1000}s）"
        }
        if (!ShellManager.isPrivileged()) return "跳过（无 shell 通道）"
        sp.edit().putLong(KEY_DEEP_TS, now).apply()

        // 1.4.86：**开机 3 分钟内禁止定向重绑**。
        // 实测（2026-11-10 17:37:56）：开机 30s 时执行「删条目→加回」，
        // AMS 的 mBindingServices 未清 → 加回后不重绑，直接进入半绑。
        val bootAge = android.os.SystemClock.elapsedRealtime()
        if (bootAge < 180_000L) {
            LsLog.w(TAG, "跳过重绑（开机 " + (bootAge / 1000) + "s < 180s，此期任何删/加都会打断 AMS 建立绑定）")
            return "跳过（开机冷却期）"
        }

        // 第一步无损：只是叫醒包让 AMS 重新评估一次绑定（不弹 UI）
        runCatching { ShellManager.exec(ctx, RESTART_BC, 8_000) }
        if (waitBound(6_000L)) return "已恢复（RESTART 广播）"

        val full = readEnabled(ctx)
        if (!A11yList.contains(full)) return "跳过（名单缺本服务）"

        // 第二档（无损）：只让 settings 的值真实变化两次，不 force-stop 自己。
        //
        // 真车实测：force-stop 会把 :guard、卡片、adblib 通道一起杀掉，包还进入
        // stopped 状态（系统从此不 bind 它），恢复期所有自愈全部停摆——14:45:57
        // 那次之后 272 秒日志零输出就是这么来的。上电自启失败的真正原因不是
        // 「没收到广播」，而是自家 deepRecover 把刚拉起来的进程自杀了。
        // 能让 AMS 重新评估的前提是 settings 的**值真的变了**，所以去/回这一步
        // 必须保留，force-stop 才是可以拿掉的那一步。
        // 1.4.86：**只加不删**。名单里已经有我们 → 绝不写 settings（写了就等于自伤）；
        // 只有名单缺我们时才写一次（追加），避免"删掉那 2 秒"被 AMS 记成解绑。
        val noKill = (if (A11yList.contains(full)) "" else
                "settings put secure enabled_accessibility_services '" + A11yList.merge(full) + "'; sleep 1; ") + RESTART_BC
        LsLog.w(TAG, "深度恢复[$reason]: 无损版 名单去/回")
        runCatching { ShellManager.exec(ctx, noKill, 20_000) }
        if (waitBound(5_000L)) return "已恢复（无损重绑）"

        // 第三档（1.4.92 已移除）：原来的「force-stop + 名单去/回 + RESTART」不再执行。
        //
        // 2026-11-10 20:30 实测（交接包 docs/13 §10）：休眠唤醒后半绑，
        //   20:30:16 无损版启动 → 20:30:21 升级 force-stop 档 → 20:32:06 disableSelf=false
        //   → 20:32:10 remove-add=false；全程无效，还把 :guard / 卡片 / adblib 通道一起杀掉。
        // 结论：本车是 AMS 侧限制（H1），再打下去只赔通道，不换绑定。
        LsLog.w(TAG, "深度恢复[" + reason + "]: 无损手段无效，已放弃本轮（force-stop 档自 1.4.92 移除）")
        return "已放弃（无损手段无效；force-stop 档已移除）"
    }

    /**
     * 重绑后的绑定校验。
     *
     * 主进程里 binder 是唯一真值：dumpsys 的 Binding services 会在 onServiceConnected
     * 之前就列出组件，单看它会误判「已绑定」。:guard 看不到主进程静态字段，先问主进程
     * Binder，主进程不在时才退回 dumpsys 解析，否则它的重绑永远没有成功判据。
     */
    private suspend fun verifyBound(ctx: Context): Boolean {
        if (waitBound(REBIND_WAIT_MS)) return true
        return !isMainProcess() && dumpsysBound(ctx) == true
    }

    /**
     * 跨进程重绑互斥。
     *
     * 主进程和 :guard 各有一份 observer，重绑的两次写入必然把对方打醒；不互斥就会
     * 出现「A 刚加回、B 又移除」的互踩。用 filesDir 下的同一个文件做 OS 文件锁
     * （真互斥，进程被杀内核自动释放）加 mtime 冷却（防刚失败就再来一次）：比只用
     * 时间戳可靠，也比走 Binder 让主进程代理 :guard 轻。
     *
     * 返回 null 表示这次不该重绑（另一进程持锁，或还在冷却里）。
     */
    private suspend fun <T> withRebindSlot(
        ctx: Context,
        force: Boolean,
        block: suspend () -> T
    ): T? {
        val f = rebindSlotFile(ctx)
        // 冷却只认「本次调用之前就存在的 mtime」。
        // 必须在 createNewFile 之前取：新建文件的 mtime 就是创建时刻，
        // 拿它当冷却基准会让装上后/onA11yConnected 删除后的第一次重绑被白白拦掉 10s。
        val lastBefore = if (f.exists()) f.lastModified() else 0L
        val raf = runCatching {
            if (!f.exists()) f.createNewFile()
            RandomAccessFile(f, "rw")
        }.getOrNull() ?: return block()   // 锁文件都建不出来时别因此放弃重绑

        val lockResult = runCatching { raf.channel.tryLock() }
        val lock = lockResult.getOrNull()
        if (lock == null && lockResult.isSuccess) {
            // tryLock 返回 null 是「另一进程正持有」，这才是要跳过的情形
            runCatching { raf.close() }
            LsLog.i(TAG, "另一进程正在重绑，跳过（文件锁）")
            return null
        }
        if (lockResult.isFailure) {
            // 个别文件系统不支持文件锁：退回纯时间戳冷却，别把重绑彻底关死
            LsLog.w(TAG, "文件锁不可用，退回时间戳冷却: ${lockResult.exceptionOrNull()?.message}")
        }
        var ran = false
        try {
            val now = System.currentTimeMillis()
            if (now - lastRealRebindAt in 1 until 3_000L) {
                LsLog.i(TAG, "3 秒内刚重绑过，跳过（进程内硬闸）")
                return null
            }
            if (!force && lastBefore > 0 && now - lastBefore < REBIND_COOLDOWN_MS) {
                LsLog.i(TAG, "重绑冷却中（<${REBIND_COOLDOWN_MS / 1000}s），跳过")
                return null
            }
            ran = true
            return block()
        } finally {
            // 冷却时间戳在 block **结束后**才落：一轮重绑本身就要 1s 间隔 + 1.5s 绑定等待 +
            // dumpsys（真车 150ms/次，Robolectric/慢机器上可能数秒）。若在 block 之前落，
            // 耗时长的这一轮一结束就已经出了 10s 冷却窗，紧接着的下一次触发会立刻再绑一次
            // —— 现场就是主进程与 :guard 互相打醒、来回 remove/add。
            // 只有真跑过 block 才刷新冷却：被跳过的那次不该继续把窗口往后推，
            // 否则外部每 3s 一次的 observer 通知就能把重绑永久压住。
            if (ran) {
                lastRealRebindAt = System.currentTimeMillis()
                runCatching { f.setLastModified(lastRealRebindAt) }
            }
            runCatching { lock?.release() }
            runCatching { raf.close() }
        }
    }

    // =====================================================================
    // 写入（任何写入前先比当前值）
    // =====================================================================

    /**
     * 写名单。值没变就一条都不写。
     *
     * SettingsProvider 对同值照样 notifyChange，AMS 收到后因为名单没变不会重新评估
     * 绑定，等于白写；更糟的是它会把主进程/:guard 的 observer 都打醒，形成
     * 「写→通知→再自愈→再写」的自激循环。这里直接拦住。
     */
    private suspend fun writeServices(ctx: Context, value: String): WriteOutcome {
        if (value == readEnabled(ctx)) {
            // 降为 d：60s 巡检在 :guard 里每轮都会走到这里，i 级会变成 2880 条/天
            LsLog.d(TAG, "无障碍名单未变，跳过（值未变）")
            return WriteOutcome(true, false)
        }
        val direct = try {
            Settings.Secure.putString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                value
            )
        } catch (t: Throwable) {
            false
        }
        if (direct) {
            LsLog.i(TAG, "经 Settings.Secure 直写名单成功（WRITE_SECURE_SETTINGS 已授予）")
            return WriteOutcome(true, true)
        }
        // 降级：shell(UID 2000)。值只含组件名和冒号，单引号包裹是安全的
        val r = ShellManager.exec(
            ctx,
            "settings put secure enabled_accessibility_services '$value'",
            8_000
        )
        LsLog.i(TAG, "经 shell 写无障碍名单：write=${r.exitCode} ok=${r.success}")
        return WriteOutcome(r.success, r.success)
    }

    /** 写 accessibility_enabled。同样先比值，已经是目标值就不写。 */
    private suspend fun writeFlag(ctx: Context, value: Int): WriteOutcome {
        if (readEnabledFlag(ctx) == value) {
            LsLog.d(TAG, "accessibility_enabled 已是 $value，跳过（值未变）")
            return WriteOutcome(true, false)
        }
        val direct = runCatching {
            Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, value)
        }.getOrDefault(false)
        if (direct) {
            LsLog.i(TAG, "经 Settings.Secure 直写 accessibility_enabled=$value")
            return WriteOutcome(true, true)
        }
        if (!ShellManager.isPrivileged()) return WriteOutcome(false, false)
        val r = ShellManager.exec(ctx, "settings put secure accessibility_enabled $value", 6_000)
        LsLog.i(TAG, "经 shell 写 accessibility_enabled=$value：exit=${r.exitCode}")
        return WriteOutcome(r.success, r.success)
    }

    /**
     * 写名单 + 置 enabled=1。
     *
     * 先走 App 自身 WRITE_SECURE_SETTINGS（若已 pm grant），失败立即降级 shell。
     * 名单写不进去就不再动 enabled，否则会把一个坏名单“启用”起来。
     */
    private suspend fun writeEnabled(ctx: Context, value: String): WriteOutcome {
        val s = writeServices(ctx, value)
        if (!s.ok) return WriteOutcome(false, false)
        val f = writeFlag(ctx, 1)
        return WriteOutcome(true, s.changed || f.changed)
    }

    // =====================================================================

    /**
     * 是否真正 bind。
     *
     * 主进程里直接看 [LsAccessibilityService.isBinderConnected]（最真实）；
     * :guard 进程看不到主进程的静态字段，退化为问主进程 Binder。
     */
    suspend fun isBound(): Boolean = boundNow()

    /**
     * 非挂起的快速判断，供 observer 提前退出用。
     *
     * 为什么不能只看 binder 静态字段：NDK 守护进程 fork 出来的那份进程名也是 `com.byd.ls`，
     * 它没有 a11y 服务、binder 必然为 false；它一旦判定“未绑定”就会去移除+加回名单，
     * 把真正连着的那个进程的服务**拆掉**（现场表现：手势偶尔能用、dumpsys 一直 Binding）。
     * 所以这里再加一路 dumpsys 兜底：任一说“已绑定”就算已绑定，宁可少修也不要误拆。
     */
    private fun boundNow(): Boolean {
        if (isMainProcess()) return LsAccessibilityService.isBinderConnected()
        // :guard 优先通过主进程 Binder 取真实状态；dumpsys 的 "Binding services"
        // 可能在 onServiceConnected 之前就列出组件，单看它会误判“已绑定”。
        return GuardService.mainA11yConnected() ?: false
    }

    private suspend fun waitBound(timeoutMs: Long): Boolean {
        if (isMainProcess()) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (LsAccessibilityService.isBinderConnected()) return true
                delay(300)
            }
            return false
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (GuardService.mainA11yConnected() == true) return true
            delay(300)
        }
        return false
    }

    /**
     * 取一份 `dumpsys accessibility` 文本。
     *
     * 优先用 App 自己的 DUMP 权限直接执行 —— ADB 不在线时也能校验绑定，
     * 开机自愈不再依赖外部通道；DUMP 未授予时才退回 shell。
     */
    private suspend fun dumpsysText(ctx: Context): String? {
        if (canDump(ctx)) {
            return withContext(Dispatchers.IO) { runCatching { dumpsysLocal() }.getOrNull() }
        }
        if (ShellManager.isPrivileged()) {
            val r = ShellManager.exec(ctx, "dumpsys accessibility", 10_000)
            return if (r.success && r.stdout.isNotBlank()) r.stdout else null
        }
        return null
    }

    /** Bound 段里是否出现本服务（只有这里出现才算真绑定）；null 表示这次不可信 */
    private suspend fun dumpsysBound(ctx: Context): Boolean? =
        A11yDumpsys.parseBound(dumpsysText(ctx))

    /** 悬空态：AMS 卡在 Binding 却没连上 —— 此时写 settings 救不回来 */
    private suspend fun dumpsysStuck(ctx: Context): Boolean =
        A11yDumpsys.parseStuck(dumpsysText(ctx))

    private fun dumpsysLocal(): String {
        val p = ProcessBuilder("/system/bin/dumpsys", "accessibility")
            .redirectErrorStream(true)
            .start()
        val text = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor(5, TimeUnit.SECONDS)
        return text
    }

    fun canDump(ctx: Context): Boolean = try {
        ctx.checkSelfPermission("android.permission.DUMP") == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    private fun isMainProcess(): Boolean = try {
        Application.getProcessName() == A11yList.PKG
    } catch (_: Throwable) { true }

    /** pm grant 是否已生效（用于界面展示真实通道） */
    /**
     * dumpsys accessibility 的**分段判定**。
     *
     * 1.2.34 踩过的坑：用全文 contains 判"已绑定"，而 `Binding services` 段里同样会出现本服务名，
     * 于是"正在绑但没绑上"被判成了"已绑定"——正是我们要消灭的假绿。
     * 这里只切出 Bound services 段，到下一个字段为止。
     */
    fun boundSectionHas(dumpsys: String, needle: String = "LsAccessibilityService"): Boolean {
        if (!dumpsys.contains("Bound services")) return false
        val sec = dumpsys.substringAfter("Bound services").substringBefore("Enabled services")
        return sec.contains(needle)
    }

    fun bindingSectionHas(dumpsys: String, needle: String = "LsAccessibilityService"): Boolean {
        if (!dumpsys.contains("Binding services")) return false
        val sec = dumpsys.substringAfter("Binding services").substringBefore("Enabled services")
        return sec.contains(needle)
    }

    /**
     * Binding 悬空的救命动作：force-stop + RESTART（脱离执行）。
     *
     * 2026-11-06 在真车上验证：这是唯一能把"名单有、Bound 段没有"救回来的动作；
     * 1.2.26 把它降级掉之后，现场就变成"永久掉线"。
     * 10 分钟冷却，避免来回杀进程。
     */
    /**
     * 用 shell 侧 sed 只取"Bound 段 / Binding 段里的本服务计数"。
     *
     * 为什么要绕这一圈：dumpsys accessibility 全文几十 KB，adblib 的流会截断
     * （真车实测 exit=-1、文本在 "Bound services" 之前就断了），于是"已绑定"被判成"未绑定"。
     * 让 shell 只回一个 0/1，输出极小，稳定。
     */
    suspend fun queryBoundState(ctx: Context): Pair<Boolean, Boolean> {
        if (!ShellManager.isPrivileged()) return false to false
        suspend fun has(cmd: String): Boolean = runCatching {
            ShellManager.exec(ctx, cmd, 10_000L).stdout.trim().endsWith("1")
        }.getOrDefault(false)
        val bound = has("dumpsys accessibility | sed -n '/Bound services/,/Enabled services/p' | grep -c LsAccessibilityService")
        val hanging = has("dumpsys accessibility | sed -n '/Binding services/,\$p' | grep -c LsAccessibilityService")
        return bound to hanging
    }

    /** 进程被杀导致 rebind 中断时，按落盘备份把自己补回名单（幂等）。 */
    suspend fun restoreIfNeeded(ctx: Context): String {
        val f = java.io.File(ctx.filesDir, "a11y_restore.txt")
        if (!f.exists()) return ""
        val saved = runCatching { f.readText() }.getOrNull()
        runCatching { f.delete() }
        if (saved.isNullOrBlank()) return ""
        val cur = readEnabled(ctx)
        if (A11yList.contains(cur)) return ""
        val ok = writeEnabled(ctx, A11yList.merge(cur)).ok
        LsLog.w(TAG, "发现中断残留的重绑备份，已补回名单: ok=" + ok)
        return if (ok) "已恢复" else "恢复失败"
    }

    suspend fun forceRestartIfAllowed(ctx: Context): String {
        if (!isMainProcess()) return "跳过（非主进程）"
        if (!ShellManager.isPrivileged()) return "跳过（无 shell 通道）"
        // 【1.4.6】已判定该车机不绑第三方无障碍 → 永不强启。
        // 实测（docs/08）：悬挂态下 force-stop + RESTART 连续 4 次都救不回来，
        // 却会杀进程、断特权通道（连续两条 shell.exec 就 exit=-1），属于纯自伤。
        // 1.4.88：同上，标记不再阻断

        val f = java.io.File(ctx.filesDir, "a11y_hardrestart.ts")
        val now = System.currentTimeMillis()
        // 冷却从 10 分钟收到 90 秒：真车实测「半绑」会持续好几分钟，10 分钟冷却意味着
        // 第一次强启被跳过之后就再也不救（01:23–01:25 现场就是这么废掉的）。
        if (f.exists() && now - f.lastModified() < 300_000L) {
            return "跳过（5 分钟内已强启过一次）"
        }
        runCatching { f.writeText(now.toString()) }

        // 第一档：无损重绑（把 settings 里的自己摘掉再写回，逼 AMS 重新评估）。
        // docs/08 的 E9 表明它对「悬挂态」通常无效，但它不伤进程、不断通道，所以先试。
        val rebound = runCatching { rebind(ctx, 1, "forceRestart-pre", force = true) }
            .getOrElse { RebindOutcome(false, "异常:" + it.message) }
        if (rebound.ok && waitBound(2_500L)) {
            LsLog.w(TAG, "强启前置重绑成功，跳过 force-stop")
            RemoteAuditLog(ctx, "forceRestart 前置重绑已恢复")
            return "重绑后已恢复（未强启）"
        }

        // 【1.4.6 行为变更】不再 force-stop：直接落「已知不支持」标记，后续交给用户手动修复。
        // 1.4.89：失败**不再**落 unsupported 标记（那会让自愈永久锁死）
        LsLog.e(TAG, "重绑失败（不落标记）: " + rebound.detail)
        RemoteAuditLog(ctx, "forceRestart 改为标记不支持：" + rebound.detail.take(120))
        return "已标记「该车机不绑第三方无障碍」（不再强启）：" + rebound.detail
    }

    /**
     * 历史强启脚本（force-stop + RESTART），1.4.6 起默认不再调用。
     * 换车型排查时如需临时启用，把 [EXP_ALLOW_FORCE_RESTART] 置 true 并在
     * [forceRestartIfAllowed] 里调用本函数。
     */
    private suspend fun forceStopAndRestart(ctx: Context) {
        val script = "sleep 1; am force-stop com.byd.ls; sleep 10; " +
            "am broadcast -a com.byd.ls.action.RESTART -p com.byd.ls -f 0x20"
        val cmd = "nohup setsid sh -c '" + script + "' >/dev/null 2>&1 &"
        runCatching { ShellManager.exec(ctx, cmd, 8_000L) }
        LsLog.w(TAG, "Binding 悬空：执行 force-stop + RESTART（脱离执行）")
        RemoteAuditLog(ctx, "forceRestart 已下发")
    }

    /** 审计写在 remote 包里；这里用反射式弱依赖，避免 service 包反向依赖 remote 包 */
    private fun RemoteAuditLog(ctx: Context, msg: String) {
        runCatching {
            Class.forName("com.byd.ls.remote.RemoteAudit")
                .getMethod("log", Context::class.java, String::class.java)
                .invoke(null, ctx, msg)
        }
    }

    fun canWriteSecureSettings(ctx: Context): Boolean = try {
        ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") ==
            PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }
}
