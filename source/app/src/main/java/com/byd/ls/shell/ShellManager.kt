package com.byd.ls.shell

import android.content.Context
import com.byd.ls.util.LsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * shell 通道选择与缓存（1.2.22 起只剩两路）。
 *
 *   1. [AdbLibChannel]  内置 ADB 客户端 → 127.0.0.1:5555（唯一特权通道）
 *   2. [LocalChannel]   App 自身 UID，只能查询，**不是**特权通道
 *
 * 1.2.22 按现场要求做了收敛：
 *  · Shizuku 通道与 ShizukuCompat 反射层整体删除，编译期依赖 dev.rikka.shizuku:aidl 一并移除；
 *  · 自研 AdbTcpChannel（裸协议客户端）整体删除，ADB 只保留 adblib 一路；
 *  · Helper（UID 2000 常驻进程）不再是独立选择项：它现在只是 adblib 通道上的一个加速壳，
 *    adblib 连不上时不再拿它兜底（现场要的是"一路"，不是"多路兜底"）。
 *
 * 为什么只留一路更稳：多路并存时每一路都要探活，adb 端点被多路各自建连还会互相把
 * adbd 的待授权槽位占满（同机迪加/迪友就授权不上了）。收敛后只有 adblib 建连，
 * 且 AdbKeepAlive 统一节流 + 空闲回收。
 */
object ShellManager {

    private const val TAG = "ShellManager"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private var adbLibChannel: AdbLibChannel? = null
    private val local = LocalChannel()

    @Volatile private var cached: ShellChannel? = null
    /** local 兜底通道最多缓存 5s；之后重新探测 adblib */
    @Volatile private var lastLocalProbeAt = 0L

    /** App 启动时异步预热，让第一次手势不必等探测 */
    fun warmup(ctx: Context) {
        scope.launch {
            runCatching { channel(ctx) }
                .onSuccess { LsLog.i(TAG, "预热完成，当前通道=${it.displayName} privileged=${it.isPrivileged()}") }
                .onFailure { LsLog.w(TAG, "预热失败: ${it.message}") }
        }
    }

    /** 唯一特权通道：adblib */
    fun adbLibChannel(): AdbLibChannel =
        adbLibChannel ?: AdbLibChannel().also { adbLibChannel = it }

    /** 取当前最优通道；已缓存且仍可用则直接返回 */
    @Suppress("UNUSED_PARAMETER")
    suspend fun channel(ctx: Context): ShellChannel = mutex.withLock {
        cached?.let { c ->
            if (c is LocalChannel) {
                if (System.currentTimeMillis() - lastLocalProbeAt < 5_000L) return@withLock c
                LsLog.i(TAG, "local 兜底缓存超过 5s，重新探测")
                cached = null
            } else if (c.isReady()) {
                return@withLock c
            } else {
                LsLog.i(TAG, "缓存通道 ${c.displayName} 已失效，重新探测")
                safeClose(c)
                cached = null
            }
        }
        probeLocked(ctx)
    }

    /** 强制重新探测（用户点了"一键启动"或通道出错时调用） */
    @Suppress("UNUSED_PARAMETER")
    suspend fun reprobe(ctx: Context): ShellChannel = mutex.withLock {
        cached?.let { safeClose(it) }
        cached = null
        lastLocalProbeAt = 0L
        probeLocked(ctx, userInitiated = true)
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun probeLocked(ctx: Context, userInitiated: Boolean = false): ShellChannel {
        // 唯一特权通道：adblib → 127.0.0.1:5555。
        val lib = adbLibChannel()
        // 退避只管**自动**重连（探活/热路径），不能管用户主动触发的探测：
        // 现场「一键启动」点下去先 reprobe，若正撞在 5/10/20s 退避窗口里就直接降级 local，
        // 按钮停在「连接权限通道…」直到 25s 超时 —— 用户看到的就是"点了没反应"。
        // 所以 userInitiated=true 时清掉退避，立刻真连一次。
        if (userInitiated) AdbKeepAlive.resetBackoffForRetry()
        if ((userInitiated || AdbKeepAlive.mayConnect()) &&
            withContext(Dispatchers.IO) { lib.connect() }
        ) {
            AdbKeepAlive.mark(true)
            LsLog.i(TAG, "选用内置 ADB（adblib）通道 ${lib.endpoint()}")
            return lib.also { cached = it }
        }
        LsLog.w(TAG, "adblib 不可用：${lib.lastDetail}（退避 ${AdbKeepAlive.backoffMs}ms）")
        // 1.4.119：端口关了就顺手自愈一次（写 adb_wifi_enabled=1），结论进日志；冷却 2 分钟。
        // 以前这里直接降级 local，端口一关就永久没有特权通道，只能人工上车开开发者选项。
        runCatching { AdbNetRestore.ensure(ctx) }.onFailure { LsLog.w(TAG, "adb 自愈异常：" + it.message) }

        // 兜底：本机 exec（无 input/settings 特权）
        LsLog.w(TAG, "无特权通道可用，降级到本机 exec（无法注入按键）")
        lastLocalProbeAt = System.currentTimeMillis()
        return local.also { cached = it }
    }

    private fun safeClose(c: ShellChannel) { runCatching { c.close() } }

    fun currentAdbEndpoint(): String = adbLibChannel().endpoint()

    /**
     * 分块写文件：给命令本身就超长的场景用（base64 大脚本、dex 之类）。
     *
     * adb 的 OPEN 帧载荷受 adbd 的 maxdata 限制（本机实测 4096 字节）。把整段 base64
     * 塞进一条 echo 加 base64 的命令里时，OPEN 的 service 串直接超限，adbd 回 CLSE，
     * lib 抛 Stream open actively rejected by remote peer。
     * 实测：OPEN 载荷 100/1000/4000 字节都能过，8000 字节直接超时。
     *
     * 做法：切分成不超过 CHUNK_SIZE 的分片，先清空目标文件，再逐片 append，最后 chmod。
     */
    suspend fun writeFileChunked(
        ctx: Context,
        content: String,
        targetPath: String,
        chunk: Int = CHUNK_SIZE,
        mode: String = "755"
    ): ShellOutput {
        val bytes = content.toByteArray()
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        // 先写临时文件，全部成功后再 mv 覆盖目标：
        // 中途失败时目标文件还是上一版完整内容，不会留下半截二进制（helper dex 被写坏过）。
        // 带 pid 后缀：主进程与 :guard 是独立进程，各自 Mutex 互不可见，共用同一个 tmp 会互删分片。
        val tmp = "$targetPath.tmp." + android.os.Process.myPid()

        // 与 [writeBinaryChunked] 同一套策略：每片独立文件 + 最后 cat 拼接，
        // 绝不使用 >> 追加（adblib 的 stream 竞态会让同一条命令被执行两次，追加就重复）。
        val part = "$tmp.part"
        // 1.4.108：连**别的 pid** 留下的旧分片一起清。现场事故：上次写入中断留下 .part.NNNN，
        // 重写时被拼进目标（期望 9095 实际 9863），看门狗脚本一直是坏的、实例起不来。
        exec(ctx, "rm -f '$targetPath'.tmp* '$part'.* '$tmp'", 6_000L)
        var off = 0
        var idx = 0
        while (off < b64.length) {
            val end = minOf(off + chunk, b64.length)
            // 零填充序号：toybox 的 ls 没有 -v，靠字典序排序就必须等宽（part.010 > part.002）
            val name = part + "." + idx.toString().padStart(4, '0')
            // 必须用字符串拼接；写成模板 $piece 会变成字面量 piece，写出乱码
            val cmd = "printf '%s' '" + b64.substring(off, end) + "' | base64 -d > '$name'"
            val r = exec(ctx, cmd, 10_000L)
            LsLog.i(TAG, "分片 $idx -> exit=${r.exitCode} out=${r.stdout.take(80).replace("\n", "⏎")}")
            if (!r.success) {
                exec(ctx, "rm -f '$part'.*", 4_000L)
                return r
            }
            off = end
            idx++
        }
        // 拼接：**一条 cat 一条 exec**，不发复合语句（for/do/done）。
        // adblib 的 service 串里带 shell 语法结构时真车上会静默不执行（exit=0 但什么也没做），
        // 现场表现就是"分片都写好了，cat 却拼不出文件"。简单命令最稳。
        val names = (0 until idx).joinToString(" ") { i -> "'" + part + "." + i.toString().padStart(4, '0') + "'" }
        val cat = exec(ctx, "cat " + names + " > '$tmp'", 15_000L)
        if (!cat.success) {
            exec(ctx, "rm -f '$part'.* '$tmp'", 4_000L)
            LsLog.w(TAG, "分片拼接失败：" + cat.stdout.take(160))
            return cat
        }
        exec(ctx, "rm -f '$part'.*", 4_000L)
        // 校验：字节数必须与源内容一致，否则宁可失败也不落地一个坏文件
        val want = content.toByteArray().size
        val sizeOut = exec(ctx, "wc -c < '$tmp'", 5_000L)
        val got = sizeOut.stdout.trim().toIntOrNull()
        if (got != want) {
            exec(ctx, "rm -f '$tmp'", 4_000L)
            return ShellOutput("分块写校验失败：期望 $want 字节，实际 ${got ?: "读取失败（${sizeOut.stdout.trim()}）"}", -1)
        }
        val mv = exec(ctx, "chmod $mode '$tmp' && mv -f '$tmp' '$targetPath'", 5_000L)
        return mv
    }

    /**
     * 二进制版本的分块写（dex / 图片 / 任何非文本内容）。
     *
     * 为什么不直接复用 [writeFileChunked]：那条走的是 `content.toByteArray()`，
     * 传入 String 时二进制早已被 UTF-8 编码破坏（helper dex 就这么坏过一次）。
     * 这里直接对原始 ByteArray 做 base64，字节完全原样。
     */
    suspend fun writeBinaryChunked(
        ctx: Context,
        bytes: ByteArray,
        targetPath: String,
        chunk: Int = CHUNK_SIZE,
        mode: String = "644"
    ): ShellOutput {
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        // 每次调用用**独立临时目录**：主进程与 :guard 都是独立进程，
        // 各自的 Mutex 拦不住对方。共用一个 "$targetPath.tmp.part" 时两边会互相删对方的
        // 分片（现场日志：两个 pid 同时写 part.0..13，然后 cat 报 part.0002 不存在）。
        // 用 pid 后缀隔离，各写各的，最后 mv 覆盖目标（mv 是原子的，谁后到算谁）。
        val tmp = "$targetPath.tmp." + android.os.Process.myPid()
        // 【为什么不用 >> 追加】
        // adblib 下每条 exec 的 stream 存在 CLSE/OKAY 竞态，同一条命令可能被执行两次
        // （现场：追加型写入把 5364 字节写成 8436 / 7284 / 3060 这种"多出若干片"的长度）。
        // 改成每片写**独立文件**（> 覆盖，天然幂等：重复执行最多覆盖同内容），
        // 最后用 cat 按序号拼接。这样即使某片被跑两次，结果也不会重复。
        val part = "$tmp.part"
        exec(ctx, "rm -f '$part'.* '$tmp'", 6_000L)
        var off = 0
        var idx = 0
        while (off < b64.length) {
            val end = minOf(off + chunk, b64.length)
            // 零填充序号：toybox 的 ls 没有 -v，靠字典序排序就必须等宽（part.010 > part.002）
            val name = part + "." + idx.toString().padStart(4, '0')
            // printf '%s' 而不是 echo：echo 在部分 toybox 上会把 -n/-e 当选项吃掉字符
            val cmd = "printf '%s' '" + b64.substring(off, end) + "' | base64 -d > '$name'"
            val r = exec(ctx, cmd, 10_000L)
            LsLog.i(TAG, "分片 $idx -> exit=${r.exitCode} out=${r.stdout.take(80).replace("\n", "⏎")}")
            if (!r.success) {
                exec(ctx, "rm -f '$part'.*", 4_000L)
                return r
            }
            off = end
            idx++
        }
        // 拼接：一条 cat 一条 exec，绝不发 for/do/done 这类复合语句。
        // adblib 的 service 串里带 shell 控制结构时真车上会静默不执行（exit=0 但什么都没发生），
        // 现场表现就是"14 个分片都写好了，cat 却拼不出文件"。简单命令最稳。
        exec(ctx, "rm -f '$tmp'", 5_000L)
        // 1.4.108：一条 cat（> 截断）替代逐片 >> 追加，避免 adblib 重复执行把分片写两遍
        val names = (0 until idx).joinToString(" ") { i -> "'" + part + "." + i.toString().padStart(4, '0') + "'" }
        val cr = exec(ctx, "cat " + names + " > '$tmp'", 15_000L)
        if (!cr.success) {
            exec(ctx, "rm -f '$part'.* '$tmp'", 4_000L)
            LsLog.w(TAG, "分片拼接失败：" + cr.stdout.take(160))
            return cr
        }
        exec(ctx, "rm -f '$part'.*", 4_000L)

        val sizeOut = exec(ctx, "wc -c < '$tmp'", 5_000L)
        val got = sizeOut.stdout.trim().toIntOrNull()
        if (got != bytes.size) {
            exec(ctx, "rm -f '$tmp'", 4_000L)
            return ShellOutput("分块写校验失败：期望 ${bytes.size} 字节，实际 ${got ?: sizeOut.stdout.trim()}", -1)
        }
        return exec(ctx, "chmod $mode '$tmp' && mv -f '$tmp' '$targetPath'", 5_000L)
    }

    /**
     * 分块大小。
     *
     * 从 2048 降到 512：adblib 的 open() 在 service 串偏长时（>2KB 起）会在真车上丢字节/重复字节，
     * 实测 2048 分片会写出 8436 或 756 字节这种完全对不上的长度，而同样的命令走 pc 端 adb 是对的。
     * 512 分片后 service 串约 600 字节，远低于 adbd maxdata，稳定。
     * 代价只是分片数变多（一个 5KB dex 约 14 次 exec），这是一次性安装，可以接受。
     */
    // 1.4.120：512 → 2000。512 时 6.5 KB 的 base64 要 13 条 printf 才写完，每条都新开一个
    // adbd shell stream —— 现场 `Stream open actively rejected by remote peer` 的流量大头就是这里。
    // adblib 握手 maxdata=4096，单条命令 2000+60 字节仍安全；分片数降到 4。
    private const val CHUNK_SIZE = 2000

    /** 失败路径重探的最小间隔（1.4.9：防止采样失败触发建连风暴，见 docs/03 §7） */
    private const val AUTO_REPROBE_MIN_GAP_MS = 5_000L
    @Volatile private var lastAutoReprobeAt = 0L

    /**
     * 1.4.126：全局串行化。实车 19:56 的证据 —— fid 轮询、看门狗、nav 采集拉起、dex 校验
     * 同时各开一条 adbd shell stream，adbd 直接回 "Stream open actively rejected by remote peer"，
     * 一次失败又触发退避闸门，把后面所有命令（包括 lswatch.sh 写入）一起跳过，
     * 表现成 "navwatch 拉起 = false (空)"。adbd 的 shell 槽位本来就只有几条，
     * 串行排队比并发抢更划算：一条命令几十毫秒，排队代价远小于重连+退避的代价。
     */
    private val execLock = kotlinx.coroutines.sync.Mutex()

    /** 执行一条命令。失败会自动重探一次，容忍 adbd 重启的窗口期 */
    suspend fun exec(ctx: Context, cmd: String, timeoutMs: Long = 8_000L, bestEffort: Boolean = false): ShellOutput =
        execLock.withLock { execSerial(ctx, cmd, timeoutMs, bestEffort) }

    private suspend fun execSerial(ctx: Context, cmd: String, timeoutMs: Long, bestEffort: Boolean): ShellOutput {
        // adblib 挂起后 isReady() 仍为 true，不探活会拿一条已死的 socket 去跑命令，白等一个超时
        keepAliveIfNeeded()
        val ch = channel(ctx)
        // 硬超时：adblib 的 exec 在 adbd 槽位被占死时会永久阻塞，
        // 而它一卡住就同时持有通道与互斥，RPC（连 ping）都进不来（真车 17:3x 实测）。
        val first = kotlinx.coroutines.withTimeoutOrNull(timeoutMs + 3_000L) {
            ch.exec(cmd, timeoutMs, bestEffort)
        } ?: run {
            LsLog.w(TAG, "exec 硬超时（" + timeoutMs + "ms），关闭通道: " + cmd.take(60))
            runCatching { safeClose(ch) }
            cached = null
            // 1.4.120：bestEffort 退出时不占「5s 重探闸门」—— 一次慢截屏不该让接下来
            // 5 秒内的所有命令直接返回失败（实车 129 次/70s 的主因）。
            return ShellOutput("", -1)
        }
        if (first.exitCode != -1) return first
        // bestEffort：慢命令失败就失败，不重探、不占闸门（1.4.120）。
        if (bestEffort) return ShellOutput("", -1)
        // 超时/异常：必须先把这条通道**关掉**再重探。
        // adblib 的 socket 一旦被不退出子进程占住（例如 `logcat -d -s TAG:*` 在真机上不返回），
        // isReady() 仍为 true，重探会拿到同一条死连接 —— 1.2.29 就是这样把整条特权通道拖死的
        // （之后连 echo 都超时，手势注入与卡片判据一起瘫）。
        // 【1.4.9】失败路径限频：旧实现在每次 exitCode=-1 时都 safeClose+reprobe，
        // 而卡片采样每 2s 一次 sustains 偶发失败 → 变成 2.2s 一轮的「建连+握手」风暴
        // （ls.log 实测，见 docs/03 §7 遗留问题）。5s 内只允许一次重探，其余直接失败返回，
        // 交给采样自己的 5s 退避；下一次 exec 若缓存已清会自然重建。
        val now = System.currentTimeMillis()
        if (now - lastAutoReprobeAt < AUTO_REPROBE_MIN_GAP_MS) {
            // 1.4.120：这条是「重探冷却」而不是通道真的坏了，改成 I 级并换措辞 ——
            // 原来用 W「通道执行失败」把它和真实故障混在一起，复验断言也跟着误判（1 次真实失败刷出几十条）。
            LsLog.i(TAG, "重探冷却中（跳过本次）: " + cmd.take(50))
            return ShellOutput("", -1)
        }
        lastAutoReprobeAt = now
        runCatching { safeClose(ch) }
        cached = null
        LsLog.w(TAG, "通道 ${ch.displayName} 执行失败，已关闭并重探后重试一次")
        val ch2 = reprobe(ctx)
        return ch2.exec(cmd, timeoutMs, bestEffort)
    }


    /**
     * 探活 + 自愈。仅在当前通道是 adblib 且已到 14s 门限时真正发一次 echo。
     *
     * 返回 true 表示通道可用。任何异常都不抛出——调用方是手势热路径，
     * 所以故意不收 Context 参数（探活路径上不需要它，也不该在这里碰 SharedPreferences）。
     */
    suspend fun keepAliveIfNeeded(): Boolean {
        val lib = adbLibChannel
        if (lib == null || cached !== lib || !lib.isReady()) return cached?.isReady() == true
        return runCatching { AdbKeepAlive.probeAndHeal(lib) { lib.connect() } }
            .getOrElse { e ->
                LsLog.w(TAG, "adblib 探活异常: " + (e.message ?: e.javaClass.simpleName))
                false
            }
    }

    /** 界面/流程用的「当前通道」快照：不触发探测，拿不到就返回 local（只读，无特权） */
    fun localChannelForDisplay(): ShellChannel = cached ?: local

    fun currentChannelName(): String = when {
        cached != null -> cached!!.displayName
        adbLibChannel?.isReady() == true -> adbLibChannel!!.displayName
        else -> "未探测"
    }
    fun currentChannelId(): String = when {
        cached != null -> cached!!.id
        adbLibChannel?.isReady() == true -> adbLibChannel!!.id
        else -> "none"
    }
    /**
     * 是否有特权通道。
     *
     * 1.2.22 修正：以前只看 [cached]，而 cached 只在 [channel]/[reprobe] 里赋值。
     * 界面 onResume 刷新状态时如果预热还没跑完，就会显示「已授权 / 特权=否」
     * 这种自相矛盾的状态 —— 现场截图里正是它。这里在 cached 为空时直接问
     * [AdbLibChannel.isReady]（它就是通道的真实健康口径），口径与 [adbAuthState] 一致。
     */
    fun isPrivileged(): Boolean = cached?.isPrivileged() == true ||
        adbLibChannel?.isReady() == true

    /** 内置 ADB 握手状态（未授权时界面据此给真实引导，不误导用户等弹窗） */
    fun adbAuthState(): AdbAuthState {
        val lib = adbLibChannel
        if (lib != null && lib.isReady()) return AdbAuthState.AUTHORIZED
        return if (AdbEndpoint.probe()) AdbAuthState.TCP_CONNECTED else AdbAuthState.PORT_CLOSED
    }

    /** 界面显示用的完整状态 */
    fun statusLines(): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        m["内置 ADB 端点"] = currentAdbEndpoint()
        val state = when (adbAuthState()) {
            AdbAuthState.AUTHORIZED -> "已授权"
            AdbAuthState.WAITING_USER -> "等待车机屏幕点「允许」"
            AdbAuthState.UNAUTHORIZED -> "未授权（密钥未被信任）"
            AdbAuthState.PORT_CLOSED -> "端口未监听（5555 未开启）"
            AdbAuthState.CONNECT_TIMEOUT -> "连接/握手超时（端口是否监听未知）"
            AdbAuthState.TCP_CONNECTED -> "已连接，握手未完成"
        }
        val detail = adbLibChannel?.lastDetail?.takeIf { it.isNotBlank() }
        val tail = when {
            detail != null && adbLibChannel?.isReady() == true -> " · $detail"
            !AdbKeepAlive.healthy -> " · 探活失败（自动重建中）"
            else -> ""
        }
        m["ADB 授权"] = state + tail
        // 看门狗状态读缓存，不在刷新路径上发起 shell 调用
        m["shell 看门狗"] = WatchdogInstaller.stateText
        return m
    }

    /** 界面提示语：告诉用户下一步该做什么 */
    fun hint(): String {
        return when {
            isPrivileged() -> "shell 通道就绪，可以执行一键激活。"
            adbAuthState() == AdbAuthState.WAITING_USER ->
                "公钥已发到车机，请立刻看车机屏幕点「允许」；点完回本页按「一键启动 / 修复」确认。"
            adbAuthState() == AdbAuthState.UNAUTHORIZED ->
                "端口已连通，但车机没有信任内置密钥。请点「一键启动 / 修复」并留意车机屏幕弹窗。"
            adbAuthState() == AdbAuthState.CONNECT_TIMEOUT ->
                "内置 ADB 探测超时（${currentAdbEndpoint()} 没回包），端口是否监听未知。" +
                    "仍不行再确认开发者选项里的 USB 调试已开启。"
            adbAuthState() == AdbAuthState.PORT_CLOSED ->
                "127.0.0.1:5555 端口未监听。请先在车机开发者选项开启 USB 调试；" +
                    "或点「打开车机 ADB 设置」自动点开开关。"
            else ->
                "正在探测本地 ADB（127.0.0.1:5555）。采用内置 adblib 通道，不需要安装任何外部 App。"
        }
    }
}
