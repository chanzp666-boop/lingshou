package com.byd.ls.cluster.data;

import com.byd.ls.cluster.ClusterExec;
import com.byd.ls.cluster.cfg.ClusterDebug;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * 导航状态，优先级从高到低：
 * 1. 手动覆盖 /sdcard/clusterlab_nav.txt（1=导航中 0=未导航）
 * 2. 仪表屏左上角有没有高德导航卡片（截图像素判定，见 NavProbe）—— 主判据
 * 3. 无障碍文本兜底："288 米 后" / "5.2公里 16分钟" / "14:35 到"
 *
 * 踩过的坑，别再试：
 *   - MeterActivity 窗口常驻（引导页也在）→ 不能当判据
 *   - NaviService 高德一启动就有 → 不能当判据
 *   - 画中画：本项目的高德是把卡片投到 display 1，不是 PiP
 *   - uiautomator dump 在车机上被 OOM kill（rc=137）
 */
public final class NavState {
    public static volatile boolean navigating = false;
    public static volatile String source = "none";

    private static long lastTrueAt = 0L;
    /** 最后一次收到高德广播的时刻（不管 EXTRA_STATE 是 0 还是非 0）。 */
    private static volatile long lastBroadcastAt = 0L;

    private static volatile long boostUntil = 0L;

    /** 有事件提示导航状态可能变了（高德广播 / 无障碍事件）：短时间把探测提到 300ms 一轮。
     *  这是「大小窗切换慢」的正解：平时低频省 CPU，有事立刻高频确认。 */
    public static void boost(long ms) {
        if (!demandOn || detectOff) return;
        long t = android.os.SystemClock.elapsedRealtime() + ms;
        if (t > boostUntil) boostUntil = t;
        // 1.4.192：boost 以前只改截止时间，采样线程在 10s/60s 的 sleep 里收不到；
        // 现场 18:21:08 导航事件到达后仍等到 18:22:07 才切小窗，正是这条。
        startPoller();
        wakePoller();
    }

    /**
     * 1.4.192：事件不仅要把轮询提速，还必须让 B1 的空闲 helper 立刻截新帧。
     * 否则 poller 醒来后一直读到旧 .nav_metrics（4s TTL）和新鲜心跳，只能返回缓存，
     * 要等 helper 自己的 60s 兜底帧 —— 现场 18:21:08→18:22:07 的第二段 60 秒就是这个。
     */
    public static void eventBoost(long ms) {
        if (!demandOn || detectOff) return;
        requestCaptureAsync();
        boost(ms);
    }

    static boolean boostActive() {
        return android.os.SystemClock.elapsedRealtime() < boostUntil;
    }

    /** 高德广播 90 秒内有效：这段时间完全不需要截图探测（screencap 是全场最贵的一次操作）。 */
    public static boolean broadcastFresh() {
        return android.os.SystemClock.elapsedRealtime() - lastBroadcastAt < 3000L;
    }

    /** 高德广播链路还活着：只要 3 秒内来过包，就不必高频截图（screencap 一次 5.5MB）。 */
    public static void noteBroadcast() {
        lastBroadcastAt = android.os.SystemClock.elapsedRealtime();
    }

    // ------------------------------------------------------------------
    // 1.4.117：导航 App 的窗口状态变化 → 短时提速采样
    //
    // 背景（13 §18.3）：进入导航要 5~10 秒，因为空闲节拍 5s + 2 帧确认。广播收不到、
    // 导航在画中画里所以前台包名法不可用；唯一不增截图开销的信号是「窗口状态变化」——
    // 用户点开导航 App 时，无障碍一定能收到它自己的 TYPE_WINDOW_STATE_CHANGED
    // （本车无障碍已稳定绑定，19.2 长跑 162/200 为 true）。
    //
    // 原则：**只提节拍，不改判定**。事件不来 → 行为与 1.4.116 逐字一致，只多几行日志；
    // 事件来了 → 进入窗口缩到 2 帧 × 800ms ≈ 2 秒，且稳态 screencap 次数不变。
    // ------------------------------------------------------------------
    private static final String[] NAV_PKGS = {
            "com.autonavi.amapauto",   // 高德车机版（本车实证）
            "com.byd.automap",         // 比亚迪定制高德：DiLink4.0 上导航主应用与仪表 MeterActivity 都是这个包名（1.4.141 实测 pm list packages 只有它，没有 com.autonavi.*）
            "com.autonavi.amap",       // 高德地图（部分车机装的是这个）
            "com.autonavi.minimap",
            "com.baidu.naviauto",      // 百度地图车机版
            "com.baidu.BaiduMap",
    };

    public static boolean isNavPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        for (String p : NAV_PKGS) {
            // 进程后缀两种写法都见过：com.autonavi.amapauto:remote / com.autonavi.amapauto.something
            if (pkg.equals(p) || pkg.startsWith(p + ":") || pkg.startsWith(p + ".")) return true;
        }
        return false;
    }

    private static volatile long lastNavWinAt = 0L;

    /** 1.4.140：事件通道（广播 / 无障碍文本）最近一次给出定论的时刻。 */
    private static volatile long lastEventAt = 0L;
    /** 事件可信窗：这段时间内像素只记账、不改状态 —— 事件通道权威，像素抖不动它。
     *  20 秒是折中：短了挡不住高德重绘，长了广播静默时像素接管太慢。 */
    private static final long EVENT_TRUST_MS = 20_000L;

    static boolean eventTrusted() {
        long t = lastEventAt;
        return t != 0L && android.os.SystemClock.elapsedRealtime() - t < EVENT_TRUST_MS;
    }

    /** 导航特征文本在 1.5.4.4 已退出运行链路，判定统一由 AmapService 日志负责。 */

    /** 兼容旧调用：只有包名。 */
    public static void onNavWindow(String pkg) {
        onNavWindow(pkg, null, null);
    }

    /**
     * 无障碍报了导航 App 的窗口变化。
     *
     * 1.5.4.4：只负责叫醒一次信号轮询；不再解析窗口文本、不 force、不截屏。
     * 进入/退出统一由 AmapService 的 mIsBYDMapNaving 判定。
     */
    public static void onNavWindow(String pkg, String text, String desc) {
        long now = android.os.SystemClock.elapsedRealtime();
        // 1.5.4.4：无障碍只负责叫醒一次信号轮询，不再用窗口文本直接定论，
        // 也不再触发截屏。判定统一收口到 AmapService 的 mIsBYDMapNaving。
        if (lastNavWinAt != 0L && now - lastNavWinAt < 2_000L) return;
        lastNavWinAt = now;
        boost(6_000L);
        wakePoller();
        NavLog.w("NAV_WIN pkg=" + pkg + " → 唤醒信号轮询", true);
    }

    private static long lastOverrideRead = 0L;
    private static boolean overrideOn = false;
    private static boolean overrideValue = false;

    private NavState() {}

    /** false 的时候如果刚才是 true，且在保持期内，就不改（卡片文本会闪）。 */
    public static void setWithHold(boolean nav, String src, long holdMs) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (nav) { lastTrueAt = now; set(true, src); return; }
        if (navigating && now - lastTrueAt < holdMs) return;
        set(false, src);
    }

    public static void set(boolean nav, String src) {
        if (!demandOn || detectOff) return;
        if (nav != navigating) {
            if (nav) {
                // 1.4.138：开始导航补发仪表 16 档（方向盘/中控切页会把档位打回 17）
                try { com.byd.ls.cluster.ClusterProjection.requestOnNav(); } catch (Throwable ignored) {}
            }
            ClusterDebug.e("CLUSTERLAB-NAV", "导航状态: " + (nav ? "开始" : "结束") + " (" + src + ")");
        NavLog.w("导航状态: " + (nav ? "开始" : "结束") + " 来源=" + src + " 采样=" + NavProbe.lastRatio, true);
        }
        navigating = nav;
        source = src;
        sigConfirmCount = 0;
        sigConfirmValue = false;
        boost(4000);
        // 立刻通知界面重绘，不等下一个绘制周期
        Runnable cb = onChange;
        if (cb != null) {
            try { cb.run(); } catch (Throwable ignored) {}
        }
    }

    /**
     * 1.4.121：广播/显式动作**直接定论**，连像素状态机一起改。
     *
     * 只调 set() 不够：isNavigating() 返回的是 dashCard || navigating，
     * 像素那边 dashCard 卡在 true（读到旧帧、或浅色帧被判成过渡帧丢弃）时，
     * 广播说「导航结束」也回不到大窗 —— 这正是实车「结束不切换」的直接原因。
     */
    public static void force(boolean nav, String src) {
        if (!demandOn || detectOff) return;
        long now = android.os.SystemClock.elapsedRealtime();
        // 1.4.187 B1：只在导航状态真翻转时让 ls-nav 截一帧确认；
        // 稳态不重复触发，避免把事件通道变成新的 200ms 截屏循环。
        if (nav != navigating) requestCaptureAsync();
        dashCard = nav;
        setFastCapture(nav);
        confirmCount = 0;
        lastSwitchAt = now;
        lastEventAt = now;
        // 1.4.140：两个方向都立刻生效（下一行 set() 就会改 navigating 并回调重绘），
        // holdUntil 只管「挡像素」，不负责延迟生效：
        //   进入 = 4 秒（导航刚点开，仪表还没画卡片，像素只会读到无卡）；
        //   退出 = 1.5 秒（高德清屏有残留，但用户要立刻看到大窗，不能等 4 秒）。
        holdUntil = now + (nav ? BROADCAST_HOLD_MS : BROADCAST_EXIT_HOLD_MS);
        set(nav, src);
    }

    /** 1.4.187 B1：异步 touch 触发文件；helper 截 2 帧后回到空闲。 */
    private static volatile long lastCaptureReqAt = 0L;
    private static void requestCaptureAsync() {
        if (!demandOn || detectOff) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastCaptureReqAt < 500L) return;
        lastCaptureReqAt = now;
        final android.content.Context c = ClusterExec.ctx();
        if (c == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    com.byd.ls.shell.HelperBridge.requestNavCapture(c);
                } catch (Throwable ignored) {}
            }
        }, "LsNavCaptureReq").start();
    }

    /** 界面注册的重绘回调（CardActivity 在 onCreate 里设）。 */
    public static volatile Runnable onChange = null;

    /**
     * 最终状态：手动覆盖 > 仪表导航卡片（像素） > 无障碍文本。
     *
     * 绝对不能在这里做实际测量：isNavigating() 每帧（16ms）都会被 onDraw /
     * ensureStatic / drawLyrics 各调一次，而 NavProbe.sample() 要起一次
     * screencap 子进程 + 读 raw 文件（实测 200~500ms）。放在这里 = 每帧阻塞
     * UI 线程几百毫秒 = 歌词动画整块卡死、掉帧到个位数。
     *
     * 所以这里只读缓存值，真正的测量全部交给 startPoller() 的后台线程。
     * readOverride() 自己也带 2s 节流，代价可忽略。
     */
    public static boolean isNavigating() {
        // 1.5.3beta2：是否允许识别由 FeatureGate.nav() 显式下发，不再在这里读全屏偏好。
        // 音乐关/全屏优化开时，界面调用只会拿到 false，不会再把 poller 拉起来。
        if (!demandOn || detectOff) return false;
        // 每帧都会进来：这一行是「界面还活着」的心跳，poller 据此决定何时停采样。
        // 卡片窗口被回收后 onDraw 不再被调，采样线程随之退出，不再白烧 CPU 和 adbd 槽位。
        lastUiAt = android.os.SystemClock.elapsedRealtime();
        readOverride();
        if (overrideOn) return overrideValue;
        startPoller();
        return dashCard || navigating;
    }

    /** 1.5.3beta2：音乐卡片是否在要导航识别；由 CardActivity/FeatureGate 下发。 */
    private static volatile boolean demandOn = false;
    private static volatile boolean detectOff = true;

    /**
     * 显式设置导航识别需求。
     *
     * 不在这里启动 poller：只有界面真的调用 isNavigating() 时才启动，
     * 避免“功能关了但后台还活着”。重新打开后下一次界面绘制会自然恢复。
     */
    public static void setDemand(boolean on) {
        if (on) {
            if (demandOn && !detectOff) return;
            demandOn = true;
            detectOff = false;
            lastUiAt = android.os.SystemClock.elapsedRealtime();
            ClusterDebug.e("CLUSTERLAB-NAV", "导航识别需求: 开");
            return;
        }
        demandOn = false;
        setDetectionDisabled(true);
    }

    public static void setDetectionDisabled(boolean off) {
        if (detectOff == off) return;
        detectOff = off;
        if (!off) {
            // 兼容旧调用：只放开闸门，不在这里主动启动线程。
            lastUiAt = android.os.SystemClock.elapsedRealtime();
            return;
        }
        dashCard = false;
        navigating = false;
        confirmCount = 0;
        setFastCapture(false);
        pollerStarted = false;
        android.content.Context c = ClusterExec.ctx();
        if (c != null) runCatchingStop(c);
        ClusterDebug.e("CLUSTERLAB-NAV", "导航识别已关闭");
    }

    private static int confirmCount = 0;
    private static volatile long lastSwitchAt = 0L;
    /** 1.4.122：广播/显式动作的保持窗，窗内像素不得翻盘。 */
    private static final long BROADCAST_HOLD_MS = 4_000L;
    /** 1.4.140：退出方向只挡 1.5 秒 —— 够高德清屏、够挡住残留帧，又不让用户等大窗。 */
    private static final long BROADCAST_EXIT_HOLD_MS = 1_500L;
    private static volatile long holdUntil = 0L;
    /** 已经吃过的最新帧序号（缓存值不算新证据）。 */
    private static long lastSeq = 0L;

    /** 仪表上有没有高德导航卡片（像素判定，见 NavProbe）。 */
    private static volatile boolean dashCard = false;
    private static volatile boolean pollerStarted = false;
    /** 信号翻转连续确认：防止两个 helper 交替写 1/0 时一帧一帧地翻窗。 */
    private static int sigConfirmCount = 0;
    private static boolean sigConfirmValue = false;
    private static long sigConfirmAt = 0L;
    private static final long SIG_CONFIRM_WINDOW_MS = 2_500L;
    /** 采样线程的唤醒闸门：导航事件来了必须立刻打断 10s/60s 的等待。 */
    private static final Object POLLER_GATE = new Object();
    private static volatile boolean pollerWakeAsked = false;
    /** 1.4.193：没有高德广播，退出导航只能靠像素；导航期间必须持续产出新帧。 */
    private static final long NAV_KEEPALIVE_MS = 600L;
    /** 打开导航 App 后找目的地/规划路线可能超过 6 秒，热窗放宽到 60s，覆盖真正的路线开始。 */
    private static final long NAV_EVENT_HOT_MS = 60_000L;
    private static volatile long lastKeepaliveAt = 0L;
    /** 1.4.193：导航态让 helper 进入 200ms 连续采样，退出立即恢复 B1 低频。 */
    private static volatile boolean fastCaptureOn = false;
    /** 1.5.4.4：当前是否已让 helper 进入像素兜底；只在信号失效/恢复时切换。 */
    private static volatile boolean navPixelModeOn = false;
    /** 界面最近一次调用 isNavigating() 的时刻（onDraw 每帧都会调），poller 据此判断界面是否还在 */
    private static volatile long lastUiAt = 0L;
    /** 采样线程多久没被界面调用就自行退出（卡片被关/被回收后不再截屏） */
    private static final long UI_IDLE_STOP_MS = 60_000L;

    /**
     * 采样节拍。
     *
     * 这几个值决定「音乐窗口跟随导航卡片大小切换」的手感：需要连续 3 次一致才切，
     * 所以实际响应时间 ≈ 3 × 间隔。上一版把它们拉到 3s/1.5s，切换要 9 秒，
     * 表现就是"跟随切换失效"。恢复原始节拍（700/500/300/800），
     * 只把失败间隔从 700ms 放大到 1.5s —— 失败自激才是抢 adbd 的根源。
     */
    // 1.4.61：整体降频 —— adbd 的 shell 槽位是**全车共享**的资源（迪友/迪加也要握手），
    // 我们每多一次 screencap 就多占一次槽位。用户要求"绝不导致别人掉 ADB 授权/连不上"，
    // 这里主动让路：空闲 2s→5s、导航中 1.2s→2.5s、加速窗 0.8s→1.5s、失败 5s→8s。
    // 1.4.123：空闲 5s → 2.5s。用户实测「点了导航 10 秒才切」的第一段延迟就在这，
    // 5s 空闲节拍 + 2 帧确认 = 10s。2.5s 时 screencap 只占 adbd 不到 1/6 的时间片。
    // 1.4.124：空闲 2.5s → 1.2s。采样已经搬进 shell 域常驻 helper（navshot），
    // 一帧不占 adbd 槽位，节拍可以收紧到「0.4s 采样 + 1.2s 轮询」，最坏 1.6s 内定论。
    private static final long GAP_IDLE_MS = 600L;      // 1.4.125：采样改本地读占比（1~7ms），节拍再压一半
    private static final long GAP_SIGNAL_MS = 500L;    // 1.5.4.4：正常只读信号文件，500ms 足够快且不耗电
    private static final long GAP_NAV_MS = 250L;     // 1.4.193：导航中配合 helper 200ms 连续帧，回到 1.4.178 的读取节拍
    private static final long GAP_BOOST_MS = 800L;
    private static final long GAP_BROADCAST_MS = 800L;
    private static final long GAP_FAIL_MS = 2_000L;   // 1.4.125：8s → 2s。采样已不占 adbd，失败退避不需要这么长；8s 的洞会漏掉整次导航切换
    /** 1.4.112（需求 5）：单次失败的退避。原来一次失败就退 8s，撞上一次通道忙就白等 8 秒 →「偶尔很慢」。 */
    private static final long GAP_FAIL_SOFT_MS = 1_500L;
    /** 1.4.128：轮询刚起来这 4 秒不采信「一帧定论」。开机/进卡片头几帧正好赶上界面在画，
     *  高置信判据会被半张卡片骗过去，实测 20:01:39~20:02:10 连翻 5 次（大→小→大→小→大）。
     *  这 4 秒里一律要 2 帧确认，代价最多 0.4s，换来不再来回跳。 */
    private static final long WARMUP_MS = 4_000L;
    private static long pollerStartedAt = 0L;
    /** 1.4.112（需求 6）：蓝条占比越过阈值时立刻补一帧确认，不等节拍 */
    private static final long GAP_CONFIRM_MS = 300L;
    private static float prevBlueRatio = -1f;
    /** 1.4.194：采样已搬进 helper 本地执行，不再占 adbd 槽位，开机让路 90s→取消。
     *  原来开机头 90 秒按 10 秒一采，叠加 helper 60 秒一帧，造成「开机点导航几分钟才反应」。 */
    private static final long YIELD_AFTER_BOOT_MS = 0L;
    private static final long GAP_YIELD_MS = 10_000L;
    /** 1.4.194：高德早信号有效期与像素让路窗口。信号本身来自 AmapService 日志，秒级新鲜。 */
    private static final long SIG_TTL_MS = 3_000L;
    /** 信号新鲜时压住像素。音乐大窗会盖住仪表顶部导航卡片，像素会持续误判“无卡”；
     *  原来 1.5s 保持窗不够覆盖两帧采样，现场表现就是信号 true 和像素 false 每 2 秒对拉一次。 */
    private static final long SIG_HOLD_MS = 5_000L;

    /** 采集进程收工；通道忙时失败也无所谓，helper 自己会在 app 进程消失时退出。 */
    private static void runCatchingStop(android.content.Context c) {
        try {
            com.byd.ls.shell.HelperBridge.stopNavWatch(c);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 1.4.193：1.4.178 是 200ms 连续采样，不依赖事件；B1 之后 helper 空闲只写心跳，
     * 而本车收不到高德广播。退出导航没有负向事件时，只有靠这里的保活抓帧才能拿到新像素。
     * 只在导航中或 NAV_WIN 后 60 秒热窗内抓，空闲不增加开销。
     */
    private static void maybeKeepaliveCapture(long now) {
        if (fastCaptureOn) return;
        long lastWin = lastNavWinAt;
        boolean eventHot = lastWin != 0L && now - lastWin < NAV_EVENT_HOT_MS;
        if (!dashCard && !eventHot) return;
        if (now - lastKeepaliveAt < NAV_KEEPALIVE_MS) return;
        lastKeepaliveAt = now;
        requestCaptureAsync();
    }

    /** helper 的 200ms 连续模式开关；只在导航态翻转时动一次，不在每帧写文件。 */
    private static void setFastCapture(boolean on) {
        if (fastCaptureOn == on) return;
        fastCaptureOn = on;
        final android.content.Context c = ClusterExec.ctx();
        if (c == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try { com.byd.ls.shell.HelperBridge.setNavCaptureActive(c, on); }
                catch (Throwable ignored) {}
            }
        }, "LsNavActive").start();
    }

    /** 正常信号链：只按 AmapService 日志翻窗，不触发任何截图。 */
    private static void applySignalOnly(boolean sigOn, long nowMs) {
        holdUntil = Math.max(holdUntil, nowMs + SIG_HOLD_MS);
        if (sigOn == dashCard) {
            sigConfirmCount = 0;
            sigConfirmValue = false;
            return;
        }
        if (sigOn != sigConfirmValue
                || nowMs - sigConfirmAt > SIG_CONFIRM_WINDOW_MS) {
            sigConfirmValue = sigOn;
            sigConfirmCount = 1;
            sigConfirmAt = nowMs;
        } else {
            sigConfirmCount++;
        }
        if (sigConfirmCount >= 2) {
            ClusterDebug.e("CLUSTERLAB-NAV", "布局切换: "
                    + (sigOn ? "小窗(导航中)" : "大窗(未导航)"));
            NavLog.w("布局切换: " + (sigOn ? "小窗" : "大窗") + " 来源=amapsig", true);
            dashCard = sigOn;
            sigConfirmCount = 0;
            sigConfirmValue = false;
            lastSwitchAt = nowMs;
            set(sigOn, "amapsig");
        }
    }

    /** 信号或 helper 掉线时才打开像素兜底；helper 收到文件后才会开始截屏。 */
    private static void setNavPixelMode(boolean on) {
        if (navPixelModeOn == on) return;
        navPixelModeOn = on;
        final android.content.Context c = ClusterExec.ctx();
        if (c == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try { com.byd.ls.shell.HelperBridge.setNavPixelMode(c, on); }
                catch (Throwable ignored) {}
            }
        }, on ? "LsNavPixelOn" : "LsNavPixelOff").start();
    }

    private static void startPoller() {
        if (!demandOn || detectOff) return;
        if (pollerStarted) return;
        pollerStarted = true;
        pollerStartedAt = android.os.SystemClock.elapsedRealtime();
        // 如果上次进程在导航中被杀，.nav_active 会残留；新进程还没确认导航时先清一次，
        // 避免 helper 一启动就按 200ms 连续采样空转。真正进导航后由 setFastCapture(true) 再置位。
        if (!dashCard && !fastCaptureOn) {
            final android.content.Context c0 = ClusterExec.ctx();
            if (c0 != null) {
                new Thread(new Runnable() {
                    @Override public void run() {
                        try { com.byd.ls.shell.HelperBridge.setNavCaptureActive(c0, false); }
                        catch (Throwable ignored) {}
                    }
                }, "LsNavActiveClear").start();
            }
        }
        setFastCapture(dashCard);
        // 1.4.192：事件启动采样时不能因为 lastUiAt 还是旧的，下一轮就按 UI_IDLE 退出；
        // 这里补一次“刚活动过”，保证事件触发后至少完整跑一个采样周期。
        lastUiAt = android.os.SystemClock.elapsedRealtime();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                while (true) {
                    // 界面已经不在（卡片窗口被回收/卡片被关）：直接退出采样线程。
                    // 原实现 while(true) 永不退出，卡片关掉后仍每秒 screencap 5.5MB，
                    // 还会把 adbd 的连接槽位一直占着，导致拉卡片反复失败。
                    if (detectOff) {
                        pollerStarted = false;
                        android.content.Context cc = ClusterExec.ctx();
                        if (cc != null) runCatchingStop(cc);
                        return;
                    }
                    if (android.os.SystemClock.elapsedRealtime() - lastUiAt > UI_IDLE_STOP_MS) {
                        pollerStarted = false;
                        // 1.4.124：界面不在就别让采集进程白截屏（它写的是 5.5 MB/帧）。
                        android.content.Context c = ClusterExec.ctx();
                        if (c != null) runCatchingStop(c);
                        return;
                    }
                    // 1.5.4.4：正常只走 AmapService 日志信号，零截屏。
                    // 信号文件和 helper 心跳都有效就立刻判定，不再等像素。
                    boolean signalOk = NavProbe.refreshSignal();
                    if (signalOk) {
                        setNavPixelMode(false);
                        applySignalOnly(NavProbe.lastSig == 1,
                                android.os.SystemClock.elapsedRealtime());
                        sleepGap(GAP_SIGNAL_MS);
                        continue;
                    }
                    // 信号失效/helper 掉线：才打开像素兜底，让 helper 恢复截图。
                    setNavPixelMode(true);
                    boolean fail = false;
                    try {
                        // sample() 返回 false 有两种情况：真的没卡片，或者截图失败。
                        // 用 lastRatio<0 区分：<0 表示这一轮没测出来，直接跳过，别改状态。
                        boolean on = NavProbe.sampleForFallback();
                        // 采到帧才算数：失败时不能拿旧值喂状态机（导航结束了会回不到大窗）
                        fail = !NavProbe.lastOk;
                        long nowMs = android.os.SystemClock.elapsedRealtime();
                        boolean fresh = !fail && NavProbe.seq != lastSeq;
                        if (fresh) lastSeq = NavProbe.seq;
                        // 1.4.194：早信号先定论。AmapService 的 mIsBYDMapNaving 比仪表卡早 1~2 秒，
                        // 进出同一套判定、同一延迟；命中后 1.5 秒内像素不许翻盘。
                        int sig = NavProbe.lastSig;
                        long sigAt = NavProbe.lastSigAt;
                        if (!fail && sig >= 0 && sigAt > 0
                                && System.currentTimeMillis() - sigAt < SIG_TTL_MS) {
                            boolean sigOn = sig == 1;
                            // 信号新鲜时始终压住像素，哪怕当前状态已经一致。
                            // 音乐大窗会遮住导航卡片，像素会一直读“无卡”；不续保持窗，
                            // 就会在 amapsig=true 和 dash=false 之间来回跳。
                            holdUntil = Math.max(holdUntil, nowMs + SIG_HOLD_MS);
                            if (sigOn != dashCard) {
                                // 两个 helper 同时写 .nav_sig 时会出现 1/0 交替；
                                // 连续两次同值才翻窗，单帧噪声直接丢掉。
                                if (sigOn != sigConfirmValue
                                        || nowMs - sigConfirmAt > SIG_CONFIRM_WINDOW_MS) {
                                    sigConfirmValue = sigOn;
                                    sigConfirmCount = 1;
                                    sigConfirmAt = nowMs;
                                } else {
                                    sigConfirmCount++;
                                }
                                if (sigConfirmCount >= 2) {
                                    ClusterDebug.e("CLUSTERLAB-NAV", "布局切换: "
                                            + (sigOn ? "小窗(导航中)" : "大窗(未导航)") + " 来源=amapsig");
                                    NavLog.w("布局切换: " + (sigOn ? "小窗" : "大窗") + " 来源=amapsig", true);
                                    dashCard = sigOn;
                                    setFastCapture(sigOn);
                                    confirmCount = 0;
                                    lastSwitchAt = nowMs;
                                    set(sigOn, "amapsig");
                                }
                            } else {
                                sigConfirmCount = 0;
                                sigConfirmValue = false;
                            }
                        }
                        if (!fail && !fresh) {
                            // 1.4.122：限频期拿回的是缓存结论，不喂状态机。
                        } else if (!fail && nowMs < holdUntil) {
                            // 广播定论的保持窗内，像素不许翻盘（导航刚点开那几秒仪表还没画卡片）。
                            confirmCount = 0;
                        } else if (!fail && eventTrusted()) {
                            // 1.4.140：广播/无障碍刚定论过 —— 事件通道权威，像素只记账不改状态。
                            // 这是「跟手 + 不抖」的关键：高德重绘那一两帧再白也翻不动窗口。
                            confirmCount = 0;
                        } else if (!fail) {
                            // 连续 3 次一致才切换，避免 screencap 偶尔拿到空帧导致大小窗来回跳；
                            // 刚切过就锁 1.5 秒，防止高德重绘把状态来回翻。
                            if (on == dashCard
                                    || android.os.SystemClock.elapsedRealtime() - lastSwitchAt < 800L) {
                                confirmCount = 0;
                            } else {
                                confirmCount++;
                                // 进入 3 次、退出 4 次：实测高德刷新卡片的一两帧会整块变亮
                                // （暗底 95% → 0%，蓝条 100% → 0%），2 次确认会被这种帧骗过去。
                                // 1.4.123：高置信单帧直接定论；普通帧仍然 2 帧确认。
                                // 1.4.140：一帧定论只给「进入」方向。退出方向的最后一帧常常是
                                // 高德重画的整块变亮帧，拿它定论 = 小窗无预警跳大窗。
                                int need = (on && NavProbe.lastDecisive) ? 1 : 2;
                                // 1.4.128：刚起来的头 4 秒不吃「一帧定论」
                                if (nowMs - pollerStartedAt < WARMUP_MS) need = Math.max(need, 2);
                                if (confirmCount >= need) {
                                    ClusterDebug.e("CLUSTERLAB-NAV", "布局切换: "
                                            + (on ? "小窗(导航中)" : "大窗(未导航)"));
                                    NavLog.w("布局切换: " + (on ? "小窗" : "大窗") + " 来源=dash", true);
                                    dashCard = on;
                                    setFastCapture(on);
                                    confirmCount = 0;
                                    lastSwitchAt = android.os.SystemClock.elapsedRealtime();
                                    set(on, "dash");
                                }
                            }
                        } else if (dashCard && NavProbe.failStreak >= 20 && !eventTrusted()) {
                            // 1.4.116：回滚到 1.4.114 的失联阈值（20 次 ≈ 3 分钟）；
                            // 6 次那版在实车上被判定为"改坏了"，退回。
                            dashCard = false;
                            setFastCapture(false);
                            confirmCount = 0;
                            lastSwitchAt = android.os.SystemClock.elapsedRealtime();
                            ClusterDebug.e("CLUSTERLAB-NAV", "采样失联 ≥6 次，回退大窗");
                            NavLog.w("布局切换: 大窗 来源=stale", true);
                            set(false, "stale");
                        }
                    } catch (Throwable ignored) {}
                    // 1.4.193：导航中/事件热窗内保持 helper 有新帧；没有广播时退出导航靠这条。
                    if (!fail) maybeKeepaliveCapture(android.os.SystemClock.elapsedRealtime());
                    // 统一节拍：失败也走同一个出口（原来是 sleep(700)+continue，
                    // 失败越快重试越猛，等于自激，把 adbd 抢得更死）。
                    try {
                        long gap;
                        if (fail) {
                            // 需求 5：单次失败只退 1.5s，连续 3 次才退 8s（真的连不上才让路 adbd）
                            gap = NavProbe.failStreak >= 3 ? GAP_FAIL_MS : GAP_FAIL_SOFT_MS;
                        } else if (broadcastFresh()) gap = GAP_BROADCAST_MS;
                        else if (boostActive()) gap = GAP_BOOST_MS;
                        else gap = dashCard ? GAP_NAV_MS : GAP_IDLE_MS;
                        // 1.4.123：已经读到和当前状态不一致的帧 → 立刻补一帧确认，
                        // 不等节拍。这是把「导航结束」从 10 秒压到 2 秒内的关键。
                        if (!fail && NavProbe.lastResult != dashCard) gap = Math.min(gap, GAP_CONFIRM_MS);

                        // 需求 6：蓝条占比越过 60%/20% 阈值 → 立刻补一帧确认，
                        // 把「导航开始/结束」的最坏等待从 5~10s 压到 ~0.3s 起算。
                        if (!fail) {
                            float r = NavProbe.lastRatio;
                            if (prevBlueRatio >= 0f) {
                                boolean cross = (prevBlueRatio >= 60f && r < 60f)
                                        || (prevBlueRatio < 60f && r >= 60f)
                                        || (prevBlueRatio >= 20f && r < 20f)
                                        || (prevBlueRatio < 20f && r >= 20f);
                                if (cross) gap = Math.min(gap, GAP_CONFIRM_MS);
                            }
                            prevBlueRatio = r;
                        }
                        // 开机 90 秒内：别人也在拉 ADB，我们完全让路（只保留 10s 一次的慢采样）。
                        // 1.4.191：导航事件已经把采样提速到 800ms/300ms，不能再被这条
                        // 10s 上限反向压回去；否则冷启动后刚开始导航，音乐大小窗要等
                        // 3×10s 才切换，现场就是“超级久才切换”。
                        if (android.os.SystemClock.elapsedRealtime() < YIELD_AFTER_BOOT_MS
                                && !boostActive() && !broadcastFresh() && !eventTrusted()) {
                            gap = Math.max(gap, GAP_YIELD_MS);
                        }
                        sleepGap(gap);
                    } catch (Throwable ignored) {}
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    /** 叫醒当前采样的 sleep；没有线程时由 boost 先 startPoller。 */
    private static void wakePoller() {
        synchronized (POLLER_GATE) {
            pollerWakeAsked = true;
            POLLER_GATE.notifyAll();
        }
    }

    /** 可唤醒的等待：导航事件到达时直接进入下一帧，不再把 10s/60s 等满。 */
    private static void sleepGap(long ms) {
        synchronized (POLLER_GATE) {
            if (pollerWakeAsked) {
                pollerWakeAsked = false;
                return;
            }
            try { POLLER_GATE.wait(ms); } catch (Throwable ignored) {}
            pollerWakeAsked = false;
        }
    }

    private static void readOverride() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastOverrideRead < 2000) return;
        lastOverrideRead = now;
        File f = new File("/sdcard/clusterlab_nav.txt");
        if (!f.exists()) { overrideOn = false; return; }
        boolean on = false, val = false;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(f));
            String line = br.readLine();
            if (line != null) {
                String v = line.trim();
                if (v.equals("1")) { on = true; val = true; }
                else if (v.equals("0")) { on = true; val = false; }
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (br != null) br.close(); } catch (Throwable ignored) {}
        }
        overrideOn = on;
        overrideValue = val;
    }
}
