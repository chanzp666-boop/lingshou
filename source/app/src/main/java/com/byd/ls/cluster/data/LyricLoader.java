package com.byd.ls.cluster.data;

import com.byd.ls.cluster.cfg.ClusterDebug;

import org.json.JSONArray;
import org.json.JSONObject;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

public class LyricLoader {
    public static volatile List<Long> times = new ArrayList<>();
    public static volatile List<String> lines = new ArrayList<>();
    public static volatile String loadedKey = "";
    /** 正在请求的 key；同一首不重复起线程，换歌必须允许新请求。 */
    private static volatile String loadingKey = "";
    /** 全网失败后的短退避，避免每帧都重试四个源。 */
    private static volatile String failKey = "";
    private static volatile long failAt = 0L;
    private static volatile int generation = 0;
    private static final Object LOCK = new Object();
    /** 单次响应体上限：搜索/歌词接口正常都远小于这个值，超大包直接丢弃。 */
    private static final int MAX_BODY_BYTES = 64 * 1024;
    /** 同一首歌四源都没有歌词时，落盘负缓存，24 小时内不再联网重试。 */
    private static final long MISS_TTL_MS = 24L * 60 * 60 * 1000;
    /** 主源先等 1.8s；超时或未命中再启动另外三源，避免第一源卡住拖死整首。 */
    private static final long PRIMARY_HEDGE_MS = 1800L;
    /** 单个 key 的总预算；超时只算网络失败，不写 24h 负缓存。 */
    private static final long TOTAL_BUDGET_MS = 9000L;
    /** 主源/对冲源共享单次请求的短期状态，替代原来的 ThreadLocal。 */
    private static final class Request {
        final String key;
        final int gen;
        final long deadlineAt;
        final AtomicBoolean ioFail = new AtomicBoolean(false);
        final List<HttpURLConnection> conns = new ArrayList<>();
        volatile boolean active = true;
        volatile boolean applied = false;

        Request(String key, int gen) {
            this.key = key;
            this.gen = gen;
            this.deadlineAt = System.currentTimeMillis() + TOTAL_BUDGET_MS;
        }

        long remainingMs() {
            return deadlineAt - System.currentTimeMillis();
        }

        void track(HttpURLConnection c) {
            synchronized (conns) {
                if (active && !applied) {
                    conns.add(c);
                } else {
                    c.disconnect();
                }
            }
        }

        /** 第一路落地或总预算结束时，主动断开其它仍在读的 socket，别让对冲源白跑。 */
        void abortAll() {
            List<HttpURLConnection> copy;
            synchronized (conns) {
                copy = new ArrayList<>(conns);
                conns.clear();
            }
            for (HttpURLConnection c : copy) {
                try { c.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }
    /** B7 遗留的 24h 负缓存只清理一次；之后的新负缓存继续按 24h 生效。 */
    private static final String MISS_PURGE_MARKER = ".miss_purged_b9";
    private static volatile boolean missPurgeChecked = false;
    public static volatile long lrcDuration = 0L;
    /** 当前在跑的那次四源请求；音乐关闭时用来立即断网并中止线程。 */
    private static volatile Request currentReq = null;

    private static void abortActive() {
        Request r = currentReq;
        currentReq = null;
        if (r != null) {
            r.active = false;
            r.abortAll();
        }
    }

    public static void reset() {
        abortActive();
        synchronized (LOCK) {
            generation++;
            loadingKey = "";
            failKey = "";
            loadedKey = "";
        }
        times = new ArrayList<>();
        lines = new ArrayList<>();
    }

    /** 1.5.3beta2：音乐功能关闭时调用；断开在途 socket、作废旧结果，重新打开后可立即再加载。 */
    public static void cancel() {
        abortActive();
        synchronized (LOCK) {
            generation++;
            loadingKey = "";
            failKey = "";
            loadedKey = "";
        }
        times = new ArrayList<>();
        lines = new ArrayList<>();
        lrcDuration = 0L;
    }

    public static void load(final String title, final String artist) { load(null, title, artist, 0); }

    public static void load(final String title, final String artist, final long durationMs) {
        load(null, title, artist, durationMs);
    }

    public static void load(final Context ctx, final String title, final String artist,
                            final long durationMs) {
        if (title == null || title.length() == 0) return;
        purgeLegacyMissOnce();
        final String artistSafe = artist == null ? "" : artist;
        final String key = title + "|" + artistSafe;
        if (key.equals(loadedKey) || key.equals(loadingKey)) return;
        if (key.equals(failKey)
                && android.os.SystemClock.elapsedRealtime() - failAt < 15000L) return;

        // 先用上一轮落盘的歌词，开机/切歌不用等网络；没有缓存才联网。
        String cached = readCache(title, artistSafe);
        if (cached != null && cached.length() > 0) {
            parse(cached, title, artistSafe);
            if (!lines.isEmpty()) {
                loadedKey = key;
                failKey = "";
                clearMiss(title, artistSafe);
                ClusterDebug.e("CLUSTERLAB", "歌词缓存命中: " + title + " - " + artistSafe);
                return;
            }
        }

        // 四源都确认没有这首歌的歌词时，24 小时内不再联网；避免同一首反复重播时重复打接口。
        if (missFresh(title, artistSafe)) {
            failKey = key;
            failAt = android.os.SystemClock.elapsedRealtime();
            return;
        }

        // 没网就不起请求线程：四家源串行超时只会白等 6–8 秒，不会产生有效流量。
        if (!online(ctx)) {
            failKey = key;
            failAt = android.os.SystemClock.elapsedRealtime();
            return;
        }

        final int gen;
        synchronized (LOCK) {
            if (key.equals(loadingKey)) return;
            loadingKey = key;
            gen = ++generation;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                runSearch(ctx, key, gen, title, artistSafe, durationMs);
            }
        }).start();
    }

    /**
     * 主源先行，1.8s 未回/未命中再启动另外三源；总预算 9s。
     * 先成功的那一路在 commit() 里一次性 parse + save，其他路只返回结果，不再改全局歌词。
     */
    private static void runSearch(final Context ctx, final String key, final int gen,
                                  final String title, final String artist, final long durationMs) {
        final String clean = searchForm(title.replaceAll("\\s*[\\(\\[（【].*?[\\)\\]）】]", "").trim());
        final Request req = new Request(key, gen);
        synchronized (LOCK) {
            // 任务在起线程前就被 cancel/reset 作废：一个请求都不发。
            if (gen != generation) return;
            currentReq = req;
        }
        final ExecutorService pool = Executors.newFixedThreadPool(4, new java.util.concurrent.ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "ls-lyric-fetch");
                t.setDaemon(true);
                return t;
            }
        });
        final CountDownLatch done = new CountDownLatch(4);
        try {
            Future<Boolean> primary = submitSource(pool, done, req,
                    new Callable<Boolean>() {
                        @Override public Boolean call() {
                            return loadFromNetease(clean, artist, durationMs, title, req);
                        }
                    });

            boolean primaryDone = false;
            try {
                Boolean ok = primary.get(PRIMARY_HEDGE_MS, TimeUnit.MILLISECONDS);
                primaryDone = true;
                if (Boolean.TRUE.equals(ok) || req.applied) return;
            } catch (TimeoutException te) {
                ClusterDebug.e("CLUSTERLAB", "歌词主源 1.8s 未回，启动三源对冲: " + clean);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                req.ioFail.set(true);
                primaryDone = true;
            } catch (Throwable t) {
                req.ioFail.set(true);
                primaryDone = true;
            }

            if (!primaryDone) {
                // 主源仍在跑，但不再等它；三源并行抢时间。
            } else {
                ClusterDebug.e("CLUSTERLAB", "歌词主源未命中，启动三源对冲: " + clean);
            }

            submitSource(pool, done, req, new Callable<Boolean>() {
                @Override public Boolean call() {
                    return loadFromQQ(clean, artist, durationMs, title, req);
                }
            });
            submitSource(pool, done, req, new Callable<Boolean>() {
                @Override public Boolean call() {
                    return loadFromKugou(clean, artist, durationMs, title, req);
                }
            });
            submitSource(pool, done, req, new Callable<Boolean>() {
                @Override public Boolean call() {
                    return loadFromKuwo(clean, artist, durationMs, title, req);
                }
            });

            boolean allDone = false;
            long remain;
            while ((remain = req.remainingMs()) > 0) {
                if (!req.active || req.applied) break;
                if (done.await(Math.min(remain, 100L), TimeUnit.MILLISECONDS)) {
                    allDone = true;
                    break;
                }
            }
            if (!allDone && !req.applied) {
                req.ioFail.set(true);
                ClusterDebug.e("CLUSTERLAB", "歌词请求总预算 9s 超时: " + clean);
            }

            if (req.applied) return;
            if (req.gen != generation) return;

            failKey = key;
            failAt = android.os.SystemClock.elapsedRealtime();
            // 只有四源都正常返回“没这首歌”才写 24h 负缓存；任何一路网络/解析失败
            // 或总预算超时都不写，避免一次慢请求把歌词锁死一整天。
            if (shouldPersistMiss(req.ioFail.get(), online(ctx))) {
                markMiss(title, artist);
            } else {
                ClusterDebug.e("CLUSTERLAB", "歌词请求异常，跳过 24h 负缓存: " + clean);
            }
            ClusterDebug.e("CLUSTERLAB", "四个来源都没拿到歌词"
                    + (req.ioFail.get() ? "（网络异常）" : "") + ": " + clean);
        } catch (Throwable t) {
            req.ioFail.set(true);
            synchronized (LOCK) {
                if (gen == generation) {
                    failKey = key;
                    failAt = android.os.SystemClock.elapsedRealtime();
                }
            }
            ClusterDebug.e("CLUSTERLAB", "歌词加载异常: " + t);
        } finally {
            req.active = false;
            req.abortAll();
            if (currentReq == req) currentReq = null;
            pool.shutdownNow();
            synchronized (LOCK) {
                if (gen == generation) loadingKey = "";
            }
        }
    }

    private static Future<Boolean> submitSource(final ExecutorService pool,
                                                final CountDownLatch done,
                                                final Request req,
                                                final Callable<Boolean> work) {
        return pool.submit(new Callable<Boolean>() {
            @Override public Boolean call() {
                try {
                    if (!req.active || req.applied) return false;
                    return work.call();
                } catch (Throwable t) {
                    req.ioFail.set(true);
                    return false;
                } finally {
                    done.countDown();
                }
            }
        });
    }

    /** 只有第一路成功结果能落地；并行/对冲的其它结果在这里被挡住。 */
    private static boolean commit(Request req, String source, String text,
                                  long durationMs, String title, String artist) {
        if (text == null || text.trim().length() == 0) return false;
        synchronized (LOCK) {
            if (!req.active || req.gen != generation || req.applied) return false;
            parse(text, title, artist);
            if (lines.isEmpty()) return false;
            req.applied = true;
            lrcDuration = durationMs;
            loadedKey = req.key;
            failKey = "";
            saveCache(title, artist, text);
            ClusterDebug.e("CLUSTERLAB", "歌词来源: " + source + " " + title);
        }
        req.abortAll();
        return true;
    }

    /** 网易云：搜索接口 + 歌词接口。成功落地返回 true。 */
    private static boolean loadFromNetease(String clean, String artist, long durationMs, String title, Request req) {
        try {
            String q = URLEncoder.encode(clean + " " + artist, "UTF-8");
            String search = http("https://music.163.com/api/search/get/web?csrf_token=&s="
                    + q + "&type=1&offset=0&total=true&limit=6", req);
            JSONObject o = new JSONObject(search);
            JSONArray songs = o.getJSONObject("result").getJSONArray("songs");
            if (songs.length() == 0) return false;
            long id = -1L, bestDur = 0L;
            double bestScore = -1;
            String bestName = "", bestSinger = "";
            for (int i = 0; i < songs.length(); i++) {
                JSONObject s = songs.getJSONObject(i);
                String nm = s.optString("name", "");
                String singer = joinArtists(s.optJSONArray("artists"));
                long d = s.optLong("duration", 0);
                double score = scoreCandidate(nm, singer, d, durationMs, clean, artist, i);
                if (score > bestScore) {
                    bestScore = score; id = s.getLong("id"); bestDur = d;
                    bestName = nm; bestSinger = singer;
                }
            }
            if (id < 0 || bestScore < 70) {
                ClusterDebug.e("CLUSTERLAB", "网易云无可靠匹配: " + clean + " / " + artist);
                return false;
            }
            ClusterDebug.e("CLUSTERLAB", "网易云候选: " + bestName + " - " + bestSinger + " score=" + bestScore);
            String lyric = http("https://music.163.com/api/song/lyric?id=" + id + "&lv=1&kv=1&tv=-1", req);
            JSONObject lo = new JSONObject(lyric);
            String text = lo.getJSONObject("lrc").getString("lyric");
            if (text == null || text.trim().length() == 0) return false;
            return commit(req, "网易云", text, bestDur, title, artist);
        } catch (Throwable t) {
            req.ioFail.set(true);
            return false;
        }
    }

    /**
     * QQ 音乐：搜索 → 拿 songmid → 取歌词。
     * 走 c.y.qq.com 的公开接口，Referer 必须带上，否则 403。
     */
    private static boolean loadFromQQ(String clean, String artist, long durationMs, String title, Request req) {
        try {
            String kw = URLEncoder.encode((clean + " " + artist).trim(), "UTF-8");
            String searchUrl = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"
                    + "?cr=1&t=0&aggr=1&flag=1&lossless=0&curnum=8&curpage=1&format=json"
                    + "&w=" + kw;
            String r = httpQQ(searchUrl, req);
            if (r == null) { ClusterDebug.e("CLUSTERLAB", "QQ搜索无响应"); return false; }
            // format=json 返回纯 JSON；个别情况是 callback({...})，只在这种形态才去壳
            r = stripJsonp(r);
            JSONObject o = new JSONObject(r);
            JSONObject songObj = o.getJSONObject("data").getJSONObject("song");
            JSONArray list = songObj.getJSONArray("list");
            if (list.length() == 0) { ClusterDebug.e("CLUSTERLAB", "QQ搜索无结果"); return false; }

            String mid = null, bestName = null, bestSinger = "";
            long bestDur = 0L;
            double bestScore = -1;
            for (int i = 0; i < list.length(); i++) {
                JSONObject s = list.getJSONObject(i);
                String nm = s.optString("songname", "");
                String mid2 = s.optString("songmid", "");
                if (mid2.length() == 0) continue;
                String singer = joinArtists(s.optJSONArray("singer"));
                long interval = s.optLong("interval", 0) * 1000L;   // 秒
                double score = scoreCandidate(nm, singer, interval, durationMs, clean, artist, i);
                if (score > bestScore) {
                    bestScore = score; mid = mid2; bestName = nm; bestSinger = singer; bestDur = interval;
                }
            }
            if (mid == null || bestScore < 70) {
                ClusterDebug.e("CLUSTERLAB", "QQ无可靠匹配: " + clean + " / " + artist);
                return false;
            }

            String text = fetchQQLyric(mid, req);
            if (text != null) {
                ClusterDebug.e("CLUSTERLAB", "QQ 歌词命中: " + bestName);
                return commit(req, "QQ音乐", text, bestDur, title, artist);
            }
            ClusterDebug.e("CLUSTERLAB", "QQ最佳候选无歌词，尝试同曲同版本: " + bestName);

            // 同一首歌在 QQ 会挂多个版本；首选版本没有歌词时，回退到曲名精确
            // 且时长相同的版本。不能只看歌手名，张晓涵/张曦匀就是同一人的不同写法。
            for (int i = 0; i < list.length(); i++) {
                JSONObject s = list.getJSONObject(i);
                String mid2 = s.optString("songmid", "");
                if (mid2.length() == 0 || mid2.equals(mid)) continue;
                String nm = s.optString("songname", "");
                long d = s.optLong("interval", 0) * 1000L;
                if (!sameExactVersion(nm, d, clean, bestDur)) continue;
                String alt = fetchQQLyric(mid2, req);
                if (alt != null) {
                    ClusterDebug.e("CLUSTERLAB", "QQ备用候选命中: " + nm + " - "
                            + joinArtists(s.optJSONArray("singer")));
                    return commit(req, "QQ音乐", alt, d, title, artist);
                }
            }
            return false;
        } catch (Throwable t) {
            req.ioFail.set(true);
            ClusterDebug.e("CLUSTERLAB", "QQ 歌词失败: " + t);
            return false;
        }
    }

    private static String fetchQQLyric(String mid, Request req) throws Exception {
        String lyricUrl = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg"
                + "?songmid=" + mid + "&g_tk=5381&format=json&nobase64=0";
        String lr = httpQQ(lyricUrl, req);
        if (lr == null) return null;
        lr = stripJsonp(lr);
        JSONObject lo = new JSONObject(lr);
        String b64 = lo.optString("lyric", "");
        if (b64.length() == 0) return null;
        String text = new String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), "UTF-8");
        // QQ 的歌词是 HTML 转义的
        return text.replace("&apos;", "'").replace("&quot;", "\"")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
    }

    /** 曲名精确且与首选版本同长度，才允许回退，避免为了出词匹配到别的翻唱/剪辑。 */
    private static boolean sameExactVersion(String name, long candDurMs, String clean, long bestDurMs) {
        if (!norm(name).equals(norm(clean))) return false;
        if (bestDurMs <= 0 || candDurMs <= 0) return false;
        return Math.abs(candDurMs - bestDurMs) <= 1500L;
    }

    /**
     * 酷狗：搜索拿 hash → krcs 取歌词下载地址 → 下载 base64 的歌词。
     * 迪加的 kugou-online 来源就是这套。
     */
    private static boolean loadFromKugou(String clean, String artist, long durationMs, String title, Request req) {
        try {
            String kw = URLEncoder.encode((clean + " " + artist).trim(), "UTF-8");
            String r = httpPlain("http://mobilecdn.kugou.com/api/v3/search/song"
                    + "?format=json&keyword=" + kw + "&page=1&pagesize=8", "http://m.kugou.com/", req);
            if (r == null) return false;
            JSONObject o = new JSONObject(r);
            JSONArray info = o.getJSONObject("data").optJSONArray("info");
            if (info == null || info.length() == 0) return false;

            String hash = null, bestName = "", bestSinger = "";
            long bestDur = 0L;
            double bestScore = -1;
            for (int i = 0; i < info.length(); i++) {
                JSONObject it = info.getJSONObject(i);
                String h = it.optString("hash", "");
                if (h.length() == 0) continue;
                String nm = it.optString("songname", "");
                String singer = it.optString("singername", "");
                long d = it.optLong("duration", 0);
                if (d > 0 && d < 10000) d *= 1000L;
                double score = scoreCandidate(nm, singer, d, durationMs, clean, artist, i);
                if (score > bestScore) {
                    bestScore = score; hash = h; bestName = nm; bestSinger = singer; bestDur = d;
                }
            }
            if (hash == null || bestScore < 70) {
                ClusterDebug.e("CLUSTERLAB", "酷狗无可靠匹配: " + clean + " / " + artist);
                return false;
            }
            ClusterDebug.e("CLUSTERLAB", "酷狗候选: " + bestName + " - " + bestSinger + " score=" + bestScore);

            String text = fetchKugouLyric(hash, clean, req);
            if (text != null) {
                ClusterDebug.e("CLUSTERLAB", "酷狗 歌词命中: " + bestName);
                return commit(req, "酷狗", text, bestDur, title, artist);
            }
            ClusterDebug.e("CLUSTERLAB", "酷狗最佳候选无歌词，尝试同曲同版本: " + bestName);

            // 同 QQ：优先版本没有词时，只回退曲名精确且时长相同的版本。
            for (int i = 0; i < info.length(); i++) {
                JSONObject it = info.getJSONObject(i);
                String h = it.optString("hash", "");
                if (h.length() == 0 || h.equals(hash)) continue;
                String nm = it.optString("songname", "");
                long d = it.optLong("duration", 0);
                if (d > 0 && d < 10000) d *= 1000L;
                if (!sameExactVersion(nm, d, clean, bestDur)) continue;
                String alt = fetchKugouLyric(h, clean, req);
                if (alt != null) {
                    ClusterDebug.e("CLUSTERLAB", "酷狗备用候选命中: " + nm + " - "
                            + it.optString("singername", ""));
                    return commit(req, "酷狗", alt, d, title, artist);
                }
            }
            return false;
        } catch (Throwable t) {
            req.ioFail.set(true);
            ClusterDebug.e("CLUSTERLAB", "酷狗歌词失败: " + t);
            return false;
        }
    }

    private static String fetchKugouLyric(String hash, String clean, Request req) throws Exception {
        String krcs = httpPlain("http://krcs.kugou.com/search"
                + "?ver=1&man=yes&client=mobi&keyword="
                + URLEncoder.encode(clean, "UTF-8") + "&duration=&hash=" + hash,
                "http://m.kugou.com/", req);
        if (krcs == null) return null;
        JSONObject ko = new JSONObject(krcs);
        JSONArray cand = ko.optJSONArray("candidates");
        if (cand == null || cand.length() == 0) return null;
        for (int i = 0; i < cand.length(); i++) {
            JSONObject c = cand.getJSONObject(i);
            String id = c.optString("id", "");
            String accesskey = c.optString("accesskey", "");
            if (id.length() == 0 || accesskey.length() == 0) continue;
            String dl = httpPlain("http://lyrics.kugou.com/download"
                    + "?ver=1&client=pc&id=" + id + "&accesskey=" + accesskey
                    + "&fmt=lrc&charset=utf8", "http://m.kugou.com/", req);
            if (dl == null) continue;
            JSONObject lo = new JSONObject(dl);
            String content = lo.optString("content", "");
            if (content.length() == 0) continue;
            return new String(android.util.Base64.decode(content, android.util.Base64.DEFAULT), "UTF-8");
        }
        return null;
    }

    /** 酷我：搜索拿 RID → 取歌词。 */
    private static boolean loadFromKuwo(String clean, String artist, long durationMs, String title, Request req) {
        try {
            String kw = URLEncoder.encode((clean + " " + artist).trim(), "UTF-8");
            String r = httpPlain("http://search.kuwo.cn/r.s?all=" + kw
                    + "&ft=music&itemset=web_2013&client=kt&pn=0&rn=8&rformat=json&encoding=utf8",
                    "http://www.kuwo.cn/", req);
            if (r == null) return false;
            // 酷我返回单引号 JSON，先规整成标准 JSON
            String j = r.replace('\'', '"');
            JSONObject o = new JSONObject(j);
            JSONArray list = o.optJSONArray("abslist");
            if (list == null || list.length() == 0) return false;
            String rid = null, bestName = "", bestSinger = "";
            long bestDur = 0L;
            double bestScore = -1;
            for (int i = 0; i < list.length(); i++) {
                JSONObject it = list.getJSONObject(i);
                String id = it.optString("DC_TARGETID", "");
                if (id.length() == 0) continue;
                String nm = it.optString("SONGNAME", it.optString("songname", ""));
                String singer = it.optString("ARTIST", it.optString("artist", ""));
                long d = it.optLong("DURATION", it.optLong("duration", 0));
                if (d > 0 && d < 10000) d *= 1000L;
                double score = scoreCandidate(nm, singer, d, durationMs, clean, artist, i);
                if (score > bestScore) {
                    bestScore = score; rid = id; bestName = nm; bestSinger = singer; bestDur = d;
                }
            }
            if (rid == null || bestScore < 70) {
                ClusterDebug.e("CLUSTERLAB", "酷我无可靠匹配: " + clean + " / " + artist);
                return false;
            }
            ClusterDebug.e("CLUSTERLAB", "酷我候选: " + bestName + " - " + bestSinger + " score=" + bestScore);

            String lrc = httpPlain("http://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=" + rid,
                    "http://m.kuwo.cn/", req);
            if (lrc == null) return false;
            JSONObject lo = new JSONObject(lrc);
            JSONObject data = lo.optJSONObject("data");
            if (data == null) return false;
            JSONArray lrcList = data.optJSONArray("lrclist");
            if (lrcList == null || lrcList.length() == 0) return false;

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < lrcList.length(); i++) {
                JSONObject it = lrcList.getJSONObject(i);
                String tm = it.optString("time", "");
                String tx = it.optString("lineLyric", "");
                if (tm.length() == 0 || tx.length() == 0) continue;
                try {
                    double sec = Double.parseDouble(tm);
                    long min = (long) (sec / 60);
                    double s2 = sec - min * 60;
                    sb.append(String.format(java.util.Locale.US, "[%02d:%05.2f]", min, s2))
                      .append(tx).append('\n');
                } catch (Throwable ignored) {}
            }
            if (sb.length() == 0) return false;
            return commit(req, "酷我", sb.toString(), bestDur, title, artist);
        } catch (Throwable t) {
            req.ioFail.set(true);
            ClusterDebug.e("CLUSTERLAB", "酷我歌词失败: " + t);
            return false;
        }
    }

    /* ---------- 歌词缓存：/sdcard/clusterlab_lyrics/<hash>.lrc ---------- */

    private static final String CACHE_DIR = "/sdcard/clusterlab_lyrics";
    /** 歌词缓存总上限：成功歌词 + 负缓存，最多占用 100MB。 */
    private static final long MAX_CACHE_BYTES = 100L * 1024 * 1024;
    private static final Object CACHE_TRIM_LOCK = new Object();

    private static String cacheFile(String title, String artist) {
        String key = title + "|" + (artist == null ? "" : artist);
        return CACHE_DIR + "/" + Integer.toHexString(key.hashCode()) + ".lrc";
    }

    /**
     * B7/B8 的 24h 负缓存可能是开机网络未就绪、或繁体歌名匹配失败时误写的。
     * B9 第一次运行时清一次，之后新写的 .miss 继续按 24h 生效；成功歌词 .lrc 一律不动。
     */
    private static void purgeLegacyMissOnce() {
        if (missPurgeChecked) return;
        File dir = new File(CACHE_DIR);
        File marker = new File(dir, MISS_PURGE_MARKER);
        if (marker.exists()) {
            missPurgeChecked = true;
            return;
        }
        if (!dir.exists() && !dir.mkdirs()) return;
        int removed = purgeMissFiles(dir);
        try {
            if (marker.createNewFile() || marker.exists()) missPurgeChecked = true;
        } catch (Throwable ignored) {}
        if (removed > 0) {
            ClusterDebug.e("CLUSTERLAB", "清理 B7/B8 遗留歌词负缓存: " + removed);
        }
    }

    /** 只删 .lrc.miss；包级可见，单测直接用临时目录验证。 */
    static int purgeMissFiles(File dir) {
        if (dir == null) return 0;
        File[] all = dir.listFiles();
        if (all == null) return 0;
        int removed = 0;
        for (File f : all) {
            if (f != null && f.isFile() && f.getName().endsWith(".lrc.miss") && f.delete()) {
                removed++;
            }
        }
        return removed;
    }

    private static String readCache(String title, String artist) {
        try {
            File f = new File(cacheFile(title, artist));
            if (!f.exists() || f.length() <= 0 || f.length() > 512 * 1024) return null;
            FileInputStream in = new FileInputStream(f);
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                // 命中即刷新 mtime，让淘汰顺序接近 LRU；一分钟内不重复写元数据。
                long now = System.currentTimeMillis();
                if (now - f.lastModified() > 60 * 1000L) f.setLastModified(now);
                return new String(bos.toByteArray(), "UTF-8");
            } finally { try { in.close(); } catch (Throwable ignored) {} }
        } catch (Throwable ignored) { return null; }
    }

    private static void saveCache(String title, String artist, String lrc) {
        if (lrc == null || lrc.length() == 0) return;
        try {
            File dir = new File(CACHE_DIR);
            if (!dir.exists()) dir.mkdirs();
            File f = new File(cacheFile(title, artist));
            FileOutputStream out = new FileOutputStream(f);
            try { out.write(lrc.getBytes("UTF-8")); }
            finally { try { out.close(); } catch (Throwable ignored) {} }
            clearMiss(title, artist);
            trimCache(dir, MAX_CACHE_BYTES);
        } catch (Throwable ignored) {}
    }

    /**
     * 只处理本缓存目录的 .lrc / .lrc.miss，按 mtime 从旧到新删除，直到总量不超过上限。
     * 包级可见，便于 JVM 单测直接验证淘汰顺序和保留新文件。
     */
    static int trimCache(File dir, long maxBytes) {
        if (dir == null || maxBytes <= 0) return 0;
        synchronized (CACHE_TRIM_LOCK) {
            File[] all = dir.listFiles();
            if (all == null || all.length == 0) return 0;
            ArrayList<File> candidates = new ArrayList<>();
            long total = 0L;
            for (File f : all) {
                if (f == null || !f.isFile()) continue;
                String name = f.getName();
                if (!name.endsWith(".lrc") && !name.endsWith(".lrc.miss")) continue;
                candidates.add(f);
                total += f.length();
            }
            if (total <= maxBytes) return 0;
            java.util.Collections.sort(candidates, new java.util.Comparator<File>() {
                @Override public int compare(File a, File b) {
                    long am = a.lastModified(), bm = b.lastModified();
                    if (am != bm) return am < bm ? -1 : 1;
                    return a.getName().compareTo(b.getName());
                }
            });
            int removed = 0;
            for (File f : candidates) {
                if (total <= maxBytes) break;
                long len = f.length();
                if (f.delete()) {
                    total -= len;
                    removed++;
                }
            }
            return removed;
        }
    }

    /** 负缓存与成功歌词同目录：<hash>.lrc.miss，文件 mtime 即失败时间。 */
    private static String missFile(String title, String artist) {
        return cacheFile(title, artist) + ".miss";
    }

    private static boolean missFresh(String title, String artist) {
        try {
            File f = new File(missFile(title, artist));
            if (!f.exists()) return false;
            long age = System.currentTimeMillis() - f.lastModified();
            if (age >= 0 && age < MISS_TTL_MS) return true;
            f.delete();
        } catch (Throwable ignored) {}
        return false;
    }

    /** 只有“网络正常 + 没有发生 IO/解析失败”才承认四源都没找到，允许写负缓存。 */
    static boolean shouldPersistMiss(boolean ioFail, boolean online) {
        return !ioFail && online;
    }

    private static void markMiss(String title, String artist) {
        try {
            File dir = new File(CACHE_DIR);
            if (!dir.exists()) dir.mkdirs();
            File f = new File(missFile(title, artist));
            if (!f.exists()) new FileOutputStream(f).close();
            f.setLastModified(System.currentTimeMillis());
            trimCache(dir, MAX_CACHE_BYTES);
        } catch (Throwable ignored) {}
    }

    private static void clearMiss(String title, String artist) {
        try { new File(missFile(title, artist)).delete(); } catch (Throwable ignored) {}
    }

    /** 读取前先看默认网络；ctx 为空时保持旧行为（测试/兼容调用）。 */
    private static boolean online(Context ctx) {
        if (ctx == null) return true;
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo info = cm == null ? null : cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Throwable ignored) {
            return true; // 系统查询异常时不要误杀歌词能力，让请求自己按超时失败
        }
    }

    /* ---------- 候选打分：曲名必须匹配，歌手/时长决定选哪一首 ---------- */

    private static String joinArtists(JSONArray a) {
        if (a == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            String n = o == null ? "" : o.optString("name", "");
            if (n.length() == 0) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(n);
        }
        return sb.toString();
    }

    private static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase().replace("&nbsp;", " ")
                .replaceAll("[\\s\\u00a0·,，/&（）()\\[\\]【】]+", "");
    }

    /**
     * 搜索口径：Apple Music 常见繁体歌名（如“这麽近那麽远”），四源返回简体候选
     * （“这么近那么远”），直接用原字串会判成曲名不符。搜索/评分前统一转一次简体；
     * 缓存 key 仍用原始 title|artist，不影响已有缓存。
     */
    static String searchForm(String s) {
        if (s == null || s.length() == 0) return "";
        String out = s;
        try {
            String v = android.icu.text.Transliterator
                    .getInstance("Traditional-Simplified").transliterate(s);
            if (v != null && v.length() > 0) out = v;
        } catch (Throwable ignored) {}
        return out
                .replace("那麽", "那么")
                .replace("這麽", "这么")
                .replace("麼", "么")
                .replace("麽", "么")
                .replace("這", "这")
                .replace("遠", "远")
                .replace("還", "还")
                .replace("為", "为")
                .replace("與", "与")
                .replace("對", "对")
                .replace("無", "无")
                .replace("聽", "听")
                .replace("愛", "爱")
                .replace("夢", "梦");
    }

    /**
     * 返回 -1 表示曲名不符；分数 >=70 才用。
     * 曲名命中 100/40，歌手命中 +80，不命中 -60，时长接近 +50/+20，远了 -40。
     */
    private static double scoreCandidate(String name, String singer, long candDurMs,
                                         long targetDurMs, String clean, String artist, int idx) {
        String nm = norm(name), cl = norm(clean);
        if (nm.length() == 0 || cl.length() == 0) return -1;
        double score;
        if (nm.equals(cl)) score = 100;
        else if (nm.contains(cl) || cl.contains(nm)) score = 40;
        else return -1;

        String si = norm(singer), ar = norm(artist);
        if (ar.length() > 0) {
            if (si.length() > 0 && (si.contains(ar) || ar.contains(si))) score += 80;
            else score -= 60;
        }
        if (targetDurMs > 20000 && candDurMs > 0) {
            long diff = Math.abs(candDurMs - targetDurMs);
            if (diff <= 1500) score += 50;
            else if (diff <= 5000) score += 20;
            else score -= 40;
        }
        return score - idx * 2;
    }

    /** 通用 HTTP GET（酷狗/酷我都要带 Referer）。 */
    private static String httpPlain(String url, String referer, Request req) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            int timeout = requestTimeoutMs(req, 6000);
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout);
            if (req != null) req.track(c);
            c.setRequestProperty("Referer", referer);
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            c.setRequestProperty("Accept-Encoding", "gzip");
            if (c.getResponseCode() != 200) {
                req.ioFail.set(true);
                return null;
            }
            byte[] body = readBody(c, MAX_BODY_BYTES);
            return new String(body, "UTF-8");
        } catch (Throwable t) {
            req.ioFail.set(true);
            return null;
        }
    }

    /** 单次 HTTP 不能超过当前 key 的剩余总预算。 */
    private static int requestTimeoutMs(Request req, int maxMs) {
        if (req == null) return maxMs;
        long remain = req.remainingMs();
        if (remain <= 0) return 1;
        return (int) Math.min((long) maxMs, Math.max(1000L, remain));
    }

    /** 只在 callback(...) / jQuery(...) 这种形态下去壳，纯 JSON 原样返回。 */
    private static String stripJsonp(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.startsWith("{")) return t;
        int lb = t.indexOf('(');
        int rb = t.lastIndexOf(')');
        if (lb > 0 && rb > lb) return t.substring(lb + 1, rb).trim();
        return t;
    }

    /** 读响应体：显式接受 gzip，按 Content-Encoding 解码，并限制单次最多 64KB。 */
    private static byte[] readBody(HttpURLConnection c, int maxBytes) throws IOException {
        long len = c.getContentLengthLong();
        if (len > maxBytes) throw new IOException("body too large: " + len);
        InputStream raw = c.getInputStream();
        InputStream in = raw;
        String enc = c.getContentEncoding();
        if (enc != null && enc.toLowerCase(Locale.US).contains("gzip")) {
            in = new GZIPInputStream(raw);
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) throw new IOException("body too large");
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
    }

    /** QQ 接口要 Referer，否则返回 403。 */
    private static String httpQQ(String url, Request req) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            int timeout = requestTimeoutMs(req, 6000);
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout);
            if (req != null) req.track(c);
            c.setRequestProperty("Referer", "https://y.qq.com/");
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            c.setRequestProperty("Accept-Encoding", "gzip");
            if (c.getResponseCode() != 200) {
                req.ioFail.set(true);
                return null;
            }
            byte[] body = readBody(c, MAX_BODY_BYTES);
            return new String(body, "UTF-8");
        } catch (Throwable t) {
            req.ioFail.set(true);
            return null;
        }
    }

    private static void parse(String lrc) {
        parse(lrc, null, null);
    }

    private static void parse(String lrc, String title, String artist) {
        List<Long> t = new ArrayList<>();
        List<String> s = new ArrayList<>();
        for (String raw : lrc.split("\n")) {
            String line = raw.trim();
            if (line.length() < 6 || line.charAt(0) != '[') continue;
            int close = line.indexOf(']');
            if (close < 0) continue;
            String stamp = line.substring(1, close);
            String body = line.substring(close + 1).trim();
            if (body.length() == 0) continue;
            String[] mmss = stamp.split(":");
            if (mmss.length != 2) continue;
            try {
                long min = Long.parseLong(mmss[0].trim());
                double sec = Double.parseDouble(mmss[1].trim());
                t.add((long) (min * 60000 + sec * 1000));
                s.add(body);
            } catch (Throwable ignored) {
            }
        }
        // 源里没有曲名/歌手等头部信息时（很多日韩歌源只有正文），
        // 自己补上，保证歌词结构和以前一致：歌名 → 歌手 → 词曲 → 正文
        if (s.size() > 0 && title != null && title.length() > 0 && !hasHeaderInfo(s)) {
            s.add(0, artist != null && artist.length() > 0 ? (title + " - " + artist) : title);
            t.add(0, 0L);
        }

        if (s.size() > 0) {
            StringBuilder sb = new StringBuilder("歌词前3行: ");
            for (int i = 0; i < Math.min(3, s.size()); i++) {
                sb.append("[").append(s.get(i)).append("] ");
            }
            ClusterDebug.e("CLUSTERLAB", sb.toString());
        }

        // 整体替换、绝不原地改：绘制线程每帧都在读这两个 list，
        // 原来直接清空再逐条 add，读到"清空后、加回前"就是 IndexOutOfBounds。
        times = java.util.Collections.unmodifiableList(t);
        lines = java.util.Collections.unmodifiableList(s);
    }

    /**
     * LRC 头部元数据（作词/作曲/编曲/制作…）。
     * 它们通常带 [00:00.00] 时间戳，不过滤的话会被当成第一句歌词，
     * 表现为"前奏还没唱就高亮了当前歌词"。
     */
    /** 前 4 行里是否已经有曲名/歌手/词曲这类头部信息。 */
    private static boolean hasHeaderInfo(java.util.List<String> s) {
        int n = Math.min(4, s.size());
        for (int i = 0; i < n; i++) {
            String b = s.get(i).trim();
            if (b.length() == 0) continue;
            if (b.contains("作词") || b.contains("作曲") || b.contains("词：") || b.contains("曲：")
                    || b.contains("编曲") || b.contains("Lyrics") || b.contains("Composer")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMetaLine(String body) {
        String b = body.trim();
        if (b.length() == 0) return true;
        // 必须带冒号（「词：李焯雄」），纯歌词很少有
        int c1 = b.indexOf('：'), c2 = b.indexOf(':');
        int c = c1 >= 0 ? c1 : c2;
        if (c <= 0 || c > 6) return false;          // 冒号太靠后，不是标签
        String tag = b.substring(0, c).trim();
        String[] META = {"词", "曲", "编曲", "作词", "作曲", "制作", "监制", "出品",
                "混音", "录音", "和声", "吉他", "贝斯", "鼓", "弦乐", "母带",
                "op", "sp", "出品人", "统筹", "发行", "企划"};
        for (String m : META) {
            if (tag.equalsIgnoreCase(m)) return true;
        }
        return false;
    }


    public static int indexAt(long positionMs) {
        List<Long> t = times;
        int lo = 0, hi = t.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) / 2;
            if (t.get(mid) <= positionMs) { ans = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        return ans;
    }

    private static String http(String url, Request req) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        int timeout = requestTimeoutMs(req, 6000);
        c.setConnectTimeout(timeout);
        c.setReadTimeout(timeout);
        if (req != null) req.track(c);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        c.setRequestProperty("Referer", "https://music.163.com");
        c.setRequestProperty("Accept-Encoding", "gzip");
        if (c.getResponseCode() != 200) {
            req.ioFail.set(true);
            throw new IOException("HTTP " + c.getResponseCode());
        }
        return new String(readBody(c, MAX_BODY_BYTES), "UTF-8");
    }
}
