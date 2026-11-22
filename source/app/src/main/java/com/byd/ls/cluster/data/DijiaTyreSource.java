package com.byd.ls.cluster.data;

import android.os.SystemClock;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * 公开版 1.5.1.2 胎压降级源：原生 instrument 四轮缺失/异常时，从迪加本地 HTTP 取一次。
 *
 * 接口与自用版历史实现一致（1.4.46 之前的旧路径）：
 *   GET http://127.0.0.1:8988/api/getVal?name=左前轮气压
 *   返回 {"success":true,"val":"245"}
 *
 * 只在原生值不完整时调用；迪加未装、8988 未开、超时、返回失败都返回 -1，不改变原生值。
 * 4 个轮位并发请求，避免串行 HTTP 在冷启动时拖住刷新线程。
 */
final class DijiaTyreSource {
    private static final String HOST = "http://127.0.0.1:8988";
    private static final String[] NAMES = {
            "左前轮气压", "右前轮气压", "左后轮气压", "右后轮气压"
    };
    private static final int CONNECT_TIMEOUT_MS = 1500;
    private static final int READ_TIMEOUT_MS = 1500;
    private static final long JOIN_MS = 1600L;
    private static final long OK_INTERVAL_MS = 5000L;
    private static final long FAIL_BACKOFF_MS = 15000L;
    private static final int PRESSURE_MAX = 500;

    private static final Object LOCK = new Object();
    private static long nextTryAt = 0L;

    private DijiaTyreSource() {}

    /**
     * 返回 4 轮 kPa；本轮被节流/迪加不可用时返回 null。
     * 单个轮位失败为 -1，调用方按轮位合并，不整体丢弃。
     */
    static int[] read() {
        long now = SystemClock.elapsedRealtime();
        synchronized (LOCK) {
            if (now < nextTryAt) return null;
            // 先按失败退避占位，成功后再缩到 5 秒，避免迪加没装时每 800ms 打 4 次。
            nextTryAt = now + FAIL_BACKOFF_MS;
        }

        int[] out = new int[]{-1, -1, -1, -1};
        Thread[] threads = new Thread[NAMES.length];
        for (int i = 0; i < NAMES.length; i++) {
            final int idx = i;
            threads[i] = new Thread(new Runnable() {
                @Override public void run() {
                    out[idx] = val(NAMES[idx]);
                }
            }, "dijia-tyre-" + i);
            threads[i].setDaemon(true);
            threads[i].start();
        }
        for (Thread t : threads) {
            try {
                t.join(JOIN_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        boolean any = false;
        for (int v : out) {
            if (v > 0) {
                any = true;
                break;
            }
        }
        synchronized (LOCK) {
            nextTryAt = SystemClock.elapsedRealtime() + (any ? OK_INTERVAL_MS : FAIL_BACKOFF_MS);
        }
        return out;
    }

    private static int val(String name) {
        try {
            String body = http(HOST + "/api/getVal?name=" + URLEncoder.encode(name, "UTF-8"));
            JSONObject o = new JSONObject(body);
            if (!o.optBoolean("success", false)) return -1;
            double v = Double.parseDouble(o.optString("val", "-1"));
            if (Double.isNaN(v) || Double.isInfinite(v)) return -1;
            if (v <= 0 || v > PRESSURE_MAX) return -1;
            return (int) v;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String http(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setUseCaches(false);
        try {
            InputStream in = c.getInputStream();
            try {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[1024];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                return bo.toString("UTF-8");
            } finally {
                in.close();
            }
        } finally {
            c.disconnect();
        }
    }
}
