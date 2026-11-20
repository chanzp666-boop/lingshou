package com.byd.ls.shell

/**
 * 写车白名单 + 命令翻译（第二档第 7 条，1.4.131）。**只翻译与校验，不真写。**
 *
 * 设计依据（BYDMate 的写车纪律）：写车动作必须从「高层意图」翻译成 `(deviceId, fid, raw)`，
 * 并且在发出去之前做完三道检查 —— 动作在白名单里、fid 在 FidCatalog 里且 kind 对得上、
 * 取值在值域/枚举里。白名单外一律拒绝，不做任何兜底猜测。
 *
 * 为什么要翻译而不是让调用方直接给 dev/fid：
 *  · 调用方（远程操作台、自动化脚本）看不到 FidCatalog 的定标细节，直接给 raw 等于绕过定标；
 *  · 同址别名（如 range 曾经与 evRangeKm 同址）会让「写 A 改 B」，翻译层按名字取唯一 fid 可挡住；
 *  · 值域错误在车上是真实风险（空调温度写 260 可能被固件当 2600），必须在发出前拒绝。
 *
 * 现在 helper 只有读通道（txn 9/5/7），所以 [plan] 的产物只有「能不能写 + 该写什么」，真写等
 * setInt/setFloat 通道落地后接进 [Plan.raw]。这也是 dry-run 的意义。
 */
object WriteGuard {

    /** 一条可写动作：高层名 → 目标 fid + 取值规则。 */
    class Rule(
        val action: String,
        val fidName: String,
        val kind: FidCatalog.Kind,
        val min: Double,
        val max: Double,
        val allowed: List<Int>?,
        val unit: String,
        val note: String,
    )

    /** 翻译结果。ok=false 时 [reason] 说明为什么拒绝，[raw] 恒为 null。 */
    class Plan(
        val action: String,
        val ok: Boolean,
        val dev: Int,
        val fid: Int,
        val raw: Int?,
        val human: Double?,
        val unit: String,
        val reason: String,
    )

    /**
     * 白名单：只放实车读过、语义确定的动作。
     * 空调四条来自 1.3.x 三指手势实测（`Ac.AC_WIND_LEVEL` 实读 0–7）；车门窗与尾门两条来自
     * 1.4.129 的符号表审计（windowRR 已改到 1001:1267728408，实读 0 = 窗关）。
     */
    val rules: List<Rule> = listOf(
        Rule("ac.power", "acStatus", FidCatalog.Kind.INT_ENUM, 0.0, 1.0, listOf(0, 1), "", "空调总开关：0 关 1 开"),
        Rule("ac.temp", "acTemp", FidCatalog.Kind.INT_TEMP_C, 16.0, 32.0, null, "°C", "主驾温度：车机接受整数摄氏度"),
        Rule("ac.wind", "fanLevel", FidCatalog.Kind.INT_RAW, 0.0, 7.0, null, "级", "风量：实车 0–7"),
        Rule("ac.cycle", "acCirc", FidCatalog.Kind.INT_ENUM, 0.0, 2.0, listOf(0, 1, 2), "", "循环模式：0 外循环 1 内循环 2 自动"),
        Rule("window.rearRight", "windowRR", FidCatalog.Kind.INT_PERCENT, 0.0, 100.0, null, "%", "右后窗开度：0 关 100 全开（车机给的是百分比，不是开关）"),
    )

    val actions: List<String> = rules.map { it.action }

    /** 在线动作清单（远程操作台用）。 */
    fun catalog(): List<Map<String, Any?>> = rules.map { r ->
        mapOf(
            "action" to r.action,
            "fid" to r.fidName,
            "dev" to FidCatalog.byName[r.fidName]?.dev,
            "fidNo" to FidCatalog.byName[r.fidName]?.fid,
            "min" to r.min,
            "max" to r.max,
            "allowed" to r.allowed,
            "unit" to r.unit,
            "note" to r.note,
        )
    }

    /**
     * 翻译 + 三道校验。任何一道不过就返回 ok=false，绝不返回「近似值」。
     * 纯函数，无 IO —— 所以能直接在单测里跑。
     */
    fun plan(action: String, value: Double): Plan {
        val r = rules.firstOrNull { it.action == action.trim() }
            ?: return reject(action, value, "不在写车白名单（可用：" + actions.joinToString(", ") + "）")
        if (value.isNaN() || value.isInfinite()) return reject(action, value, "取值不是有限数")
        val f = FidCatalog.byName[r.fidName]
            ?: return reject(action, value, "白名单引用的 fid " + r.fidName + " 不在 FidCatalog 里")
        if (f.kind != r.kind) {
            return reject(action, value, "kind 不一致：表里是 " + f.kind + "，白名单写的是 " + r.kind +
                "（FidCatalog 改过而白名单没跟着改）")
        }
        // 枚举动作只收整数且在集合里；区间动作只收区间，且不做小数截断（截断=静默改值）。
        if (value != Math.floor(value)) {
            return reject(action, value, "该动作只接受整数（" + r.unit + "），收到 " + value)
        }
        val v = value.toInt()
        val allowed = r.allowed
        if (allowed != null) {
            if (v !in allowed) {
                return reject(action, value, "取值必须是 " + allowed.joinToString("/") + " 之一，收到 " + v)
            }
        } else if (value < r.min || value > r.max) {
            return reject(action, value, "超出值域 " + r.min + "–" + r.max + " " + r.unit + "，收到 " + value)
        }
        // 高层值 → raw：raw = round((value - offset) / scale)。scale=0 视为坏表。
        if (f.scale == 0.0) return reject(action, value, "fid " + f.name + " 的 scale 为 0，换算会得到无穷大")
        val raw = Math.round((value - f.offset) / f.scale).toInt()
        val human = raw * f.scale + f.offset
        return Plan(action, true, f.dev, f.fid, raw, human, r.unit, "ok")
    }

    private fun reject(action: String, value: Double, why: String) =
        Plan(action, false, -1, -1, null, null, "", why + "（value=" + value + "）")

    /** 人类可读的一行，用于日志与远程回复。 */
    fun describe(p: Plan): String =
        if (!p.ok) "拒绝 " + p.action + "：" + p.reason
        else "计划 " + p.action + " → dev=" + p.dev + " fid=" + p.fid + " raw=" + p.raw +
            "（回读 " + p.human + " " + p.unit + "）"
}
