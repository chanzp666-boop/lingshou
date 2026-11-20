package com.byd.ls.shell

import java.math.BigInteger
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内置密钥合法性回归：密钥损坏会让 ADB 通道永远握手失败，且只表现为"未授权"。
 */
class AdbKeyStoreTest {

    @Test
    fun `key pair is rsa 2048 and signature verifies`() {
        val kp = AdbKeyStore.keyPair()
        assertEquals("RSA", kp.public.algorithm)
        val bits = (kp.public as java.security.interfaces.RSAPublicKey).modulus.bitLength()
        assertEquals(2048, bits)

        val token = ByteArray(20) { (it * 7).toByte() }
        val sig = AdbKeyStore.signToken(token)
        val v = Signature.getInstance("SHA1withRSA")
        v.initVerify(kp.public)
        v.update(token)
        assertTrue(v.verify(sig))
    }

    @Test
    fun `buildKeyPair accepts a non-CRT RSA private key`() {
        // 模拟 Android 10 Conscrypt 的 OpenSSLRSAPrivateKey：只实现 RSAPrivateKey，不是 CRT
        val modulus = (AdbKeyStore.keyPair().public as RSAPublicKey).modulus
        val fake = object : PrivateKey, RSAPrivateKey {
            override fun getModulus(): BigInteger = modulus
            override fun getPrivateExponent(): BigInteger = BigInteger.ONE
            override fun getAlgorithm(): String = "RSA"
            override fun getFormat(): String? = null
            override fun getEncoded(): ByteArray? = null
        }
        val kp = AdbKeyStore.buildKeyPair(fake)
        assertEquals(modulus, (kp.public as RSAPublicKey).modulus)
        assertEquals(BigInteger.valueOf(65537), (kp.public as RSAPublicKey).publicExponent)
    }

    @Test
    fun `android public key blob is 524 bytes plus user host`() {
        val blob = AdbKeyStore.androidPublicKeyBlob()
        val text = String(blob, Charsets.US_ASCII)
        val b64 = text.substringBefore(' ')
        val raw = Base64.getDecoder().decode(b64)
        // len(4) + n0inv(4) + n(256) + rr(256) + exponent(4)
        assertEquals(524, raw.size)
        assertTrue(text.endsWith("\u0000"))
        assertTrue(text.contains("lingshou@byd-dilink"))
    }

    @Test
    fun `fingerprint is md5 formatted with 16 groups`() {
        val fp = AdbKeyStore.publicKeyFingerprint()
        assertEquals(16, fp.split(':').size)
        assertTrue(fp.split(':').all { it.length == 2 })
    }
}
