package com.byd.ls.remote

import android.content.Context
import java.io.File

/**
 * 远程调试的静态配置。
 *
 * 公开包（applicationId=com.byd.ls，PUBLIC_BUILD=true）不读取 prefs、外部 remote_url.txt，
 * 也不允许在界面上改写地址；远程调试固定关闭。
 */
object RemoteConfig {

    // 公开包只保留占位符，configured() 永远返回 false。
    const val RELAY_URL = "wss://REPLACE-ME.invalid/agent"
    const val BOOTSTRAP_SECRET = "REPLACE-ME-BOOTSTRAP-SECRET"

    private const val PREFS = "ls_remote"
    /** 1.4.174 一次性迁移：旧版默认开，升级后先强制回到关闭，用户再手动开。 */
    private const val KEY_DEFAULT_OFF = "default_off_v174"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 地址解析顺序：prefs 覆盖 → APP 外部目录里的 remote_url.txt → 打包常量。
     *
     * 为什么留文件这一层：车主自己装车时，改地址只要在 /sdcard/Android/data/com.byd.ls/files/
     * 放一行 wss://… 就行，不用等我们重新打包。
     */
    fun url(ctx: Context): String {
        if (com.byd.ls.BuildConfig.PUBLIC_BUILD) return RELAY_URL
        sp(ctx).getString("url", null)?.let { return it }
        runCatching {
            val f = File(ctx.getExternalFilesDir(null), "remote_url.txt")
            if (f.exists()) {
                val u = f.readText().trim().lines().firstOrNull { it.startsWith("wss://") }
                if (u != null) { setUrl(ctx, u); return u }
            }
        }
        return RELAY_URL
    }
    fun bootstrap(ctx: Context): String =
        if (com.byd.ls.BuildConfig.PUBLIC_BUILD) BOOTSTRAP_SECRET
        else sp(ctx).getString("bootstrap", null) ?: BOOTSTRAP_SECRET

    /** 默认关：正常行驶不建立任何后台连接；需要远程排查时在首页手动打开。 */
    fun enabled(ctx: Context): Boolean {
        if (com.byd.ls.BuildConfig.PUBLIC_BUILD) return false
        val sp = sp(ctx)
        if (!sp.getBoolean(KEY_DEFAULT_OFF, false)) {
            // 旧安装可能残留 enabled=true；升级到 1.4.174 后只强制关一次，之后尊重用户开关。
            sp.edit().putBoolean("enabled", false).putBoolean(KEY_DEFAULT_OFF, true).apply()
        }
        return sp.getBoolean("enabled", false)
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        if (com.byd.ls.BuildConfig.PUBLIC_BUILD) return
        sp(ctx).edit().putBoolean("enabled", on).putBoolean(KEY_DEFAULT_OFF, true).apply()
    }

    fun setUrl(ctx: Context, url: String) {
        if (com.byd.ls.BuildConfig.PUBLIC_BUILD) return
        sp(ctx).edit().putString("url", url).apply()
    }
    fun setBootstrap(ctx: Context, s: String) {
        if (com.byd.ls.BuildConfig.PUBLIC_BUILD) return
        sp(ctx).edit().putString("bootstrap", s).apply()
    }

    fun deviceId(ctx: Context): String {
        val sp = sp(ctx)
        sp.getString("dev", null)?.let { return it }
        val id = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        sp.edit().putString("dev", id).apply()
        return id
    }

    fun configured(ctx: Context): Boolean {
        if (com.byd.ls.BuildConfig.PUBLIC_BUILD) return false
        val u = url(ctx)
        val b = bootstrap(ctx)
        // 正式包只认 wss://；debug 包额外允许 ws://（本机模拟器用 10.0.2.2 / 127.0.0.1）
        val schemeOk = u.startsWith("wss://") ||
            (com.byd.ls.BuildConfig.DEBUG && (u.startsWith("ws://10.0.2.2") || u.startsWith("ws://127.0.0.1")))
        return schemeOk && !u.contains("REPLACE-ME") && b.length >= 16 && !b.contains("REPLACE-ME")
    }
}
