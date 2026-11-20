package com.byd.ls.shell

import com.byd.ls.util.LsLog
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.MessageDigest
import java.security.Signature
import java.security.PrivateKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/**
 * 内置 ADB 客户端的 RSA 密钥。
 *
 * 直接内置车机上已被 adbd 信任的固定密钥（与迪友内置密钥同一对），不再本机生成、
 * 不再提供复制 / 注入公钥入口。握手流程：
 *  1. adbd 发来 20 字节 AUTH_TOKEN；
 *  2. 客户端用私钥做 SHA1withRSA(PKCS#1 v1.5) 签名，回 AUTH_SIGNATURE；
 *  3. 若 adbd 仍未命中信任列表，再回 AUTH_RSAPUBLICKEY 携带公钥 blob 兜底。
 */
object AdbKeyStore {

    private const val TAG = "AdbKeyStore"

    private const val KEY_BITS = 2048
    /** 2048bit 模数 n 与 rr 各 64 个 uint32 字（小端） */
    private const val RSA_NUM_WORDS = KEY_BITS / 32

    /**
     * 每台设备独立的 RSA 私钥，首次使用时生成，保存在 App 私有目录。
     *
     * 不再内置固定私钥：固定私钥可从 APK 反编译提取，等于所有安装共用一把
     * 可被信任的 ADB key，安全边界会被打破。代价是首次连接需要用户在车机上
     * 点一次「允许 USB 调试」，之后 key 持久化。
     */
    private const val KEY_FILE = "ls_adb_key.pk8"

    @Volatile private var appCtx: android.content.Context? = null

    fun init(ctx: android.content.Context) {
        appCtx = ctx.applicationContext
        cached = null
    }

    private fun generatePrivateKey(): PrivateKey {
        val gen = java.security.KeyPairGenerator.getInstance("RSA")
        gen.initialize(KEY_BITS)
        return gen.generateKeyPair().private
    }

    private fun loadOrCreatePrivateKey(ctx: android.content.Context): PrivateKey {
        val file = java.io.File(ctx.filesDir, KEY_FILE)
        val lockFile = java.io.File(ctx.filesDir, "$KEY_FILE.lock")
        // 主进程和 :guard 可能同时首次启动；必须跨进程串行，否则会各生成一把 key，
        // 导致主进程用新 key、:guard 用另一把，ADB 授权随机失败。
        val channel = runCatching {
            java.nio.channels.FileChannel.open(
                lockFile.toPath(),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.WRITE
            )
        }.getOrNull()
        val lock = runCatching { channel?.lock() }.getOrNull()
        try {
            if (file.exists() && file.length() > 0) {
                runCatching {
                    val kf = KeyFactory.getInstance("RSA")
                    return kf.generatePrivate(PKCS8EncodedKeySpec(file.readBytes()))
                }.onFailure { LsLog.w(TAG, "读取本机 ADB key 失败，重新生成: ${it.message}") }
            }
            val priv = generatePrivateKey()
            val encoded = priv.encoded ?: throw IllegalStateException("ADB 私钥没有 PKCS#8 编码")
            file.writeBytes(encoded)
            runCatching {
                file.setReadable(false, false)
                file.setReadable(true, true)
                file.setWritable(false, false)
                file.setWritable(true, true)
            }
            LsLog.i(TAG, "已生成本机独立 ADB key: ${file.absolutePath}")
            return priv
        } finally {
            runCatching { lock?.release() }
            runCatching { channel?.close() }
        }
    }

    /** 公钥 blob 里附带的主机标签，仅用于 adbd 记录展示，不参与鉴权 */
    private const val USER_HOST = "lingshou@byd-dilink"

    /** 固定公钥指数（与内置私钥一致；Android 的 OpenSSLRSAPrivateKey 不实现 CRT 接口） */
    private val RSA_PUBLIC_EXPONENT: BigInteger = BigInteger.valueOf(65537)

    /**
     * 车机已信任的固定私钥（PKCS#8 DER，Base64）。
     *
     * 1.2.2 的安全审计为「每台设备独立 key」删掉了它，代价在这台 DiLink 上完全暴露：
     * 用户点「允许」之后 adbd 仍不接受新生成的 key（现场 `已发满 3 轮签名仍未收到 CNXN`），
     * 而 **1.2.8 用的是车机 adb_keys 里已经存在、且跨重启验证过有效的那把 key**
     * （本轮实测：同一 key 在深度重启后仍能静默连上，说明 adb_keys 本身是持久化的，
     * 之前失败的只是「新 key 从未被成功写入」）。静默握手直接拿 shell、全程不弹窗。
     */
    private const val PRIVATE_PKCS8_B64 = "MIIEvwIBADANBgkqhkiG9w0BAQEFAASCBKkwggSlAgEAAoIBAQDXGPQCHZ2p7NuFCMS4KWkDwbsqYk8f/AJugH76Z9D3I2VleDDVlvQ4rmcwKPgpgkwTepGqiMXnYJa+4AaTYA8I+sHnx0rW6e5rdhA9tFtsjscXCAO0pHBrILcg7vMQcsvdsTUNiajIZe1wJsAJnW0GMcaMmxZDYsoleyVl2l6dtJGl6ZYniC+mw+fjmbUlllq5W6dwbT4jTpVaLdYhacozg2CKrVLry4dxAypaPpSRzHCaDeV2eQW11w/0b+tt3nVJF6uJP5W86Kc5KeT4gCQu8rOGlv1uXJ3hB5523B0/uTqLubRdV9uINR9TiAmyZjzpcItbFdeFAFfzhtthXrtVAgMBAAECggEAHrJ/+GFTo/GarpS+xm2agB/A316iXjUv/6c09/Wi+/JAQUfy6Dif6ArzzQq7x+jBhLIkYcsdO1UsjlcYeSXnX2eyDqsBgkRmc02pKbU4qviBb0cEiF5DcPZ9TCTt6XYIpD3rDWCNBvSM+0l8dKLT8WNfK6fieIF7yfeWMcAFasocU5/d90N0EiOgBPI1B77VtM5owByQ+l2aaHpX2eoPB+/FkHg2/L0WWmNfxTaQj3mfJduVXrM286B9fql8nEtN0CWbXdawGT9ca6j8rfo9y1xZVGKQG0J9LMzYdgYZrtBGNf49+6o0GFw5BP/x8/yyVOM4cEAS6Md3l0nKvh8BjQKBgQDwWNcWUOWBbdTyjvHyyOYXVk9OHNoR62EUuOYvN2Rdq78nQOD9UNEf0A/eKefBxi4e/0kiahGGs/xkFEUX/u17WurAvladVagOhCLdNbSbKsIkLdZ2cjOUVyPPO+WReygmqITWSeWs/sTca8fJ4GONUBv7MqKVgtySaiKjMIEAywKBgQDlGyR3RIunCPXn/ES7Yj+en7nygdKyuI4X/SS3vX2/dttPpMj7jnCzBcPOBTGF6NF1OnUwQCWKsu6KEYR8SllIqgKreLa4FV+W5mu38sdFh9b7xSTNAS4kyskkoY+qswXsYk33nZTzC2MH4zVX4ZgYTdOHHNu6ar2yByZ4S8BQXwKBgQC/Q2N+vce1tOqGUBzYQOlh3uxjU/Bx3Cc4b1+2aVU0ea03lpi03XBd6ZwHBsAIYyZmwm63tULXODn8hmjpjJP92z7/PTj+IaEIVDzOXqOn62HtmpCVV5IWVTYI/zhUCnbMNMTpJotgnKmaYybtTuTKGtr5jrT1V9shpNaFmN6/+wKBgQDAHd6aVNR2oUDz48sPBvgqX+5huROh4rjkzNX5beEAqP6G7rvKlktTE8Z6AEji7bSXBQo4B9JzWFBHXyElyILOOCI8/+DYxLN/qoExo1gOqjZQsaSGdDLpj1LUcNY0YiLZciXHOlEo7aeBebnFk9sipkB20HBBBjlEDFLqFTuTKQKBgQCL/4xAaz+QFU4P0iORWS6AKuPaxx6Hm4R+11bLn/RX8QtV6pjlGi6Reh+BXGiZ1VcNZFYdGIhpE9bcFEmX1dnr9XkBjUPwJutyJkhzG3NGgDLGSM0u+dw42OSDwCud+Vi9f5lfJXUY7BHMcWz5hc03XmzEm4fwt55pPAkOzWVCBg=="

    private const val ADB_PREFS = "ls_adb"
    private const val PREF_USE_BUILTIN = "use_builtin_key"

    @Volatile private var cached: KeyPair? = null

    @Synchronized
    fun keyPair(): KeyPair {
        cached?.let { return it }
        val ctx = appCtx
        val builtin = ctx != null && useBuiltinKey(ctx)
        val priv = when {
            ctx == null -> generatePrivateKey()
            builtin -> builtinPrivateKey()
            else -> loadOrCreatePrivateKey(ctx)
        }
        return buildKeyPair(priv).also {
            cached = it
            // 现场排查用：确认到底拿哪把 key 在握手（旧日志只有 UNAUTHORIZED，看不出 key 来源）
            LsLog.i(TAG, "ADB key 就绪：来源=" + (if (builtin) "内置已信任" else "本机生成") +
                " 指纹=" + publicKeyFingerprint())
        }
    }

    /** 是否使用内置的「车机已信任」固定 key（默认 true；1.2.2~1.2.5 的行为是 false） */
    fun useBuiltinKey(ctx: android.content.Context): Boolean =
        ctx.getSharedPreferences(ADB_PREFS, android.content.Context.MODE_PRIVATE)
            .getBoolean(PREF_USE_BUILTIN, true)


    private fun builtinPrivateKey(): PrivateKey =
        KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(PRIVATE_PKCS8_B64)))

    /**
     * 从私钥派生公钥。
     *
     * ⚠️ 不能用 `priv as RSAPrivateCrtKey`：Android 10 的 Conscrypt 返回
     * `OpenSSLRSAPrivateKey`，它只实现 `RSAPrivateKey`，强转会抛
     * `ClassCastException`（1.1.0-1.1.2 授权页闪退与 ADB 永远握手失败的真因）。
     * 这里只用 `RSAPrivateKey.modulus` + 固定指数 e=65537 生成公钥。
     */
    internal fun buildKeyPair(priv: PrivateKey): KeyPair {
        val rsa = priv as? RSAPrivateKey
            ?: throw IllegalStateException("内置 ADB 私钥不是 RSA 私钥: ${priv.javaClass.name}")
        val pub = KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(rsa.modulus, RSA_PUBLIC_EXPONENT))
        return KeyPair(pub, priv)
    }

    /** 用私钥签名 adbd 发来的 token */
    fun signToken(token: ByteArray): ByteArray {
        val sig = Signature.getInstance("SHA1withRSA")
        sig.initSign(keyPair().private)
        sig.update(token)
        return sig.sign()
    }

    /**
     * 兜底：发送 Android 私有格式公钥 blob。
     *
     * struct RSAPublicKey {
     *   uint32 len;        // 字数，2048bit = 64
     *   uint32 n0inv;      // -1 / n[0] mod 2^32，Montgomery 约简用
     *   uint32 n[64];      // 模数，小端字序
     *   uint32 rr[64];     // R^2 mod n = 2^4096 mod n，小端字序
     *   uint32 exponent;   // 65537
     * }  // 共 524 字节，Base64 后追加 " user@host\0"
     */
    fun androidPublicKeyBlob(): ByteArray =
        "${androidBlobBase64()} $USER_HOST\u0000".toByteArray(Charsets.US_ASCII)

    /**
     * 把一段 PKCS#8 DER 解析成 KeyPair（供 adblib 自管 key 场景复用同一套派生逻辑）。
     *
     * 注意 [buildKeyPair] 只用 modulus + 固定指数 e=65537：
     * Android 10 的 OpenSSLRSAPrivateKey 不实现 RSAPrivateCrtKey，强转会抛 ClassCastException。
     */
    internal fun pairFromPkcs8(der: ByteArray): KeyPair =
        buildKeyPair(KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der)))

    /**
     * 公钥指纹（MD5，大写冒号分隔，与 adbd "允许USB调试" 弹窗展示一致）。
     * 用于授权页核对：若车机弹窗/日志给出指纹，应与此一致。
     */
    fun publicKeyFingerprint(): String {
        val digest = MessageDigest.getInstance("MD5")
            .digest(Base64.getDecoder().decode(androidBlobBase64()))
        return digest.joinToString(":") { b -> "%02X".format(b.toInt() and 0xFF) }
    }

    private fun androidBlobBase64(): String {
        val pub = keyPair().public as RSAPublicKey
        val n = pub.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        // n0inv = (-1) * n^{-1} mod 2^32
        val nInv = n.mod(r32).modInverse(r32)
        val n0inv = r32.subtract(nInv).mod(r32).toInt()
        // rr = 2^(32*2*words) mod n = 2^4096 mod n
        val rr = BigInteger.ONE.shiftLeft(32 * RSA_NUM_WORDS * 2).mod(n)

        val buf = ByteBuffer.allocate(4 + 4 + RSA_NUM_WORDS * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(RSA_NUM_WORDS)
        buf.putInt(n0inv)
        putWords(buf, n, RSA_NUM_WORDS)
        putWords(buf, rr, RSA_NUM_WORDS)
        buf.putInt(pub.publicExponent.toInt())

        return Base64.getEncoder().encodeToString(buf.array())
    }

    /** 把大端 BigInteger 拆成小端 uint32 字序写入 buffer */
    private fun putWords(buf: ByteBuffer, value: BigInteger, words: Int) {
        var bytes = value.toByteArray()
        // 去掉 BigInteger 的符号扩展字节
        if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes = bytes.copyOfRange(1, bytes.size)
        val out = IntArray(words)
        for (i in bytes.indices) {
            val b = bytes[bytes.size - 1 - i].toInt() and 0xFF
            out[i / 4] = out[i / 4] or (b shl (8 * (i % 4)))
        }
        for (w in out) buf.putInt(w)
    }
}
