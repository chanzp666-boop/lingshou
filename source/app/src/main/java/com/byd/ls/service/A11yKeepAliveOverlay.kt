package com.byd.ls.service

import android.graphics.Color
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager
import com.byd.ls.util.LsLog

/**
 * GKD 同款保活窗：挂在无障碍服务内部的一个 1x1 透明 TYPE_ACCESSIBILITY_OVERLAY。
 *
 * 它不是手势条，不接收触摸，只让无障碍服务进程多一个可见窗口/更高的进程优先级，
 * 降低被 “应用加速管理” 整组回收的概率。窗口随 AccessibilityService 生命周期创建/移除；
 * 服务被系统重绑后会自动重新挂上。
 */
object A11yKeepAliveOverlay {

    private const val TAG = "A11yKeepAlive"
    private var view: View? = null
    private var wm: WindowManager? = null

    fun attach(service: LsAccessibilityService) {
        if (view != null) return
        runCatching {
            val manager = service.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
            val v = View(service).apply { setBackgroundColor(Color.TRANSPARENT) }
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            manager.addView(v, lp)
            view = v
            wm = manager
            LsLog.i(TAG, "保活窗已挂载（1x1 无障碍 overlay）")
        }.onFailure { LsLog.w(TAG, "保活窗挂载失败: ${it.message}") }
    }

    fun detach() {
        val v = view ?: return
        val manager = wm
        view = null
        wm = null
        runCatching { manager?.removeViewImmediate(v) }
            .onFailure { LsLog.w(TAG, "保活窗移除失败: ${it.message}") }
        LsLog.i(TAG, "保活窗已移除")
    }
}
