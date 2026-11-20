package com.byd.ls.cluster.data;

import com.byd.ls.cluster.ClusterExec;

/**
 * shell 执行器（仪表助理原版自带 YBAdb，合并后统一走灵手 adblib）。
 *
 * 对外语义与原版一致：未就绪时返回 null 而不是空串——本地 exec 是 unprivileged 的，
 * 拿 dumpsys/grep 只会得到恒为 0 的假结果，比"未知"更糟。
 */
public final class Shell {

    private Shell() {}

    public static boolean ready() {
        return ClusterExec.ready();
    }

    public static String exec(String cmd) { return exec(cmd, 8000); }

    public static String exec(String cmd, int timeoutMs) {
        if (!ready()) return null;
        try {
            return ClusterExec.exec(cmd + " 2>&1", timeoutMs);
        } catch (Throwable t) {
            return null;
        }
    }
}
