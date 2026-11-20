package com.byd.ls.service

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.byd.ls.config.GesturePrefs
import com.byd.ls.util.LsLog

/**
 * 持久化 JobScheduler 兜底。
 *
 * 为什么需要：
 *  · AlarmManager 在重启后清空；
 *  · native 守护与 shell 看门狗都依赖“已经有进程”；
 *  · 部分 DiLink ROM 会漏发/延迟 BOOT_COMPLETED。
 *
 * JobScheduler 的 persisted 任务在重启后由 system_server 重新排程，即使 App 没被
 * 任何广播拉起，也能把进程叫醒，随后 startForegroundService + 无障碍自愈。
 * 前提是用户没有在系统里“强行停止”灵手（强行停止会清掉 job，这是 Android 限制）。
 */
class BootJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        val app = applicationContext
        GesturePrefs.init(app)
        LsLog.init(app)
        LsLog.i(TAG, "JobScheduler 唤醒，拉起服务并自愈")

        runCatching { GesturePrefs.writeEnabledFlag(app) }
        // 统一走 BootReceiver 的幂等静默启动（它也负责 enabled / bootAutoStart 判定、
        // 手势服务 + 守护 + 心跳 + ADB 修复），这里不再重复一遍相同逻辑。
        runCatching { com.byd.ls.receiver.BootReceiver.beginQuietStart(app, "boot-job") }
        schedule(app)
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        private const val TAG = "BootJob"
        private const val JOB_ID = 4101
        private const val PERIOD_MS = 15 * 60 * 1000L

        fun schedule(ctx: Context) {
            // 只在主进程排程，避免 :guard 重复
            val proc = runCatching { Application.getProcessName() }.getOrDefault("")
            if (proc != ctx.packageName) return

            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            runCatching {
                if (js.getPendingJob(JOB_ID) != null) return
                val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, BootJobService::class.java))
                    .setPersisted(true)
                    .setPeriodic(PERIOD_MS)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                    .build()
                val r = js.schedule(job)
                LsLog.i(TAG, "JobScheduler 排程结果=$r")
            }.onFailure { LsLog.w(TAG, "JobScheduler 排程失败: ${it.message}") }
        }

        fun isScheduled(ctx: Context): Boolean = runCatching {
            (ctx.getSystemService(JobScheduler::class.java))?.getPendingJob(JOB_ID) != null
        }.getOrDefault(false)
    }
}
