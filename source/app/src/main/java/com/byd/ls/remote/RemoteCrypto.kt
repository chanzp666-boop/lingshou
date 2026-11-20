package com.byd.ls.remote

import android.content.Context
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 端到端加密：P-256 ECDH → HKDF-SHA256 → AES-256-GCM。
 *
 * 为什么要自己做而不是只靠隧道 TLS：隧道厂商（ngrok / Cloudflare）在边缘终结 TLS，
 * 明文对它可见。这一层让「车机 ↔ 我们的 Mac」之间的内容对隧道方是密文。
 * 私钥只在本机 prefs（mode private），不随日志/备份外发。
 */
object RemoteCrypto {

    private const val PREFS = "ls_remote"

    fun keyPair(ctx: Context): KeyPair {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pubB64 = sp.getString("kp_pub", null)
        val privB64 = sp.getString("kp_priv", null)
        if (pubB64 != null && privB64 != null) {
            runCatching {
                val kf = KeyFactory.getInstance("EC")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.NO_WRAP)))
                val pub = kf.generatePublic(X509EncodedKeySpec(Base64.decode(pubB64, Base64.NO_WRAP)))
                return KeyPair(pub, priv)
            }
        }
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"))
        val kp = g.generateKeyPair()
        sp.edit()
            .putString("kp_pub", Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP))
            .putString("kp_priv", Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP))
            .apply()
        return kp
    }

    fun publicKeyB64(ctx: Context): String =
        Base64.encodeToString(keyPair(ctx).public.encoded, Base64.NO_WRAP)

    /** 与对端公钥（SPKI base64）做 ECDH，得到 32 字节共享密钥 */
    fun shared(ctx: Context, peerPubB64: String): ByteArray {
        val priv: PrivateKey = keyPair(ctx).private
        val peer = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(Base64.decode(peerPubB64, Base64.NO_WRAP)))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(peer, true)
        return ka.generateSecret()
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray?, info: String, len: Int = 32): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        fun hmac(key: ByteArray, data: ByteArray): ByteArray {
            mac.reset()
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }
        // 与 Node 的 crypto.hkdfSync 对齐：salt 缺省时用 hashLen 个 0，不是空串
        val prk = hmac(salt?.takeIf { it.isNotEmpty() } ?: ByteArray(32), ikm)
        return hmac(prk, info.toByteArray() + byteArrayOf(1)).copyOf(len)
    }

    data class Sealed(val n: String, val c: String)

    fun seal(key: ByteArray, plaintext: ByteArray): Sealed {
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ct = c.doFinal(plaintext)
        return Sealed(
            Base64.encodeToString(iv, Base64.NO_WRAP),
            Base64.encodeToString(ct, Base64.NO_WRAP)
        )
    }

    fun open(key: ByteArray, n: String, cB64: String): ByteArray {
        val iv = Base64.decode(n, Base64.NO_WRAP)
        val ct = Base64.decode(cB64, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return c.doFinal(ct)
    }
}
