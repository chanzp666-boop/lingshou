package com.byd.ls.cluster.data;

import android.os.SystemClock;

import com.byd.ls.cluster.cfg.ClusterDebug;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * 公开版 1.5.1.3 胎压单一数据源仲裁。
 *
 * 规则：
 *   1. 迪加本地接口优先：/api/getVal?name=左前轮气压 等四条；
 *   2. 5 秒内迪加没有给出完整四轮值，才允许原生 FID/映射表写入；
 *   3. 迪加只要给过一次完整四轮值，就永久锁定迪加，映射表不再写胎压；
 *   4. 锁定后迪加偶尔失败，保留最后一次迪加值，绝不回退映射。
 *
 * 这样“迪加有数据”和“映射表”永远只有一路在写，不会互相覆盖成四轮乱跳。
 */
final class TyreSource {
    private static final String HOST = "http://127.0.0.1:8988";
    private static final String[] NAMES = {
            "左前轮气压", "右前轮气压", "左后轮气压", "右后轮气压"
    };
    private static final int CONNECT_TIMEOUT_MS = 800;
    private static final int READ_TIMEOUT_MS = 800;
    private static final long JOIN_MS = 1200L;
    private static final long DIJIA_GRACE_MS = 5000L;
    private static final int PRESSURE_MAX = 500;
    private static final long START_AT = SystemClock.elapsedRealtime();

    private static volatile boolean dijiaLocked = false;
    private static volatile boolean mappingFallback = false;
    private static volatile int[] values = {-1, -1, -1, -1};

    private TyreSource() {}

    /** 映射表/FID 是否允许写胎压。迪加锁定后永远 false。 */
    static boolean mappingAllowed() {
        return mappingFallback && !dijiaLocked;
    }

    static boolean dijiaLocked() {
        return dijiaLocked;
    }

    static int[] current() {
        int[] v = values;
        return new int[]{v[0], v[1], v[2], v[3]};
    }

    /** 每次刷新都可调用；到 5 秒仍没有迪加，就放行映射表。 */
    static void maybeFallback() {
        if (dijiaLocked || mappingFallback) return;
        if (SystemClock.elapsedRealtime() - START_AT < DIJIA_GRACE_MS) return;
        mappingFallback = true;
        log("迪加 5s 无完整数据，切换映射表");
    }

    /**
     * 主动读一次迪加。由 VehicleData 的刷新线程按 2s 节拍调用。
     * 只有四轮全部有效才认为“迪加有返回”，避免半套数据锁死。
     */
    static void poll() {
        int[] v = readDijia();
        if (complete(v)) {
            boolean was = dijiaLocked;
            values = v;
            dijiaLocked = true;
            mappingFallback = false;
            if (!was) log("迪加胎压已锁定: " + v[0] + "/" + v[1] + "/" + v[2] + "/" + v[3]);
            return;
        }
        if (!dijiaLocked) maybeFallback();
    }

    static boolean complete(int[] v) {
        if (v == null || v.length < 4) return false;
        for (int x : v) if (x <= 0 || x > PRESSURE_MAX) return false;
        return true;
    }

    /** 仅单测：跳过 5s 等待，模拟“迪加无返回、映射表已放行”。 */
    static void forceMappingForTest() {
        dijiaLocked = false;
        mappingFallback = true;
        values = new int[]{-1, -1, -1, -1};
    }

    private static int[] readDijia() {
        final int[] out = {-1, -1, -1, -1};
        Thread[] ts = new Thread[NAMES.length];
        for (int i = 0; i < NAMES.length; i++) {
            final int idx = i;
            final String name = NAMES[i];
            Thread t = new Thread(() -> out[idx] = readOne(name), "LsDijiaTyre" + idx);
            t.setDaemon(true);
            ts[i] = t;
            t.start();
        }
        for (Thread t : ts) {
            try {
                t.join(JOIN_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return out;
    }

    /** 单条 /api/getVal；迪加返回 kPa，兼容个别车型返回 bar 的情况。 */
    private static int readOne(String name) {
        HttpURLConnection c = null;
        InputStream in = null;
        try {
            String url = HOST + "/api/getVal?name=" + URLEncoder.encode(name, "UTF-8");
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setUseCaches(false);
            in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0 && bo.size() < 8192) bo.write(buf, 0, n);
            JSONObject o = new JSONObject(bo.toString("UTF-8"));
            if (!o.optBoolean("success", false)) return -1;
            double d = Double.parseDouble(o.optString("val", "-1"));
            if (!Double.isFinite(d) || d <= 0) return -1;
            if (d < 10.0) d *= 100.0;           // 2.45 bar -> 245 kPa
            int v = (int) Math.round(d);
            return (v > 0 && v <= PRESSURE_MAX) ? v : -1;
        } catch (Throwable t) {
            return -1;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private static void log(String msg) {
        ClusterDebug.e("CLUSTERLAB-TYRE", msg);
    }
}
