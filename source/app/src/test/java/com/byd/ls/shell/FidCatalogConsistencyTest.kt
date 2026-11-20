package com.byd.ls.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.4.118 回归：fid 表自身的完整性。
 *
 * 真事故（2026-11-11 实车）：给 1014:1246765118 又加了一条名字叫 "range" 的 Fid，
 * FidDirect.byAddr 用 associateBy(dev:fid) 建索引，后写覆盖前写 →
 * evRangeKm 的值「凭空变 NaN」，而现象像是「车不报续航」，排查了两轮才回到表本身。
 * 这条测试把「同址只能有一个名字」钉死。
 */
class FidCatalogConsistencyTest {

    private val addrKey = { f: FidCatalog.Fid -> f.dev.toString() + ":" + f.fid }

    @Test
    fun `no duplicate dev-fid addresses`() {
        val dup = FidCatalog.all.groupBy(addrKey).filterValues { it.size > 1 }
        assertTrue("同址别名会让读值变 NaN，冲突项：" + dup.keys, dup.isEmpty())
    }

    @Test
    fun `no duplicate names`() {
        val dup = FidCatalog.all.groupBy { it.name }.filterValues { it.size > 1 }
        assertTrue("重名会让先写的被覆盖，冲突项：" + dup.keys, dup.isEmpty())
    }

    @Test
    fun `range aliases stay removed`() {
        val names = FidCatalog.all.map { it.name }.toSet()
        assertFalse("range/fuelRange 是已废弃的别名，用 evRangeKm/fuelRangeKm", "range" in names)
        assertFalse("range/fuelRange 是已废弃的别名，用 evRangeKm/fuelRangeKm", "fuelRange" in names)
    }

    /** 本车实测口径钉死：改了必须同时改这条测试并写清实车证据。 */
    @Test
    fun `calibrated addresses for this firmware`() {
        fun addrOf(name: String): String? = FidCatalog.all.firstOrNull { it.name == name }?.let(addrKey)
        assertEquals("1014:1145045040", addrOf("soc"))
        assertEquals("1009:842006544", addrOf("socCharging"))
        assertEquals("1014:1246765118", addrOf("evRangeKm"))
        assertEquals("1014:1246773304", addrOf("fuelRangeKm"))
        assertEquals("1039:1134559256", addrOf("insulationKohm"))
        assertEquals("1014:1246765072", addrOf("mileage"))
        // 1.4.119：综合油耗直读（仪表 50km 等效口径），此前只有事件推送，开机常常空着
        assertEquals("1014:1246785586", addrOf("fuelConsumption"))
        assertEquals("1016:-1728052956", addrOf("tirePressFL"))
        // 1.4.135：云辇C（智能悬架）模式；实车 00:34–00:36 切换定标，舒适=1 / 运动=2
        assertEquals("1023:938475528", addrOf("suspensionMode"))
    }

    @Test
    fun `table is big enough to be the real one`() {
        // 1.4.129 删了两条无效/重复（windowRR 旧址改址后 windowRRGen3 合并、chargeBatteryVolt 与 hvVoltage 同源），
        // 真实条数 129；下限留余量，只用来拦「表被截断」。
        assertTrue("fid 表被截断了？n=" + FidCatalog.all.size, FidCatalog.all.size >= 126)
    }
}
