package com.byd.ls.shell

import com.byd.ls.util.LsLog
import com.tananaev.adblib.AdbConnection
import com.tananaev.adblib.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * 首发特权通道（1.2.21）：基于 com.tananaev:adblib 的内置 ADB 客户端。
 *
 * 本类已经是**唯一**特权通道（1.2.22 删除了自研裸协议客户端与 Shizuku 通道）：
 *  - adblib 走标准 24 字节帧头 + 4096 字节 maxdata，握手/流管理由库的线程负责；
 *  - 库的 AUTH 节奏是「第一轮签名，之后只发公钥」，与迪友线上实现一致；
 *  - 库**没有**健康探针，挂起后 isReady() 仍是 true —— 所以本类自己维护 [healthy]，
 *    由 [AdbKeepAlive] 每 14s 探活来纠正。
 *
 * 关键约束：库里没有 getLocalId/isFine，也不回调退出码，所以 exec 用
 * `"<cmd>; echo __LS_EXIT__=$?"` 拼哨兵，再从输出里剥出退出码。
 *
 * 1.2.23 起不再提供常驻 shell（详见 [KeyEventExecutor] 注释）：adbd 待授权槽位有限，
 * 一条常驻连接会让同机的迪加/迪友授权不上，而手势的正路本来就无条件走无障碍。
 */
class AdbLibChannel(
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    private val cryptoProvider: () -> com.tananaev.adblib.AdbCrypto = { LsAdbCrypto.builtin() }
) : ShellChannel {

    override val id = "adblib"
    override val displayName = "内置 ADB（adblib $host:$port）"

    @Volatile private var conn: AdbConnection? = null
    @Volatile private var healthy = false
    @Volatile var lastDetail: String = "未连接"
        private set

    // 1.4.109：失败静默 —— 通道半死时上层会立刻再来一条命令（status.truth 一次要 3~4 条），
    // 等于反复踩一条必然失败的连接。这里做「失败一次静默 30s，连续两次静默 60s」，
    // 期间所有 exec 直接返回降级文案、不碰 socket，让连接自己恢复。
    @Volatile private var failStreak = 0
    @Volatile private var lastFailAt = 0L
    @Volatile private var lastPassAt = 0L
    // 1.4.120：30s/60s 的静默期太长了 —— 实车表现为「通道失败计数=1，静默 30s」之后
    // 整段时间所有命令（fid 轮询、看门狗、装 helper）全被跳过，日志里 70 秒刷出 275 条
    // 「通道执行失败」的其实是这道闸门自己。adbd 的拒绝是瞬时的，退 8s/20s 足够躲开。
    private val quietMs: Long get() = if (failStreak >= 2) 20_000L else 8_000L

    /**
     * 建连/断开必须互斥。
     *
     * 车机上实测到的两类自伤都源于这里没锁：
     *  1. 两个线程同时 [connect]，后进的那个在开头 [close] 掉先进线程刚建好、正在试开 stream 的连接
     *     → 日志表现为「握手成功」紧跟「试开 stream 失败: Stream closed」，adbd 侧则是
     *     `adb client authorized` 之后立刻 `read failed`；
     *  2. [exec] 的 `finally close()` 关掉别的线程正在用的连接。
     */
    private val connLock = Mutex()

    /** 保护「取连接」这一步；命令本身在锁外跑，避免把耗时命令串行化 */
    private val execLock = Mutex()

    /**
     * 当前有多少条命令正共用 [conn]。
     *
     * 用完即断要求每条命令结束就关连接，但并发命令共用同一条 socket：
     * 先跑完的那条一关，后跑的那条 open() 立刻收到 CLSE
     * （车机表现：`Stream open actively rejected by remote peer`）。
     * 所以关连接前先看计数，归零才真的关。
     */

    /**
     * 「通道可用」而不是「连接正开着」。
     *
     * [exec] 是「用完即断」的：命令跑完就把连接关掉，这是硬要求（adbd 待授权槽位有限）。
     * 如果这里以 conn != null 为准，ShellManager 的缓存每轮都会判失效 → 重新探测 → 重连，
     * 车机上实测成了一分钟连十几次的抖动（日志里刷「缓存通道 ... 已失效，重新探测」）。
     *
     * 所以判定口径改为：按键/命令路径能用（未被标记为坏）+ 退避窗口外。
     * 连接本身的健康由 [probe] 与 [AdbKeepAlive] 负责。
     */
    override fun isReady(): Boolean = healthy && AdbKeepAlive.mayConnect()

    override fun isPrivileged(): Boolean = true

    override suspend fun connect(): Boolean = connect(false)

    /**
     * [force]=true 时先在退避窗口外做一次完整重连：
     * 用户点「允许」之后 adbd 需要**新的 CNXN** 才会放行，复用旧握手结果会一直失败。
     */
    suspend fun connect(force: Boolean): Boolean = connLock.withLock { withContext(Dispatchers.IO) {
        if (force) AdbKeepAlive.resetBackoffForRetry()
        closeLocked()
        runCatching {
            // 用显式 IPv4 的 InetSocketAddress：adbd 在这台车上是双栈监听，
            // 但 127.0.0.1 字符串在多网卡机型上可能被解析成别的地址族。
            val addr = InetSocketAddress(java.net.InetAddress.getByName(host), port)
            LsLog.i(TAG, "adblib 建连中 -> " + addr + "（解析自 " + host + "）")
            val socket = Socket().apply {
                connect(addr, TCP_TIMEOUT_MS)
                soTimeout = 0
                tcpNoDelay = true
            }
            LsLog.i(TAG, "adblib TCP 已建立 local=" + socket.localSocketAddress +
                " remote=" + socket.remoteSocketAddress)
            val c = AdbConnection.create(socket, cryptoProvider())
            // 库内跑完 CNXN/AUTH；未授权时抛 AdbAuthenticationFailedException。
            // abortOnUnauthorised=true：不要干等用户点「允许」，由 AdbBootstrap 走引导流程。
            val ok = c.connect(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS, true)
            if (ok) {
                conn = c
                healthy = true
                AdbKeepAlive.onConnected()
                lastDetail = "adblib 握手成功（maxdata=" + runCatching { c.maxData }.getOrDefault(-1) + "）"
                LsLog.i(TAG, lastDetail)
                // 【不要在这里预开试探 stream】。
                //
                // 之前握手成功后立刻 open("shell:id -u") 做假健康检查，真车上连坐两次事故：
                //  1. 试探 stream 的 CLSE 与接下来真实命令的 OKAY 交错，adblib 的 localId 分配错位，
                //     同一条命令被执行两次（dex 写出 5364×2 = 10728 字节）；
                //  2. 试探 stream 与 exec 的 stream 并存时，后者被 adbd 直接 CLSE，
                //     库抛 "Socket closed" / "Stream open actively rejected by remote peer"。
                // 握手成功（CNXN/AUTH 通过）已经足够证明通道可用；
                // 真实健康由第一条业务命令的退出码判定（exec 失败会自动重连一次）。
                true
            } else {
                lastDetail = "adblib 握手未完成（超时/未回 CNXN）"
                runCatching { c.close() }
                AdbKeepAlive.onFailed()
                LsLog.w(TAG, lastDetail)
                false
            }
        }.getOrElse { e ->
            lastDetail = describe(e)
            // 贴现场：真实原因是「端口在哪一层失败」——TCP 建连、还是库内握手。
            // 车机上 127.0.0.1:5555 由 adbd 双栈监听，用 shell 的 nc 能通，
            // 但 App 侧失败时只看一句"拒绝连接"分不清是端口没开还是被 adbd 踢。
            LsLog.w(TAG, "adblib 连接失败: " + lastDetail +
                " | 原始异常=" + e.javaClass.name + ": " + (e.message ?: "") +
                " | cause=" + (e.cause?.let { it.javaClass.name + ": " + it.message } ?: "无") +
                " | 端点=" + endpoint() + " 解析=" + runCatching {
                    java.net.InetAddress.getByName(host).hostAddress
                }.getOrElse { "解析失败:" + it.message })
            runCatching { conn?.close() }
            conn = null
            healthy = false
            AdbKeepAlive.onFailed()
            false
        }
    } }

    /**
     * 执行一条命令。
     *
     * 生命周期按「用完即断」：已有连接就直接用，没有（或已死）就临时建一条，
     * 执行完立刻关掉——adbd 上常态零连接，不占待授权槽位（见 [AdbKeepAlive] 注释）。
     */
    override suspend fun exec(cmd: String, timeoutMs: Long, bestEffort: Boolean): ShellOutput = withContext(Dispatchers.IO) {
        // 1.4.109/111：降级闸门（在加锁之前，失败时零成本返回）。
        // 1.4.111：**不允许饿死** —— 降级期间每 15 秒仍放行一条，否则开机那阵
        // （helper/看门狗脚本/探针一起上）会把闸门踩成"永久静默"，看门狗装不上。
        val nowMs = System.currentTimeMillis()
        if (failStreak > 0 && !bestEffort) {
            val sinceFail = nowMs - lastFailAt
            val sincePass = nowMs - lastPassAt
            if (sinceFail < quietMs && sincePass < 3_000L) {
                // stdout 必须留空：HelperBridge 用 stdout.isNotEmpty() 判成功，塞文案会误报。
                LsLog.i(TAG, "通道退避中（" + (quietMs - sinceFail) + "ms 后放行），跳过: " + cmd.take(40))
                return@withContext ShellOutput("", -1)
            }
        }
        lastPassAt = nowMs
        // 【并发正确性】每条命令独占一条连接，绝不共享。
        //
        // 之前是「多条命令共用一条连接 + inflight 计数」，实测在真车上不稳：
        // 两个 stream 同时 open 时，adbd 会随机用 CLSE 拒掉后到的那条，
        // 库抛 "Stream open actively rejected by remote peer"（HelperInstaller 装 dex 时就踩到）。
        // adblib 的 AdbConnection.openStreams / localId 分配不是为高频并发设计的。
        //
        // 改成「一条命令一条命令」：全进程串行（execLock 覆盖整段），
        // 连接不再分给任何"常驻"用途。
        // 代价是并发命令排队；收益是绝不出现被拒的 stream，dex/脚本这类写入才可靠。
        // 锁必须覆盖「拿连接 → 跑命令 → 关连接」整段，而不是只包拿连接：
        // 只包前半段时，A 拿到连接去跑命令，B 进来看到 conn==null 又建一条，
        // A 结束时把 B 的连接关掉 → 现场就是 "Stream open actively rejected / Socket closed"。
        execLock.withLock {
            if (conn == null || !healthy) {
                if (!AdbKeepAlive.mayConnect()) {
                    return@withLock ShellOutput("adblib 退避中（" + AdbKeepAlive.backoffMs + "ms 后再试）", -1)
                }
                connect()
            }
            val target = conn ?: return@withLock ShellOutput("adblib 未连接：" + lastDetail, -1)
            AdbKeepAlive.touch()
            var ok = true
            try {
                withTimeoutOrNull(timeoutMs) {
                    runCatching { execOnce(target, cmd) }.getOrElse { e ->
                        ok = false
                        LsLog.w(TAG, "exec 失败[" + cmd.take(60) + "]: " + (e.message ?: e.javaClass.simpleName))
                        ShellOutput(e.message ?: "adblib error", -1)
                    }
                } ?: run {
                    ok = false
                    ShellOutput("[timeout after " + timeoutMs + "ms]", -1)
                }
            } catch (t: Throwable) {
                ok = false
                ShellOutput(t.message ?: "adblib error", -1)
            } finally {
                // 【1.4.8 连接复用】正常命令不再断连，交给「空闲 15s 回收」（reapIfIdle）。
                //
                // 旧实现是「每条命令 finally close()」，现场实测代价（docs/03）：
                // 仪表卡片每 2s 一次 screencap → 每 2.25s 重新 TCP 建连 + CNXN/AUTH 握手一轮，
                // 一键启动与采样互相抢 adbd 槽位，用户感知就是「点一键启动要等好几秒」。
                //
                // 只在这个连接已经不可信（异常/超时）时断开并置坏，下一轮重建；
                // 不要在成功路径断连——断连后下一次调用又要建连，等于没改。
                if (!ok) {
                    close()
                    healthy = false
                    // 1.4.120：慢命令超时不毒化全局通道，只影响本次返回。
                    if (bestEffort) {
                        LsLog.i(TAG, "慢命令未完成，不计入通道失败: " + cmd.take(40))
                    } else {
                        failStreak += 1
                        lastFailAt = System.currentTimeMillis()
                        LsLog.w(TAG, "通道失败计数=" + failStreak + "，静默 " + (quietMs / 1000) + "s")
                    }
                } else {
                    if (failStreak != 0) LsLog.i(TAG, "通道恢复（此前连续失败 " + failStreak + " 次）")
                    failStreak = 0
                }
            }
        }
    }

    /**
     * 空闲 reaper：调用方（心跳/手势入口）每隔几秒调一次即可。
     * 正常路径下连接在 exec 结束就关了，这里只兜住「漏关」的情况。
     */
    fun reapIfIdle(now: Long = System.currentTimeMillis()): Boolean {
        if (conn == null) return false
        if (AdbKeepAlive.idleSince(now) <= AdbKeepAlive.IDLE_MS) return false
        LsLog.i(TAG, "空闲断开 adb（不占 adbd 槽位）")
        close()
        return true
    }

    /**
     * 执行一条命令。
     *
     * 【关键】命令通过 **stdin 写入**，而不是拼进 service 串：
     * `open("shell:")  然后 write(cmd + "\n")`。
     *
     * 为什么：把命令拼进 service 串时，adbd 对超过 maxdata（本机 4096）的串会截断/折行，
     * 现场表现是 `echo '<2KB base64>' | base64 -d >> file` 写出 8436 或 756 字节这种
     * 完全对不上的长度。走 stdin 就只受 WRTE 分片限制（库自己按 maxdata 切），字节原样。
     */
    private fun execOnce(c: AdbConnection, cmd: String): ShellOutput {
        // 命令走 service 串（open("shell:<cmd>")），**不走 stdin**。
        //
        // 踩过的坑：adblib 的 open("shell:") 在真车上会拿到带 pty 的会话，
        // 我们 write 进去的命令会被原样回显（wc 的"输出"变成命令本身），连 stty -echo 那行也回显。
        // 所以命令必须在 open 时就交给 adbd，由它直接 exec，不经过 pty 输入。
        //
        // 长度约束：service 串受 adbd maxdata（本机 4096）限制，超了直接 CLSE。
        // 调用方负责把单条命令控制在 [SAFE_CMD_MAX] 以内（分块写已按 2KB 切）。
        val stream = c.open("shell:" + cmd + "; echo __LS_EXIT__=$?")
        return try {
            parseExitSentinel(drain(stream))
        } finally {
            runCatching { stream.close() }
        }
    }

    /**
     * 读干一条 stream 的全部输出。
     *
     * 不能用库的 [AdbStream.read] 做循环：它的字节码是「先 poll 取数据，再判 isClosed，
     * 关了就直接 throw new IOException("Stream closed")」，已取出的那一片跟着被丢掉。
     * 而 adbd 执行完 shell 命令是「先 WRTE、立刻 CLSE」，两者几乎同时到达，
     * 于是真车上的表现就是命令明明执行了、输出却只剩一句 Stream closed。
     *
     * 这里改为反射直接读同一个 readQueue：每轮先把队列排空，再判断是否已关闭，
     * 关闭后仍把剩余数据交出去——数据永远不丢。
     */
    private fun drain(stream: AdbStream): String {
        val queueF = stream.javaClass.getDeclaredField("readQueue").apply { isAccessible = true }
        val closedF = stream.javaClass.getDeclaredField("isClosed").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val queue = queueF.get(stream) as java.util.Queue<ByteArray>
        val sb = StringBuilder()
        try {
            var idleRounds = 0
            while (true) {
                var got = false
                while (true) {
                    val chunk = queue.poll() ?: break
                    got = true
                    sb.append(String(chunk))
                }
                if (closedF.getBoolean(stream)) {
                    // 关闭后再排空一次，收掉与 CLSE 同批到达的尾巴
                    while (true) {
                        val chunk = queue.poll() ?: break
                        sb.append(String(chunk))
                    }
                    break
                }
                if (!got && ++idleRounds > IDLE_SPIN_LIMIT) break
                Thread.sleep(if (got) 1L else DRAIN_POLL_MS)
            }
        } finally {
            runCatching { stream.close() }
        }
        return sb.toString()
    }


    /** 探活：开一条 echo stream，返回 true 表示通道仍可用（[AdbKeepAlive] 依赖它） */
    fun probe(): Boolean {
        val c = conn ?: return false
        val out = runCatching {
            withBlocking {
                val s = c.open("shell:echo '1'")
                drain(s)
            }
        }.getOrElse { e ->
            LsLog.w(TAG, "adblib 探活异常: " + (e.message ?: e.javaClass.simpleName))
            ""
        }
        val ok = out.contains("1")
        healthy = ok
        if (!ok) lastDetail = "探活失败（echo '1' 无回包）"
        return ok
    }

    /**
     * 断开当前连接，但**不把通道判死**。
     *
     * 用完即断是常态（每条命令跑完都关一次），若顺手把 [healthy] 置 false，
     * 上层就会认为通道坏了而去重新探测、重连，形成抖动。
     * 真正判死只发生在 [connect] 失败与 [probe] 失败这两处。
     */
    override fun close() {
        val c = conn
        conn = null
        runCatching { c?.close() }.onFailure { LsLog.w(TAG, "关闭 adblib 连接失败: " + it.message) }
    }

    /** [connLock] 内调用：不重复取锁（Kotlin Mutex 不可重入） */
    private fun closeLocked() = close()

    /** 供界面显示 */
    fun endpoint(): String = host + ":" + port

    private fun describe(e: Throwable): String = when (e) {
        is com.tananaev.adblib.AdbAuthenticationFailedException ->
            "adbd 拒绝该 key（未授权）；请用「申请 ADB 授权」把公钥发到车机"
        is java.net.ConnectException ->
            "端口未监听（" + endpoint() + " 拒绝连接）—— 需要在车机上开启 ADB tcpip"
        is java.net.SocketTimeoutException ->
            "握手超时（" + endpoint() + " 没回包，端口是否监听未知）"
        is IOException -> "IO 异常: " + (e.message ?: e.javaClass.simpleName)
        else -> e.javaClass.simpleName + ": " + (e.message ?: "")
    }

    /** 阻塞读包没有超时参数，套一层线程 + join 兜底，避免探活永久挂起 */
    private fun <T> withBlocking(timeoutMs: Long = PROBE_TIMEOUT_MS, body: () -> T): T {
        var result: Any? = null
        var error: Throwable? = null
        val t = Thread {
            try { result = body() } catch (e: Throwable) { error = e }
        }.apply { isDaemon = true; name = "adblib-probe"; start() }
        t.join(timeoutMs)
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return (result as? T) ?: throw IOException("探活超时 " + timeoutMs + "ms")
    }

    companion object {
        private const val TAG = "Ling_ADB"
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 5555
        private const val TCP_TIMEOUT_MS = 3_000
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val PROBE_TIMEOUT_MS = 4_000L
        private const val EXIT_SENTINEL = "__LS_EXIT__="

        /** 单条命令（service 串）安全上限：adbd maxdata 4096，留出 "shell:" 与哨兵尾巴余量 */
        const val SAFE_CMD_MAX = 3_400

        /** 排空 readQueue 时，连续这么多轮都没有新数据就认为输出已经结束 */
        private const val IDLE_SPIN_LIMIT = 200


        /** 单测入口：验证排空逻辑不丢「与 CLSE 同批到达」的输出 */
        internal fun invokeDrain(stream: AdbStream): String = AdbLibChannel().drain(stream)

        /** 排空等待粒度：adbd 的 WRTE/CLSE 几乎同批到达，1ms 足够又不烧 CPU */
        private const val DRAIN_POLL_MS = 1L

        /**
         * 解析 exec 的退出码哨兵。
         *
         * adblib 的流只回 stdout 正文，没有 exit code 通道，所以命令末尾拼
         * echo __LS_EXIT__$? 由这里剥掉。
         */
        fun parseExitSentinel(raw: String, echoOf: String? = null): ShellOutput {
            var code = -1
            val sb = StringBuilder()
            // 用 split 而不是 lineSequence：末尾的 "\n" 若也走 lineSequence 会被补成空行，
            // 输出就会多出一个换行（单测踩过：expected<just output\n> but was<just output\n\n>）。
            val body = raw.trimEnd('\n')
            if (body.isNotEmpty()) {
                for (line in body.split('\n')) {
                    if (line.startsWith(EXIT_SENTINEL)) {
                        code = line.removePrefix(EXIT_SENTINEL).trim().toIntOrNull() ?: -1
                    } else if (echoOf != null && line.trim() == echoOf.trim()) {
                        // tty 回显，丢掉
                    } else {
                        sb.append(line).append('\n')
                    }
                }
            }
            return ShellOutput(sb.toString(), code)
        }
    }
}
