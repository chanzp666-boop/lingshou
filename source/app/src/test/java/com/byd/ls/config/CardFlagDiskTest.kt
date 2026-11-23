package com.byd.ls.config

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 1.5.2：卡片总开关要跨进程传给 :guard。
 *
 * SP 在每个进程各有一份缓存；这里锁“主进程写 0/1，guard 读同一个 0/1”，
 * 避免全关后 :guard 继续按旧的“全开”缓存反复拉起空 CardActivity。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CardFlagDiskTest {

    private lateinit var ctx: android.content.Context
    private lateinit var flag: File

    @Before
    fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        GesturePrefs.init(ctx)
        flag = File(ctx.filesDir, "ls_card_any.flag")
        flag.delete()
        GesturePrefs.clusterEnabled = true
        GesturePrefs.showOil = false
        GesturePrefs.showElec = false
        GesturePrefs.showTotal = false
        GesturePrefs.showTyre = false
        GesturePrefs.showMusic = false
    }

    @Test
    fun `missing flag keeps old self-heal behavior`() {
        assertTrue(GesturePrefs.cardAnyFromDisk(ctx))
    }

    @Test
    fun `all card elements off writes zero`() {
        GesturePrefs.writeCardFlag(ctx)
        assertEquals("0", flag.readText())
        assertFalse(GesturePrefs.cardAnyFromDisk(ctx))
    }

    @Test
    fun `one card element on writes one`() {
        GesturePrefs.showMusic = true
        GesturePrefs.writeCardFlag(ctx)
        assertEquals("1", flag.readText())
        assertTrue(GesturePrefs.cardAnyFromDisk(ctx))
    }

    @Test
    fun `cluster off writes zero even if an element is on`() {
        GesturePrefs.showTyre = true
        GesturePrefs.clusterEnabled = false
        GesturePrefs.writeCardFlag(ctx)
        assertEquals("0", flag.readText())
        assertFalse(GesturePrefs.cardAnyFromDisk(ctx))
    }

    @Test
    fun `shell watchdog skips card when public flag is zero`() {
        val sh = find("app/src/main/assets/ls-watchdog.sh").readText()
        assertTrue(sh.contains("CARD_FLAG=/sdcard/clusterlab_card_any.txt"))
        assertTrue(sh.contains("cat \"\$CARD_FLAG\" 2>/dev/null"))
        assertTrue(sh.contains("= \"0\" ] && return 0"))
    }

    private fun find(rel: String): File {
        val candidates = listOf(File(rel), File("../$rel"), File("01-代码仓库/$rel"), File("../../$rel"))
        return candidates.firstOrNull { it.exists() }
            ?: error("找不到文件：$rel，测试工作目录=" + File(".").absolutePath)
    }
}
