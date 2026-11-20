package com.byd.ls.cluster

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 1.4.138：全屏导航优化（仪表 16 档）的接线契约。
 *
 * 这条链路靠「源码里有没有这段」来守：开关在 XML 里、闸门在 BootReceiver 里、
 * 补发钩子在 NavState 里、节流在 ClusterProjection 里 —— 任何一环漂移，
 * 症状都是「打开了但上电没切、或者开始导航没补发」，现场极难定位。
 * 同时守住 ANR 那条：startForeground 必须在 onCreate 的最前面。
 */
class ProjectionWiringTest {

    private fun find(vararg rel: String): File {
        val candidates = rel.flatMap { listOf(File(it), File("../$it")) }
        return candidates.firstOrNull { it.exists() }
            ?: error("找不到文件：" + rel.joinToString() + "，测试工作目录=" + File(".").absolutePath)
    }

    @Test
    fun homeLeftColumnHasProjectionCard() {
        val xml = find("app/src/main/res/layout/activity_main.xml", "src/main/res/layout/activity_main.xml").readText()
        assertTrue("首页左栏必须有开关 id", xml.contains("@+id/switch_projection_full"))
        assertTrue("卡片标题必须是「全屏导航优化」", xml.contains("全屏导航优化"))
        assertTrue(
            "备注文案必须与需求一致",
            xml.contains("打开后可以释放全屏地图左边的空间")
        )
    }

    @Test
    fun bootGateRespectsSwitch() {
        val kt = find(
            "app/src/main/java/com/byd/ls/receiver/BootReceiver.kt",
            "src/main/java/com/byd/ls/receiver/BootReceiver.kt"
        ).readText()
        assertTrue(
            "上电下发必须受 projectionFullOnBoot 闸门控制，关闭时不能碰仪表",
            kt.contains("GesturePrefs.projectionFullOnBoot")
        )
        assertTrue("上电下发必须走 ClusterProjection", kt.contains("ClusterProjection.apply"))
    }

    @Test
    fun navStartRequestsReapply() {
        val java = find(
            "app/src/main/java/com/byd/ls/cluster/data/NavState.java",
            "src/main/java/com/byd/ls/cluster/data/NavState.java"
        ).readText()
        assertTrue(
            "开始导航必须补发 16 档（方向盘/中控切页会打回 17）",
            java.contains("ClusterProjection.requestOnNav()")
        )
    }

    @Test
    fun reapplyIsThrottled() {
        val kt = find(
            "app/src/main/java/com/byd/ls/cluster/ClusterProjection.kt",
            "src/main/java/com/byd/ls/cluster/ClusterProjection.kt"
        ).readText()
        assertTrue("补发必须有节流窗", kt.contains("REAPPLY_GAP_MS"))
        assertTrue("节流窗按 5 分钟计", kt.contains("5 * 60 * 1000L"))
        assertTrue("补发必须跑在后台线程，不能阻塞采样线程", kt.contains("ls-nav-proj"))
    }

    @Test
    fun startForegroundRunsFirstInOnCreate() {
        val kt = find(
            "app/src/main/java/com/byd/ls/service/GlobalGestureService.kt",
            "src/main/java/com/byd/ls/service/GlobalGestureService.kt"
        ).readText()
        val fg = kt.indexOf("startForeground(NOTIF_ID, buildNotification())")
        val heavy = kt.indexOf("setInstance(this)")
        assertTrue("必须找到 startForeground 调用", fg >= 0)
        assertTrue("必须找到 onCreate 里的初始化点", heavy >= 0)
        assertTrue(
            "startForeground 必须在任何初始化之前：晚于 5 秒系统就判 ANR 杀进程",
            fg < heavy
        )
    }

    @Test
    fun startIsIdempotent() {
        val kt = find(
            "app/src/main/java/com/byd/ls/service/GlobalGestureService.kt",
            "src/main/java/com/byd/ls/service/GlobalGestureService.kt"
        ).readText()
        assertTrue(
            "服务已在跑时不得再调 startForegroundService（每次都会开一个 5 秒倒计时）",
            kt.contains("if (instanceRef != null)")
        )
        assertTrue(
            "onStartCommand 必须兜底再确认一次前台状态",
            kt.contains("任何路径进来都再确认一次前台状态")
        )
    }

    @Test
    fun cardRebuildNoLongerForcesProjection() {
        val java = find(
            "app/src/main/java/com/byd/ls/cluster/ui/CardActivity.java",
            "src/main/java/com/byd/ls/cluster/ui/CardActivity.java"
        ).readText()
        assertTrue(
            "1.4.142：卡片重建不再补发 16 档 —— 它会在用户于仪表上手动切到其他信息时把档位强行打回全屏导航",
            !java.contains("ClusterProjection.requestOnCardRebuild()")
        )
        assertTrue(
            "撤销理由必须写在现场，避免以后又被加回来",
            java.contains("1.4.142：撤掉「卡片重建就补发 16 档」")
        )
    }

    @Test
    fun navStartStillReappliesButRebuildHookIsGone() {
        val kt = find(
            "app/src/main/java/com/byd/ls/cluster/ClusterProjection.kt",
            "src/main/java/com/byd/ls/cluster/ClusterProjection.kt"
        ).readText()
        assertTrue("导航开始补发必须保留", kt.contains("fun requestOnNav()"))
        assertTrue("卡片重建补发钩子必须删除", !kt.contains("fun requestOnCardRebuild()"))
        assertTrue("重建节流常量必须一并删除", !kt.contains("REBUILD_GAP_MS"))
    }

    @Test
    fun switchGateIsExplicitInProjection() {
        val kt = find(
            "app/src/main/java/com/byd/ls/cluster/ClusterProjection.kt",
            "src/main/java/com/byd/ls/cluster/ClusterProjection.kt"
        ).readText()
        assertTrue(
            "关闭开关后两条补发路径都必须直接返回，不能靠 appRef 是否为空来隐式判断",
            kt.contains("if (!GesturePrefs.projectionFullOnBoot) return")
        )
    }
}
