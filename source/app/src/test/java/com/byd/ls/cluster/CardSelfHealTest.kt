package com.byd.ls.cluster

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 1.4.142：卡片窗口丢失后的自愈契约。
 *
 * 现场问题：仪表从「其他信息」切回全屏导航后，卡片不启动、要等一两分钟才回来。
 * 根因是判活链路全部压在 :guard 的 20 秒巡检上：窗口销毁 -> 心跳 25 秒过期 ->
 * 下一轮巡检 -> 两次复核各 5.5 秒，最坏 50 秒。
 *
 * 这条链路靠「源码里有没有这段」来守，因为每一步漂移的症状都只是「慢」，没有报错。
 */
class CardSelfHealTest {

    private fun find(vararg rel: String): File {
        val candidates = rel.flatMap { listOf(File(it), File("../$it"), File("01-代码仓库/$it"), File("../../$it")) }
        return candidates.firstOrNull { it.exists() }
            ?: error("找不到文件：" + rel.joinToString() + "，测试工作目录=" + File(".").absolutePath)
    }

    private fun cardActivity() = find(
        "app/src/main/java/com/byd/ls/cluster/ui/CardActivity.java",
        "src/main/java/com/byd/ls/cluster/ui/CardActivity.java"
    ).readText()

    @Test
    fun detachTriggersSelfHeal() {
        val java = cardActivity()
        assertTrue(
            "onDetachedFromWindow 必须调自愈（这是窗口真没了的最早信号）",
            java.contains("scheduleSelfHeal();")
        )
        assertTrue("自愈方法必须存在", java.contains("private static void scheduleSelfHeal()"))
        assertTrue("自愈延迟常量必须存在", java.contains("SELF_HEAL_DELAY_MS = 800L"))
    }

    @Test
    fun selfHealRespectsUserStop() {
        val java = cardActivity()
        assertTrue("必须有用户主动停止标记", java.contains("public static volatile boolean stopping;"))
        assertTrue("shutdown 必须先置停止标记再 finish", java.contains("stopping = true;"))
        assertTrue("自愈必须先判停止标记", java.contains("if (stopping) return;"))
        assertTrue(
            "0.8 秒后还要再判一次「窗口又自己回来了」",
            java.contains("if (stopping || windowVisible) {")
        )
    }

    @Test
    fun onCreateClearsStopping() {
        val java = cardActivity()
        val idx = java.indexOf("protected void onCreate(Bundle b)")
        assertTrue("找不到 onCreate", idx > 0)
        val head = java.substring(idx, minOf(java.length, idx + 400))
        assertTrue("onCreate 第一件事必须清掉 stopping（否则重建后永远不自愈）", head.contains("stopping = false;"))
    }

    @Test
    fun localLaunchFallbackDoesNotSelfHealLoop() {
        val java = cardActivity()
        assertTrue(
            "local_launch 落到 0 号屏自退时必须置 stopping，否则自愈会无限重拉",
            java.contains("stopping = true;   // 1.4.142：这不是「卡片死了」，别让自愈把它拉回来")
        )
    }

    @Test
    fun guardPatrolIsFasterForCardOnly() {
        val kt = find(
            "app/src/main/java/com/byd/ls/service/GuardService.kt",
            "src/main/java/com/byd/ls/service/GuardService.kt"
        ).readText()
        assertTrue("卡片巡检周期常量必须是 3 秒（1.4.164 提速）", kt.contains("CARD_INTERVAL_MS = 3_000L"))
        assertTrue("必须有独立的 cardTask", kt.contains("private val cardTask"))
        assertTrue("onCreate 必须启动 cardTask", kt.contains("handler.postDelayed(cardTask, CARD_INTERVAL_MS)"))
        assertTrue("onDestroy 必须停 cardTask", kt.contains("handler.removeCallbacks(cardTask)"))
        assertTrue(
            "A11yHealer 的 20 秒周期不能被卡片提速带跑（那条带一堆解析）",
            kt.contains("HEAL_INTERVAL_MS = 20_000L")
        )
        val healTaskBody = kt.substring(
            kt.indexOf("private val healTask"),
            kt.indexOf("private val cardTask")
        )
        assertTrue(
            "原来的 20 秒 healTask 里不能再调 ClusterCardKeeper.heal（避免两条路径互相节流）",
            !healTaskBody.contains("ClusterCardKeeper.heal")
        )
    }
}
