package com.byd.ls.cluster.data

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 1.4.124：采样搬进 shell 域常驻 helper 之后，两边共享同一个截图文件。
 *
 * 这个契约一旦漂移，症状极其隐蔽：helper 往 A 写，app 从 B 读，
 * 读到的永远是上一帧（或根本不存在）——状态机被喂死帧，
 * 表现就是「导航开始/结束都不切换」。所以路径与 verb 名都钉死在测试里。
 */
class NavProbeHelperPathTest {

    private fun helperSource(): String? {
        val candidates = listOf(
            File("../helper/src/com/byd/ls/helper/HelperMain.java"),
            File("helper/src/com/byd/ls/helper/HelperMain.java")
        )
        return candidates.firstOrNull { it.exists() }?.readText()
    }

    private fun probeRaw(): String {
        val f = NavProbe::class.java.getDeclaredField("RAW")
        f.isAccessible = true
        return f.get(null) as String
    }

    @Test
    fun helperAndProbeShareSameRawFile() {
        val src = helperSource()
        assumeTrue("公开源码不含 helper 源码，跳过", src != null)
        src!!
        assertTrue("helper 里必须有 NAV_RAW 常量", src.contains("NAV_RAW"))
        assertTrue(
            "helper 的 NAV_RAW 必须等于 NavProbe.RAW，否则读到的是旧帧/空文件",
            src.contains("NAV_RAW = \"" + probeRaw() + "\"")
        )
    }

    @Test
    fun helperExposesNavshotVerbOnDisplay1() {
        val src = helperSource()
        assumeTrue("公开源码不含 helper 源码，跳过", src != null)
        src!!
        assertTrue("socket 必须有 navshot 分支", src.contains("\"navshot\".equals(cmd.trim())"))
        assertTrue(
            "navshot 必须截 display 1（仪表屏），不是默认 display 0",
            src.contains("\"/system/bin/screencap\", \"-d\", \"1\"")
        )
    }
}
