package com.byd.ls.cluster.data;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 歌词时间轴偏移（单位毫秒，正数 = 歌词提前，负数 = 歌词延后）。
 *
 * 原来只有一个全局数字，每首歌版本不同（前奏长短不同）时只能反复手改文件。
 * 现在支持按曲目记忆：调好一次，同一首歌下次自动套用。
 *
 * 文件 {@link #PATH} 一行一条，向后兼容老的「整文件只有一个数字」：
 * <pre>
 *   80                      全局默认（也接受 "=80" 和 "*|*=80"）
 *   Mr. Wrong|刘浩龙=250     按歌名 + 歌手
 *   Mr. Wrong|=250           只按歌名（歌手字段常变，留空表示忽略歌手）
 * </pre>
 * 后写的覆盖先写的；解析失败的行直接跳过，绝不因为一行坏数据让整份配置失效。
 */
public final class LyricTune {

    public static final String PATH = "/sdcard/clusterlab_lyric_offset.txt";

    /** 与渲染侧的限幅一致：超过 2 秒的偏移一定是数据错了，不是听感 */
    public static final long MIN_MS = -2000L;
    public static final long MAX_MS = 2000L;

    /** 全局键，给 Map 用 */
    private static final String GLOBAL_KEY = "*|*";

    /** 文件是 /sdcard，读一次几毫秒；缓存 2 秒足够，又不会挡住刚写入的值 */
    private static final long CACHE_MS = 2_000L;
    private static long cacheAt = 0L;
    private static Map<String, Long> cache = null;

    private LyricTune() {}

    /** 取某个曲目的偏移；没有专属配置时退回全局值，再没有就是 0。 */
    public static long offsetFor(String title, String artist) {
        Map<String, Long> m = load();
        String t = norm(title);
        String a = norm(artist);
        if (t.length() > 0) {
            Long v = m.get(t + "|" + a);
            if (v != null) return clamp(v);
            v = m.get(t + "|");
            if (v != null) return clamp(v);
        }
        Long g = m.get(GLOBAL_KEY);
        return g == null ? 0L : clamp(g);
    }

    /** 按当前曲目是否已有专属配置（UI 用来区分"已校准过"和"还在用全局值"）。 */
    public static boolean hasOwn(String title, String artist) {
        Map<String, Long> m = load();
        String t = norm(title);
        if (t.length() == 0) return false;
        return m.containsKey(t + "|" + norm(artist)) || m.containsKey(t + "|");
    }

    /** 写入某曲目的偏移并落盘（保留文件里其它行）。 */
    public static void save(String title, String artist, long ms) {
        String t = norm(title);
        if (t.length() == 0) return;
        Map<String, Long> m = load();
        m.put(t + "|" + norm(artist), clamp(ms));
        writeAll(m);
    }

    /** 清掉某曲目的专属偏移（退回全局值）。 */
    public static void reset(String title, String artist) {
        String t = norm(title);
        if (t.length() == 0) return;
        Map<String, Long> m = load();
        m.remove(t + "|" + norm(artist));
        m.remove(t + "|");
        writeAll(m);
    }

    /** 强制下一次读取重新解析文件（写完不需要，writeAll 已经清了缓存）。 */
    public static void invalidate() {
        cache = null;
        cacheAt = 0L;
    }

    // ---------------------------------------------------------------- 内部

    private static long clamp(long v) {
        if (v < MIN_MS) return MIN_MS;
        if (v > MAX_MS) return MAX_MS;
        return v;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim();
    }

    private static Map<String, Long> load() {
        long now = android.os.SystemClock.elapsedRealtime();
        Map<String, Long> c = cache;
        if (c != null && now - cacheAt < CACHE_MS) return c;
        Map<String, Long> m = new LinkedHashMap<>();
        File f = new File(PATH);
        if (f.exists()) {
            BufferedReader br = null;
            try {
                br = new BufferedReader(new FileReader(f));
                String line;
                while ((line = br.readLine()) != null) {
                    String s = line.trim();
                    if (s.length() == 0 || s.startsWith("#")) continue;
                    parseLine(s, m);
                }
            } catch (Throwable ignored) {
            } finally {
                try { if (br != null) br.close(); } catch (Throwable ignored) {}
            }
        }
        cache = m;
        cacheAt = now;
        return m;
    }

    /**
     * 解析一行。
     *
     * 用最后一个 '=' 切分：歌名里带 '=' 的情况比"值里带 '='"常见得多，
     * 而右侧永远是纯数字，从右往左切最稳。
     */
    private static void parseLine(String s, Map<String, Long> m) {
        try {
            int eq = s.lastIndexOf('=');
            if (eq < 0) {
                // 老格式：整文件一个数字
                m.put(GLOBAL_KEY, clamp(Long.parseLong(s)));
                return;
            }
            String k = s.substring(0, eq).trim();
            long v = clamp(Long.parseLong(s.substring(eq + 1).trim()));
            if (k.length() == 0 || k.equals("*") || k.equals(GLOBAL_KEY)) {
                m.put(GLOBAL_KEY, v);
                return;
            }
            if (k.endsWith("|")) {
                String t = k.substring(0, k.length() - 1).trim();
                if (t.length() == 0) { m.put(GLOBAL_KEY, v); return; }
                m.put(t + "|", v);
                return;
            }
            if (k.contains("|")) { m.put(k, v); return; }
            // "歌名=250" 这种只写了歌名的按歌名匹配
            m.put(k + "|", v);
        } catch (Throwable ignored) {
        }
    }

    private static void writeAll(Map<String, Long> m) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 歌词时间轴偏移（毫秒，正数=歌词提前）\n");
        for (Map.Entry<String, Long> e : m.entrySet()) {
            String k = e.getKey();
            if (GLOBAL_KEY.equals(k)) sb.append(e.getValue());
            else if (k.endsWith("|")) sb.append(k).append('=').append(e.getValue());
            else sb.append(k).append('=').append(e.getValue());
            sb.append('\n');
        }
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(PATH, false);
            out.write(sb.toString().getBytes("UTF-8"));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignored) {}
        }
        cache = m;
        cacheAt = android.os.SystemClock.elapsedRealtime();
    }
}
