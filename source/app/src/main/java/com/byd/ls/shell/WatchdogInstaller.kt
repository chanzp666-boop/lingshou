package com.byd.ls.shell

import android.content.Context
import android.os.SystemClock
import com.byd.ls.util.LsLog
import java.security.MessageDigest

/**
 * shell 侧看门狗的安装与拉起。
 *
 * 为什么需要它：灵手 App 内的四层守护（native daemon、:guard、RTC 心跳、BOOT_COMPLETED）
 * 全部跑在 com.byd.ls 自己的 UID 下。这台车机熄火是「整机 suspend」而不是断电，唤醒时
 * 没有任何广播，BOOT_COMPLETED 永远不来；进程一旦被清就没人能把它拉回来。
 * 看门狗常驻在 UID 2000：只被 suspend 冻结、不会被按 UID 清理，唤醒后 sleep 到期立刻巡检。
 *
 * 安装方式：脚本落到 /data/local/tmp，用 setsid 脱离会话启动，之后与 adblib 连接无关
 * （因此不占 adbd 的待授权槽位，不影响迪加/迪友授权）。
 */
object WatchdogInstaller {

    private const val TAG = "Watchdog"
    private const val ASSET = "ls-watchdog.sh"
    private const val REMOTE = "/data/local/tmp/ls-watchdog.sh"
    private const val BOOT_SH = "/data/local/tmp/ls-watchdog-boot.sh"
    private const val PIDFILE = "/data/local/tmp/ls-watchdog.pid"

    /** 同一进程内 30s 内不重复安装，避免 onResume 反复刷。 */
    private const val ENSURE_GAP_MS = 30_000L
    @Volatile private var lastEnsureAt = 0L

    /** 给界面直接读的状态文案，由 [ensure] / [isRunning] 刷新，读的时候不发起 shell 调用。 */
    @Volatile var stateText: String = "未知"
        private set

    /**
     * 幂等安装并启动。已在跑就直接返回，不做任何写入。
     * 返回一行可直接展示的摘要。
     */
    suspend fun ensure(ctx: Context, reason: String, force: Boolean = false): String {
        val app = ctx.applicationContext
        val now = SystemClock.elapsedRealtime()
        // lastEnsureAt 初值 0 会和 elapsedRealtime() 的开机小值相撞：boot 后约 9.7s 调用时
        // 9.7 - 0 < 30 命中防抖直接返回（这又是唯一不写日志的分支），真车实测 11-04 00:30
        // 那次 boot 从头到尾没装上看门狗，熄屏被 stop 后再无人拉起。初值必须当作
        // 「从未执行过」，而不是「刚刚执行过」。
        if (!force && lastEnsureAt != 0L && now - lastEnsureAt < ENSURE_GAP_MS) return stateText
        lastEnsureAt = now

        if (!ShellManager.isPrivileged()) {
            // 开机瞬间 adblib 通常还没握手（真车实测 22:39:21 才握手成功），
            // 若在这里静默放弃并锁死 30s 防抖，下一次机会要等 TICK 闹钟，最长 60s 起不来。
            // 通道没通时把防抖窗口让出来，让 onResume / TICK 每次都能再试一次。
            lastEnsureAt = 0L
            stateText = "未运行（无特权通道）"
            LsLog.i(TAG, "看门狗 跳过 · 无特权通道 reason=" + reason)
            return stateText
        }

        val script = runCatching {
            app.assets.open(ASSET).bufferedReader().use { it.readText() }
        }.getOrElse {
            stateText = "读取资源失败"
            LsLog.w(TAG, "读取 $ASSET 失败: ${it.message}")
            return stateText
        }

        // 1.4.103：实测曾同时跑 5 个看门狗实例（pidfile 单例失效），旧脚本混在里面导致新逻辑永不生效。
        // force 重装时先清光所有匹配进程，再走正常写入 + 启动。
        if (force) {
            clearStale(app, "force")
            lastEnsureAt = 0L
        }

        if (isRunning(app)) {
            // 升级场景：旧版本进程还在跑旧脚本，必须换掉，否则新逻辑永远不生效。
            // 1.4.160 只比字节数；脚本内容变了但长度没变时会误判为“运行中”，
            // 所以优先比 sha256，只有通道不支持时退回长度。
            val assetBytes = script.toByteArray(Charsets.UTF_8)
            val assetHash = sha256(script)
            val remoteHash = ShellManager.exec(
                app, "sha256sum $REMOTE 2>/dev/null | cut -d' ' -f1", 4_000L
            ).stdout.trim().lowercase()
            val remoteLen = ShellManager.exec(app, "wc -c < $REMOTE 2>/dev/null", 4_000L)
                .stdout.trim().filter { it.isDigit() }.toIntOrNull() ?: -1
            if (remoteHash.isNotEmpty()) {
                if (remoteHash == assetHash) {
                    stateText = "运行中"
                    return stateText
                }
                LsLog.i(TAG, "脚本已变更（sha256 remote=$remoteHash asset=$assetHash），换掉旧看门狗")
            } else if (remoteLen == assetBytes.size) {
                stateText = "运行中"
                return stateText
            } else {
                LsLog.i(TAG, "脚本已变更（remote=$remoteLen asset=${assetBytes.size}），换掉旧看门狗")
            }
            // 旧实现只 kill pidfile 里的 pid；pid 文件过期时旧实例仍持锁，新实例会
            // 因 mkdir 锁失败直接退出，表现为“升级了但看门狗还是旧逻辑”。
            clearStale(app, "upgrade")
        }

        // 能走到写入路径，说明当前没有可用的在跑实例（脚本一致时会提前 return）。
        // 上一轮异常退出可能留下 pid/lock；不清理的话新实例会在 mkdir 锁处直接退出，
        // 表现为“脚本已写入，但看门狗永远起不来”。
        ShellManager.exec(app, "rm -f $PIDFILE; rm -rf /data/local/tmp/ls-watchdog.lock", 4_000L)

        // 4.9 KB 的脚本 base64 后约 6.5 KB，超过 adbd 单条 OPEN 的安全上限（实测 8000 字节超时），
        // 必须走分块写入（每片 2048，独立文件再 cat 拼接）。
        // 写分片是十几次独立 exec，与 NavProbe 每秒的 screencap 抢同一条 adblib 通道，
        // 现场出现过 adbd 直接拒绝新 stream（"Stream open actively rejected by remote peer"）。
        // 单次失败不代表脚本坏了，探活一次再重试，最多 3 轮。
        // 1.4.108：写入前先清掉**任意 pid** 留下的旧分片。
        // 事故链：上次写入中断留下 .part.NNNN → 本次拼接混入旧内容 → 期望 9095 实际 9863
        // → 看门狗脚本一直是坏的 → 实例起不来 → 熄火唤醒后无人硬恢复。
        ShellManager.exec(app, "rm -f " + REMOTE + ".tmp* " + BOOT_SH + ".tmp*", 5_000L)
        var w: ShellOutput? = null
        for (attempt in 1..3) {
            w = ShellManager.writeFileChunked(app, script, REMOTE, mode = "755")
            if (w.success) break
            LsLog.w(TAG, "写入看门狗脚本失败#" + attempt + " exit=" + w.exitCode + " out=" + w.stdout.take(120))
            ShellManager.exec(app, "echo probe", 4_000L)
            Thread.sleep(1_500L)
        }
        if (w == null || !w.success) {
            stateText = "写入脚本失败（exit=" + (w?.exitCode ?: -1) + "）"
            return stateText
        }

        // 启动分两步：先把启动动作落成独立脚本，再 exec 一条最简单的 `sh <脚本>`。
        // 见 [bootScript] 里的真车记录——一步式串在 adblib 里必超时。
        // 引导脚本写不进去时才退回原来的一步式串（已知在真机上会超时，仅作兜底）。
        val boot = runCatching {
            ShellManager.writeFileChunked(app, bootScript(), BOOT_SH, mode = "755")
        }.getOrNull()
        if (boot == null || !boot.success) {
            LsLog.w(TAG, "引导脚本落盘失败（exit=" + (boot?.exitCode ?: -1) + "），退回单条 exec")
        } else {
            // 落盘"成功"不等于文件存在：adblib 下 chmod && mv 这类复合串可能 exit=0 但什么都没干，
            // 现场就出现过 boot 脚本凭空消失、后面 sh 直接报 No such file 的情况。写一次就量一次。
            val size = ShellManager.exec(app, "wc -c < " + BOOT_SH + " 2>/dev/null", 4_000L)
            LsLog.i(TAG, "引导脚本 落盘 exit=" + boot.exitCode + " size=" + size.stdout.trim())
        }

        var ok = false
        for (attempt in 1..2) {
            val rr = if (boot != null && boot.success) {
                ShellManager.exec(app, "/system/bin/sh " + BOOT_SH, 8_000L)
            } else {
                ShellManager.exec(app, "/system/bin/sh -c " + Q + "setsid /system/bin/sh " + REMOTE + " loop " +
                        "</dev/null >/dev/null 2>&1 & echo LS_STARTED" + Q, 6_000L)
            }
            LsLog.i(TAG, "拉起#" + attempt + " exit=" + rr.exitCode + " out=" + rr.stdout.trim().take(200))
            // 脚本自己是 exit 0 的，给内核一点时间把后台进程挂到 init 下再判活。
            Thread.sleep(1_500L)
            if (isRunning(app)) { ok = true; break }
            LsLog.i(TAG, "第 " + attempt + " 次拉起后未检测到进程")
        }

        stateText = if (ok) "运行中" else "未运行（引导脚本未能拉起）"
        LsLog.i(TAG, "看门狗 ${if (ok) "已启动" else "启动失败"} · reason=$reason")
        return stateText
    }

    /**
     * 清掉所有旧看门狗实例、pid 文件和锁。
     * 一条 shell 里扫 /proc 逐个 grep 会 fork 上千次 → adblib exec 直接硬超时；
     * 命令里带单引号也会被外层 sh -c 的引号配对搞坏。所以 Kotlin 侧解析 ps，再一条 kill。
     */
    private suspend fun clearStale(app: Context, reason: String) {
        val ps = ShellManager.exec(app, "ps -ef", 6_000L)
        val pids = ps.stdout.lineSequence()
            .filter { it.contains("ls-watchdog.sh") && !it.contains("grep") }
            .mapNotNull { line -> line.trim().split(Regex(" +")).getOrNull(1)?.toIntOrNull() }
            .filter { it > 0 }
            .toList()
        if (pids.isNotEmpty()) {
            val r = ShellManager.exec(app, "kill -9 " + pids.joinToString(" ") + " 2>/dev/null", 6_000L)
            LsLog.w(TAG, "看门狗 清理残留 pids=" + pids + " exit=" + r.exitCode + " reason=" + reason)
        } else {
            LsLog.i(TAG, "看门狗 无残留实例 reason=" + reason)
        }
        ShellManager.exec(app, "rm -f " + PIDFILE + "; rm -rf /data/local/tmp/ls-watchdog.lock", 4_000L)
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            for (b in digest) append(String.format("%02x", b.toInt() and 0xFF))
        }
    }

    /**
     * 启动看门狗的引导脚本内容。
     *
     * 为什么必须落成脚本文件：真车 1.2.26 实测，把 `sh -c 'setsid … & echo LS_STARTED'`
     * 直接塞进 adblib 的 exec 一律超时，并把刚拉起的常驻进程一起带走（日志只有
     * 「看门狗 启动失败 · reason=boot」）；同样一行在 `adb shell` 里立刻成功。
     * 差别是 adblib 的 exec 走 OPEN shell: 服务，宿主 sh 会一直等这个后台作业。
     * 落成脚本后由新起的 sh 解释、自己 exit 0，adblib 的 stream 立即拿到退出码；
     * 后台进程靠 setsid/nohup 加全 fd 重定向脱钩。
     */
    private fun bootScript(): String =
        "#!/system/bin/sh\n" +
        // 子 shell + 后台 = 双 fork 守护化：子 shell 立刻 exit，常驻进程被 init 收养，
        // 不再挂在 adblib 那条 shell 会话的进程树上——那正是它随 stream 关闭被一起带走的原因。
        // 每一步都写 boot 日志：adblib 下 App 侧拿不到 stdout（实测 out 恒为空）。
        "LOG=/data/local/tmp/ls-watchdog-boot.log\n" +
        "echo \"boot \$(date +%s) pid=\$\$\" >> \$LOG 2>/dev/null\n" +
        "( /system/bin/setsid /system/bin/sh $REMOTE loop </dev/null >/dev/null 2>&1 & echo \"spawned p=\$!\" >> \$LOG 2>/dev/null )\n" +
        // sleep 1 不能删：adbd 在 shell 会话结束时清理这条会话的进程树，
        // 子进程必须在这 1 秒里完成 setsid + exec 才躲得过去。去掉后实测 spawn 出来立刻被带走
        // （boot 日志有 spawned p=…，ps 却是 NONE）。ps 那行已去掉，它才是把 exec 拖到超时的原因。
        "sleep 1\n" +
        "exit 0\n"

    /** 判断看门狗是否在跑。pid 文件优先（ps 的 COMM 是 sh，看不到脚本名）。 */
    suspend fun isRunning(ctx: Context): Boolean {
        val app = ctx.applicationContext
        val pid = ShellManager.exec(app, "cat $PIDFILE 2>/dev/null", 4_000L).stdout.trim()
        if (pid.isNotEmpty() && pid.all { it.isDigit() }) {
            // kill -0 只能证明该 pid 存在，pid 可能已被别的进程复用；必须核对 cmdline
            val alive = ShellManager.exec(
                app,
                "kill -0 $pid 2>/dev/null && grep -q ls-watchdog /proc/$pid/cmdline 2>/dev/null && echo ALIVE",
                4_000L
            )
            if (alive.stdout.contains("ALIVE")) return true
        }
        val r = ShellManager.exec(app, "ps -A -o PID,ARGS 2>/dev/null | grep " + Q + "[l]s-watchdog.sh" + Q, 5_000L)
        return r.stdout.contains("ls-watchdog.sh")
    }

    private const val Q = "\u0027"
}
