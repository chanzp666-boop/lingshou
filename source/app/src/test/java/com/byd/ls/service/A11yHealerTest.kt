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
 * 自愈核心回归：授予 WRITE_SECURE_SETTINGS 后，App 必须能自己把本服务写回名单，
 * 且绝不能覆盖 KD / 迪友等已有无障碍服务。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = LsApp::class)
class A11yHealerTest {

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

    @Test
    fun `heal appends self and keeps existing services`() = runBlocking {
        val kd = "com.van.diplus/com.van.diplus.service.KDService"
        val diyou = "com.dy.launcher/com.dy.launcher.A11yService"
        Settings.Secure.putString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "$kd:$diyou"
        )
        Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)

        val result = A11yHealer.healNow(ctx, "unit-test")

        val after = A11yHealer.readEnabled(ctx)
        assertTrue("结果: $result / 名单: $after", after.contains(A11yList.COMPONENT))
        assertTrue(after.contains(kd))
        assertTrue(after.contains(diyou))
        assertEquals(1, Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0))
    }

    @Test
    fun `heal never removes self or reorders an already-complete list`() = runBlocking {
        val kd = "com.van.diplus/com.van.diplus.service.KDService"
        val before = "${A11yList.COMPONENT}:$kd"
        Settings.Secure.putString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            before
        )
        Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)

        A11yHealer.healNow(ctx, "unit-no-remove")

        assertEquals(before, A11yHealer.readEnabled(ctx))
        assertEquals(1, Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0))
    }

    @Test
    fun `heal is idempotent and does not duplicate self`() = runBlocking {
        Settings.Secure.putString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "a/a.A"
        )
        A11yHealer.healNow(ctx, "first")
        A11yHealer.healNow(ctx, "second")
        val after = A11yHealer.readEnabled(ctx)
        assertEquals(1, after.split(':').count { it == A11yList.COMPONENT })
    }
}
