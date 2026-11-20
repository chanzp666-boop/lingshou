package com.byd.ls.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * dumpsys 分段判定回归。
 *
 * 背景：1.2.34 用"全文 contains 本服务名"判"已绑定"，而 `Binding services` 段里同样会出现这个名字，
 * 于是"正在绑但没绑上"被判成"已绑定"——正是现场"状态显示正常、实际划不动"的同一类假绿。
 * 这里用真实 dumpsys 的字段顺序（Bound → Enabled → Binding）做三条断言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class A11yDumpsysSectionTest {

    private val cmp = "com.byd.ls/com.byd.ls.service.LsAccessibilityService"

    private fun dump(bound: String, binding: String) = """
        ACCESSIBILITY MANAGER (dumpsys accessibility)
          Bound services:{$bound
          }
          Enabled services:{{$cmp}, {com.van.diplus/com.van.diplus.service.KDService}}
          Binding services:{$binding
          }
    """.trimIndent()

    @Test
    fun `只在 Bound 段出现才算已绑定`() {
        val d = dump("Service[label=灵手 · 全屏手势, $cmp]", "")
        assertTrue(A11yHealer.boundSectionHas(d))
        assertFalse(A11yHealer.bindingSectionHas(d))
    }

    @Test
    fun `只在 Binding 段出现时不能算 Bound`() {
        val d = dump("", "{$cmp}")
        assertFalse("Binding 段出现不算已绑定", A11yHealer.boundSectionHas(d))
        assertTrue(A11yHealer.bindingSectionHas(d))
    }

    @Test
    fun `两段都没有时都判 false`() {
        val d = dump("Service[label=迪友桌面]", "")
        assertFalse(A11yHealer.boundSectionHas(d))
        assertFalse(A11yHealer.bindingSectionHas(d))
    }
}
