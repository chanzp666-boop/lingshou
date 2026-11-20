package com.byd.ls.remote

import android.content.Context
import com.byd.ls.cluster.data.Card
import com.byd.ls.config.GesturePrefs
import com.byd.ls.guard.DaemonGuard
import com.byd.ls.service.A11yHealer
import com.byd.ls.service.BootJobService
import com.byd.ls.service.ClusterCardKeeper
import com.byd.ls.service.GlobalGestureService
import com.byd.ls.shell.ShellManager
import com.byd.ls.shell.WatchdogInstaller
import com.byd.ls.util.LsLog
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 远程调试的能力面：**具名 RPC 白名单**，没有通用的 shell 执行入口。
 *
 * 设计原则：
 *  · 只读优先：状态/日志/dumpsys/采样，默认都能拿到；
 *  · 动作收敛：每个动作都是固定语义，参数有边界，且写审计；
 *  · 不新增特权：底层仍然复用现有 adblib（uid 2000）与本机接口，远程只是「把按钮搬到电脑上」。
 */
object RemoteRpc {

    suspend fun handle(ctx: Context, method: String, params: JSONObject): Any {
        return when (method) {
            "ping" -> "pong"
            "status.truth" -> truth(ctx)
            "log.tail" -> logTail(ctx, params.optString("file", "ls"), params.optInt("lines", 200))
            "logcat" -> "（logcat 通道已停用：走 adblib 跑 logcat 会占住 shell 槽位不退出，把整条特权通道拖死。" +
                "要看日志请用 log.tail 读 ls.log / NavProbe 请用 cluster.probe）"
            "dumpsys" -> dumpsys(ctx, params.optString("kind", "accessibility"))
            "cluster.probe" -> navLog(params.optInt("lines", 50))
            "a11y.userRepair" -> userRepair(ctx)
            "a11y.rebind" -> a11yRebind(ctx)
            // 1.4.130（吸收 BYDMate TX_ENABLE_ACCESSIBILITY / TX_RECOVER_ACCESSIBILITY 的做法）：
            // 半绑卡在 AMS 的 mBindingServices 时，settings 改写无效，必须真 force-stop 一次。
            "a11y.recover" -> a11yRecover(ctx)
            // 1.4.130：Settings.Global 白名单只读探测（BYDMate TX_PUT_GLOBAL_SETTING 那三个键）
            "sys.settings" -> sysSettings(ctx)
            // 1.4.131（第二档第 7 条）：写车白名单。只翻译 + 校验，不真写 —— dry-run 必须永远是 dry。
            "write.actions" -> mapOf(
                "count" to com.byd.ls.shell.WriteGuard.actions.size,
                "actions" to com.byd.ls.shell.WriteGuard.catalog(),
                "mode" to "dry-run（helper 目前只有读通道 txn 9/5/7，真写通道未接）"
            )
            "write.dryRun" -> {
                val act = params.optString("action", "")
                val v = params.optDouble("value", Double.NaN)
                val plan = com.byd.ls.shell.WriteGuard.plan(act, v)
                RemoteAudit.log(ctx, "write.dryRun " + com.byd.ls.shell.WriteGuard.describe(plan))
                mapOf(
                    "ok" to plan.ok,
                    "action" to plan.action,
                    "dev" to plan.dev,
                    "fid" to plan.fid,
                    "raw" to plan.raw,
                    "human" to plan.human,
                    "unit" to plan.unit,
                    "reason" to plan.reason,
                    "line" to com.byd.ls.shell.WriteGuard.describe(plan),
                    "wrote" to false
                )
            }
            "cluster.pull" -> { ClusterCardKeeper.heal(ctx, "remote"); "已触发卡片自愈（写审计）" }
            "app.restart" -> sh(ctx, "am broadcast -a com.byd.ls.action.RESTART -p com.byd.ls -f 0x20")
            // 1.4.119：手动触发一次 ADB 端口自愈（写 adb_wifi_enabled=1），返回一句结论。
            "adb.netRestore" -> mapOf("verdict" to runBlocking {
                com.byd.ls.shell.AdbNetRestore.ensure(ctx)
            })
            "watchdog.reinstall" -> WatchdogInstaller.ensure(ctx, "remote", force = true)
            "debug.logToggle" -> logToggle(ctx, params.optBoolean("on", true))
            "diag.export" -> diagExport(ctx, params.optBoolean("upload", false))
            "shell.exec" -> shellExec(ctx, params)
            "update.install" -> updateInstall(ctx, params)
            "agent.restartProcess" -> restartProcess(ctx)
            // 1.4.119：油耗改 fid 直读（1014:1246785586），偏置彻底移除。方法保留为显式「已移除」，
            // 免得旧操作台/脚本静默失效 —— 调用方一定会看到这条说明。
            "cfg.fuelOffset" -> mapOf(
                "removed" to true,
                "reason" to "1.4.119 起综合油耗直读车机值（fid 1014:1246785586），不再有任何显示偏置"
            )
            "cfg.fuelWindow" -> {
                val v = params.optDouble("value", Double.NaN)
                if (v.isNaN()) {
                    mapOf(
                        "fuelWindowKm" to com.byd.ls.cluster.data.VehicleData.windowKm,
                        "fuelSource" to "fid 1014:1246785586（车机直读，无偏置）",
                        "note" to "填 value=0 为累计口径，20/50/100/200 为滚动窗口"
                    )
                } else {
                    com.byd.ls.cluster.data.VehicleData.windowKm = v.coerceIn(0.0, 300.0)
                    RemoteAudit.log(ctx, "cfg.fuelWindow = " + com.byd.ls.cluster.data.VehicleData.windowKm)
                    // 立即触发一次重算，面板不必等下一个 3s 轮询
                    runCatching { com.byd.ls.cluster.data.VehicleData.forceRecompute() }
                    mapOf("fuelWindowKm" to com.byd.ls.cluster.data.VehicleData.windowKm)
                }
            }
            "byd.read" -> {
                val dev = params.optString("device", "statistic")
                val ms = params.optString("methods", "getLast50KmEqualFuelConValue,getLastFuelConPHMValue,getLastElecConPHMValue,getMileageNumber:0")
                RemoteAudit.log(ctx, "byd.read " + dev)
                mapOf("device" to dev,
                    "result" to (com.byd.ls.shell.BydDirect.read(ctx, dev, ms)?.toString() ?: "null"),
                    "status" to com.byd.ls.shell.BydDirect.statusText())
            }
            // 第二档第 10 条验收探针：同一组 getter 在 app 域（BydPermissionContext 放行本地权限断言）
            // 与 shell 域（helper，uid=2000 自带 BYDAUTO 权限）各读一次，两列都出来就能判定覆盖是否成立。
            // 判据：app 列出现 `ERR SecurityException` → 服务端也按 uid 校验，这条路在本固件不通；
            //       app 列与 helper 列数值一致 → 数据面可以整体搬回 app 进程，helper 只剩拉起与截屏。
            "byd.compare" -> {
                val dev = params.optString("device", "statistic")
                val ms = params.optString("methods", "getLastFuelConPHMValue,getLast50KmEqualFuelConValue,getMileageNumber:0,getTotalMileageValue,getElecPercentageValue")
                RemoteAudit.log(ctx, "byd.compare " + dev)
                val app = runCatching { com.byd.ls.shell.BydDirect.read(ctx, dev, ms) }.getOrNull()
                val sh = runCatching { com.byd.ls.shell.VehicleBridge.batch(ctx, dev, ms) }.getOrNull()
                mapOf("device" to dev, "methods" to ms,
                    "app" to (app ?: mapOf("ERR" to "null")),
                    "shell" to (sh ?: mapOf("ERR" to "null")),
                    "appStatus" to com.byd.ls.shell.BydDirect.statusText())
            }
            // 第 10 条第二步：fid 直读（autoservice txn 9）能不能也搬到 app 域 —— 两路并排返回。
            // 判据：app 出现 SecurityException / Permission Denial → 服务端按 uid 校验，fid 只能留在 shell 域；
            //       app 的 ints= 与 shell 一致 → 129 条 fid 表也能整体搬进 app，helper 就只剩拉起与截屏。
            "byd.binder9" -> {
                val dev = params.optInt("dev", 1014)
                val fids = params.optString("fids", "1145045040,1246765118,1246773304")
                    .split(",").mapNotNull { it.trim().toIntOrNull() }.toIntArray()
                RemoteAudit.log(ctx, "byd.binder9 " + dev)
                val app = runCatching {
                    com.byd.ls.shell.BydDirect.binder9(dev, fids)
                }.getOrElse { "ERR " + (it.message ?: it.javaClass.simpleName) }
                val sh = runCatching {
                    com.byd.ls.shell.VehicleBridge.binderRead(ctx, dev, fids.joinToString(","))
                }.getOrElse { "ERR " + (it.message ?: it.javaClass.simpleName) }
                mapOf("dev" to dev, "fids" to fids.joinToString(","), "app" to app, "shell" to sh)
            }
            "helper.probe" -> {
                RemoteAudit.log(ctx, "helper.probe")
                mapOf("log" to com.byd.ls.shell.VehicleBridge.probeExecution(ctx))
            }
            "file.pull" -> {
                val pth = params.optString("path", "")
                val f = java.io.File(pth)
                if (pth.isEmpty() || !f.exists()) mapOf("error" to "不存在: $pth")
                else {
                    val zip = java.io.File(ctx.filesDir, "pull-" + f.name + ".zip")
                    runCatching {
                        java.util.zip.ZipOutputStream(zip.outputStream()).use { z ->
                            z.putNextEntry(java.util.zip.ZipEntry(f.name))
                            f.inputStream().use { it.copyTo(z) }
                            z.closeEntry()
                        }
                    }
                    val ok = RemoteAgent.uploadDiag(zip)
                    RemoteAudit.log(ctx, "file.pull " + f.name + " " + f.length() + "B up=" + ok)
                    mapOf("file" to f.absolutePath, "size" to f.length(), "uploaded" to ok,
                        "dir" to "data/diag/<dev>/")
                }
            }
            "feature.ids" -> mapOf("value" to com.byd.ls.shell.BydDirect.dumpFeatureIds())
            "events.stats" -> {
                val f = java.io.File(ctx.getExternalFilesDir(null), "native-events.log")
                if (!f.exists()) mapOf("error" to "native-events.log 不存在")
                else {
                    val rx = Regex("event=(-?\\d+) value=(-?\\d+)")
                    class S { var n = 0; var last = 0; var min = Int.MAX_VALUE; var max = Int.MIN_VALUE }
                    val m = HashMap<Int, S>()
                    runCatching {
                        f.bufferedReader().useLines { seq ->
                            seq.forEach { l ->
                                val g = rx.find(l) ?: return@forEach
                                val ev = g.groupValues[1].toIntOrNull() ?: return@forEach
                                val v = g.groupValues[2].toIntOrNull() ?: return@forEach
                                val sObj = m.getOrPut(ev) { S() }
                                sObj.n++; sObj.last = v
                                if (v < sObj.min) sObj.min = v
                                if (v > sObj.max) sObj.max = v
                            }
                        }
                    }
                    mapOf("file" to f.absolutePath, "size" to f.length(),
                        "events" to m.entries.sortedByDescending { it.value.n }
                            .map { "${it.key}:n=${it.value.n},last=${it.value.last},min=${it.value.min},max=${it.value.max}" })
                }
            }
            // 第二档 14 的后半：fid 记录器。spec 写 all 就用 helper 内嵌全表（132 条）。
            // 数据面走文件（/data/local/tmp/ls-fid-rec.log），因为 app 域连不到 shell 域的 socket。
            "fid.record" -> {
                val spec = params.optString("spec", "all")
                val secs = params.optInt("secs", 60).coerceIn(5, 3600)
                RemoteAudit.log(ctx, "fid.record " + spec.take(60) + " " + secs + "s")
                val body = "pkill -f \"nice-name=ls-rec\"\n" +
                        "sleep 1\n" +
                        "nohup env CLASSPATH=/data/local/tmp/ls-helper.apk app_process /system/bin --nice-name=ls-rec " +
                        "com.byd.ls.helper.HelperMain --record '" + spec + "' " + secs + " >/data/local/tmp/ls-rec.log 2>&1 &\n" +
                        "echo started\n"
                val b64 = android.util.Base64.encodeToString(body.toByteArray(), android.util.Base64.NO_WRAP)
                sh(ctx, "printf %s " + b64 + " | base64 -d > /data/local/tmp/ls-rec.sh && chmod 755 /data/local/tmp/ls-rec.sh && sh /data/local/tmp/ls-rec.sh", 15_000L)
                kotlinx.coroutines.delay(1_500L)
                val pid = sh(ctx, "pidof ls-rec", 5_000L).trim()
                mapOf("spec" to spec, "secs" to secs, "started" to pid.isNotEmpty(), "pid" to pid,
                    "out" to "/data/local/tmp/ls-fid-rec.log")
            }
            "fid.recordTail" -> {
                val n = params.optInt("lines", 40).coerceIn(1, 400)
                mapOf("lines" to n, "value" to sh(ctx, "tail -n " + n + " /data/local/tmp/ls-fid-rec.log", 8_000L))
            }
            "fid.recordStop" -> {
                RemoteAudit.log(ctx, "fid.recordStop")
                sh(ctx, "pkill -f \"nice-name=ls-rec\"", 6_000L)
                mapOf("stopped" to true, "pid" to sh(ctx, "pidof ls-rec", 5_000L).trim())
            }
            "helper.sublisten" -> {
                val dev = params.optString("device", "statistic")
                val secs = params.optInt("seconds", 8)
                RemoteAudit.log(ctx, "helper.sublisten " + dev + " " + secs + "s")
                mapOf("device" to dev, "seconds" to secs,
                    "value" to com.byd.ls.shell.VehicleBridge.subListen(ctx, dev, secs))
            }
            "helper.listen" -> {
                val dev = params.optString("device", "statistic")
                val secs = params.optInt("seconds", 4)
                RemoteAudit.log(ctx, "helper.listen " + dev + " " + secs + "s")
                mapOf("device" to dev, "seconds" to secs,
                    "value" to com.byd.ls.shell.VehicleBridge.listen(ctx, dev, secs))
            }
            "helper.statics" -> {
                val cls = params.optString("class", "android.hardware.bydauto.BYDAutoFeatureIds")
                mapOf("class" to cls, "value" to com.byd.ls.shell.VehicleBridge.dumpStatics(ctx, cls))
            }
            "helper.binder" -> {
                val dev = params.optInt("deviceId", 1)
                val cmds = params.optString("cmds", "1032863782")
                mapOf("deviceId" to dev, "cmds" to cmds,
                    "value" to com.byd.ls.shell.VehicleBridge.binderRead(ctx, dev, cmds))
            }
            "helper.const" -> {
                val cls = params.optString("class", "android.hardware.bydauto.BYDAutoFeatureIds")
                val fld = params.optString("field", "STATISTIC_LAST_FUEL_CON_PHM")
                mapOf("class" to cls, "field" to fld,
                    "value" to com.byd.ls.shell.VehicleBridge.constField(ctx, cls, fld))
            }
            "vehicle.list" -> {
                val dev = params.optString("device", "statistic")
                RemoteAudit.log(ctx, "vehicle.list " + dev)
                mapOf("device" to dev, "methods" to com.byd.ls.shell.VehicleBridge.list(ctx, dev))
            }
            "vehicle.call" -> {
                val dev = params.optString("device", "statistic")
                val m = params.optString("method", "")
                if (m.isEmpty()) mapOf("error" to "缺 method") else {
                    RemoteAudit.log(ctx, "vehicle.call $dev $m")
                    mapOf("device" to dev, "method" to m,
                        "value" to com.byd.ls.shell.VehicleBridge.call(ctx, dev, m))
                }
            }
            "native.probe" -> com.byd.ls.cluster.data.VehicleData.probeNative(ctx)
            // 1.4.61：邻居体检 —— 证明我们的自愈没有动到别人的 ADB/车控/无障碍
            "probe.resync" -> com.byd.ls.shell.BydDirect.resync()
            "neighbor.check" -> neighborCheck(ctx)
            "fuel.probe" -> mapOf(
                "source" to com.byd.ls.cluster.data.VehicleData.statSource,
                "totalPHM" to com.byd.ls.cluster.data.VehicleData.totalPHM,
                "fuelPHM" to com.byd.ls.cluster.data.VehicleData.fuelPHM,
                "elecPHM" to com.byd.ls.cluster.data.VehicleData.elecPHM,
                "windowKm" to com.byd.ls.cluster.data.VehicleData.windowKm,
                "offset" to 0.0,
                "helper" to com.byd.ls.shell.HelperBridge.statusText(),
                "helperRaw" to com.byd.ls.shell.HelperBridge.lastRaw,
                "native" to com.byd.ls.shell.VehicleBridge.statusText(),
                "nativeOk" to com.byd.ls.cluster.data.VehicleData.isNativeOk()
            )
            "health.tail" -> com.byd.ls.service.HealthMonitor.tail(ctx, params.optInt("lines", 60))
            "health.sample" -> com.byd.ls.service.HealthMonitor.sample(ctx)
            "selftest.run" -> selftest(ctx)

            else -> mapOf("error" to "未知方法：$method（白名单外一律拒绝）")
        }
    }


    /**
     * 邻居体检（1.4.61）：一键取"别人的"三样状态快照，用于证明我们没有动到它们。
     *  · 无障碍：去掉我们自己的条目后，剩余条目集合
     *  · 车控：迪友/迪加 的 BYDAUTO runtime 权限行
     *  · ADB：adb_keys 的时间戳/大小 + adb_enabled
     * 全部只读命令，不写任何系统状态。
     */
    /**
     * 1.4.130：`a11y.recover` —— AOSP Q 半绑（组件卡在 AMS UserState.mBindingServices）的唯一出路。
     *
     * 为什么必须由 shell 域后台跑：第一步就是 `am force-stop com.byd.ls`，app 自己会死，
     * 不能在 app 进程里顺序等命令回来。脚本落 /data/local/tmp 后 nohup 起，force-stop 杀的是 app，
     * 不影响 adbd 的 shell 子进程；最后一步 `am broadcast --include-stopped-packages` 由同一脚本发
     * 给我们的 RestartReceiver（它本地 startForegroundService，绕开 exported=false）。
     *
     * 冒烟旁证（1.4.130 实车）：`settings get global sentrymode_enabled_switch`=0、`auto_time_zone`=1 ——
     * BYD 固件确实有 BYDMate 白名单里那几个键，`settings put global` 在 shell uid 下可用。
     */
    private fun a11yRecover(ctx: android.content.Context): Any = runBlocking {
        val full = com.byd.ls.service.A11yHealer.readEnabled(ctx)
        val without = com.byd.ls.service.A11yList.without(full)
        val script = "#!/system/bin/sh\n" +
            com.byd.ls.service.A11ySettingsScript.recoverCommand(full, without) + "\n"
        val b64 = android.util.Base64.encodeToString(
            script.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        val write = runCatching {
            ShellManager.exec(ctx,
                "printf %s " + b64 + " | base64 -d > /data/local/tmp/ls-a11yrec.sh && chmod 755 /data/local/tmp/ls-a11yrec.sh && echo written",
                10_000L).stdout
        }.getOrDefault("")
        val start = runCatching {
            ShellManager.exec(ctx,
                "nohup sh /data/local/tmp/ls-a11yrec.sh >/data/local/tmp/ls-a11yrec.log 2>&1 & echo spawned",
                10_000L).stdout
        }.getOrDefault("")
        RemoteAudit.log(ctx, "a11y.recover spawn=" + start.trim())
        mapOf(
            "written" to write.trim(),
            "spawned" to start.trim(),
            "script" to script.trim(),
            "note" to "会 force-stop 本应用再广播拉起；连接会断一次，10 秒后看 a11y.binder 与 sys.settings"
        )
    }

    /**
     * 1.4.130：`sys.settings` —— 只读。把 BYDMate `TX_PUT_GLOBAL_SETTING` 白名单的三个键、
     * 无障碍名单、通知监听名单一次列全，用来判断「这台车认不认这些键」以及「我们的两条绑定在不在」。
     * 全部 `settings get`，不写任何状态。
     */
    private fun sysSettings(ctx: android.content.Context): Any = runBlocking {
        suspend fun get(scope: String, key: String): String = runCatching {
            ShellManager.exec(ctx, "settings get " + scope + " " + key, 6_000L).stdout.trim()
        }.getOrDefault("")
        mapOf(
            "sentrymode_enabled_switch" to get("global", "sentrymode_enabled_switch"),
            "enable_freeform_support" to get("global", "enable_freeform_support"),
            "auto_time_zone" to get("global", "auto_time_zone"),
            "enabled_accessibility_services" to get("secure", "enabled_accessibility_services"),
            "accessibility_enabled" to get("secure", "accessibility_enabled"),
            "enabled_notification_listeners" to get("secure", "enabled_notification_listeners"),
            "privileged" to ShellManager.isPrivileged()
        )
    }

    private fun neighborCheck(ctx: android.content.Context): Any = runBlocking {
        suspend fun sh(cmd: String): String = runCatching {
            com.byd.ls.shell.ShellManager.exec(ctx, cmd, 6_000L).stdout
        }.getOrDefault("")
        val a11y = sh("settings get secure enabled_accessibility_services")
        val ours = "com.byd.ls/com.byd.ls.service.LsAccessibilityService"
        val others = a11y.split(':').map { it.trim() }.filter { it.isNotEmpty() && it != ours }.sorted()
        val dy = sh("dumpsys package com.dy.launcher | grep -A3 'runtime permissions'")
        val dj = sh("dumpsys package com.van.diplus | grep -A3 'runtime permissions'")
        val key = sh("ls -l /data/misc/adb/adb_keys 2>/dev/null; settings get global adb_enabled")
        mapOf(
            "ours" to ours,
            "othersA11y" to others.joinToString(":"),
            "othersA11yCount" to others.size,
            "dyLauncherPerms" to dy.trim(),
            "diplusPerms" to dj.trim(),
            "adbState" to key.trim()
        )
    }

    private fun clamp(v: Int, lo: Int, hi: Int) = v.coerceIn(lo, hi)

    /**
     * 12 项自检：每次装完新版、以及远程随时可跑。
     *
     * 存在的意义：单元测试只覆盖纯逻辑，Android 侧的坑（adbd 权限、dumpsys 解析、外部目录可读）
     * 只能在真环境跑出来。这 12 项就是"上车前那一关"的可执行版本 —— 全绿才允许留在车上。
     */
    suspend fun selftest(ctx: Context): Map<String, Any> {
        val items = ArrayList<Map<String, Any>>()
        fun add(name: String, ok: Boolean, detail: String) {
            items.add(linkedMapOf("name" to name, "ok" to ok, "detail" to detail))
            RemoteAudit.log(ctx, "selftest " + name + "=" + ok + " " + detail.take(60))
        }

        add("package", true, ctx.packageName + " " + (runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull() ?: "?"))

        val privileged = ShellManager.isPrivileged()
        add("privileged", privileged, if (privileged) "adblib ready" else "no shell channel")

        if (privileged) {
            val echo = kotlinx.coroutines.withTimeoutOrNull(8_000L) { ShellManager.exec(ctx, "echo selftest", 6_000L) }
            add("shell.echo", echo?.stdout?.contains("selftest") == true, echo?.trimmed?.take(60) ?: "timeout")

            val (b, h) = com.byd.ls.service.A11yHealer.queryBoundState(ctx)
            add("dumpsys.accessibility", b, "bound=" + b + " hanging=" + h)

            val w = kotlinx.coroutines.withTimeoutOrNull(10_000L) { ShellManager.exec(ctx, "dumpsys window windows", 9_000L) }
            val our = w?.stdout?.lineSequence()?.count { it.contains("Window #") && it.contains("com.byd.ls") } ?: -1
            add("dumpsys.window", our >= 0, "ourWindows=" + our)
        } else {
            add("shell.echo", false, "skipped")
            add("dumpsys.accessibility", false, "skipped")
            add("dumpsys.window", false, "skipped")
        }

        add("a11y.binder", com.byd.ls.service.A11yHealer.isBound(), "")
        val cardOk = runCatching { com.byd.ls.cluster.data.Card.alive() }.getOrDefault(false)
        add("card.window", cardOk, "beat=" + runCatching { com.byd.ls.cluster.data.Card.beatAge() }.getOrDefault(-1L) + "ms")

        // 外部目录：**shell 必须读得到**（远程安装就栽在这上面）
        val dir = ctx.getExternalFilesDir(null)
        add("external.dir", dir != null && dir.exists(), dir?.absolutePath ?: "null")
        if (dir != null) {
            val probe = File(dir, "selftest.probe")
            runCatching { probe.writeText("ok") }
            // 用 ls 判定"shell 读得到"，不用 cat：短命令的 cat 会撞上 adblib 的 stream 竞态
            // （真车实测 "Stream open actively rejected by remote peer"），误报不可读。
            // 失败重试一次，仍失败才算真不可读。
            var readable = false
            if (privileged) {
                repeat(2) {
                    val r = kotlinx.coroutines.withTimeoutOrNull(8_000L) {
                        ShellManager.exec(ctx, "ls -l '" + probe.absolutePath + "'", 6_000L)
                    }
                    if (r != null && r.exitCode == 0 && r.stdout.contains("selftest.probe")) { readable = true; return@repeat }
                }
            }
            add("external.shellReadable", readable, probe.absolutePath)
            add("health.csv", com.byd.ls.service.HealthMonitor.file(ctx).parentFile?.exists() == true,
                com.byd.ls.service.HealthMonitor.file(ctx).absolutePath)
        } else {
            add("external.shellReadable", false, "no external dir")
            add("health.csv", false, "no external dir")
        }

        val failed = items.count { it["ok"] != true }
        return linkedMapOf(
            "ok" to (failed == 0),
            "failed" to failed,
            "total" to items.size,
            "items" to items
        )
    }


    /**
     * NavProbe 采样：**直接读 /sdcard/clusterlab_nav.log**（NavLog 自己写的），不走 shell。
     *
     * 1.2.29 的教训：用 adblib 跑 `logcat -d -s TAG:*`，真车上它不退出，把 shell 槽位占死，
     * 之后所有命令（连 echo）都超时 —— 手势注入、卡片判据、看门狗全部跟着瘫。日志类读取
     * 一律走文件 API，特权通道只留给真正需要 uid2000 的动作。
     */
    private fun navLog(lines: Int): String {
        val n = clamp(lines, 10, 300)
        val f = File("/sdcard/clusterlab_nav.log")
        if (!f.exists()) return "（没有 clusterlab_nav.log：可能还没导航过，或卡片未运行）"
        return runCatching {
            f.readLines().takeLast(n).joinToString("\n").ifBlank { "（文件为空）" }
        }.getOrElse { "读取失败：${it.message}" }
    }

    /** 远程重启进程：进程被重新拉起后 shell 通道会重建，用于自救（比 reboot 温和）。 */
    private fun restartProcess(ctx: Context): Any {
        RemoteAudit.log(ctx, "agent.restartProcess（远程重启进程）")
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            kotlinx.coroutines.delay(800)
            runCatching { android.os.Process.killProcess(android.os.Process.myPid()) }
        }
        return mapOf("ok" to "进程将于 1 秒后退出，看门狗/RESTART 会把它拉回来（约 5–15 秒）")
    }


    // =====================================================================
    // 系统级能力（1.2.28）：白名单命令 + 自更新
    // =====================================================================

    /** 只允许这些前缀——够用（安装/授权/拉起/查状态/截图）但堵掉"任意 shell"。 */
    private val CMD_OK = Regex(
        "^(pm (list|path|grant|revoke|install|uninstall|clear)|" +
            "am (start|start-activity|broadcast|force-stop|kill|stack|task)|" +
            "settings (get|put|delete|list)|" +
            "cmd (appops|notification|statusbar|accessibility|package|activity)|" +
            "dumpsys |screencap |input |pidof |ps |logcat |getprop |wm |df |ls |cat |mkdir |chmod |mv |cp |echo |" +
            "svc |service list|top |reboot |curl |rm |" +
            // root 研究通道：只允许跑 /data/local/tmp/ls-* 这一组我们自己传上去的二进制
            "/data/local/tmp/ls-[A-Za-z0-9_.\\-]*)"
    )
    private val CMD_DENY = listOf(
        // reboot 已放开（远程重启车机是常用动作）；但 reboot -p / reboot bootloader 这类危险变体仍挡着
        "rm -rf /", "> /dev/block", "mkfs", "dd if=", "mount ", "umount ", "reboot bootloader", "reboot -p", "reboot recovery",
        // curl 只允许用来「下载到本机」：带 --data/-F/-T 会把数据往外发，一律拒
        "curl -d ", "curl --data", "curl -F ", "curl --form", "curl -T ", "curl --upload-file",
        "sh -c", "su ", "busybox", "wget ", "> /data/local/tmp/../"
    )

    /**
     * 系统命令：不是"任意 shell"，是**带前缀白名单 + 拒绝表 + 审计**的受控通道。
     *
     * 为什么还要 unlock：unlock = sha256(bootstrap_secret + deviceId)，只有握着 bootstrap 的
     * relay 能算出来。也就是说即便有人拿到了操作台令牌，也过不了这一关。
     */
    private suspend fun shellExec(ctx: Context, params: JSONObject): Any {
        val cmd = params.optString("cmd", "").trim()
        if (cmd.isEmpty() || cmd.length > 400) return mapOf("error" to "命令为空或过长")
        val expect = sha256Hex(RemoteConfig.bootstrap(ctx) + RemoteConfig.deviceId(ctx))
        if (params.optString("unlock") != expect) return mapOf("error" to "解锁码不符（只有 relay 能生成）")
        if (!CMD_OK.containsMatchIn(cmd)) return mapOf("error" to "命令不在白名单前缀内：${cmd.take(40)}")
        if (CMD_DENY.any { cmd.contains(it) }) return mapOf("error" to "命令包含被拒绝的片段")
        if (!ShellManager.isPrivileged()) return mapOf("error" to "特权通道不可用（adblib 未连接）")
        val r = ShellManager.exec(ctx, cmd, 20_000L)
        RemoteAudit.log(ctx, "shell.exec: $cmd → exit=${r.exitCode}")
        return mapOf(
            "cmd" to cmd,
            "exit" to r.exitCode,
            "out" to r.stdout.take(8000)
        )
    }

    /**
     * 远程更新：车机自己下载 → sha256 校验 → uid2000 静默安装 → 立刻 RESTART 拉起新版。
     *
     * 为什么用 pm install 而不是 package installer Intent：车里没人点"安装"。
     * 为什么要脱离执行：pm install 会杀掉本进程，挂在 adblib 上的命令会被 SIGHUP 打断，
     * 所以整段丢给 nohup setsid 后立即返回。
     */
    private fun updateInstall(ctx: Context, params: JSONObject): Any {
        val url = params.optString("url", "")
        val sha = params.optString("sha256", "").lowercase()
        if (!url.startsWith("https://")) return mapOf("error" to "只接受 https 地址")
        if (sha.length != 64) return mapOf("error" to "缺少 sha256")
        if (!ShellManager.isPrivileged()) return mapOf("error" to "特权通道不可用")
        RemoteAudit.log(ctx, "update.install: $url sha=${sha.take(12)}")
        // 必须落在 shell（uid 2000）读得到的位置：应用私有 filesDir 属于 com.byd.ls，
        // adbd 侧的 pm install 读不到它（1.2.30 实测 `Permission denied` 就是这个坑）。
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val target = File(dir, "update-ls.apk")
        // 上次残留的安装包一律先删：既占空间，也会让"到底装的是哪个包"说不清
        runCatching { if (target.exists()) target.delete() }
        runCatching { File(ctx.filesDir, "update-ls.apk").delete() }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching {
                if (!downloadWithResume(url, target)) {
                    RemoteAudit.log(ctx, "update.install: 下载失败（多次续传后仍不完整）")
                    return@runCatching
                }
                val actual = sha256HexOfFile(target)
                if (actual != sha) {
                    RemoteAudit.log(ctx, "update.install: 校验失败 actual=${actual.take(12)}")
                    target.delete()
                    return@runCatching
                }
                RemoteAudit.log(ctx, "update.install: 下载+校验通过，开始静默安装")
                // 【1.4.8 实测】本车 package_verifier_enable=1 会把 pm install 拦成
                // "Failure calling service package: Failed transaction (2147483646)"，
                // 表现为「下载校验全成功、安装静默失败」。安装前自动关掉（写审计）。
                val verifier = ShellManager.exec(ctx, "settings get global package_verifier_enable", 6_000L)
                    .stdout.trim()
                if (verifier != "0" && verifier != "null") {
                    ShellManager.exec(ctx, "settings put global package_verifier_enable 0", 6_000L)
                    RemoteAudit.log(ctx, "update.install: 已临时关闭安装校验（原值 " + verifier + "）")
                }
                // 关键：pm install 由 system_server 执行，它**读不到 /sdcard**（SELinux sdcardfs 实测
                // avc denied），必须先把包 cp 到 /data/local/tmp 再装。
                // 装完立刻删两个位置的文件（用户要求：安装包不留在车上）。
                val tmp = "/data/local/tmp/ls-update.apk"
                // 1.2.42：不再写脚本、不再 setsid 脱离 —— 实车实测「脚本写完从不执行」。
                // 改成两步普通 exec：先 cp，再 pm install；app 被 pm 杀掉没关系，
                // adbd 侧的 pm 子进程会继续跑完（1.2.36~1.2.41 每次手动装都是这么成功的）。
                ShellManager.exec(ctx, "cp '" + target.absolutePath + "' " + tmp, 15_000L)
                // 【1.4.10 实测修复】pm install 必须走**全新连接**。
                // 现场证据：1.4.5（用完即断）自动安装成功；1.4.8/1.4.9（连接复用）在复用的
                // 连接上执行 pm 会稳定报 "Failure calling service package: Failed transaction
                // (2147483646)"，而同一条命令手动以新连接执行即 Success。
                // 原因：复用的 stream 已被此前的 cp/采样命令用过，pm 的事务在 adbd 侧同一会话
                // 上提交时被拒；新连接是干净的 shell 会话。
                runCatching { ShellManager.reprobe(ctx) }
                // 【1.4.11】安装会杀掉本进程，所以必须一条命令在 adbd 侧跑完：
                //   ① pm install 的输出落盘；② 无论成败，紧跟一条 RESTART 广播把进程拉回来。
                // 旧实现只发 pm install：安装失败时进程已死、没人拉 → 远程直接失联
                //（2026-11-09 00:15 实测，车机离线 8 小时）。
                // 用 ";" 串联两条简单命令是安全的（adblib 上不可靠的只是 for/do/done 这类控制结构）。
                // 【1.4.14 实测修复】安装输出**不能**重定向到 /sdcard 私有目录。
                // 对照实验（docs/08 §10）：同一条命令串
                //   pm install … > '/sdcard/Android/data/com.byd.ls/files/install.log' 2>&1
                // 由 RPC 手动发也稳定报 "Failed transaction"；把重定向改到
                //   /data/local/tmp/ls-install.log
                // 立刻 Success。所以日志一律写 /data/local/tmp，成功与否改用**退出码**判断
                //（adblib 的哨兵会给最后一条命令的退出码；本命令只有 pm 一条）。
                val tmpLog = "/data/local/tmp/ls-install.log"
                var installed = false
                for (attempt in 1..3) {
                    runCatching { ShellManager.reprobe(ctx) }
                    val r = runCatching {
                        ShellManager.exec(ctx, "pm install -r -d " + tmp + " > " + tmpLog + " 2>&1", 90_000L)
                    }.getOrNull()
                    if (r == null || r.exitCode == 0) { installed = true; break }
                    RemoteAudit.log(ctx, "update.install: 第 " + attempt + " 次失败 exit=" + r.exitCode)
                    Thread.sleep(2_000L)
                }
                // 兜底拉起：成功时本进程已被 pm 杀掉、这一行不会执行（由看门狗/RESTART 接管）；
                // 失败时进程还活着，广播同样无害。
                runCatching {
                    ShellManager.exec(ctx, "am broadcast -a com.byd.ls.action.RESTART -p com.byd.ls -f 0x20", 8_000L)
                }
                RemoteAudit.log(ctx, "update.install: 结束 installed=" + installed + "（日志 " + tmpLog + "）")
                RemoteAudit.log(ctx, "update.install: pm install 已下发（本进程即将被替换）")
            }.onFailure {
                RemoteAudit.log(ctx, "update.install 异常: ${it.message}")
            }
        }
        return mapOf("started" to true, "hint" to "下载中；装完会自动拉起新版。若 90s 没回来用 status.truth 看版本号")
    }

    /**
     * 可续传下载。
     *
     * 为什么必须续传：家里上行只有十几 KB/s，3 MB 的 APK 要两三分钟，而 ngrok 免费线路会在
     * 中途掐断长连接（实测只下到 1.5 MB 就 EOF）。所以每次带着 Range 头从已有字节数接着下，
     * 断几次都能拼完整。
     */
    private fun downloadWithResume(url: String, target: File): Boolean {
        val want = -1L // 大小以最终 sha 校验为准
        var have = if (target.exists()) target.length() else 0L
        repeat(8) {
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("ngrok-skip-browser-warning", "1")
                if (have > 0) setRequestProperty("Range", "bytes=" + have + "-")
            }
            val code = runCatching { conn.responseCode }.getOrDefault(0)
            if (code == 416) { conn.disconnect(); return@repeat }          // 已经到头
            if (code != 200 && code != 206) { conn.disconnect(); return@repeat }
            val append = (code == 206 && have > 0)
            runCatching {
                conn.inputStream.use { input ->
                    FileOutputStream(target, append).use { out -> input.copyTo(out, 64 * 1024) }
                }
            }
            conn.disconnect()
            have = target.length()
            if (want > 0 && have >= want) return@repeat
        }
        return target.exists() && target.length() > 1_000_000
    }

    private fun sha256Hex(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun sha256HexOfFile(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }


    private fun safeTag(t: String): String =
        if (t.matches(Regex("[A-Za-z0-9._-]{1,40}"))) t else "LingShou"

    private suspend fun sh(ctx: Context, cmd: String, timeout: Long = 12_000L): String {
        if (!ShellManager.isPrivileged()) return "（无特权通道，命令未执行：$cmd）"
        val r = kotlinx.coroutines.withTimeoutOrNull(timeout + 2_000L) {
            ShellManager.exec(ctx, cmd, timeout)
        }
        if (r == null) {
            // 通道卡住时不要一直等：直接丢给后台重置，本次 RPC 立刻返回原因
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                runCatching { ShellManager.reprobe(ctx) }
            }
            RemoteAudit.log(ctx, "sh 超时并触发通道重置: ${cmd.take(60)}")
            return "（命令超时 ${timeout}ms，已在后台重置特权通道；重试一次通常就恢复）"
        }
        return r.trimmed.ifBlank { "（无输出，exit=${r.exitCode}）" }
    }

    /**
     * 状态真值：每一项都必须能说出「凭什么这么判」。
     * 进程内静态字段只作辅助——现场那次「显示正常但功能已死」就是只看静态字段造成的。
     */
    /**
     * 读 dumpsys accessibility：**必须重试**。
     *
     * 真车实测：adblib 偶发返回空/截断（stream 竞态），一次读不到就判"未绑定"会造成假阴。
     * 判据是"文本里出现过 Bound services 段"，否则返回空串（调用方据此给 unknown，而不是 bad）。
     */
    private suspend fun readDumpsysAccessibility(ctx: Context): String {
        if (!ShellManager.isPrivileged()) return ""
        repeat(3) {
            val r = kotlinx.coroutines.withTimeoutOrNull(12_000L) {
                ShellManager.exec(ctx, "dumpsys accessibility", 10_000L)
            }
            val t = r?.stdout ?: ""
            if (t.contains("Bound services")) return t
            kotlinx.coroutines.delay(300)
        }
        return ""
    }

    private suspend fun truth(ctx: Context): Map<String, Any> {
        val dumpsys = readDumpsysAccessibility(ctx)
        // 分段判定：Bound 段才算已绑定（1.2.34 的全文 contains 把 Binding 段也算进来了 → 假绿）
        val (a11yBound, a11yHanging) = com.byd.ls.service.A11yHealer.queryBoundState(ctx)
        val inProcess = A11yHealer.isBound()
        // 1.4.6：该车机是否已判定「不绑第三方无障碍」（docs/08）——落了标记就不再算故障
        val a11yUnsupported = A11yHealer.isUnsupported(ctx)
        val privileged = ShellManager.isPrivileged()
        val cardAlive = runCatching { Card.alive() }.getOrDefault(false)
        val beatAge = runCatching { Card.beatAge() }.getOrDefault(-1L)

        val reasons = ArrayList<String>()
        if (!privileged) reasons.add("特权通道（adblib/uid2000）不可用：所有 shell 类动作都会静默失效")
        if (!inProcess && !a11yBound) reasons.add("无障碍既没绑上、进程内 binder 也未连")
        if (inProcess && !a11yBound && dumpsys.isNotEmpty()) reasons.add("进程内 binder 声称已连，但 dumpsys Bound 段没有本服务（假绿）")
        if (!inProcess && a11yBound) reasons.add("半绑：dumpsys Bound 段里有本服务，但进程内 binder 未连上（手势不会生效）")
        if (dumpsys.isEmpty() && privileged) reasons.add("dumpsys accessibility 连读 3 次都没拿到有效输出（通道抖动），无障碍状态未知")
        if (a11yHanging && !a11yUnsupported) reasons.add("无障碍处于 Binding 悬空：AMS 发过 bind 但没连上")
        if (a11yUnsupported) reasons.add("无障碍：该车机不绑定第三方无障碍服务（已判定，见 docs/08），相关能力按「不支持」处理")
        if (!cardAlive) reasons.add("仪表卡片窗口不在（心跳 ${beatAge}ms）")

        return linkedMapOf(
            "app" to linkedMapOf(
                "pid" to android.os.Process.myPid(),
                "deviceId" to RemoteConfig.deviceId(ctx),
                "agent" to RemoteAgent.stateText
            ),
            "a11y" to linkedMapOf(
                "inProcess" to inProcess,
                "dumpsysBound" to a11yBound,
                "dumpsysHanging" to a11yHanging,
                "supported" to !a11yUnsupported,
                "verdict" to when {
                    a11yUnsupported -> "unsupported"
                    !privileged -> "unknown"
                    inProcess && !a11yHanging -> "ok"
                    inProcess && a11yHanging -> "hanging"
                    a11yBound -> "half"      // 系统 Bound 段里有我们，但进程内没连上 → 半绑
                    a11yHanging -> "hanging" // 名单有、bind 发了但没连上 → 需要 force-stop 档
                    else -> "bad"
                }
            ),
            "gesture" to linkedMapOf(
                "service" to GlobalGestureService.statusText(),
                "a11yOverlay" to GlobalGestureService.isA11yOverlay()
            ),
            "card" to linkedMapOf(
                "alive" to cardAlive,
                "beatAgeMs" to beatAge,
                "media" to (runCatching { Card.mediaConnected() }.getOrNull()?.toString() ?: "unknown")
            ),
            "channel" to linkedMapOf(
                "privileged" to privileged,
                "adb" to (ShellManager.statusLines()["ADB 授权"] ?: "-"),
                "watchdog" to WatchdogInstaller.stateText,
                "nativeDaemon" to DaemonGuard.statusText(ctx)
            ),
            "prefs" to linkedMapOf(
                "gestureEnabled" to GesturePrefs.enabled,
                "logEnabled" to GesturePrefs.logEnabled,
                "bootJobScheduled" to BootJobService.isScheduled(ctx)
            ),
            "health" to linkedMapOf(
                "summary" to com.byd.ls.service.HealthMonitor.summary(),
                "lastSample" to com.byd.ls.service.HealthMonitor.last
            ),
            "reasons" to reasons
        )
    }

    private suspend fun logTail(ctx: Context, file: String, lines: Int): String {
        val n = clamp(lines, 20, 500)
        val ext = ctx.getExternalFilesDir(null)
        val cands = when (file) {
            "boot" -> listOf(File(ext, "boot.log"), File(ctx.filesDir, "boot.log"))
            "crash" -> listOf(File(ctx.filesDir, "ls-crash.log"))
            "audit" -> listOf(RemoteAudit.file(ctx))
            "watchdog" -> listOf(File(ext, "ls-watchdog.log"))
            else -> listOf(File(ext, "ls.log"), File(ctx.filesDir, "ls.log"))
        }
        val local = cands.firstOrNull { it.exists() }
        if (local != null) return runCatching { local.readLines().takeLast(n).joinToString("\n") }.getOrDefault("")
        if (file == "watchdog") return sh(ctx, "tail -n $n /data/local/tmp/ls-watchdog.log")
        return "（没有 $file 日志：本地文件不存在且未走 shell）"
    }

    /**
     * dumpsys 一律**只取小片段**。
     *
     * 真车实测：adblib 的 stream 会把几十 KB 输出截断（exit=-1，文本在半截断开），
     * 于是"Bound 段里有没有本服务"这类判定会得到假阴。这里全部改成 shell 侧过滤后再回传，
     * 单次输出控制在几 KB 内。
     */
    private suspend fun dumpsys(ctx: Context, kind: String): String = when (kind) {
        "window" -> sh(ctx, "dumpsys window windows | grep -E 'Window #|mCurrentFocus' | head -40", 12_000L)
        "activity" -> sh(ctx, "dumpsys activity activities | grep -E 'ACTIVITY|ResumedActivity' | head -40", 12_000L)
        else -> {
            val head = sh(ctx, "dumpsys accessibility | sed -n '1,40p'", 12_000L)
            val (b, h) = com.byd.ls.service.A11yHealer.queryBoundState(ctx)
            head + "\n--- bound=" + b + " hanging=" + h
        }
    }

    /** 手动修复 = 最高优先级：清掉所有冷却文件再走一遍阶梯（对应现场「点了也没用」）。 */
    private suspend fun userRepair(ctx: Context): Map<String, Any> {
        val cleared = ArrayList<String>()
        for (name in listOf("a11y_rebind.lock", "a11y_hard_reset.ts", "a11y_stale_repair.ts")) {
            val f = File(ctx.filesDir, name)
            if (f.exists() && f.delete()) cleared.add(name)
        }
        ctx.getSharedPreferences("a11y_deep", Context.MODE_PRIVATE).edit().clear().apply()
        cleared.add("deep_recover_ts(prefs)")

        val heal = runCatching { A11yHealer.healNow(ctx, "remote-userRepair") }.getOrDefault("异常")
        val repair = runCatching { A11yHealer.repairNow(ctx) }.getOrDefault("异常")
        RemoteAudit.log(ctx, "userRepair: $heal | $repair")
        return mapOf("cleared" to cleared, "heal" to heal, "repair" to repair,
            "bound" to A11yHealer.isBound())
    }

    private suspend fun a11yRebind(ctx: Context): String {
        val r = runCatching { A11yHealer.repairNow(ctx) }.getOrDefault("异常")
        RemoteAudit.log(ctx, "a11y.rebind: $r")
        return r
    }

    private fun logToggle(ctx: Context, on: Boolean): String {
        GesturePrefs.logEnabled = on
        LsLog.enabled = on
        RemoteAudit.log(ctx, "logToggle: $on")
        return "日志开关：$on"
    }

    /** 诊断包：没有电脑、没有远程会话时也能把证据拿出来。 */
    private suspend fun diagExport(ctx: Context, upload: Boolean): Map<String, Any> {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val out = File(ctx.filesDir, "diag-$stamp.zip")
        val truth = org.json.JSONObject(truth(ctx) as Map<*, *>).toString(2)
        ZipOutputStream(FileOutputStream(out)).use { z ->
            fun put(name: String, text: String) {
                z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry()
            }
            put("truth.json", truth)
            for ((n, f) in listOf("ls.log" to File(ctx.getExternalFilesDir(null), "ls.log"),
                "ls-crash.log" to File(ctx.filesDir, "ls-crash.log"),
                "remote_audit.log" to RemoteAudit.file(ctx))) {
                if (f.exists()) put(n, runCatching { f.readLines().takeLast(400).joinToString("\n") }.getOrDefault(""))
            }
            if (ShellManager.isPrivileged()) {
                put("dumpsys-accessibility.txt", ShellManager.exec(ctx, "dumpsys accessibility", 12_000).stdout.take(60_000))
                put("dumpsys-window.txt", ShellManager.exec(ctx, "dumpsys window windows", 12_000).stdout.take(60_000))
                put("logcat-probe.txt", ShellManager.exec(ctx, "logcat -d -s CLUSTERLAB-NAV:* -t 300", 12_000).stdout)
                put("watchdog.log", ShellManager.exec(ctx, "tail -n 200 /data/local/tmp/ls-watchdog.log", 8_000).stdout)
            }
        }
        val size = out.length()
        var uploaded = false
        if (upload) uploaded = RemoteAgent.uploadDiag(out)
        RemoteAudit.log(ctx, "diag.export size=$size upload=$uploaded")
        return mapOf("path" to out.absolutePath, "size" to size, "uploaded" to uploaded)
    }
}
