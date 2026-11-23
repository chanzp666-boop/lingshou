package com.byd.ls.ac

import android.content.Context
import android.util.Base64
import android.util.Log
import com.byd.ls.shell.ShellManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 三指空调功能层：ADB 通道 → 部署/拉起 shell 助手 → 读三指轨迹 → 写空调。
 *
 * 生命周期跟 GlobalGestureService 同口径：手势服务在，它就按开关运行；
 * 助手断开/启动失败会自己退避重连，不依赖用户重新点开关。
 */
object AcFeature {

    private const val TAG = "AcFeature"
    private const val HELPER = "/data/local/tmp/ls-finger-touch"
    private const val HELPER_LOCK = "/data/local/tmp/ls-finger-touch.lock"
    private const val HELPER_ASSET = "finger-touch"
    private const val PART = "/data/local/tmp/ls-fh.part."

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startJob: Job? = null
    private var server: ServerSocket? = null
    private var socket: Socket? = null
    private var reader: Thread? = null
    private var appCtx: Context? = null
    private val engine = GestureEngine()
    private var hudAxis: GestureEngine.Kind? = null
    private var tempSyncJob: Job? = null

    @Volatile private var desired = false
    @Volatile var running = false
        private set

    @Volatile var status = "未启动"
        private set

    @Synchronized
    fun setEnabled(ctx: Context, on: Boolean) {
        desired = on
        appCtx = ctx.applicationContext
        if (on) startAsync() else stop()
    }

    @Synchronized
    private fun startAsync() {
        if (running || startJob?.isActive == true) return
        val ctx = appCtx ?: return
        startJob = scope.launch {
            val err = runCatching { startOnce(ctx) }
                .getOrElse { "启动异常：${it.message ?: it.javaClass.simpleName}" }
            if (err == null && desired) {
                running = true
                status = "运行中（三指步进）"
                startReader(ctx)
            } else if (!desired) {
                status = "已停止"
            } else {
                status = err ?: "启动失败"
                Log.w(TAG, "启动失败：$err")
                if (desired) {
                    scope.launch {
                        delay(8_000L)
                        startJob = null
                        if (desired) startAsync()
                    }
                }
            }
        }
    }

    private suspend fun startOnce(ctx: Context): String? {
        status = "准备空调通道…"
        AcSdk.init(ctx)
        AcTickPlayer.init(ctx)
        val hudReady = AcHud.init(ctx)
        if (!hudReady) Log.w(TAG, "悬浮窗权限缺失，HUD 不显示（手势仍可用）")

        if (!pushHelper(ctx)) return "推送助手失败：$lastPushError"

        server?.close()
        server = runCatching { ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")) }.getOrNull()
        val port = server?.localPort ?: 0
        if (port <= 0) return "监听本地端口失败"

        val uid = ctx.applicationInfo.uid
        ShellManager.exec(ctx, "kill -9 \$(pidof ls-finger-touch) 2>/dev/null", 3_000L)
        // adblib 的超时重试可能把同一条 start 命令执行两次；用 flock 让第二个助手立刻退出，
        // 否则会出现两个 ls-finger-touch：一个 grab 成功、另一个只能只读，输入设备还会多一份。
        val start = "( flock -n 9 || exit 0; nohup $HELPER $uid $port </dev/null " +
            ">/data/local/tmp/ls-finger-helper.log 2>&1 & ) 9>'$HELPER_LOCK'; sleep 1"
        ShellManager.exec(ctx, start, 8_000L)

        status = "等待助手…"
        var connected = false
        for (i in 0 until 12) {
            if (acceptHelper()) {
                connected = true
                break
            }
            Thread.sleep(200)
        }
        if (!connected) {
            val pid = runCatching {
                ShellManager.exec(ctx, "pidof ls-finger-touch", 2_000L).trimmed
            }.getOrDefault("")
            return "助手 socket 未就绪（pid=$pid）"
        }

        status = "预热空调值…"
        AcController.prime(ctx)
        return null
    }

    private fun startReader(ctx: Context) {
        reader = Thread({ readLoop(ctx) }, "ls-ac-reader").apply {
            isDaemon = true
            start()
        }
    }

    private fun acceptHelper(): Boolean = runCatching {
        val ss = server ?: return false
        ss.soTimeout = 1_000
        val s = ss.accept()
        s.tcpNoDelay = true
        socket = s
        ss.close()
        server = null
        true
    }.getOrDefault(false)

    private fun readLoop(ctx: Context) {
        val s = socket ?: return
        val br = BufferedReader(InputStreamReader(s.inputStream))
        try {
            while (running) {
                val line = br.readLine() ?: break
                when {
                    line.startsWith("C,") -> {
                        val count = line.substring(2).toIntOrNull() ?: continue
                        if (count == 3) {
                            // 三指刚落下就先亮 HUD，不能等位移过 96px 才出现。
                            // 上一次手势是温度就沿用温度，否则默认风量；轴锁定后再刷新。
                            val initialKind = hudAxis ?: GestureEngine.Kind.WIND
                            hudAxis = null
                            AcHud.show(initialKind, AcController.windLevel, AcController.tempValue)
                            // 读实车温度是 shell service call，不能放在读 socket 的线程里同步做，
                            // 否则会把后续 S 采样卡住 1 秒，表现就是 HUD 明显迟滞。
                            if (tempSyncJob?.isActive != true) {
                                tempSyncJob = scope.launch {
                                    runCatching { AcController.syncTemp(ctx) }
                                }
                            }
                        } else {
                            engine.reset()
                            hudAxis = null
                            AcHud.scheduleHide()
                        }
                    }
                    line.startsWith("S,") -> {
                        val p = line.split(',')
                        val cx = p.getOrNull(1)?.toFloatOrNull() ?: continue
                        val cy = p.getOrNull(2)?.toFloatOrNull() ?: continue
                        val step = engine.onSample(3, cx, cy, System.currentTimeMillis())
                        val axis = engine.axisKind ?: continue
                        if (step == null) {
                            if (hudAxis != axis) {
                                hudAxis = axis
                                AcHud.show(axis, AcController.windLevel, AcController.tempValue)
                            }
                            continue
                        }
                        hudAxis = step.kind
                        runCatching { runBlocking { AcController.apply(ctx, step.kind, step.dir) } }
                        AcHud.show(step.kind, AcController.windLevel, AcController.tempValue)
                    }
                }
            }
        } catch (t: Throwable) {
            if (running) Log.w(TAG, "读助手异常：${t.message}")
        } finally {
            running = false
            socket = null
            status = "助手连接断开"
            if (desired) {
                scope.launch {
                    delay(5_000L)
                    startJob = null
                    if (desired) startAsync()
                }
            }
        }
    }

    fun stop() {
        desired = false
        running = false
        runCatching { socket?.close() }
        socket = null
        runCatching { server?.close() }
        server = null
        reader?.interrupt()
        reader = null
        tempSyncJob?.cancel()
        tempSyncJob = null
        AcTickPlayer.release()
        AcHud.hideNow()
        status = "已停止"
        val ctx = appCtx
        if (ctx != null) {
            Thread {
                runCatching {
                    runBlocking { ShellManager.exec(ctx, "kill -9 \$(pidof ls-finger-touch) 2>/dev/null", 3_000L) }
                }
            }.apply { isDaemon = true; name = "ls-ac-stop"; start() }
        }
    }

    private var lastPushError = ""

    private suspend fun pushHelper(ctx: Context): Boolean {
        val bytes = ctx.assets.open(HELPER_ASSET).use { it.readBytes() }
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        ShellManager.exec(ctx, "rm -f '$HELPER' '$HELPER.tmp' '$PART'*", 5_000L)

        var off = 0
        var idx = 0
        while (off < b64.length) {
            val end = minOf(off + 1800, b64.length)
            val part = PART + idx.toString().padStart(4, '0')
            val cmd = "printf '%s' '" + b64.substring(off, end) + "' | base64 -d > '$part'"
            val r = ShellManager.exec(ctx, cmd, 8_000L)
            if (!r.success) {
                lastPushError = "分片$idx exit=${r.exitCode} ${r.stdout.take(60)}"
                ShellManager.exec(ctx, "rm -f '$PART'*", 3_000L)
                return false
            }
            off = end
            idx++
        }
        val names = (0 until idx).joinToString(" ") { "'" + PART + it.toString().padStart(4, '0') + "'" }
        val cat = ShellManager.exec(ctx, "cat $names > '$HELPER.tmp'", 10_000L)
        if (!cat.success) {
            lastPushError = "拼接失败 ${cat.stdout.take(80)}"
            return false
        }
        ShellManager.exec(ctx, "rm -f '$PART'*", 3_000L)
        val size = ShellManager.exec(ctx, "wc -c < '$HELPER.tmp'", 5_000L).stdout.trim().toIntOrNull()
        if (size != bytes.size) {
            lastPushError = "体积不符 期望${bytes.size} 实际${size ?: "读不到"}"
            return false
        }
        val chmod = ShellManager.exec(ctx, "chmod 755 '$HELPER.tmp'", 5_000L)
        val mv = ShellManager.exec(ctx, "mv -f '$HELPER.tmp' '$HELPER'", 5_000L)
        if (!chmod.success || !mv.success) {
            lastPushError = "落地失败 ${mv.stdout.take(80)}"
            return false
        }
        return true
    }
}
