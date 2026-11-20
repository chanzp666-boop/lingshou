package com.byd.ls.shell

import com.byd.ls.util.LsLog
import com.tananaev.adblib.AdbBase64
import com.tananaev.adblib.AdbCrypto
import java.io.File
import java.security.KeyPair
import java.security.Signature

/**
 * adblib 的 RSA 密钥与签名口径（1.2.21）。
 *
 * 这一层存在的唯一理由是「签名算法必须对」：
 * adblib 1.3 内部用 SHA1withRSA（对 token 先哈希再签），而仪表助理 v4.5 的实车结论
 * （ADB自授权要点-给灵手.md 第 1 节）是——adbd 把 20 字节 token 当成「已算好的 SHA-1 摘要」，
 * 要直接签 DigestInfo||token（35 字节）；用 SHA1withRSA 会陷入「点了允许还是过不去」的死循环。
 * 决定性证据：拿 256 字节签名做模幂还原，尾部 20 字节是原始 token，不是它的 SHA-1。
 *
 * 所以本对象自己出签名（[signToken]），并把库内部的 keyPair 对齐成 AdbKeyStore 那一对。
 */
object LsAdbCrypto {

    private const val TAG = "Ling_ADB"
    private const val FILE_PRIV = "privKey"
    private const val FILE_PUB = "pubKey"

    /** PKCS#1 v1.5 的 SHA-1 DigestInfo 前缀（15 字节），后面直接拼 20 字节 token */
    private val SHA1_DIGEST_INFO = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    )

    /** adblib 需要的 Base64 实现（Android Base64.NO_WRAP 语义 + 纯 JVM 兜底） */
    val base64: AdbBase64 = object : AdbBase64 {
        override fun encodeToString(data: ByteArray): String = androidBase64(data)
    }

    /**
     * 签名策略。true = 直接签 DigestInfo||token（实车验证口径，默认）；
     * false = 交给 adblib 自带的 SHA1withRSA（仅用于 A/B 对照实验 E2）。
     */
    @Volatile var useRawDigestInfo: Boolean = true

    @Volatile private var cachedBuiltin: AdbCrypto? = null

    /**
     * 默认来源：车机 adb_keys 里已经存在、跨重启验证过有效的那把内置密钥
     * （AdbKeyStore.keyPair 的 PKCS#8）。
     *
     * 与 App 内置 key 完全一致，所以无论走哪条通道，
     * 授权状态都是同一个——不存在「新通道要重新点一次允许」的问题。
     */
    fun builtin(): AdbCrypto {
        cachedBuiltin?.let { return it }
        synchronized(this) {
            cachedBuiltin?.let { return it }
            val pair: KeyPair = AdbKeyStore.keyPair()
            val crypto = buildSigned(pair)
            cachedBuiltin = crypto
            LsLog.i(TAG, "adblib crypto 就绪：来源=内置已信任 key 指纹=" +
                AdbKeyStore.publicKeyFingerprint() + " 签名口径=" + signMode())
            return crypto
        }
    }


    /**
     * 把 20 字节 token 签成 256 字节（RSA-2048 / PKCS#1 v1.5）。
     *
     * useRawDigestInfo=true：待签 = DigestInfo(15B) || token(20B)，裸私钥运算，不再哈希。
     * 这是 adbd 真正接受的格式。
     */
    fun signToken(token: ByteArray, pair: KeyPair): ByteArray {
        if (!useRawDigestInfo) return pbkcs1(pair, hash = true, payload = token)
        val di = ByteArray(SHA1_DIGEST_INFO.size + token.size)
        System.arraycopy(SHA1_DIGEST_INFO, 0, di, 0, SHA1_DIGEST_INFO.size)
        System.arraycopy(token, 0, di, SHA1_DIGEST_INFO.size, token.size)
        return pbkcs1(pair, hash = false, payload = di)
    }

    /** hash=true 走 SHA1withRSA；hash=false 走裸私钥运算（NONEwithRSA） */
    private fun pbkcs1(pair: KeyPair, hash: Boolean, payload: ByteArray): ByteArray {
        val name = if (hash) "SHA1withRSA" else "NONEwithRSA"
        return try {
            val s = Signature.getInstance(name)
            s.initSign(pair.private)
            s.update(payload)
            s.sign()
        } catch (e: java.security.GeneralSecurityException) {
            LsLog.w(TAG, name + " 不可用，退回 Cipher 裸运算: " + e.message)
            val c = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding")
            c.init(javax.crypto.Cipher.ENCRYPT_MODE, pair.private)
            c.doFinal(payload)
        }
    }

    /**
     * 把库内部持有的 keyPair 换成我们这一对。
     *
     * AdbCrypto 没有公开的「带 KeyPair 构造」入口（loadAdbKeyPair 是静态工厂），
     * 但它的签名字段就是那一个 keyPair——换成同一个对象的引用即可，
     * 不改变任何可见行为，只是保证后续 AUTH 用的确实是 AdbKeyStore 的 key。
     */
    fun buildSigned(pair: KeyPair): AdbCrypto =
        SignedCrypto(AdbCrypto.loadAdbKeyPair(base64, pair)).also { it.keyPairForSign = pair }

    /**
     * 覆盖签名的 AdbCrypto。
     *
     * 关键点：AdbConnection 在 AUTH 里调的是虚方法 signAdbTokenPayload，
     * 子类覆盖就全链路生效——不用反射改库字节码，也不需要库提供构造入口。
     * 其余能力（公钥 blob、指纹、Base64）仍走库原实现，adbd 弹窗里的指纹不变。
     */
    internal class SignedCrypto(private val delegate: AdbCrypto) : AdbCrypto() {
        @Volatile var keyPairForSign: KeyPair? = null
        private val ownPayload: ByteArray by lazy {
            // 库的 getAdbPublicKeyPayload 只把名字写成 "unknown@unknown"，
            // adbd 与现场排查都靠这一行认人，所以用 AdbKeyStore 的 lingshou@byd-dilink 版本。
            AdbKeyStore.androidPublicKeyBlob()
        }

        override fun signAdbTokenPayload(payload: ByteArray): ByteArray {
            val pair = keyPairForSign ?: throw IllegalStateException("adblib 签名 key 未就绪")
            return signToken(payload, pair)
        }

        override fun getAdbPublicKeyPayload(): ByteArray = ownPayload
    }

    fun signMode(): String =
        if (useRawDigestInfo) "DigestInfo||token（实车口径）" else "SHA1withRSA（库默认）"


    /** 单测用：清掉缓存，让下一次 [builtin] 重新从 AdbKeyStore 取 */
    fun clearCache() {
        cachedBuiltin = null
    }

    private fun androidBase64(data: ByteArray): String = try {
        android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
    } catch (e: Throwable) {
        java.util.Base64.getEncoder().encodeToString(data)
    }
}
