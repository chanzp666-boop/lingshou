package com.byd.ls.ac

import android.content.Context
import com.byd.ls.shell.ShellManager

/**
 * 三指空调控制器：风量/温度都走 BYD SDK 底层特征写入，不弹空调页。
 *
 * 沿用手指 App 已实车验证的口径：
 *  · 风量写 AC_WIND_LEVEL_SET + AC_CTRL_SOURCE_SET；
 *  · 温度同时写 AC_TEMP_MAIN_SET + AC_TEMP_DEPUTY_SET，主副驾统一；
 *  · service call 14/16 只用于读当前值，不参与写入。
 */
object AcController {

    @Volatile
    var windLevel: Int = -1
        private set

    @Volatile
    var tempValue: Int = -1
        private set

    /** 每次成功写温度都递增；异步 syncTemp 回来时用它丢弃过期读数。 */
    @Volatile
    private var tempEpoch: Int = 0

    /** 启动时读一次当前值；读不到就保持 -1，下一步用默认基准。 */
    suspend fun prime(ctx: Context) {
        if (windLevel < 0) windLevel = readWind(ctx) ?: -1
        if (tempValue < 0) tempValue = readTemp(ctx) ?: -1
    }

    /** 新手势开始时异步同步一次实车温度；读回来时若已被本地写温度超越，就丢弃。 */
    suspend fun syncTemp(ctx: Context) {
        val epoch = tempEpoch
        val read = readTemp(ctx)
        if (read != null && epoch == tempEpoch) tempValue = read
    }

    suspend fun apply(ctx: Context, kind: GestureEngine.Kind, dir: Int): String {
        return when (kind) {
            GestureEngine.Kind.WIND -> {
                val current = windLevel.takeIf { it > 0 } ?: readWind(ctx)
                val next = AcProbeParser.nextWind(current, dir)
                val err = AcSdk.setWind(next)
                if (err == null) {
                    windLevel = next
                    AcTickPlayer.play()
                    "风量=$next"
                } else {
                    "风量=$next(SDK失败:$err)"
                }
            }
            GestureEngine.Kind.TEMP -> {
                val current = tempValue.takeIf { it > 0 } ?: readTemp(ctx)
                val next = AcProbeParser.nextTemp(current, dir)
                val err = AcSdk.setTemp(next, unified = true)
                if (err == null) {
                    // 先递增 epoch，再写缓存；在途的 syncTemp 回来时会被判定过期。
                    tempEpoch++
                    tempValue = next
                    AcTickPlayer.play()
                    "温度=$next(SDK统一)"
                } else {
                    "温度=$next(SDK失败:$err)"
                }
            }
        }
    }

    private suspend fun readWind(ctx: Context): Int? {
        val r = ShellManager.exec(ctx, AcProbeParser.READ_WIND, 3_000L)
        if (!r.success) return null
        return AcProbeParser.parseWind(r.stdout)
    }

    private suspend fun readTemp(ctx: Context): Int? {
        val r = ShellManager.exec(ctx, AcProbeParser.READ_TEMP, 3_000L)
        if (!r.success) return null
        return AcProbeParser.parseTemp(r.stdout)
    }
}
