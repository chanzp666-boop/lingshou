package com.byd.ls.service

import org.junit.Assert.assertEquals
import org.junit.Test

class A11yMergeOrderTest {

    @Test
    fun `order is stable across repeated read-modify-write`() {
        var raw = "a/a.A:b/b.B"
        repeat(5) { raw = A11yList.merge(raw) }
        assertEquals("a/a.A:b/b.B:${A11yList.COMPONENT}", raw)
    }
}
