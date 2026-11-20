package com.byd.ls.service

/**
 * `dumpsys accessibility` 各段解析（纯函数，方便 JVM 单测）。
 *
 * 段语义：
 *  · Binding services：AMS 已经发出 bind 请求，但服务还没连上（未完成）；
 *  · Bound services：真正连上了。
 * 现场「dumpsys=true 但已绑定=false」就是把 Binding 段当成了已绑定。
 *
 * 注意 Bound 段里只打服务 label，不打组件名——认自己要用 label 前缀。
 */
object A11yDumpsys {

    /** 无障碍服务在 Bound 段里的显示名前缀（来自 @string/accessibility_label） */
    const val LABEL_PREFIX = "灵手 · 全屏手势"

    private val SECTION_HEADS = listOf(
        "Bound services:",
        "Binding services:",
        "Enabled services:"
    )

    /**
     * Binding 段里是否出现本服务。
     *
     *  · 1.1.2 的旧实现从索引往后扫 400 字符，会把后面的 Enabled services 里的自己
     *    误判成已绑定，导致自愈不去重绑；
     *  · 这里遇到空行/下一个段头立即停止，段内出现 "[]" 直接判未绑定。
     */
    fun parseBinding(
        text: String?,
        component: String = A11yList.COMPONENT,
        serviceClass: String = A11yList.SERVICE_CLASS
    ): Boolean? = parseSection(text, "Binding services:", component, serviceClass)

    /**
     * Bound 段里是否出现本服务 —— 只有这里出现才算真正绑定。
     *
     * Bound 段只打 label（如 `Service[label=灵手 · 全屏手势（键盘避…`），组件名不会出现，
     * 所以除了组件名还要按 [LABEL_PREFIX] 认。
     */
    fun parseBound(
        text: String?,
        component: String = A11yList.COMPONENT,
        serviceClass: String = A11yList.SERVICE_CLASS
    ): Boolean? = parseSection(text, "Bound services:", component, serviceClass, LABEL_PREFIX)

    /**
     * 悬空态：AMS 卡在 Binding（发过 bind 却没连上），且 Bound 段里也没有本服务。
     *
     * 这种状态下写 settings 不会让它恢复（AMS 比较后发现值没变，直接跳过重新绑定），
     * 必须走更硬的恢复动作。
     */
    fun parseStuck(
        text: String?,
        component: String = A11yList.COMPONENT,
        serviceClass: String = A11yList.SERVICE_CLASS
    ): Boolean {
        if (parseBinding(text, component, serviceClass) != true) return false
        return parseBound(text, component, serviceClass) != true
    }

    private fun parseSection(
        text: String?,
        header: String,
        component: String,
        serviceClass: String,
        labelPrefix: String? = null
    ): Boolean? {
        if (text.isNullOrBlank()) return null
        val lines = text.lines()
        val start = lines.indexOfFirst { it.trim().startsWith(header) }
        if (start < 0) return null

        fun hit(s: String): Boolean =
            s.contains(component) || s.contains(serviceClass) ||
                (labelPrefix != null && s.contains(labelPrefix))

        val inline = lines[start].substringAfter(header).trim()
        if (inline.startsWith("[]")) return false
        if (hit(inline)) return true

        var sawContent = false
        for (i in start + 1 until lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) {
                if (sawContent) break else continue
            }
            if (line == "[]") return false
            if (SECTION_HEADS.any { line.startsWith(it) } || line.startsWith("Window[")) break
            if (line.endsWith(":")) break
            if (hit(line)) return true
            sawContent = true
        }
        return false
    }
}
