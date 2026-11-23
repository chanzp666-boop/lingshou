package com.byd.ls.cluster.data

import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 回放 **真实车机日志行**（2026-11-09 20:0x 实车）。
 *
 * 用户口径（1.4.55 定案）：**能读到车机值就直显，不计算、不叠加、不迟滞**。
 *  - 综合油耗 = 1246785586（STATISTIC_LAST_50KM_EQUAL_FUEL_CON）
 *  - 最近段油耗 = 1032863782（STATISTIC_LAST_FUEL_CON_PHM）
 *  - 最近段电耗 = 1032863794（STATISTIC_LAST_ELEC_CON_PHM）
 *  - 四轮胎压 = instrument 1246781456/468/480/492（BYDAutoFeatureIds 原文，kPa）
 *  - tyre 设备的 1020264488（dval 28~30）**不绑轮位**（曾把左前顶成 290/300，用户实测为错）
 */
class VehicleDataEventsTest {

    @Before
    fun setUp() {
        // 本测试只验证 FID/instrument 映射解析；跳过 5s 迪加等待，模拟映射表已放行。
        TyreSource.forceMappingForTest()
    }

    private val realLines = """
        statistic event=1246765072 value=486153 dval=0.0
        statistic event=1246785586 value=0 dval=4.6
        statistic event=1032863782 value=130 dval=0.0
        statistic event=1032863794 value=0 dval=16.1
        statistic event=1032871984 value=0 dval=5396.5
        tyre event=1020264488 value=0 dval=30.0
        statistic event=1246781456 value=245 dval=0.0
        statistic event=1246781468 value=247 dval=0.0
        statistic event=1246781480 value=242 dval=0.0
        statistic event=1246781492 value=245 dval=0.0
    """.trimIndent()

    @Test
    fun `car values are taken verbatim, never computed`() {
        VehicleData.parseLines(realLines)
        val s = VehicleData.snapshot()
        assertTrue("综合油耗应直取 dval=4.6，实际: " + s, s.contains("totalPHM=4.6"))
        assertTrue("最近段油耗应为 130/10=13.0，实际: " + s, s.contains("fuelPHM=13.0"))
        assertTrue("最近段电耗应直取 dval=16.1，实际: " + s, s.contains("elecPHM=16.1"))
        assertTrue("累计电耗应直取 dval=5396.5，实际: " + s, s.contains("elecTotal=5396.5"))
        assertTrue("里程应为 48615.3，实际: " + s, s.contains("mileage=48615.3"))
    }

    @Test
    fun `four tyre pressures land on the four wheels from instrument ids only`() {
        VehicleData.nTyreLF = -1.0; VehicleData.nTyreRF = -1.0
        VehicleData.nTyreLB = -1.0; VehicleData.nTyreRB = -1.0
        VehicleData.parseLines("tyre event=1020264488 value=0 dval=30.0")
        assertTrue("tyre 设备 dval 不得绑轮位，实际: " + VehicleData.snapshot(),
            VehicleData.snapshot().contains("tyreLF=-1.0"))
        VehicleData.parseLines(realLines)
        val s = VehicleData.snapshot()
        assertTrue("LF=245，实际: " + s, s.contains("tyreLF=245.0"))
        assertTrue("RF=247，实际: " + s, s.contains("tyreRF=247.0"))
        assertTrue("LB=242，实际: " + s, s.contains("tyreLB=242.0"))
        assertTrue("RB=245，实际: " + s, s.contains("tyreRB=245.0"))
    }

    @Test
    fun `zero dval does not override a real int value`() {
        VehicleData.parseLines("statistic event=339738632 value=22 dval=0.0")
        assertTrue(VehicleData.snapshot().contains("speed=22.0"))
    }
}
