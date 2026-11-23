package com.byd.ls.cluster.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 迪加胎压“四轮完整才锁定”的判定。
 * 半套数据（例如只回了左前）不能锁死迪加，否则另外三轮会一直空着。
 */
class TyreSourceTest {

    @Test
    fun `four valid wheels lock`() {
        assertTrue(TyreSource.complete(intArrayOf(245, 247, 245, 245)))
    }

    @Test
    fun `partial or invalid wheels do not lock`() {
        assertFalse(TyreSource.complete(intArrayOf(245, -1, 245, 245)))
        assertFalse(TyreSource.complete(intArrayOf(0, 247, 245, 245)))
        assertFalse(TyreSource.complete(intArrayOf(245, 247, 245, 501)))
    }
}
