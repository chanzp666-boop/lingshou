package com.byd.ls.cluster

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 1.4.140 跟手改造接线校验：事件通道（广播 / 无障碍文本）定论，像素退成兜底。
 * 全源码级断言 —— 这类「改了一处忘了另一处」的断点，跑真机前必须先在编译期钉住。
 */
class NavFollowTest {

    private fun src(rel: String): String {
        for (c in listOf(rel, "../$rel", "01-代码仓库/$rel", "../../$rel")) {
            val f = File(c)
            if (f.exists()) return f.readText()
        }
        throw IllegalStateException("找不到源码: $rel")
    }

    private val navState by lazy { src("app/src/main/java/com/byd/ls/cluster/data/NavState.java") }
    private val navProbe by lazy { src("app/src/main/java/com/byd/ls/cluster/data/NavProbe.java") }
    private val a11y by lazy { src("app/src/main/java/com/byd/ls/service/LsAccessibilityService.kt") }

    @Test
    fun eventTrustWindowExists() {
        assertTrue(navState.contains("EVENT_TRUST_MS = 20_000L"))
        assertTrue(navState.contains("static boolean eventTrusted()"))
        assertTrue(navState.contains("lastEventAt = now;"))
    }

    @Test
    fun forceMarksEventAndSplitsHoldByDirection() {
        assertTrue(navState.contains("BROADCAST_EXIT_HOLD_MS = 1_500L"))
        assertTrue(navState.contains("holdUntil = now + (nav ? BROADCAST_HOLD_MS : BROADCAST_EXIT_HOLD_MS);"))
    }

    @Test
    fun pixelYieldsToEventChannel() {
        assertTrue(navState.contains("} else if (!fail && eventTrusted()) {"))
        assertTrue(navState.contains("dashCard && NavProbe.failStreak >= 20 && !eventTrusted()"))
    }

    @Test
    fun exitDirectionLosesSingleFrameVerdict() {
        assertTrue(navState.contains("int need = (on && NavProbe.lastDecisive) ? 1 : 2;"))
        assertTrue(navProbe.contains("int need = (decisive && hasCard) ? 1 : (hasCard ? 2 : 3);"))
    }

    @Test
    fun a11yTextDrivesVerdict() {
        assertTrue(navState.contains("public static void onNavWindow(String pkg, String text, String desc)"))
        assertTrue(navState.contains("onNavWindow(pkg, null, null);"))
        assertTrue(navState.contains("force(true, \"a11y-text\");"))
        assertTrue(navState.contains("\"公里\"") && navState.contains("\"剩余\""))
        assertTrue(a11y.contains("NavState.onNavWindow("))
        assertTrue(a11y.contains("event.contentDescription"))
        assertTrue(a11y.contains("TYPE_WINDOW_CONTENT_CHANGED"))
        assertTrue(a11y.contains("lastNavContentAt"))
    }

    /**
     * 1.4.141 实测缺陷：DiLink4.0 上 `pm list packages | grep -iE 'automap|autonavi'` 只有
     * com.byd.automap / com.example.amapservice，**没有 com.autonavi.***。1.4.140 只列了
     * com.autonavi.*，于是 isNavPackage() 对实车导航包恒为 false，无障碍通道全废、跟手改造白改。
     */
    @Test
    fun navPackageCoversBydCustomAmap() {
        assertTrue(navState.contains("\"com.byd.automap\""))
        assertTrue(navState.contains("\"com.autonavi.amapauto\""))
    }
}
