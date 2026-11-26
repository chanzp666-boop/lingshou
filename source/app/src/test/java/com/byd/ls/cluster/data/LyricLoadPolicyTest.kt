package com.byd.ls.cluster.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.3beta1：只有四源干净地确认“没有这首歌”才允许写 24h 负缓存。 */
class LyricLoadPolicyTest {

    @Test
    fun keepsMissCacheWhenAllSourcesCleanAndOnline() {
        assertTrue(LyricLoader.shouldPersistMiss(false, true))
    }

    @Test
    fun skipsMissCacheWhenAnyIoOrParseFailureHappened() {
        assertFalse(LyricLoader.shouldPersistMiss(true, true))
    }

    @Test
    fun skipsMissCacheWhenOffline() {
        assertFalse(LyricLoader.shouldPersistMiss(false, false))
    }
}
