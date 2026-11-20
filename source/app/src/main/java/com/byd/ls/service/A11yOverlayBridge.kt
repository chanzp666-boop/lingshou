package com.byd.ls.service

import android.content.Context
import android.view.WindowManager

/**
 * 悬浮窗宿主：决定用哪种 window type、哪个 WindowManager、是否需要 SYSTEM_ALERT_WINDOW。
 *
 *  · 无障碍已连接 → TYPE_ACCESSIBILITY_OVERLAY，用无障碍服务的 WindowManager，
 *    **不需要 SYSTEM_ALERT_WINDOW**；
 *  · 无障碍未连接 → 退回 TYPE_APPLICATION_OVERLAY + SYSTEM_ALERT_WINDOW。
 */
data class OverlayHost(
    val context: Context,
    val windowManager: WindowManager,
    val windowType: Int,
    val requiresPermission: Boolean,
    val label: String
)

/**
 * 无障碍 ↔ 前台手势服务的桥。
 *
 * GKD 的做法是把保活 overlay 直接挂在 AccessibilityService 里；灵手保留
 * GlobalGestureService 作为悬浮窗宿主，但把 window type/WindowManager 切到无障碍，
 * 并在无障碍连上时由本桥通知 FGS 重建悬浮条。这样：
 *  · 无障碍存活 = 手势可用，不需要 shell/ADB；
 *  · 无障碍被系统重绑 = onServiceConnected 再次触发 = 悬浮条自动回来；
 *  · 不再依赖 SYSTEM_ALERT_WINDOW 才能显示边缘条。
 */
object A11yOverlayBridge {

    @Volatile private var connected: LsAccessibilityService? = null

    /** FGS 注册；无障碍连上/断开时回调新的宿主 */
    @Volatile var listener: ((OverlayHost) -> Unit)? = null

    fun currentHost(app: Context): OverlayHost =
        connected?.let { a11yHost(it) } ?: appHost(app)

    fun a11yHost(service: LsAccessibilityService): OverlayHost = OverlayHost(
        context = service,
        windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager,
        windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        requiresPermission = false,
        label = "无障碍悬浮窗"
    )

    fun appHost(ctx: Context): OverlayHost = OverlayHost(
        context = ctx.applicationContext,
        windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager,
        windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        requiresPermission = true,
        label = "应用悬浮窗"
    )

    fun onConnected(service: LsAccessibilityService) {
        connected = service
        listener?.invoke(a11yHost(service))
    }

    fun onDisconnected(service: LsAccessibilityService) {
        if (connected === service) {
            connected = null
            listener?.invoke(appHost(service.applicationContext))
        }
    }

    fun isA11yConnected(): Boolean = connected != null
}
