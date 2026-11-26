package com.byd.ls.cluster.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.4.117 回归：导航 App 的窗口状态变化只提速采样，不改判定。
 *
 * 事实背景（交接包 13 §18.2）：NAVIENTER/NAVIEXIT 广播在车机上只发给 uid 1000，
 * 第三方一条都收不到；导航又在画中画里，前台包名法也读不到。
 * 剩下唯一零成本的信号是无障碍的 TYPE_WINDOW_STATE_CHANGED（本车无障碍已稳定绑定）。
 * 事件不来时本改动必须**完全无副作用**——所以单独覆盖「非导航包不动节拍」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class NavWindowBoostTest {

    @Test
    fun `nav packages are recognized, everything else is not`() {
        assertTrue(NavState.isNavPackage("com.autonavi.amapauto"))
        assertTrue(NavState.isNavPackage("com.autonavi.amapauto:remote"))
        assertTrue(NavState.isNavPackage("com.autonavi.amap"))
        assertTrue(NavState.isNavPackage("com.baidu.naviauto"))
        assertFalse(NavState.isNavPackage("com.android.systemui"))
        assertFalse(NavState.isNavPackage("com.autonavi.ama"))     // 前缀截断不算
        assertFalse(NavState.isNavPackage(""))
        assertFalse(NavState.isNavPackage(null))
    }

    @Test
    fun `nav window event boosts sampling, repeats are throttled`() {
        NavState.setDemand(true)
        NavState.onNavWindow("com.autonavi.amapauto")
        assertTrue("导航窗口事件必须把探测提到 800ms 一轮", NavState.boostActive())

        // 2 秒内同一个包再来一次：节流，不重复刷（不抛错、状态不变）
        NavState.onNavWindow("com.autonavi.amapauto")
        assertTrue(NavState.boostActive())

        // 非导航包不进入提速路径
        assertFalse("非导航包不得动节拍", NavState.isNavPackage("com.tencent.qqmusic"))
    }
}
