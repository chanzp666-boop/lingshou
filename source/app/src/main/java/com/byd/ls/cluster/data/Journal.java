package com.byd.ls.cluster.data;

import com.byd.ls.cluster.cfg.ClusterDebug;

/** 进程内日志环，同时写 logcat。 */
public final class Journal {
    private static final Object LOCK = new Object();
    private static final java.util.ArrayDeque<String> RING = new java.util.ArrayDeque<String>();
    private static final java.text.SimpleDateFormat FMT =
            new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US);


    public static void add(String s) {
        if (!ClusterDebug.on()) return;                 // 默认关：不写 ring、不写 logcat
        String line = FMT.format(new java.util.Date()) + "  " + s;
        ClusterDebug.i("CLUSTERLAB-ASSIST", s);
        synchronized (LOCK) {
            RING.addLast(line);
            while (RING.size() > 400) RING.removeFirst();
        }
    }

    public static String dump() {
        synchronized (LOCK) {
            StringBuilder sb = new StringBuilder();
            for (String s : RING) sb.append(s).append('\n');
            return sb.toString();
        }
    }

    public static void clear() {
        synchronized (LOCK) { RING.clear(); }
    }
}
