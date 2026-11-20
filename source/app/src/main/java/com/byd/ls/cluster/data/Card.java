package com.byd.ls.cluster.data;

import com.byd.ls.cluster.ClusterExec;
import com.byd.ls.cluster.ui.CardActivity;

/**
 * 仪表卡片（现在就是本进程的 CardActivity）的启动 / 停止 / 存活判定。
 *
 * 合并前判据里的包名是 com.byd.clusterlab，合并后统一改成 com.byd.ls；
 * 数据文件 /sdcard/clusterlab_*.txt 保持原名不动（老爷车机上还留着用户样式）。
 */
public final class Card {
    public static final String PKG = "com.byd.ls";

    /** 全名写法，避免 dumpsys / am 里相对名解析歧义。 */
    public static final String CMP = "com.byd.ls/com.byd.ls.cluster.ui.CardActivity";
    public static final String NLS = "com.byd.ls/com.byd.ls.cluster.data.MediaListener";

    /**
     * 窗口判定：只数真正的 WindowState（"Window #" 行），窗口被回收后该行随之消失。
     *
     * 车机实测 `dumpsys activity activities` 的 Display #1 分段拿不到 CardActivity（C1=0），
     * 而 `dumpsys window displays` 里既不区分可见性、isVisible 字段也不存在，
     * 只有这条同时满足「简单管道」与「窗口级精确」。
     */
    static final String CHECK_WIN =
            "dumpsys window windows | grep 'Window #' | grep -c 'cluster.ui.CardActivity'";

    static final String LISTENER_CMD =
            "settings get secure enabled_notification_listeners | grep -c com.byd.ls/cluster.data.MediaListener";

    /**
     * 跨进程安全的重启：只有 ActivityRecord 在、窗口已被回收时，普通 am start 是空操作，
     * 必须带 CLEAR_TASK 强制走一遍销毁重建，窗口才会重新创建。
     */
    public static String restartViaShell() {
        // 0x04000000 是 RESET_TASK_IF_NEEDED（**不是** CLEAR_TASK），实车实测常被判成
        // "已在顶部"直接丢弃 → 所谓"重启"其实什么都没做。真正的 CLEAR_TASK 是 0x00008000。
        String out = Shell.exec("am start-activity --display 1 -f 0x00008000 -n " + CMP, 8000);
        return out == null ? "通道未就绪" : "已请求重建";
    }

    private Card() {}

    /** null = ADB 未就绪，无法判定。 */
    /** 卡片心跳文件：守卫进程据此判断"界面还活着"，健康时完全不碰 adb。 */
    public static final String HB = "/sdcard/clusterlab_card_beat.txt";

    /** 卡片每 10 秒调一次（主进程 -> 文件）。 */
    public static void beat() {
        try {
            java.io.FileOutputStream o = new java.io.FileOutputStream(HB, false);
            try { o.write(String.valueOf(System.currentTimeMillis()).getBytes("UTF-8")); }
            finally { try { o.close(); } catch (Throwable ignored) {} }
        } catch (Throwable ignored) {}
    }

    /**
     * 本进程启动时刻（wall clock）。
     *
     * 心跳文件是跨进程、跨进程重启存活的：app 被杀/被安装打断后重启，读到的可能是
     * **上一个进程**留下的时间戳，按"超过 [BEAT_FRESH_MS]"判死就会把刚起来的卡片
     * 判成"不在"，进而被 CLEAR_TASK 重建 —— 现场表现就是"卡片先显示出来，几秒后
     * 自己关掉再重开"（1.4.133 实车 00:06:44 复现）。比本进程启动还旧的时间戳一律
     * 当"不知道"，不自作聪明。
     */
    public static final long PROC_START_WALL = System.currentTimeMillis();

    /** 心跳距今多少毫秒；没文件、或文件名明显是上个进程留下的，返回 Long.MAX_VALUE。 */
    public static long beatAge() {
        java.io.File f = new java.io.File(HB);
        if (!f.exists()) return Long.MAX_VALUE;
        long m = f.lastModified();
        if (m <= 0L || m < PROC_START_WALL) return Long.MAX_VALUE;
        return System.currentTimeMillis() - m;
    }

    /** 心跳新鲜度阈值：卡片每 10 秒写一次心跳，超过这个时间当作窗口已经不在了。 */
    static final long BEAT_FRESH_MS = 25_000L;

    /**
     * 卡片窗口是否在（1=在，0=不在，null=判不出来）。
     *
     * 两条快路径都**不碰 adb**，这是 1.2.26 真车问题的修法：NavProbe 每约 1 秒截一次屏、
     * 每次一条新 adblib 连接并持有 execLock，原来的 dumpsys 判据抢不到锁就返回 null，
     * 首页因此长期误报「卡片：未运行」。
     *  · 主进程：CardActivity 与本类同进程，窗口标记就是真相（View 附着/脱离窗口时维护）；
     *  · :guard 进程：静态标记永远为 false，改用卡片每 10 秒写的心跳文件判活。
     * 只有心跳文件从来没写过（存储权限缺失）才退回 dumpsys 窗口判据。
     */
    public static Integer count() {
        if (CardActivity.windowSeen) {
            // 1.4.139：判活看 windowAttached（View 附着），不看 windowAlive（前台/后台）
            // 1.4.144：窗口可能 attach 但被系统设成 GONE（画面没了），必须连可见性一起判
            return Integer.valueOf(CardActivity.windowAttached && CardActivity.windowVisible ? 1 : 0);
        }
        long age = beatAge();
        if (age != Long.MAX_VALUE) {
            return Integer.valueOf(age < BEAT_FRESH_MS ? 1 : 0);
        }
        String out = Shell.exec(CHECK_WIN, 8000);
        if (out == null) return null;
        try { return Integer.valueOf(Integer.parseInt(out.trim())); } catch (Throwable t) { return null; }
    }

    public static boolean alive() {
        Integer c = count();
        return c != null && c.intValue() > 0;
    }


    /** overlay 自己写的真实连接心跳；null = 心跳文件不可用（没存储权限）。 */
    public static Boolean mediaConnected() {
        String s = mediaDump();
        if (s == null || s.trim().length() == 0) return null;
        for (String line : s.split("\n")) {
            String l = line.trim();
            if (l.startsWith("connected=")) return Boolean.valueOf(l.endsWith("1"));
        }
        return null;
    }

    public static String mediaDump() {
        return Shell.exec("cat /sdcard/clusterlab_media.txt 2>/dev/null", 5000);
    }

    /** 媒体链路诊断：真实连接、session 数、当前曲目、设置条目、系统侧计数。 */
    public static String mediaDiagnose() {
        StringBuilder sb = new StringBuilder();
        String dump = mediaDump();
        String flat = dump == null ? "" : dump.trim().replace("\n", "  ");
        sb.append("心跳: ").append(flat.length() == 0 ? "无（存储权限没给？）" : flat);
        String setting = Shell.exec(LISTENER_CMD, 5000);
        sb.append(" | 设置条目=").append(setting == null ? "?" : setting.trim());
        String sys = Shell.exec("dumpsys notification --noredact | grep -c 'cluster.data.MediaListener'", 8000);
        sb.append(" | dumpsys命中=").append(sys == null ? "?" : sys.trim());
        return sb.toString();
    }

    public static String start() {
        return startOnce();
    }

    /**
     * 仪表屏当前是否被"全屏导航"占用（automap 在该屏 resumed）。null = 无法判定。
     *
     * 为什么用 dumpsys 判：导航 App 是否全屏投到仪表屏，只有 ActivityManager 知道；
     * 像素判据（NavProbe）只能看出"有没有导航卡片"，看不出"是不是全屏"。
     */
    public static Boolean clusterNavFullscreen() {
        String out = Shell.exec(
            "dumpsys activity activities | sed -n '/Display #1 /,/Display #/p' | grep -m1 'mResumedActivity' | grep -c com.byd.automap",
            6000);
        if (out == null) return null;
        return out.trim().endsWith("1");
    }

    /**
     * 强制重启：先把旧窗口关掉，再让系统重新创建。
     *
     * 为什么需要：卡片是 singleTask，窗口被系统回收后 ActivityRecord 仍在，
     * 这时单纯 am start 会因为「任务已在」什么都不做（现场表现就是"点启动没反应"）。
     */
    public static String restart() {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                @Override public void run() {
                    try { CardActivity.shutdown(); } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable ignored) {}
        // 等窗口真的脱离（finish -> onDetachedFromWindow 是异步的），比固定 sleep 稳；
        // 在 :guard 进程里 windowAlive 恒 false，最多多睡 200ms。
        for (int i = 0; i < 60 && CardActivity.windowAttached; i++) sleep(20);
        sleep(200);
        return startOnce();
    }

    private static String startOnce() {
        // 仪表屏被"全屏导航"占用时让位：卡片在 display 1 是全屏窗口，一旦启动必然把导航盖黑。
        // 用户核心功能：卡片要**叠在全屏导航之上**，任何情况下都不让位、不跳过启动。
        // （1.2.41 的"让位"逻辑已删除：既导致开机不拉卡片，也和核心功能冲突。）
        String r = Shell.exec("am start-activity --display 1 -n " + CMP, 12000);
        if (r == null) return "ADB 未就绪：未启动";
        String low = r.trim();
        if (low.length() == 0) return "已提交启动";
        return low;
    }

    /**
     * 停止卡片：合并前走 "com.byd.clusterlab.STOP" 广播给仪表自己的 BootReceiver；
     * 合并后卡片与本界面同进程，直接调 CardActivity.shutdown() 等价且更可靠。
     * （绝不用 am force-stop —— 那会把灵手自己置成 stopped 态，开机自启就废了。）
     */
    public static String stop() {
        try {
            CardActivity.shutdown();
            return "已停止";
        } catch (Throwable t) {
            return "停止失败：" + t;
        }
    }

    public static void rebindMedia() {
        ClusterExec.exec("cmd notification disallow_listener " + NLS, 8000);
        sleep(1200);
        ClusterExec.exec("cmd notification allow_listener " + NLS, 8000);
        sleep(800);
    }


    public static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (Throwable ignored) {}
    }
}
