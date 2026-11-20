package com.byd.ls.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归 1.1.2 的“假已绑定”：只认 Binding services 段，不能被后面的 Enabled services 骗。
 */
class A11yDumpsysTest {

    private val self = A11yList.COMPONENT
    private val kd = "com.van.diplus/com.van.diplus.service.KDService"

    @Test
    fun `bound when self is in binding section`() {
        val dump = """
            Enabled services:
              $kd
            Binding services:
              $self
            Crashed services:
        """.trimIndent()
        assertTrue(A11yDumpsys.parseBinding(dump) == true)
    }

    @Test
    fun `not bound when binding section is empty even if enabled contains self`() {
        val dump = """
            Enabled services:
              $self
            Binding services:
              []
            Crashed services:
        """.trimIndent()
        assertFalse(A11yDumpsys.parseBinding(dump) == true)
    }

    @Test
    fun `not bound when inline empty array`() {
        val dump = """
            Enabled services:
              $self
            Binding services: []
            Crashed services:
              $self
        """.trimIndent()
        assertFalse(A11yDumpsys.parseBinding(dump) == true)
    }

    @Test
    fun `not bound when only other service is bound`() {
        val dump = """
            Binding services:
              $kd
            Enabled services:
              $self
        """.trimIndent()
        assertFalse(A11yDumpsys.parseBinding(dump) == true)
    }

    @Test
    fun `unknown when section missing`() {
        assertNull(A11yDumpsys.parseBinding("Enabled services:\n  $self"))
        assertNull(A11yDumpsys.parseBinding(""))
        assertNull(A11yDumpsys.parseBinding(null))
    }

    @Test
    fun `stopping at next section prevents enabled false positive`() {
        val dump = """
            Binding services:
              []
            Enabled services:
              $self
              $kd
        """.trimIndent()
        assertEquals(false, A11yDumpsys.parseBinding(dump))
    }
}
