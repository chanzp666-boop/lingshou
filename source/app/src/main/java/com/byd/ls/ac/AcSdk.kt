package com.byd.ls.ac

import android.content.Context
import android.util.Log
import com.byd.ls.shell.BydDirect

/**
 * AC 写入探针用的最小 SDK 路径。
 *
 * 只做一件事：从 **com.byd.ls** 包名直接调用
 * `BYDAutoDeviceManager.setIntArray`，验证 BYD Binder 侧是否还校验权限。
 *
 * 这段实现照手指 App 已实车验证的 BydAcSdk：
 *  · 风量：AC_WIND_LEVEL_SET + AC_CTRL_SOURCE_SET，值 [level, 0]
 *  · 温度：AC_TEMPERATURE_UNIT_SET + AC_TEMP_MAIN_SET + AC_TEMP_DEPUTY_SET
 *          + AC_CTRL_SOURCE_SET，值 [0, level, level, 0]
 *
 * 不写 service call 38/52；那两条在手指 App 上实测会被硬件状态拉回，
 * 只能作为读/调试兜底。
 */
object AcSdk {

    private const val TAG = "AcSdk"
    private const val MANAGER = "android.hardware.bydauto.BYDAutoDeviceManager"
    private const val AC_DEVICE = "android.hardware.bydauto.ac.BYDAutoAcDevice"
    private const val FEATURE_IDS = "android.hardware.bydauto.BYDAutoFeatureIds"

    @Volatile
    private var appContext: Context? = null

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }

    /** 成功返回 null；失败返回可展示的原因。 */
    fun setWind(level: Int): String? = setInts(
        featureNames = arrayOf("AC_WIND_LEVEL_SET", "AC_CTRL_SOURCE_SET"),
        values = intArrayOf(level.coerceIn(1, 7), 0),
        tag = "setWind level=$level"
    )

    /** 统一温度：主驾、副驾同时写，避免只改主驾分控。 */
    fun setTemp(level: Int, unified: Boolean = true): String? = setInts(
        featureNames = arrayOf(
            "AC_TEMPERATURE_UNIT_SET",
            "AC_TEMP_MAIN_SET",
            "AC_TEMP_DEPUTY_SET",
            "AC_CTRL_SOURCE_SET"
        ),
        values = intArrayOf(0, level.coerceIn(17, 33), if (unified) level.coerceIn(17, 33) else 0, 0),
        tag = "setTemp level=$level unified=$unified"
    )

    private fun setInts(featureNames: Array<String>, values: IntArray, tag: String): String? {
        val ctx = appContext ?: return "no context; call AcSdk.init() first"

        // BYDAutoDeviceManager 是 hidden API；先解除限制，调用失败不要继续。
        if (!BydDirect.bypassHiddenApi()) {
            return "hidden API 豁免失败: ${BydDirect.lastError}"
        }

        return try {
            val mgrClass = Class.forName(MANAGER)
            val mgr = mgrClass.getMethod("getInstance", Context::class.java)
                .invoke(null, ctx) ?: return "manager null"

            val deviceType = Class.forName(AC_DEVICE)
                .getDeclaredField("mDeviceType")
                .apply { isAccessible = true }
                .getInt(null)

            val ids = Class.forName(FEATURE_IDS)
            val featureIds = featureNames.map { ids.getField(it).getInt(null) }.toIntArray()

            val ret = mgrClass.getMethod(
                "setIntArray",
                Int::class.javaPrimitiveType,
                IntArray::class.java,
                IntArray::class.java
            ).invoke(mgr, deviceType, featureIds, values) as Int

            Log.d(TAG, "$tag ret=$ret")
            if (ret == 0) null else "ret=$ret"
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            Log.w(TAG, "$tag failed: ${cause.javaClass.simpleName}: ${cause.message}")
            "${cause.javaClass.simpleName}: ${cause.message}"
        }
    }
}
