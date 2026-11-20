package com.byd.ls.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.4.119 回归：半绑写回的脚本必须「去 → 回 → 补开关 → 读回」四步齐全，且读回判定不能看退出码。
 * 车上的事故形状：settings put 返回 0、名单里也有我们，但 accessibility_enabled 是 0 或 AMS 没 bind。
 */
class A11ySettingsScriptTest {

    private val self = "com.byd.ls/com.byd.ls.service.LsAccessibilityService"

    @Test
    fun `command keeps remove then add then enable then readback order`() {
        val cmd = A11ySettingsScript.command("A:B", "A", sleepMs = 250)
        val iRemove = cmd.indexOf("enabled_accessibility_services 'A'")
        val iAdd = cmd.indexOf("enabled_accessibility_services 'A:B'")
        val iEnable = cmd.indexOf("settings put secure accessibility_enabled 1")
        val iRead = cmd.indexOf("settings get secure accessibility_enabled")
        assertTrue("缺去/回步骤：$cmd", iRemove >= 0 && iAdd >= 0)
        assertTrue("去必须在回之前：$cmd", iRemove < iAdd)
        assertTrue("必须先写回再补开关：$cmd", iAdd < iEnable)
        assertTrue("末尾必须读回：$cmd", iEnable < iRead)
        assertTrue("读回要两条设置都取：$cmd", cmd.indexOf("settings get secure enabled_accessibility_services") in 0 until iRead)
    }

    @Test
    fun `sleep is 0_25 by default and integer seconds print without dot`() {
        val fast = A11ySettingsScript.command("A:B", "A")
        assertTrue("默认节奏应是 0.25s：$fast", fast.contains("sleep 0.25;"))
        assertFalse("默认不再是 2 秒：$fast", fast.contains("sleep 2;"))
        val slow = A11ySettingsScript.command("A:B", "A", sleepMs = 2000)
        assertTrue("整秒不该写成 2.0：$slow", slow.contains("sleep 2;"))
    }

    @Test
    fun `recover force stops then broadcasts with include stopped packages then rewrites`() {
        val cmd = A11ySettingsScript.recoverCommand("A:B", "A")
        val iKill = cmd.indexOf("am force-stop com.byd.ls")
        val iBc = cmd.indexOf("am broadcast --include-stopped-packages --receiver-foreground")
        val iRestart = cmd.indexOf("-a com.byd.ls.action.RESTART -n com.byd.ls/com.byd.ls.receiver.RestartReceiver")
        val iRewrite = cmd.indexOf("settings put secure enabled_accessibility_services 'A'")
        assertTrue("缺 force-stop：$cmd", iKill >= 0)
        assertTrue("必须先杀再广播：$cmd", iKill < iBc)
        assertTrue("广播必须带显式组件与 action：$cmd", iRestart > iBc)
        assertTrue("杀完必须重写名单：$cmd", iRewrite > iBc)
        assertTrue("末尾仍要读回：$cmd", cmd.indexOf("settings get secure accessibility_enabled") > iRewrite)
    }

    @Test
    fun `canonical normalizes short and full component names`() {
        assertEquals(self, A11ySettingsScript.canonical("com.byd.ls/.service.LsAccessibilityService"))
        assertEquals(self, A11ySettingsScript.canonical(" com.byd.ls/com.byd.ls.service.LsAccessibilityService "))
        assertEquals("a/b", A11ySettingsScript.canonical("a/b"))
        assertEquals("", A11ySettingsScript.canonical(""))
    }

    @Test
    fun `verify requires flag 1 and our component in the list`() {
        val ok = "com.van.diplus/.KDService:com.byd.ls/.service.LsAccessibilityService\n1"
        assertTrue(A11ySettingsScript.verify(ok, self))
        val flagOff = "com.byd.ls/.service.LsAccessibilityService\n0"
        assertFalse("开关是 0 就不算成功", A11ySettingsScript.verify(flagOff, self))
        val missing = "com.van.diplus/.KDService\n1"
        assertFalse("名单里没有我们就不算成功", A11ySettingsScript.verify(missing, self))
        assertFalse("空输出不算成功", A11ySettingsScript.verify(null, self))
        assertFalse("空输出不算成功", A11ySettingsScript.verify("  ", self))
    }
}
