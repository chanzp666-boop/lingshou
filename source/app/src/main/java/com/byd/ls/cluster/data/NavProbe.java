package com.byd.ls.cluster.data;

import com.byd.ls.cluster.ClusterExec;
import com.byd.ls.cluster.cfg.ClusterDebug;

/**
 * 导航状态 + 导航卡片高度探针：截仪表屏（display 1）分析像素。
 *
 * 两条独立判据：
 *   1) 有没有导航卡片：扫卡片顶部 y172~196 的暗底占比。
 *      两种卡片都从 y170 开始，而我们的音乐窗口最早从 y198 才开始画，这条带永远干净。
 *   2) 大卡还是小卡：扫 y330~390 里有没有那整条高德蓝条（#237BD6）。
 *      大卡底边 y392，这一带整片是高德蓝；小卡底边 y340，这一带是地图底色。
 *      只认 #237BD6 这个精确色，躲开我们自己的歌词高亮蓝（#4DA3FF 实绘 #1170FF）。
 */
public final class NavProbe {
    /**
     * 导航判定只扫"卡片顶部这条带" y 172~196：
     * 两种卡片都从 y=170 开始，而我们的音乐窗口最早从 y=198 才开始画，
     * 所以这条带永远干净（实测导航时 96%，未导航 0%）。
     */
    private static final int X0 = 200, X1 = 540;
    private static final int Y0 = 172, Y1 = 196;

    /**
     * 大小卡判定区：y 330~390 / x 180~554（卡片整幅宽）。
     *   大卡：底边 y392，这一带整片是高德蓝条，实测窄色域命中 45.8~51.9%
     *   小卡：底边 y340，这一带是地图底色，实测窄色域命中 0.1~0.2%
     * 注意卡片底部是蓝条不是黑底，扫暗底会判反。
     */
    private static final int EX0 = 180, EX1 = 554;
    private static final int EY0 = 330, EY1 = 390;

    /** 只匹配高德蓝 #237BD6 (35,123,214)，容差收紧到 ±18/±20，避开我们的歌词蓝。 */
    private static final float AMAP_BLUE_THRESHOLD = 20f;

    /** 是否检测到大卡（到达时间下方有蓝条）。 */
    public static volatile boolean hasArrowBar = false;
    /** 最近一次蓝色占比（调试用，已不参与大小卡判定）。 */
    public static volatile float lastRatio = -1f;
    /** 最近一次底边白字占比（调试用）。 */
    public static volatile float lastWhite = -1f;
    /** 最近一次底带命中高德蓝 #237BD6 的占比：决定大卡 / 小卡。 */
    public static volatile float amapBlue = -1f;
    /**
     * 最近一次采样**是否真的读到了帧**。
     *
     * 真车实测 screencap 会被 adbd 偶发拒绝（"Stream open actively rejected"），
     * 旧实现失败时 return lastResult、并保留旧的判定值，失败必须能被上层看见。
     */
    public static volatile boolean lastOk = false;
    /** 连续失败次数：上层据此在失联很久后做安全回退。 */
    public static volatile int failStreak = 0;
    /** 兼容旧字段。 */
    public static volatile int blueTop = -1;

    private NavProbe() {}

    public static volatile long lastMs = 0;
    /** 1.4.124：这一帧是不是 shell 域采集进程产出的（否则走的是 adblib 兜底截图）。 */
    public static volatile boolean lastViaHelper = false;
    /** 采集进程拉起的最小间隔：通道很贵，失败也别每帧都去撞。 */
    private static volatile long lastWatchTryAt = 0L;

    private static final String RAW = "/data/local/tmp/.nav_probe.raw";

    /**
     * 采样一次。
     * 用 raw 格式截图（比 PNG 快一倍：200ms vs 500ms），
     * 并只从文件里读需要的那两段行，不做整图解码。
     */
    /** 1.4.120：screencap 自保最小间隔 —— 上游采样最密 800ms，一帧走 adblib 要 0.4~6s。 */
    /**
     * 1.4.123：采样最小间隔 2500 → 800 ms。
     *
     * 用户实测「导航开始/结束各慢 10 秒」的直接算术：状态机要 2 帧确认，
     * 而 2.5 s 限频 → 每帧之间至少 2.5 s，两帧 = 5 s，再加上空闲节拍 5 s，
     * 最坏就是 10 s。采样本身只要 0.4 s（459 KB 的 display 1 截图），
     * 800 ms 间隔仍有 50% 余量，且只在状态可能变化时才密集。
     */
    private static final long MIN_SAMPLE_GAP_MS = 250L;

    /** 1.4.123：单帧即可定论的高置信读数（真卡片 vs 真无卡片，两者互不重叠）。 */
    public static volatile boolean lastDecisive = false;
    /** 1.4.194：高德导航早信号（helper 从 BYD AmapService 日志读到），1=导航中 0=未导航 -1=没有。 */
    public static volatile int lastSig = -1;
    public static volatile long lastSigAt = 0L;
    private static volatile long lastSampleAt = 0L;
    /**
     * 1.4.121：有卡 / 无卡的连续帧计数。
     *
     * 1.4.116 把「暗底 < 40%」整段当过渡帧丢弃，实测是错的：本车未导航时仪表回浅色地图，
     * 暗底恒为 0%，于是「导航结束」永远翻不过去，只能等 failStreak≥20 的 stale 兜底
     * （实车 14:41:25 起连刷 8 分钟「过渡帧丢弃」，回到大窗纯属侥幸）。
     * 现在两种帧都算**合法读数**：有卡 2 帧确认、无卡 3 帧确认（退出多一帧防高德重绘抖动）。
     */
    private static int posStreak = 0, negStreak = 0;

    /** 最近一次成功截图后的 raw 文件戳：stamp 不变说明 screencap 静默失败、读到的是旧帧。 */
    private static long lastStamp = 0L;

    /**
     * 1.4.122：只有真正测到新帧才自增。
     *
     * sample() 被 MIN_SAMPLE_GAP_MS 限频时直接返回缓存值，
     * 而 NavState 的确认计数在每轮 poller 里都会走一遍 —— 缓存值被当成新证据连数
     * 2 次，1.6 秒就能把广播/像素刚刚定下的大小窗翻回去（实车 offset 见 docs/13 §20）。
     */
    public static volatile long seq = 0L;

    private static long stamp(String path) {
        java.io.File f = new java.io.File(path);
        return f.lastModified() ^ (f.length() * 31L);
    }

    /** 1.4.125：采集进程产出的占比行（90 字节），app 只读这一行。 */
    private static final String METRICS = "/data/local/tmp/.nav_metrics";
    /** 1.4.187 B1：采集进程心跳。指标文件空闲时是旧的，但进程仍在，不能误判死亡。 */
    private static final String HEARTBEAT = "/data/local/tmp/.nav_heartbeat";
    /** 占比行超过这个时限就当采集进程死了（helper 一轮 300ms）。 */
    private static final long METRIC_TTL_MS = 4_000L;  // 1.4.125：采集进程偶发 screencap 停顿 2~3s，2.5s 就判死会掉进 adblib 兜底并触发 8s 退避
    private static final long HEARTBEAT_TTL_MS = 7_000L;

    public static boolean sample() {
        // 特权命令保护窗口内直接让路：拉卡片/深恢复正在抢 adbd 槽位，
        // 此时再截屏只会把对方踢掉（实测 Stream open actively rejected）。
        if (ClusterExec.isProtected()) return lastResult;
        // 拉起采集进程的这几秒里别抢通道：adblib 同时开两条 stream 会被 adbd 直接拒
        // （实车 18:22~18:26 的 `navwatch 拉起 = false (空)`，脚本没执行、连 echo 都没回）。
        if (bootstrapBusy) return lastResult;
        long t0 = android.os.SystemClock.elapsedRealtime();
        if (t0 - lastSampleAt < MIN_SAMPLE_GAP_MS) return lastResult;
        readSigFile();
        lastSampleAt = t0;
        try {
            // 1.4.125 主路：shell 域采集进程已经把两片区域的占比算好了（screencap 走管道，
            // 不落盘），这里只读一行数字 —— 采样完全不占 adbd，也不写 5.5 MB 的 raw。
            float[] m = readMetrics();
            // 没有占比行 = 采集进程死了或还没起，先把监控挂上（10s 冷却）。
            if (m != null) {
                lastViaHelper = true;
                return decide(m[0], m[1], m[2], m[3], m[4], t0);
            }
            // 1.4.187 B1：采集进程空闲时不产出指标，但有 5s 心跳。
            // 心跳新鲜就说明它还活着，只是等待事件；不要掉进 adblib 截图兜底。
            if (helperHeartbeatFresh()) {
                lastViaHelper = true;
                lastOk = true;
                return lastResult;
            }
            // 兜底：采集进程没起来/掉线时才走 adblib 截一帧 raw（这条才是贵路径）。
            lastViaHelper = false;
            maybeStartWatcher();
            long st0 = stamp(RAW);
            ClusterExec.execBestEffort("screencap -d 1 " + RAW + " 2>/dev/null; echo ok", 6000);
            // 1.4.121：帧新鲜度校验。screencap 被 adbd 拒绝时 execBestEffort 静默返回，
            // 旧实现照读上一次的 raw —— 同一条读数能连刷好几分钟，状态机被喂死帧。
            long st1 = stamp(RAW);
            if (st1 == st0 || st1 == 0L) {
                lastOk = false;
                failStreak++;
                return lastResult;
            }
            lastStamp = st1;
            java.io.RandomAccessFile f = new java.io.RandomAccessFile(RAW, "r");
            try {
                int w = readIntLE(f);
                int h = readIntLE(f);
                readIntLE(f);                       // format
                long dataStart = f.length() - (long) w * h * 4;
                if (w <= 0 || h <= 0 || dataStart < 0) return lastResult;

                int dark = 0, wht = 0, tot = 0;
                for (int y = Y0; y < Y1 && y < h; y += 2) {
                    byte[] row = readRow(f, dataStart, w, y, X0, X1);
                    if (row == null) continue;
                    for (int i = 0; i + 2 < row.length; i += 4) {
                        int r = row[i] & 0xFF, g = row[i + 1] & 0xFF, b = row[i + 2] & 0xFF;
                        tot++;
                        if ((r <= 12 && g <= 18 && b <= 26) || (r <= 45 && g <= 58 && b <= 72)) dark++;
                        else if (r >= 200 && g >= 200 && b >= 200) wht++;
                    }
                }
                if (tot == 0) return lastResult;

                int blue = 0, white = 0, amap = 0, bt = 0;
                for (int y = EY0; y < EY1 && y < h; y += 2) {
                    byte[] row = readRow(f, dataStart, w, y, EX0, EX1);
                    if (row == null) continue;
                    for (int i = 0; i + 2 < row.length; i += 4) {
                        int r = row[i] & 0xFF, g = row[i + 1] & 0xFF, b = row[i + 2] & 0xFF;
                        bt++;
                        if (r > 200 && g > 200 && b > 200) white++;
                        if (b > 190 && b - r > 120 && g > 70 && g < 165) blue++;
                        if (Math.abs(r - 35) <= 18 && Math.abs(g - 123) <= 18
                                && Math.abs(b - 214) <= 20) amap++;
                    }
                }
                if (bt == 0) return lastResult;
                return decide(dark * 100f / tot, wht * 100f / tot,
                        amap * 100f / bt, white * 100f / bt, blue * 100f / bt, t0);
            } finally {
                try { f.close(); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            lastOk = false;
            failStreak++;
            return lastResult;
        } finally {
            // 不再每次 rm：文件会被下一次 screencap 覆盖，省掉一次 adb 往返+握手
            lastMs = android.os.SystemClock.elapsedRealtime() - t0;
        }
    }

    /** 状态机本体：输入一帧的四/五个占比，产出导航与大小卡结论（两条采样路径共用）。 */
    private static boolean decide(float darkRatio, float whiteRatio, float amapBluePct,
                                  float white2Pct, float blue2Pct, long t0) {
        boolean hasCard = darkRatio >= 55f && whiteRatio >= 1.0f;
        lastRatio = blue2Pct;
        lastWhite = white2Pct;
        amapBlue = amapBluePct;
        hasArrowBar = amapBlue >= AMAP_BLUE_THRESHOLD;

        // 1.4.123：高置信单帧定论。实测真卡片「暗底 94.8% 白字 4.2%」，
        // 真无卡片「暗底 0.0% 白字 62.0%」——两簇隔着 90 个百分点，不需要等第二帧。
        boolean decisive = hasCard
                ? (darkRatio >= 90f && whiteRatio >= 2f)
                : (darkRatio <= 1f && whiteRatio >= 30f);
        lastDecisive = decisive;
        if (hasCard) {
            negStreak = 0;
            if (posStreak < 8) posStreak++;
        } else {
            posStreak = 0;
            if (negStreak < 8) negStreak++;
        }
        {
            // 1.4.140：单帧定论只给「有卡」方向。退出方向的"高置信"帧正是高德整块重画的那帧
            // （暗底 95% → 0%），1 帧定论就是小窗无预警跳大窗。
            int need = (decisive && hasCard) ? 1 : (hasCard ? 2 : 3);
            int streak = hasCard ? posStreak : negStreak;
            if (hasCard != lastResult && streak >= need) {
                lastResult = hasCard;
                ClusterDebug.e("CLUSTERLAB-NAV", "卡片状态: "
                        + (hasCard ? "在（导航中）" : "不在（未导航）")
                        + " 暗底=" + darkRatio + "% 白字=" + whiteRatio + "%");
            }
        }

        String line = "采样: 暗底 "
                + String.format(java.util.Locale.US, "%.1f", darkRatio)
                + "% 白字 " + String.format(java.util.Locale.US, "%.1f", whiteRatio)
                + "% 底带导航蓝 " + String.format(java.util.Locale.US, "%.1f", amapBlue)
                + "% 底带白字 " + String.format(java.util.Locale.US, "%.1f", lastWhite)
                + "% 蓝条 " + String.format(java.util.Locale.US, "%.1f", lastRatio)
                + "% → " + (hasArrowBar ? "大卡" : "小卡") + " 导航=" + lastResult
                + "(帧" + (hasCard ? "有卡" : "无卡") + " " + posStreak + "/" + negStreak
                + (decisive ? " 定论" : "") + ")"
                + (lastViaHelper ? " 本地" : " adb")
                + " " + lastMs + "ms";
        ClusterDebug.e("CLUSTERLAB-NAV", line);
        NavLog.w(line, false);
        lastOk = true;
        failStreak = 0;
        seq++;
        return lastResult;
    }

    /** 1.4.194：读高德早信号文件（helper 从 AmapService 日志抓到 mIsBYDMapNaving）。 */
    private static final String SIG_FILE = "/data/local/tmp/.nav_sig";
    private static void readSigFile() {
        try {
            java.io.File f = new java.io.File(SIG_FILE);
            if (!f.exists()) return;
            long mt = f.lastModified();
            if (mt <= 0L || System.currentTimeMillis() - mt > 4_000L) return;
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            int sp = s.indexOf(0x20);
            if (sp <= 0) return;
            lastSig = Integer.parseInt(s.substring(0, sp));
            lastSigAt = Long.parseLong(s.substring(sp + 1));
        } catch (Throwable ignored) {}
    }

    /** 读采集进程写的占比行；超过 2.5 秒没更新就返回 null（采集进程死了，走兜底）。 */
    private static float[] readMetrics() {
        try {
            java.io.File f = new java.io.File(METRICS);
            long mt = f.lastModified();
            if (mt <= 0L || System.currentTimeMillis() - mt > METRIC_TTL_MS) return null;
            String s = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            if (s.isEmpty()) return null;
            float dark = -1f, white = -1f, amap = -1f, bw = -1f, blue = -1f;
            int sig = -1;
            long sigT = 0L;
            for (String kv : s.split(" ")) {
                int eq = kv.indexOf(0x3D);
                if (eq <= 0) continue;
                String k = kv.substring(0, eq);
                float v;
                try {
                    v = Float.parseFloat(kv.substring(eq + 1));
                } catch (NumberFormatException ne) {
                    continue;
                }
                if ("dark".equals(k)) dark = v;
                else if ("white".equals(k)) white = v;
                else if ("amap".equals(k)) amap = v;
                else if ("bw".equals(k)) bw = v;
                else if ("blue".equals(k)) blue = v;
                else if ("sig".equals(k)) sig = (int) v;
                else if ("sig_t".equals(k)) sigT = (long) v;
            }
            if (dark < 0f || white < 0f || amap < 0f || bw < 0f || blue < 0f) return null;
            lastSig = sig;
            lastSigAt = sigT;
            return new float[]{dark, white, amap, bw, blue};
        } catch (Throwable t) {
            return null;
        }
    }
    /** 采集进程是否还活着（b1：空闲时指标是旧的，心跳每 5s 刷新）。 */
    private static boolean helperHeartbeatFresh() {
        try {
            java.io.File f = new java.io.File(HEARTBEAT);
            long mt = f.lastModified();
            return mt > 0L && System.currentTimeMillis() - mt < HEARTBEAT_TTL_MS;
        } catch (Throwable t) {
            return false;
        }
    }

    private static int readIntLE(java.io.RandomAccessFile f) throws java.io.IOException {
        int b0 = f.read(), b1 = f.read(), b2 = f.read(), b3 = f.read();
        return (b0 & 0xFF) | ((b1 & 0xFF) << 8) | ((b2 & 0xFF) << 16) | ((b3 & 0xFF) << 24);
    }

    /** 从 raw 里读某一行的 [x0,x1) 段，返回 RGBA 字节数组。 */
    private static byte[] readRow(java.io.RandomAccessFile f, long dataStart,
                                  int w, int y, int x0, int x1) {
        try {
            int rowBytes = w * 4;
            int start = Math.min(x0, w);
            int end = Math.min(x1, w);
            if (end <= start) return null;
            f.seek(dataStart + (long) y * rowBytes + (long) start * 4);
            byte[] buf = new byte[(end - start) * 4];
            f.readFully(buf);
            return buf;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 1.4.124：采集进程不在时拉一次（10s 内最多一次）。
     *
     * 这一步要过 adblib（发命令 + 起进程），但只在冷启动/进程掉线时发生，
     * 正常采样完全靠 RAW 文件的 mtime，不碰通道。
     */
    /** 拉起采集进程期间为 true：采样让路，避免两条 adblib stream 打架。 */
    private static volatile boolean bootstrapBusy = false;

    private static void maybeStartWatcher() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastWatchTryAt < 10_000L) return;
        lastWatchTryAt = now;
        final android.content.Context c = ClusterExec.ctx();
        if (c == null) return;
        // 1.4.125：拉起要过一次 adblib（含 sleep 等首帧，最多 ~7s），绝不能在本线程等。
        // 采样线程一停，状态机就收不到新帧，表现和「通道死了」一模一样。
        bootstrapBusy = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    boolean up = com.byd.ls.shell.HelperBridge.ensureNavWatch(c);
                    ClusterDebug.e("CLUSTERLAB-NAV", "navwatch 拉起 = " + up);
                } catch (Throwable t) {
                    ClusterDebug.e("CLUSTERLAB-NAV", "navwatch 拉起异常: " + t);
                } finally {
                    bootstrapBusy = false;
                }
            }
        }, "LsNavWatchUp").start();
    }

    private static boolean analyze(int[] img) {
        int w = img[0], h = img[1];

        // 1) 卡片本体在不在：上半区有多少"极暗底色"（卡片底色 #0A0F17，地图 #3A495A）
        int tot = 0, dark = 0;
        for (int y = Y0; y < Y1 && y < h; y += 2) {
            for (int x = X0; x < X1 && x < w; x += 2) {
                int c = img[2 + y * w + x];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                tot++;
                // 卡片底色：深色模式 #0A0F17=(10,15,23)，浅色模式同样是很深的一层
                // 实测浅色模式卡片内 (36,49,63)、深色模式 (10,15,23)，都远暗于地图
                // 深色地图最暗 ~ (20,25,35)，浅色地图 (193,217,238)
                // 用"明显暗于地图"的双阈值判断
                boolean veryDark = (r <= 12 && g <= 18 && b <= 26);
                boolean cardDark = (r <= 45 && g <= 58 && b <= 72);
                if (veryDark || cardDark) dark++;
            }
        }
        if (tot == 0) { lastRatio = -1f; return lastResult; }
        float darkRatio = dark * 100f / tot;
        // 实测：卡片在时占据相当面积；不在时只有地图噪声
        boolean hasCard = darkRatio >= 50f;   // 实测：导航 95%，未导航 0%

        // 2) 大小卡：扫 y330~390 里有没有那整条高德蓝条 #237BD6。
        //    大卡这里是整片高德蓝，小卡这里是地图底色；只认这个精确色，避开歌词高亮蓝。
        int bt = 0, blue = 0, white = 0, amap = 0;
        for (int y = EY0; y < EY1 && y < h; y += 2) {
            for (int x = EX0; x < EX1 && x < w; x += 2) {
                int c = img[2 + y * w + x];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                bt++;
                if (r > 200 && g > 200 && b > 200) white++;
                if (b > 190 && b - r > 120 && g > 70 && g < 165) blue++;
                if (Math.abs(r - 35) <= 18 && Math.abs(g - 123) <= 18
                        && Math.abs(b - 214) <= 20) amap++;
            }
        }
        lastRatio = bt == 0 ? -1f : blue * 100f / bt;
        lastWhite = bt == 0 ? -1f : white * 100f / bt;
        amapBlue = bt == 0 ? -1f : amap * 100f / bt;
        hasArrowBar = amapBlue >= AMAP_BLUE_THRESHOLD;

        ClusterDebug.e("CLUSTERLAB-NAV", "采样: 卡片暗底 "
                + String.format(java.util.Locale.US, "%.1f", darkRatio)
                + "% 底带导航蓝 " + String.format(java.util.Locale.US, "%.1f", amapBlue)
                + "% 底带白字 " + String.format(java.util.Locale.US, "%.1f", lastWhite)
                + "% 蓝条 " + String.format(java.util.Locale.US, "%.1f", lastRatio)
                + "% → " + (hasArrowBar ? "大卡" : "小卡") + " 导航=" + hasCard);
        lastResult = hasCard;
        return hasCard;
    }

    /** 解 PNG（8bit RGB/RGBA、无交错）。返回 {w, h, ...像素}。 */
    private static int[] readPng(String path) {
        java.io.DataInputStream in = null;
        try {
            in = new java.io.DataInputStream(new java.io.BufferedInputStream(
                    new java.io.FileInputStream(path), 1 << 16));
            if (in.readInt() != 0x89504E47) return null;
            in.readInt();
            int w = 0, h = 0, bitDepth = 8, colorType = 6;
            java.io.ByteArrayOutputStream idat = new java.io.ByteArrayOutputStream();
            while (true) {
                int len = in.readInt();
                int type = in.readInt();
                byte[] data = new byte[len];
                in.readFully(data);
                in.readInt();
                if (type == 0x49484452) {
                    w = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16)
                            | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
                    h = ((data[4] & 0xFF) << 24) | ((data[5] & 0xFF) << 16)
                            | ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
                    bitDepth = data[8] & 0xFF;
                    colorType = data[9] & 0xFF;
                } else if (type == 0x49444154) {
                    idat.write(data);
                } else if (type == 0x49454E44) {
                    break;
                }
            }
            if (bitDepth != 8 || (colorType != 2 && colorType != 6)) return null;
            int ch = colorType == 6 ? 4 : 3;
            byte[] raw = unzip(idat.toByteArray());
            if (raw == null) return null;

            int stride = w * ch;
            int[] out = new int[2 + w * h];
            out[0] = w; out[1] = h;
            byte[] prev = new byte[stride];
            byte[] cur = new byte[stride];
            int p = 0;
            for (int y = 0; y < h; y++) {
                int filter = raw[p++] & 0xFF;
                System.arraycopy(raw, p, cur, 0, stride);
                p += stride;
                unfilter(filter, cur, prev, ch);
                for (int x = 0; x < w; x++) {
                    int i = x * ch;
                    int r = cur[i] & 0xFF, g = cur[i + 1] & 0xFF, b = cur[i + 2] & 0xFF;
                    out[2 + y * w + x] = (r << 16) | (g << 8) | b;
                }
                byte[] t = prev; prev = cur; cur = t;
            }
            return out;
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
    }

    /** 最近一次"干净帧"的导航结论。 */
    public static volatile boolean lastResult = false;

    /**
     * 检测我们自己的窗口是否进入了扫描区。
     * 音乐窗口特征：左上角有圆角深色封面（#101A2B 附近），旁边是蓝色歌词（#1170FF）。
     * 只要在扫描区的左列 (x 200~260) 出现"纯黑封面"就认为被污染。
     */
    private static boolean contaminated(int[] img, int w, int h) {
        int dark = 0, tot = 0;
        for (int y = 340; y < 420 && y < h; y += 2) {
            for (int x = 190; x < 250 && x < w; x += 2) {
                int c = img[2 + y * w + x];
                int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
                tot++;
                // 封面是纯黑/接近纯黑（#101A2B），地图底色是 #324054 明显更亮
                if (r < 25 && g < 35 && b < 50) dark++;
            }
        }
        return tot > 0 && dark * 100f / tot > 40f;
    }

    private static void unfilter(int f, byte[] cur, byte[] prev, int ch) {
        int n = cur.length;
        switch (f) {
            case 1:
                for (int i = ch; i < n; i++) cur[i] = (byte) (cur[i] + cur[i - ch]);
                break;
            case 2:
                for (int i = 0; i < n; i++) cur[i] = (byte) (cur[i] + prev[i]);
                break;
            case 3:
                for (int i = 0; i < n; i++) {
                    int a = i >= ch ? cur[i - ch] & 0xFF : 0;
                    int b = prev[i] & 0xFF;
                    cur[i] = (byte) ((cur[i] & 0xFF) + ((a + b) >> 1));
                }
                break;
            case 4:
                for (int i = 0; i < n; i++) {
                    int a = i >= ch ? cur[i - ch] & 0xFF : 0;
                    int b = prev[i] & 0xFF;
                    int c = i >= ch ? prev[i - ch] & 0xFF : 0;
                    int pp = a + b - c;
                    int pa = Math.abs(pp - a), pb = Math.abs(pp - b), pc = Math.abs(pp - c);
                    int pr = (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c);
                    cur[i] = (byte) ((cur[i] & 0xFF) + pr);
                }
                break;
            default:
                break;
        }
    }

    private static byte[] unzip(byte[] in) {
        java.util.zip.Inflater inf = new java.util.zip.Inflater();
        try {
            inf.setInput(in);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(in.length * 4);
            byte[] buf = new byte[1 << 16];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0) break;
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            try { inf.end(); } catch (Throwable ignored) {}
        }
    }
}
