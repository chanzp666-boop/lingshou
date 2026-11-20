package com.byd.ls.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/**
 * 站在 adbd 的视角验一遍：把 AdbKeyStore 生成的 524 字节公钥 blob 按 AOSP 布局
 * 还原成 RSA 公钥，再用它验证 signToken 的签名。
 *
 * 如果这条断言不过，就说明「blob 里编码的公钥」和「签名用的私钥」不是同一把 key ——
 * 现场表现正是「点允许 → 系统记 alwaysAllow=true → 下一次连接签名仍被拒」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AdbBlobVerifiesSignatureTest {

    @Before
    fun setUp() {
        AdbKeyStore.init(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `blob 还原出的公钥能验证我们发出的签名`() {
        val token = ByteArray(20) { (it * 7 + 3).toByte() }
        val sig = AdbKeyStore.signToken(token)
        val blobText = String(AdbKeyStore.androidPublicKeyBlob(), Charsets.US_ASCII)
        val b64 = blobText.substringBefore(' ')
        val raw = Base64.getDecoder().decode(b64)

        // AOSP android::base::RSAPublicKey 布局（全小端）
        val words = readIntLe(raw, 0)
        assertEquals(64, words)
        val nBytes = words * 4
        assertEquals(524, raw.size)

        val modulus = BigInteger(1, raw.copyOfRange(8, 8 + nBytes).reversedArray())
        val exponent = BigInteger(1, raw.copyOfRange(8 + nBytes * 2, 8 + nBytes * 2 + 4).reversedArray())
        assertEquals(BigInteger.valueOf(65537L), exponent)
        assertEquals(2048, modulus.bitLength())

        val pub = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
        val verifier = Signature.getInstance("SHA1withRSA")
        verifier.initVerify(pub)
        verifier.update(token)
        assertTrue("adbd 视角还原出的公钥必须能验证我们的签名", verifier.verify(sig))
    }

    private fun readIntLe(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
}
