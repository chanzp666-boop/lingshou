package com.byd.ls.ac

import android.content.Context
import com.byd.ls.shell.ShellManager
import kotlinx.coroutines.delay

/**
 * 灵手包名下的 AC 写入探针。
 *
 * 只验证两件事：
 *  1. `BYDAutoDeviceManager.setIntArray` 在 com.byd.ls 里是否返回 0；
 *  2. `service call airconditioning 14/16` 能否读回实际风量/温度。
 *
 * 不启动手势、不接服务、不改设置。
 */
object AcProbe {

    const val WIND = 0
    const val TEMP = 1
    const val BOTH = 2
    const val READ = 3

    const val TEST_WIND = 5
    const val TEST_TEMP = 24

    suspend fun run(ctx: Context, kind: Int): String {
        AcSdk.init(ctx)
        val sb = StringBuilder()
        if (kind == READ) {
            sb.append("只读，不改值\n")
        }

        if (kind == WIND || kind == BOTH) {
            sb.append("写风量 $TEST_WIND：")
                .append(AcSdk.setWind(TEST_WIND) ?: "ret=0 成功")
                .append('\n')
        }
        if (kind == TEMP || kind == BOTH) {
            sb.append("写温度 $TEST_TEMP：")
                .append(AcSdk.setTemp(TEST_TEMP) ?: "ret=0 成功")
                .append('\n')
        }

        // 给车机一点时间落值；读回走 shell 14/16，和手指 App 的验证口径一致。
        delay(500L)

        if (kind == WIND || kind == BOTH || kind == READ) {
            sb.append("回读风量：").append(readWind(ctx)).append('\n')
        }
        if (kind == TEMP || kind == BOTH || kind == READ) {
            sb.append("回读温度：").append(readTemp(ctx)).append('\n')
        }
        return sb.toString().trimEnd()
    }

    private suspend fun readWind(ctx: Context): String {
        val r = ShellManager.exec(ctx, "service call airconditioning 14", 5_000L)
        if (!r.success) {
            return "失败 exit=${r.exitCode} ${r.stdout.take(80)}".trim()
        }
        return AcProbeParser.parseWind(r.stdout)?.toString()
            ?: "解析失败 ${r.stdout.take(80)}".trim()
    }

    private suspend fun readTemp(ctx: Context): String {
        val r = ShellManager.exec(ctx, "service call airconditioning 16", 5_000L)
        if (!r.success) {
            return "失败 exit=${r.exitCode} ${r.stdout.take(80)}".trim()
        }
        return AcProbeParser.parseTemp(r.stdout)?.toString()
            ?: "解析失败 ${r.stdout.take(80)}".trim()
    }
}
