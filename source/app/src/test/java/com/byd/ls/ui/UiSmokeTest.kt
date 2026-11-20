package com.byd.ls.ui

import androidx.appcompat.app.AppCompatActivity
import com.byd.ls.LsApp
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI 冒烟测试：真实 inflate 布局并跑完 onCreate/onResume。
 *
 * 目的不是替代车机实测，而是把「安装后打开就闪退 / 点授权页就闪退」这类
 * 布局、主题、ViewBinding、主线程异常挡在构建阶段。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = LsApp::class)
class UiSmokeTest {

    @Test
    fun mainActivity_opens_without_crash() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity: AppCompatActivity = controller.get()
        assertNotNull(activity)
        assertTrue(!activity.isFinishing)
        controller.pause().stop().destroy()
    }
}
