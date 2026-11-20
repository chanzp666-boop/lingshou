package com.byd.ls.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * read-modify-write 的纯逻辑回归：任何时候都不能把车机上已有的无障碍服务顶掉。
 */
class A11yListTest {

    private val kd = "com.van.diplus/com.van.diplus.service.KDService"
    private val diyou = "com.dy.launcher/com.dy.launcher.A11yService"

    @Test
    fun `merge keeps existing services and appends self`() {
        val raw = "$kd:$diyou"
        val merged = A11yList.merge(raw)
        assertEquals("$kd:$diyou:${A11yList.COMPONENT}", merged)
        assertTrue(A11yList.contains(merged))
    }

    @Test
    fun `merge is idempotent`() {
        val once = A11yList.merge("$kd:$diyou")
        val twice = A11yList.merge(once)
        assertEquals(once, twice)
    }

    @Test
    fun `merge handles empty and null literals`() {
        assertEquals(A11yList.COMPONENT, A11yList.merge(null))
        assertEquals(A11yList.COMPONENT, A11yList.merge(""))
        assertEquals(A11yList.COMPONENT, A11yList.merge("null"))
        assertEquals("$kd:${A11yList.COMPONENT}", A11yList.merge("$kd::null:"))
    }

    @Test
    fun `merge dedupes repeated entries`() {
        val merged = A11yList.merge("$kd:$kd:$diyou")
        assertEquals("$kd:$diyou:${A11yList.COMPONENT}", merged)
    }

    @Test
    fun `without only removes self`() {
        val raw = "$kd:${A11yList.COMPONENT}:$diyou"
        val without = A11yList.without(raw)
        assertEquals("$kd:$diyou", without)
        assertFalse(A11yList.contains(without))
    }

    /**
     * 现场事故回归：com.byd.clusterlab/.CarAccess 曾被 parse 丢掉（keyOf 归一失败 → continue），
     * 导致仪表 ClusterLab 无障碍直接失效。名单是全车机共用的，任何不认识条目都必须原样保留。
     */
    @Test
    fun `parse never drops unknown entries`() {
        val cluster = "com.byd.clusterlab/.CarAccess"
        val weird = "not-a-component"
        val raw = "$kd:$cluster:$weird"
        val merged = A11yList.merge(raw)
        assertEquals("$kd:$cluster:$weird:${A11yList.COMPONENT}", merged)
        assertTrue(merged.contains(cluster))
        assertTrue(merged.contains(weird))
    }

    @Test
    fun `without keeps unknown entries`() {
        val raw = "$kd:not-a-component:${A11yList.COMPONENT}"
        val out = A11yList.without(raw)
        assertTrue(out.contains(kd))
        assertTrue(out.contains("not-a-component"))
        assertFalse(A11yList.contains(out))
    }

    @Test
    fun `contains is exact component match not package prefix`() {
        val other = "com.byd.ls/com.byd.ls.service.OtherService"
        assertFalse(A11yList.contains(other))
        assertTrue(A11yList.contains("$other:${A11yList.COMPONENT}"))
    }
}
