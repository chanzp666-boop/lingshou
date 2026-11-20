package com.byd.ls.cluster

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 1.4.137 回归：仪表投屏档位命令与回执解析。
 *
 * 事实来源（docs/13 §30，均由实车/反编译取证）：
 *  · transact 号来自 framework.jar 的 classes2.dex 里 IAutoContainer$Stub$Proxy
 *    每个方法自带的常量：sendJson=1 / sendInfo=2 / sendInfo2=3 /
 *    registerCallback=4 / getProjectionDisplayInfo=5。**sendInfo 是 2**。
 *  · service call 的成功回执是 `Result: Parcel(00000000 00000000 '........')`，
 *    也可能是跨行写法（Parcel( 换行后跟 8 位十六进制），两种都要解析出 0。
 *  · 服务还没 publish 时输出 `Service AutoContainer not found`，必须判成 ERR_NO_SERVICE
 *    而不是当成通道故障（否则开机首发的失败会被误记成 adb 坏了）。
 */
class ClusterProjectionTest {

    @Test
    fun commandShapeIsServiceCallWithSendInfo() {
        assertEquals(
            "service call AutoContainer 2 i32 1000 i32 16 s16 \"\"",
            ClusterProjection.command(ClusterProjection.MODE_FULL)
        )
    }

    @Test
    fun commandCarriesModeCode() {
        assertEquals(
            "service call AutoContainer 2 i32 1000 i32 17 s16 \"\"",
            ClusterProjection.command(ClusterProjection.MODE_HALF)
        )
        assertEquals(
            "service call AutoContainer 2 i32 1000 i32 18 s16 \"\"",
            ClusterProjection.command(ClusterProjection.MODE_CLOSE)
        )
    }

    @Test
    fun parseSingleLineSuccess() {
        assertEquals(
            ClusterProjection.OK,
            ClusterProjection.parseResult("Result: Parcel(00000000 00000000   '........')")
        )
    }

    @Test
    fun parseMultiLineSuccess() {
        val out = "Result: Parcel(\n" +
            "  00000000 00000000           '........')\n"
        assertEquals(ClusterProjection.OK, ClusterProjection.parseResult(out))
    }

    @Test
    fun parseNonZeroCodeIsPreserved() {
        assertEquals(1, ClusterProjection.parseResult("Result: Parcel(00000001 00000000   '........')"))
        assertEquals(-3, ClusterProjection.parseResult("Result: Parcel(fffffffd 00000000   '........')"))
    }

    @Test
    fun parseServiceNotFound() {
        assertEquals(
            ClusterProjection.ERR_NO_SERVICE,
            ClusterProjection.parseResult("Error: Service AutoContainer not found")
        )
    }

    @Test
    fun parseEmptyOrGarbageIsChannelError() {
        assertEquals(ClusterProjection.ERR_CHANNEL, ClusterProjection.parseResult(null))
        assertEquals(ClusterProjection.ERR_CHANNEL, ClusterProjection.parseResult(""))
        assertEquals(ClusterProjection.ERR_CHANNEL, ClusterProjection.parseResult("error: closed"))
    }

    @Test
    fun modeConstantsMatchVehicleDoc() {
        assertEquals(16, ClusterProjection.MODE_FULL)
        assertEquals(17, ClusterProjection.MODE_HALF)
        assertEquals(18, ClusterProjection.MODE_CLOSE)
        assertEquals(2, ClusterProjection.TX_SEND_INFO)
        assertEquals(1000, ClusterProjection.TYPE_IVI_TO_CLUSTER)
    }
}
