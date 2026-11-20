package com.byd.ls.cluster

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.4.139：卡片判活必须区分「窗口存在」与「在前台」。
 *
 * 现场铁证（2026-11-13 01:53，DiLink 4.0）：
 *  01:53:03.632 V/WindowManager: performShowLocked: mDrawState=HAS_DRAWN ... CardActivity
 *  01:53:03.687 I/LingShou: [CardKeeper] 卡片不在（tick），重拉：已请求重建
 * 只隔 55 ms —— 卡片刚画出来就被判死，因为 onPause（被高德 MeterActivity 顶下）把
 * windowAlive 置了 false，而 count() 用 windowAlive 判活。
 */
class CardLifecycleTest {

    private fun src(rel: String): String {
        for (c in listOf(rel, "../$rel", "01-代码仓库/$rel", "../../$rel")) {
            val f = File(c)
            if (f.exists()) return f.readText()
        }
        throw AssertionError("找不到源文件: $rel（cwd=" + File(".").absolutePath + "）")
    }

    @Test
    fun countUsesWindowAttachedNotAlive() {
        val card = src("app/src/main/java/com/byd/ls/cluster/data/Card.java")
        assertTrue("count() 必须用 windowAttached + windowVisible 判活", card.contains("windowAttached && CardActivity.windowVisible ? 1 : 0"))
        assertFalse("count() 不能再用 windowAlive 判活", card.contains("windowAlive ? 1 : 0"))
    }

    @Test
    fun pauseDoesNotClearAttached() {
        val act = src("app/src/main/java/com/byd/ls/cluster/ui/CardActivity.java")
        val pauseBody = act.substringAfter("protected void onPause()").substringBefore("super.onPause")
        assertFalse("onPause 不许清 windowAttached（那只代表不在前台）", pauseBody.contains("windowAttached = false"))
        val detachBody = act.substringAfter("protected void onDetachedFromWindow()").substringBefore("super.onDetachedFromWindow")
        assertTrue("真正销毁时 onDetachedFromWindow 必须清 windowAttached", detachBody.contains("windowAttached = false"))
    }

    @Test
    fun heartbeatFollowsAttachmentNotForeground() {
        val act = src("app/src/main/java/com/byd/ls/cluster/ui/CardActivity.java")
        assertTrue("心跳条件用 windowAttached + windowVisible", act.contains("if (windowAttached && windowVisible && now - beatAt > 10000L)"))
    }
}
