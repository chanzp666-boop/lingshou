package com.byd.ls.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.4.119：ADB 端口自愈的节流判定。
 * 写的是 Settings.Global（系统设置），热路径上不能每次通道失败都写一遍 —— 冷却必须真的生效。
 */
class AdbNetRestoreTest {

    @Test
    fun `first attempt always allowed`() {
        assertTrue(AdbNetRestore.shouldAttempt(nowMs = 1_000L, lastAt = 0L))
    }

    @Test
    fun `inside cooldown is blocked`() {
        val t = 10_000_000L
        assertFalse(AdbNetRestore.shouldAttempt(t, t - 1_000L))
        assertFalse(AdbNetRestore.shouldAttempt(t, t - AdbNetRestore.COOLDOWN_MS + 1L))
    }

    @Test
    fun `after cooldown allowed again`() {
        val t = 10_000_000L
        assertTrue(AdbNetRestore.shouldAttempt(t, t - AdbNetRestore.COOLDOWN_MS))
        assertTrue(AdbNetRestore.shouldAttempt(t, t - AdbNetRestore.COOLDOWN_MS * 3))
    }

    @Test
    fun `setting key is the one the framework reads`() {
        assertEquals("adb_wifi_enabled", AdbNetRestore.KEY)
    }
}
