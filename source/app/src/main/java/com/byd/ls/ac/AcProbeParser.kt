package com.byd.ls.ac

import java.util.regex.Pattern

/**
 * AC 探针的读值解析，纯逻辑，可 JVM 单测。
 *
 * 依据 DiLink 4.0 IAirConditioningService：
 *   14 getWindLevelButtonValue() -> int
 *   16 getMainTemperatureValue() -> String
 */
object AcProbeParser {

    const val WIND_MIN = 1
    const val WIND_MAX = 7
    /** 原厂温度档位：17=Lo，18…32，33=Hi。 */
    const val TEMP_MIN = 17
    const val TEMP_MAX = 33

    /** 读值命令（只读兜底；写一律走 AcSdk，service call 38/52 会被硬件拉回）。 */
    const val READ_WIND = "service call airconditioning 14"
    const val READ_TEMP = "service call airconditioning 16"

    /** 步进：读不到当前值时的默认基准与手指 App 实车口径一致。 */
    fun nextWind(current: Int?, dir: Int): Int =
        ((current ?: 3) + dir).coerceIn(WIND_MIN, WIND_MAX)

    fun nextTemp(current: Int?, dir: Int): Int =
        ((current ?: 24) + dir).coerceIn(TEMP_MIN, TEMP_MAX)

    private val P_HEX_WORD = Pattern.compile("([0-9a-fA-F]{8})")
    private val P_QUOTED = Pattern.compile("'([^']*)'")

    /** txn 14 输出：最后一个 8 位 hex 字，窗口 1..7。 */
    fun parseWind(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val last = P_HEX_WORD.matcher(raw).let { m ->
            var v: String? = null
            while (m.find()) v = m.group(1)
            v
        } ?: return null
        val value = last.toLong(16).toInt()
        return if (value in WIND_MIN..WIND_MAX) value else null
    }

    /** txn 16 输出：引号里是 Parcel 的可打印视图；Lo/Hi 映射 17/33。 */
    fun parseTemp(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val q = P_QUOTED.matcher(raw)
        var best: String? = null
        while (q.find()) best = q.group(1)
        val cleaned = best?.replace("\u0000", "")?.replace("\\u0000", "")?.trim()
        if (cleaned.isNullOrEmpty()) return null
        when (cleaned) {
            "Lo" -> return TEMP_MIN
            "Hi" -> return TEMP_MAX
        }
        val number = Regex("\\d+(?:\\.\\d+)?").find(cleaned)?.value?.toDoubleOrNull()
        if (number != null && number >= TEMP_MIN && number <= TEMP_MAX) {
            return number.toInt()
        }
        // Parcel 的可打印视图可能把 24 显示成 "2.4"：先按小数判断，
        // 不在 17..33 时再回退到“去掉非数字”，与手指 App 的解析口径一致。
        val digits = cleaned.filter { it.isDigit() }.toIntOrNull() ?: return null
        return if (digits in TEMP_MIN..TEMP_MAX) digits else null
    }
}
