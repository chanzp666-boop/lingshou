package com.byd.ls.remote

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.byd.ls.R
import com.byd.ls.util.LsLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * 远程调试 Agent（车机侧，前台服务）。
 *
 * 只做出向连接：连我们自己的 relay（经隧道暴露的固定域名）。车机不开任何入站端口。
 * 握手：ECDH(P-256) → HKDF → AES-256-GCM；首次用 bootstrap_secret 注册并在 relay 侧钉住公钥。
 * 连接断了就退避重连（5s→60s），进程被拉起时由 LsApp.onCreate 自动接上。
 */
class RemoteAgent : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var ws: Ws? = null
    private var key: ByteArray? = null
    private var reconnectMs = 5_000L
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var diagSlot: CompletableDeferred<Boolean>? = null

    override fun onCreate() {
        super.onCreate()
        inst = this
        startForeground(NOTIF_ID, buildNotification("远程调试已启动"))
        // 清掉可能残留的安装包（上次装完没删干净的情况）
        runCatching {
            val ext = getExternalFilesDir(null)
            if (ext != null) for (n in arrayOf("update-ls.apk", "ls231.apk", "diag.tmp")) File(ext, n).delete()
            File(filesDir, "update-ls.apk").delete()
        }
        connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!RemoteConfig.enabled(this)) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        runCatching { ws?.close() }
        scope.cancel()
        stateText = "已停止"
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------
    private fun connect() {
        if (!RemoteConfig.enabled(this) || !RemoteConfig.configured(this)) {
            stateText = "未启用/未配置"
            return
        }
        runCatching { ws?.close() }
        key = null
        val url = RemoteConfig.url(this)
        stateText = "连接中…"
        try {
            val c = Ws(URI(url), Draft_6455(), mapOf("ngrok-skip-browser-warning" to "1"))
            c.connectionLostTimeout = 30
            ws = c
            c.connect()
        } catch (t: Throwable) {
            stateText = "连接异常：${t.message}"
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        heartbeatJob?.cancel()
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val wait = reconnectMs
            reconnectMs = (reconnectMs * 2).coerceAtMost(60_000L)
            delay(wait)
            connect()
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                delay(30_000L)
                runCatching { send("event", JSONObject().put("hb", System.currentTimeMillis())) }
            }
        }
    }

    private fun send(t: String, payload: JSONObject, id: String? = null) {
        val k = key ?: return
        val sealed = RemoteCrypto.seal(k, payload.toString().toByteArray())
        val frame = JSONObject().put("t", t).put("n", sealed.n).put("c", sealed.c)
        if (id != null) frame.put("id", id)
        ws?.send(frame.toString())
    }

    private fun sendRaw(obj: JSONObject) = ws?.send(obj.toString())

    // ------------------------------------------------------------------
    inner class Ws(uri: URI, draft: Draft_6455, headers: Map<String, String>) :
        WebSocketClient(uri, draft, headers) {

        override fun onOpen(handshakedata: ServerHandshake?) {
            RemoteAudit.log(this@RemoteAgent, "ws open ${uri}")
            val pub = RemoteCrypto.publicKeyB64(this@RemoteAgent)
            sendRaw(
                JSONObject()
                    .put("t", "hello").put("v", 1)
                    .put("dev", RemoteConfig.deviceId(this@RemoteAgent))
                    .put("pub", pub)
            )
        }

        override fun onMessage(message: String?) {
            val m = runCatching { JSONObject(message ?: return) }.getOrNull() ?: return
            when (m.optString("t")) {
                "hello" -> {
                    runCatching {
                        val shared = RemoteCrypto.shared(this@RemoteAgent, m.getString("pub"))
                        val sid = hex(m.getString("sid"))
                        key = RemoteCrypto.hkdf(shared, sid, "lsrd-v1")
                        if (!m.optBoolean("enrolled", false)) {
                            val ek = RemoteCrypto.hkdf(
                                RemoteConfig.bootstrap(this@RemoteAgent).toByteArray(),
                                null, "lsrd-enroll-v1"
                            )
                            val body = JSONObject()
                                .put("bootstrap", RemoteConfig.bootstrap(this@RemoteAgent))
                                .put("model", android.os.Build.MODEL)
                                .put("name", "灵手车机")
                            val s = RemoteCrypto.seal(ek, body.toString().toByteArray())
                            sendRaw(JSONObject().put("t", "enroll").put("n", s.n).put("c", s.c))
                            RemoteAudit.log(this@RemoteAgent, "首次注册（TOFU）")
                        }
                    }.onFailure { LsLog.e(TAG, "hello 处理失败", it) }
                }
                "challenge" -> {
                    runCatching {
                        val k = key ?: return
                        val nonce = String(RemoteCrypto.open(k, m.getString("n"), m.getString("c")))
                        val reply = JSONObject(nonce).getString("nonce")
                        send("proof", JSONObject().put("nonce", reply))
                    }.onFailure { stateText = "握手失败：${it.message}" }
                }
                "ready" -> {
                    stateText = "已连接"
                    reconnectMs = 5_000L
                    startHeartbeat()
                    RemoteAudit.log(this@RemoteAgent, "会话建立")
                }
                "rpc" -> {
                    val id = m.optString("id")
                    val k = key ?: return
                    val payload = runCatching {
                        JSONObject(String(RemoteCrypto.open(k, m.getString("n"), m.getString("c"))))
                    }.getOrNull() ?: return
                    val method = payload.optString("method")
                    val params = payload.optJSONObject("params") ?: JSONObject()
                    scope.launch {
                        val t0 = System.currentTimeMillis()
                        val out = runCatching { RemoteRpc.handle(this@RemoteAgent, method, params) }
                            .fold({ it }, { mapOf("error" to (it.message ?: it.javaClass.simpleName)) })
                        RemoteAudit.log(this@RemoteAgent, "rpc $method ${System.currentTimeMillis() - t0}ms")
                        val body = JSONObject()
                        body.put("ok", !(out is Map<*, *> && out["error"] != null))
                        body.put("data", toJson(out))
                        runCatching { send("res", body, id) }
                    }
                }
            }
        }

        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            stateText = "已断开（$code ${reason ?: ""}）"
            key = null
            RemoteAudit.log(this@RemoteAgent, "ws close $code $reason")
            scheduleReconnect()
        }

        override fun onError(e: Exception?) {
            stateText = "连接错误：${e?.message}"
            RemoteAudit.log(this@RemoteAgent, "ws error ${e?.message}")
            scheduleReconnect()
        }
    }

    // ------------------------------------------------------------------
    private fun toJson(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, x) -> o.put(k.toString(), toJson(x)) } }
        is Collection<*> -> org.json.JSONArray().also { a -> v.forEach { a.put(toJson(it)) } }
        else -> v
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, getString(R.string.notif_channel_id))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("灵手 · 远程调试")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    companion object {
        private const val TAG = "RemoteAgent"
        private const val NOTIF_ID = 2201

        @Volatile var stateText: String = "未启动"
            private set

        fun start(ctx: Context) {
            // :guard / 其它进程也会跑 LsApp.onCreate，只能主进程连，否则两个会话互相顶掉
            if (android.app.Application.getProcessName() != ctx.packageName) return
            if (!RemoteConfig.enabled(ctx)) { stateText = "已关闭"; return }
            if (!RemoteConfig.configured(ctx)) { stateText = "未配置（relay 地址/口令为空）"; return }
            runCatching {
                ContextCompat.startForegroundService(ctx, Intent(ctx, RemoteAgent::class.java))
            }.onFailure { stateText = "拉起失败：${it.message}" }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, RemoteAgent::class.java)) }
            stateText = "已停止"
        }

        @Volatile private var inst: RemoteAgent? = null

        /** 诊断包上传：走同一条加密 WS（car → relay 的 event 帧），relay 落盘到 data/diag/。 */
        fun uploadDiag(f: File): Boolean {
            val a = inst ?: return false
            val k = a.key ?: return false
            return runCatching {
                val body = JSONObject()
                    .put("kind", "diag")
                    .put("name", f.name)
                    .put("size", f.length())
                    .put("b64", android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP))
                val sealed = RemoteCrypto.seal(k, body.toString().toByteArray())
                a.ws?.send(JSONObject().put("t", "event").put("n", sealed.n).put("c", sealed.c).toString())
                true
            }.getOrDefault(false)
        }
    }
}
