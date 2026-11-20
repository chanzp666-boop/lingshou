package com.byd.ls.service

import android.Manifest
import android.content.Context
import android.provider.Settings
import com.byd.ls.LsApp
import com.byd.ls.config.GesturePrefs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 定向重绑回归：车机上「名单里有灵手但 AMS 不 bind」只有让名单值真实变化
 * （移除 → 加回）才修得好；写同值既无效，又会让主进程与 :guard 的 observer 互相触发。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = LsApp::class)
class A11yRebindTest {

    private val kd = "com.van.diplus/com.van.diplus.service.KDService"
    private lateinit var ctx: Context

    @Before
    fun setUp() {
        // 先把上一个用例残留的名单/状态清干净：Robolectric 的 Settings 存储在同一个
        // 类加载器里是共享的，不清就会让用例隔次红，且红的还不是同一条。
        ctx = RuntimeEnvironment.getApplication()
        A11yHealer.cancelPending()
        GesturePrefs.init(ctx)
        GesturePrefs.enabled = true
        shadowOf(ctx as android.app.Application)
            .grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        Settings.Secure.putString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ""
        )
        Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        for (name in listOf("a11y_rebind.lock", "a11y_hard_reset.ts", "a11y_stale_repair.ts")) {
            java.io.File(ctx.filesDir, name).delete()
        }
    }

    private fun putList(list: String, flag: Int = 1) {
        Settings.Secure.putString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            list
        )
        Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, flag)
    }

    @Test
    fun `repairNow 重绑后名单精确还原，本服务不被挪到末尾`() = runBlocking {
        val before = "${A11yList.COMPONENT}:$kd"
        putList(before)
        val result = A11yHealer.repairNow(ctx)
        // 老实现先 without 再 merge，会把本服务挪到末尾并制造无意义的值变化
        assertEquals("名单必须逐字还原，结果: $result", before, A11yHealer.readEnabled(ctx))
        assertEquals(1, A11yHealer.readEnabledFlag(ctx))
        assertTrue("结果里应能看到重绑动作: $result", result.contains("重绑"))
    }

    @Test
    fun `锁文件刚写过时重绑被冷却拦住`() = runBlocking {
        val before = "${A11yList.COMPONENT}:$kd"
        putList(before)
        // 直接构造「刚重绑过」的状态：测的是冷却机制本身，不依赖两次调用之间的真实耗时。
        // 旧写法「连续调用两次、断言第二次被拦」在慢机器上首轮就要十几秒，10s 冷却自然过期——
        // 那是把机器速度当成了规格。
        val lock = java.io.File(ctx.filesDir, "a11y_rebind.lock")
        lock.createNewFile()
        lock.setLastModified(System.currentTimeMillis())
        val r = A11yHealer.healNow(ctx, "cooldown-probe")
        assertTrue("冷却中应跳过重绑，实际: $r", r.contains("重绑=跳过"))
        assertEquals(before, A11yHealer.readEnabled(ctx))
    }
}
