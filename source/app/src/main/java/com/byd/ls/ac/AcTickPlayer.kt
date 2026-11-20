package com.byd.ls.ac

import android.content.Context
import com.byd.ls.R
import android.media.AudioManager
import android.media.SoundPool
import android.util.Log

/** DiLink 5.0 SystemUI 空调栏档位音效（res/raw/wheel_sound1.ogg）。 */
object AcTickPlayer {

    private const val TAG = "AcTickPlayer"
    private var pool: SoundPool? = null
    private var soundId = 0
    private var loaded = false

    fun init(ctx: Context) {
        if (pool != null) return
        Log.d(TAG, "init")
        // 原厂就是单路 tick，不做增益或叠播；走 STREAM_MUSIC 让车机媒体通道出声。
        pool = SoundPool(1, AudioManager.STREAM_MUSIC, 0).also { sp ->
            soundId = sp.load(ctx.applicationContext, R.raw.wheel_sound1, 2000)
            Log.d(TAG, "load id=$soundId")
            sp.setOnLoadCompleteListener { _, id, status ->
                loaded = status == 0 && id == soundId
                Log.d(TAG, "onLoadComplete id=$id status=$status loaded=$loaded")
            }
        }
    }

    fun play() {
        val sp = pool
        if (sp == null) {
            Log.d(TAG, "play skipped: pool null")
            return
        }
        if (!loaded || soundId == 0) {
            Log.d(TAG, "play skipped: loaded=$loaded id=$soundId")
            return
        }
        val ret = sp.play(soundId, 1f, 1f, 2000, 0, 1f)
        Log.d(TAG, "play ret=$ret")
    }

    fun release() {
        pool?.release()
        pool = null
        soundId = 0
        loaded = false
    }
}

