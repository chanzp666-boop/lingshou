package com.byd.ls.service

import android.app.job.JobParameters
import android.app.job.JobService
import com.byd.ls.config.GesturePrefs
import com.byd.ls.receiver.BootReceiver
import com.byd.ls.util.LsLog

/**
 * 开机 3 分钟的一次性补拉（见 [BootReceiver.scheduleBootAssist]）。
 *
 * 只做一件事：把 [BootReceiver.beginQuietStart] 再跑一遍。它本身幂等，
 * 服务在跑时全部是空操作，所以这条兜底不会带来可见副作用。
 */
class BootAssistJob : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        val app = applicationContext
        GesturePrefs.init(app)
        LsLog.init(app)
        LsLog.enabled = GesturePrefs.logEnabled
        LsLog.i(TAG, "开机补拉 Job 触发")
        runCatching { BootReceiver.beginQuietStart(app, "boot-assist") }
            .onFailure { LsLog.w(TAG, "补拉异常: ${it.message}") }
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    private companion object {
        const val TAG = "BootAssist"
    }
}
