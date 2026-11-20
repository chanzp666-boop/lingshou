package com.byd.ls.cluster.data

import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 1.4.121: BYD nav broadcast flag parsing.
 * Real logcat (AmapService 14:40:54): action = AUTONAVI_STANDARD_BROADCAST_SEND,
 * mIsBydMap=true, mIsBYDMapNaving=true, mIsGAODENaving=false
 */
@RunWith(RobolectricTestRunner::class)
class NavReceiverFlagTest {

    @Test
    fun realWorldExtrasIsNavigating() {
        val b = Bundle()
        b.putBoolean("mIsBydMap", true)
        b.putBoolean("mIsBYDMapNaving", true)
        b.putBoolean("mIsGAODENaving", false)
        assertEquals(1, NavReceiver.bydNavFlag(b))
    }

    @Test
    fun allFalseMeansNotNavigating() {
        val b = Bundle()
        b.putBoolean("mIsBydMap", false)
        b.putBoolean("mIsBYDMapNaving", false)
        b.putBoolean("mIsGAODENaving", false)
        assertEquals(0, NavReceiver.bydNavFlag(b))
    }

    @Test
    fun gaodeOwnNavigationCounts() {
        val b = Bundle()
        b.putBoolean("mIsBYDMapNaving", false)
        b.putBoolean("mIsGAODENaving", true)
        assertEquals(1, NavReceiver.bydNavFlag(b))
    }

    @Test
    fun unknownExtrasFallBackToPixel() {
        assertEquals(-1, NavReceiver.bydNavFlag(null))
        val b = Bundle()
        b.putInt("EXTRA_STATE", 1)
        assertEquals(-1, NavReceiver.bydNavFlag(b))
    }

    @Test
    fun stringAndIntFormsAccepted() {
        val s = Bundle()
        s.putString("mIsBYDMapNaving", "true")
        assertEquals(1, NavReceiver.bydNavFlag(s))
        val i = Bundle()
        i.putInt("mIsBYDMapNaving", 2)
        assertEquals(1, NavReceiver.bydNavFlag(i))
    }
}
