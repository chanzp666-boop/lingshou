package com.byd.ls.shell

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.Signature
import java.util.Base64

/**
 * adblib 接入的纯 JVM 回归（1.2.21）。
 *
 * 覆盖三件「接错就会静默失败」的事：
 *  1. 签名口径：adbd 要的是 DigestInfo||token 裸签，不是 SHA1withRSA；
 *  2. 密钥：adblib 用的确实是 AdbKeyStore 那把已信任 key（换通道不用重新点允许）；
 *  3. exec 的退出码哨兵解析与自研通道同口径。
 */
class AdbLibChannelTest {

    private val sha1Di = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    )

    @After
    fun restore() {
        LsAdbCrypto.useRawDigestInfo = true
        LsAdbCrypto.clearCache()
    }

    /** 造一个不连 socket 的 AdbStream：只用到 readQueue / isClosed 两个字段 */
    private fun newStreamWith(localId: Int): com.tananaev.adblib.AdbStream =
        com.tananaev.adblib.AdbStream(null as com.tananaev.adblib.AdbConnection?, localId)

    @Suppress("UNCHECKED_CAST")
    private fun readQueueOf(s: com.tananaev.adblib.AdbStream): java.util.Queue<ByteArray> =
        s.javaClass.getDeclaredField("readQueue").apply { isAccessible = true }
            .get(s) as java.util.Queue<ByteArray>

    private fun markClosed(s: com.tananaev.adblib.AdbStream) {
        s.javaClass.getDeclaredField("isClosed").apply { isAccessible = true }.setBoolean(s, true)
    }

    private fun verifyNone(pub: java.security.PublicKey, payload: ByteArray, sig: ByteArray): Boolean {
        val v = Signature.getInstance("NONEwithRSA")
        v.initVerify(pub)
        v.update(payload)
        return v.verify(sig)
    }

    @Test
    fun `token signature is raw DigestInfo plus token`() {
        val pair = AdbKeyStore.keyPair()
        val token = ByteArray(20) { (it * 13 + 5).toByte() }
        val sig = LsAdbCrypto.signToken(token, pair)
        assertEquals("RSA-2048 签名长度固定 256 字节", 256, sig.size)
        assertTrue("待签数据必须是 DigestInfo||token", verifyNone(pair.public, sha1Di + token, sig))
        assertFalse(
            "不能把 token 当明文再哈希（SHA1withRSA 口径会被 adbd 拒）",
            verifyNone(pair.public, token, sig)
        )
    }

    @Test
    fun `legacy mode reproduces the library default`() {
        val pair = AdbKeyStore.keyPair()
        val token = ByteArray(20) { it.toByte() }
        LsAdbCrypto.useRawDigestInfo = false
        val sig = LsAdbCrypto.signToken(token, pair)
        val v = Signature.getInstance("SHA1withRSA")
        v.initVerify(pair.public)
        v.update(token)
        assertTrue("对照模式必须等价于库自带 SHA1withRSA", v.verify(sig))
    }

    @Test
    fun `signed crypto overrides only the signature`() {
        val pair = AdbKeyStore.keyPair()
        val crypto = LsAdbCrypto.buildSigned(pair)
        val token = ByteArray(20) { (it + 1).toByte() }
        val sig = crypto.signAdbTokenPayload(token)
        assertTrue("AdbCrypto.signAdbTokenPayload 必须走实车口径", verifyNone(pair.public, sha1Di + token, sig))

        val payload = String(crypto.getAdbPublicKeyPayload(), StandardCharsets.US_ASCII)
        assertEquals(
            "公钥 payload 必须与 adb_keys 同一行（指纹与主机名都一致）",
            AdbKeyStore.androidPublicKeyBlob().toString(StandardCharsets.US_ASCII),
            payload
        )
        assertTrue("主机名不能是库默认的 unknown@unknown", payload.contains("lingshou@byd-dilink"))
    }

    @Test
    fun `builtin crypto reuses the trusted AdbKeyStore key`() {
        LsAdbCrypto.clearCache()
        val crypto = LsAdbCrypto.builtin()
        assertNotNull(crypto)
        val token = ByteArray(20) { (it * 7).toByte() }
        val sig = crypto.signAdbTokenPayload(token)
        assertTrue(
            "adblib 必须用 AdbKeyStore 那把已信任 key 签名",
            verifyNone(AdbKeyStore.keyPair().public, sha1Di + token, sig)
        )
    }

    @Test
    fun `builtin crypto is cached across calls`() {
        LsAdbCrypto.clearCache()
        val a = LsAdbCrypto.builtin()
        val b = LsAdbCrypto.builtin()
        assertTrue("缓存失效会让每次手势都重建 AdbCrypto", a === b)
    }

    @Test
    fun `public key payload is the adb_keys one-line form`() {
        val payload = String(AdbKeyStore.androidPublicKeyBlob(), StandardCharsets.US_ASCII)
        assertTrue("公钥 payload 需以 base64 开头", payload.startsWith("QAAAA"))
        assertTrue("公钥 payload 需带名字后缀", payload.contains(" "))
        assertTrue("公钥 blob 结尾是 NUL", payload.endsWith("\u0000"))
        val b64 = payload.substringBefore(' ')
        assertEquals("Android 公钥结构固定 524 字节", 524, Base64.getDecoder().decode(b64).size)
    }

    @Test
    fun `exit sentinel is stripped and exit code parsed`() {
        val out = AdbLibChannel.parseExitSentinel("uid=2000\n/home\n__LS_EXIT__=0\n")
        assertEquals(0, out.exitCode)
        assertTrue(out.success)
        assertEquals("uid=2000\n/home\n", out.stdout)
        assertFalse(out.stdout.contains("__LS_EXIT__"))
    }

    @Test
    fun `non-zero exit code is preserved`() {
        val out = AdbLibChannel.parseExitSentinel("error\n__LS_EXIT__=127\n")
        assertEquals(127, out.exitCode)
        assertFalse(out.success)
        assertEquals("error\n", out.stdout)
    }

    @Test
    fun `missing sentinel yields -1 and keeps body`() {
        val out = AdbLibChannel.parseExitSentinel("just output\n")
        assertEquals(-1, out.exitCode)
        assertEquals("just output\n", out.stdout)
    }

    @Test
    fun `drain keeps payload that arrived together with CLSE`() {
        // 车机实测的自伤：adbd 先 WRTE 再 CLSE，两者几乎同批到达，
        // 库的 AdbStream.read() 会「先 poll 拿数据、再判 isClosed 直接抛」，
        // 于是 ps -A 明明有输出却只剩一句 Stream closed。
        // 这里用真的 AdbStream 实例验证我们的排空路径不再丢数据。
        val stream = newStreamWith(localId = 7)
        val queue = readQueueOf(stream)
        queue.add("hello\n".toByteArray(StandardCharsets.UTF_8))
        queue.add("world\n".toByteArray(StandardCharsets.UTF_8))
        markClosed(stream)

        val text = AdbLibChannel.invokeDrain(stream)
        assertEquals("hello\nworld\n", text)
    }

    @Test
    fun `drain returns empty body when stream closes with no payload`() {
        val stream = newStreamWith(localId = 8)
        markClosed(stream)
        assertEquals("", AdbLibChannel.invokeDrain(stream))
    }

    @Test
    fun `channel reports display name and endpoint`() {
        val ch = AdbLibChannel("127.0.0.1", 5555)
        assertEquals("adblib", ch.id)
        assertEquals("127.0.0.1:5555", ch.endpoint())
        assertTrue(ch.displayName.contains("adblib"))
        assertFalse("未连接时不能自称 ready", ch.isReady())
        assertTrue("adblib 通道天然是 UID 2000 特权", ch.isPrivileged())
        ch.close()
    }
}
