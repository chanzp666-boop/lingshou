package com.byd.ls.cluster

import com.byd.ls.config.GesturePrefs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 1.5.3beta2 门控契约：卡片/车辆/音乐/导航都只从 GesturePrefs 派生。
 *
 * 这里锁“关掉后 card/nav 一定为 false、重开后一定为 true”，防止后续再有人
 * 绕过 FeatureGate 直接读 showXxx，导致某个采样源在开关关闭后仍活着。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class FeatureGateTest {

    @Before
    fun setUp() {
        GesturePrefs.init(RuntimeEnvironment.getApplication())
        GesturePrefs.clusterEnabled = true
        GesturePrefs.projectionFullOnBoot = false
        GesturePrefs.showOil = false
        GesturePrefs.showElec = false
        GesturePrefs.showTotal = false
        GesturePrefs.showTyre = false
        GesturePrefs.showMusic = false
    }

    @Test
    fun `card needs cluster and at least one element`() {
        assertFalse(FeatureGate.card())
        GesturePrefs.showMusic = true
        assertTrue(FeatureGate.card())
        GesturePrefs.clusterEnabled = false
        assertFalse(FeatureGate.card())
    }

    @Test
    fun `vehicle follows fuel or tyre`() {
        assertFalse(FeatureGate.vehicle())
        GesturePrefs.showTyre = true
        assertTrue(FeatureGate.vehicle())
        assertTrue(FeatureGate.card())
        GesturePrefs.showTyre = false
        GesturePrefs.showElec = true
        assertTrue(FeatureGate.fuel())
        assertTrue(FeatureGate.vehicle())
    }

    @Test
    fun `nav only when music on and full projection off`() {
        GesturePrefs.showMusic = true
        assertTrue(FeatureGate.nav())
        GesturePrefs.projectionFullOnBoot = true
        assertFalse(FeatureGate.nav())
        GesturePrefs.showMusic = false
        assertFalse(FeatureGate.nav())
    }
}
