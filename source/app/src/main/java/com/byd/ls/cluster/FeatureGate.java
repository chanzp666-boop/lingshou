package com.byd.ls.cluster;

import com.byd.ls.config.GesturePrefs;

/**
 * 1.5.3beta2：功能开关的唯一派生状态。
 *
 * 所有“关了就必须彻底停”的入口都从这里取状态，避免各处重复拼
 * showMusic/showTyre/... 导致某条采样漏停。只读偏好，不启动/停止任何线程。
 */
public final class FeatureGate {
    private FeatureGate() {}

    private static GesturePrefs p() {
        return GesturePrefs.INSTANCE;
    }

    public static boolean fuel() {
        return p().getShowTotal() || p().getShowOil() || p().getShowElec();
    }

    public static boolean tyre() {
        // 纯 JVM 单测可能没走 GesturePrefs.init；此时按默认开处理，避免解析事件时抛未初始化异常。
        try {
            return p().getShowTyre();
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static boolean music() {
        return p().getShowMusic();
    }

    /** 油耗/胎压任一需要车辆数据。 */
    public static boolean vehicle() {
        return fuel() || tyre();
    }

    /** 整个仪表窗口是否需要存在。 */
    public static boolean card() {
        return p().getClusterEnabled() && (vehicle() || music());
    }

    /** 全屏导航优化开关。 */
    public static boolean full() {
        return p().getProjectionFullOnBoot();
    }

    /** 音乐大小窗识别只在“音乐开且全屏优化关”时需要。 */
    public static boolean nav() {
        return music() && !full();
    }
}
