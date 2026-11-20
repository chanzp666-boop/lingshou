package com.byd.ls.remote

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RemoteConfigDefaultTest {

    @Test
    fun defaultsOffAndExplicitEnableSticks() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("ls_remote", Context.MODE_PRIVATE).edit().clear().commit()

        assertFalse("升级/首次启动必须默认关闭", RemoteConfig.enabled(ctx))

        if (com.byd.ls.BuildConfig.PUBLIC_BUILD) {
            RemoteConfig.setEnabled(ctx, true)
            assertFalse("公开包不允许开启远程调试", RemoteConfig.enabled(ctx))
            return
        }

        RemoteConfig.setEnabled(ctx, true)
        assertTrue("用户手动开启后必须保持开启", RemoteConfig.enabled(ctx))

        RemoteConfig.setEnabled(ctx, false)
        assertFalse("用户手动关闭后必须保持关闭", RemoteConfig.enabled(ctx))
    }
}
