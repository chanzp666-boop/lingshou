package com.byd.ls.service

/**
 * 半绑写回的**命令行与读回校验**（1.4.119，口径取自 BYDMate 的
 * HelperDaemon.enableAccessibility：它把「写名单」做成四步而不是一步）。
 *
 * 四步：
 *  1. 名单先写成「不含我们」—— 打断 AMS 里那条死连接（本车 1.4.88 实测：只追加不删=无效）；
 *  2. 再写回「含我们」；
 *  3. 显式补 `accessibility_enabled=1` —— BYDMate 现场踩过「名单写成功、开关却是 0，框架永不 bind」；
 *  4. `settings get` 读回两条值再判定，而不是只看命令退出码（shell 退出码 0 只代表 settings 命令跑了）。
 *
 * 本对象只做字符串拼装与解析，IO 仍由 [A11yHealer] 走 shell 通道，所以可以直接单测。
 *
 * （类名不叫 A11yRebind：那个名字已经是 A11yHealer 定向重绑的 Robolectric 用例，别混。）
 */
object A11ySettingsScript {

    private const val Q = "'"

    /** 半绑修复脚本：去 → 回 → 补开关 → 读回。末尾两条 get 的输出给 [verify] 解析。 */
    fun command(full: String, without: String, sleepMs: Int = 250): String =
        "settings put secure enabled_accessibility_services $Q$without$Q; sleep ${sec(sleepMs)}; " +
        "settings put secure enabled_accessibility_services $Q$full$Q; sleep ${sec(sleepMs)}; " +
        "settings put secure accessibility_enabled 1; sleep ${sec(sleepMs)}; " +
        "settings get secure enabled_accessibility_services; settings get secure accessibility_enabled"

    /**
     * 1.4.130（吸收 BYDMate A11yDaemon.recoverAccessibilityService）：AOSP Q 上服务进程被
     * quickboot force-stop 后，组件会卡在 AccessibilityManagerService 的 UserState.mBindingServices，
     * 只有 PackageMonitor.onHandleForceStop 能清它 —— 也就是必须真的 force-stop 一次我们的包。
     * settings 改写（[command] 那四步）在这种状态下完全无效。
     *
     * 顺序（与 BYDMate 一致，拉起走**我们自己的** RestartReceiver，它出来本地 startForegroundService）：
     *  1. am force-stop 自己 —— app 进程死，脚本必须由 shell 域后台执行，不能在 app 里等；
     *  2. sleep 0.7 让 AMS 处理 onHandleForceStop；
     *  3. 显式广播拉起（--include-stopped-packages 必需：force-stop 把包放进 stopped 状态；
     *     --receiver-foreground 让它别排在其他 BOOT_COMPLETED 接收器后面，实测差 11–44 秒）；
     *  4. sleep 1 后重写四步名单（force-stop 会把我们从 enabled 集合里摘掉）。
     */
    fun recoverCommand(full: String, without: String, pkg: String = "com.byd.ls",
                       receiverClass: String = "com.byd.ls.receiver.RestartReceiver",
                       action: String = "com.byd.ls.action.RESTART"): String =
        "am force-stop $pkg; sleep 0.7; " +
        "am broadcast --include-stopped-packages --receiver-foreground -a $action -n $pkg/$receiverClass; " +
        "sleep 1; " + command(full, without)

    /** 毫秒转 shell 的 seconds 字面量（0.25 / 1）；整秒写成 1 而不是 1.0，日志更好读。 */
    private fun sec(ms: Int): String {
        val m = if (ms < 0) 0 else ms
        return if (m % 1000 == 0) (m / 1000).toString() else (m / 1000.0).toString()
    }

    /** 统一组件写法：`pkg/.Cls` 与 `pkg/pkg.Cls` 归一成 `pkg/pkg.Cls`。 */
    fun canonical(entry: String): String {
        val e = entry.trim()
        val i = e.indexOf('/')
        if (i <= 0) return e
        val pkg = e.substring(0, i)
        var cls = e.substring(i + 1)
        if (cls.startsWith(".")) cls = pkg + cls
        return pkg + "/" + cls
    }

    /**
     * 读回判定：输出里必须同时有
     *  · `accessibility_enabled` 的最后一条为 1；
     *  · 名单里含目标组件（按 [canonical] 比，`pkg/.Cls` 与全写等价）。
     */
    fun verify(out: String?, component: String): Boolean {
        if (out.isNullOrBlank()) return false
        val lines = out.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val flag = lines.lastOrNull { it == "0" || it == "1" } ?: return false
        val services = lines.lastOrNull { it.contains('/') && it.contains(':') } ?: return false
        val self = canonical(component)
        val hit = services.split(':').any { it.isNotBlank() && canonical(it) == self }
        return flag == "1" && hit
    }
}
