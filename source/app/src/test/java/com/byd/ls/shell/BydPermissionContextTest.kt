package com.byd.ls.shell

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 第二档第 10 条：BydPermissionContext 只能放行权限断言，别的一律照旧。
 * 单测就锁这两件事，免得哪天有人顺手把它改成「什么都返回成功」的假 Context。
 */
@RunWith(RobolectricTestRunner::class)
class BydPermissionContextTest {

    private fun ctx(): Context = BydPermissionContext(RuntimeEnvironment.getApplication())

    @Test
    fun checkPermission_alwaysGranted() {
        val c = ctx()
        assertEquals(PackageManager.PERMISSION_GRANTED,
            c.checkPermission(Manifest.permission.READ_PHONE_STATE, android.os.Process.myPid(), android.os.Process.myUid()))
        assertEquals(PackageManager.PERMISSION_GRANTED,
            c.checkCallingOrSelfPermission("android.permission.BYDAUTO_STATISTIC_GET"))
    }

    @Test
    fun enforcePermission_neverThrows() {
        val c = ctx()
        c.enforceCallingOrSelfPermission("android.permission.BYDAUTO_STATISTIC_GET", null)
        c.enforcePermission("android.permission.BYDAUTO_AC_SET", android.os.Process.myPid(), android.os.Process.myUid(), null)
        assertTrue(true)
    }

    @Test
    fun nonPermissionCalls_stillForwardToBase() {
        val app = RuntimeEnvironment.getApplication()
        val c = BydPermissionContext(app)
        assertEquals(app.packageName, c.packageName)
        assertEquals(app.filesDir.absolutePath, c.filesDir.absolutePath)
    }
}
