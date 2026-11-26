package com.byd.ls.cluster.data;

import com.byd.ls.cluster.cfg.ClusterDebug;

import android.app.Notification;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.List;

/**
 * 媒体源。
 * 主通道：MediaSessionManager.getActiveSessions —— 必须通知监听已绑定，否则 SecurityException。
 * 备通道：监听到的媒体通知（EXTRA_MEDIA_SESSION 存在）取标题/艺术家/大图标，
 *         在 session 拿不到数据时顶上，至少封面和歌名不会空着。
 * 另外每 2s 往 /sdcard/clusterlab_media.txt 写心跳，供「仪表助理」诊断。
 */
public class MediaListener extends NotificationListenerService {
    /** 心跳文件：主进程写，:guard 进程读，避免跨进程静态变量互不可见。 */
    public static final String HEARTBEAT = "/sdcard/clusterlab_media.txt";
    public static volatile String title = "";
    public static volatile String artist = "";
    public static volatile String album = "";
    public static volatile Bitmap art = null;
    /** 封面稳定键：同一首歌/同一封面保持同一个值，供静态层判断是否真的变化。 */
    public static volatile String artKey = "";
    public static volatile long position = 0;
    public static volatile long basePosition = 0;
    public static volatile long baseTime = 0;
    public static volatile float playSpeed = 1f;
    public static volatile long duration = 0;
    public static volatile boolean playing = false;
    public static volatile int revision = 0;

    /** 监听器是否真的绑定着（不是看 secure 设置里的条目，那条 force-stop 后不会消失）。 */
    public static volatile boolean connected = false;
    public static volatile int sessions = 0;
    public static volatile long lastMetaAt = 0L;
    public static volatile long lastLoopAt = 0L;
    public static volatile String lastErr = "";
    public static volatile boolean fromNotification = false;

    /** 1.5.3beta2：音乐卡片关闭时，监听器不刷新、不写心跳、不处理通知。 */
    private static volatile boolean featureOn = true;

    public static void setFeatureEnabled(boolean on) {
        if (!on) LyricLoader.cancel();
        if (featureOn == on) return;
        featureOn = on;
        if (on) {
            requestRebindNow();
        } else {
            connected = false;
            try {
                requestUnbind(new ComponentName(Card.PKG, MediaListener.class.getName()));
            } catch (Throwable ignored) {}
        }
        ClusterDebug.e("CLUSTERLAB-MEDIA", "音乐功能: " + (on ? "开" : "关"));
    }

    private static volatile String fbTitle = "";
    private static volatile String fbArtist = "";
    private static volatile String fbAlbum = "";
    private static volatile Bitmap fbArt = null;
    private static volatile long fbAt = 0L;

    private MediaSessionManager msm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private HandlerThread workerThread;
    private Handler worker;
    private long lastDumpAt = 0L;
    private String lastHeartbeatState = "";

    private final Runnable loop = new Runnable() {
        @Override public void run() {
            if (!featureOn) return;
            try { refresh(); } catch (Throwable t) { lastErr = "loop:" + t; }
            try { heartbeat(); } catch (Throwable ignored) {}
            if (!featureOn) return;
            Handler w = worker;
            if (w != null) w.postDelayed(this, nextPollDelay());
        }
    };

    private final Runnable refreshNow = new Runnable() {
        @Override public void run() {
            if (!featureOn) return;
            try { refresh(); } catch (Throwable t) { lastErr = "refreshNow:" + t; }
            try { heartbeat(); } catch (Throwable ignored) {}
        }
    };

    /** 播放中保持原 1.5s；暂停/有会话 5s；无会话 10s。 */
    private long nextPollDelay() {
        if (playing) return 1500L;
        if (sessions > 0) return 5000L;
        return 10000L;
    }

    private void startWorker() {
        if (workerThread != null && workerThread.isAlive()) return;
        workerThread = new HandlerThread("LsMediaPoll", Process.THREAD_PRIORITY_BACKGROUND);
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
    }

    private void stopWorker() {
        Handler w = worker;
        worker = null;
        if (w != null) w.removeCallbacksAndMessages(null);
        HandlerThread t = workerThread;
        workerThread = null;
        if (t != null) t.quitSafely();
    }

    private void requestRefreshNow() {
        Handler w = worker;
        if (w == null) return;
        w.removeCallbacks(refreshNow);
        w.post(refreshNow);
    }

    @Override public void onListenerConnected() {
        if (!featureOn) {
            connected = false;
            stopWorker();
            try {
                requestUnbind(new ComponentName(Card.PKG, MediaListener.class.getName()));
            } catch (Throwable ignored) {}
            return;
        }
        connected = true;
        lastErr = "";
        msm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        startWorker();
        Handler w = worker;
        if (w != null) {
            w.removeCallbacks(loop);
            w.post(loop);
        }
        // 休眠唤醒后系统往往不会立刻补发“当前媒体通知”，先读活动通知把正在播放的
        // 封面/标题补上，避免卡片一直显示“媒体未连接”，要等用户切歌才恢复。
        h.post(new Runnable() {
            @Override public void run() { refreshFromActiveNotifications(); }
        });
        ClusterDebug.e("CLUSTERLAB-MEDIA", "onListenerConnected");
    }

    @Override public void onListenerDisconnected() {
        connected = false;
        stopWorker();
        ClusterDebug.e("CLUSTERLAB-MEDIA", "onListenerDisconnected");
    }

    @Override public void onDestroy() {
        connected = false;
        stopWorker();
        super.onDestroy();
    }

    /**
     * 让系统重新绑定本监听。
     *
     * 车机实测：allow_listener 写进设置后系统不一定真的 bind（ServiceRecord 的 app 为 null），
     * 于是卡片永远"媒体未连接"。requestRebind 是官方入口，不经 adb，进程内直接调即可。
     */
    public static boolean requestRebindNow() {
        if (!featureOn) return false;
        try {
            android.content.ComponentName cn =
                    android.content.ComponentName.unflattenFromString(Card.NLS);
            if (cn == null) return false;
            requestRebind(cn);
            return true;
        } catch (Throwable t) {
            lastErr = "rebind:" + t;
            return false;
        }
    }

    /**
     * 刚重绑/唤醒时，系统不会对“已存在的媒体通知”再回调 onNotificationPosted。
     * 主动读当前活动通知，把封面、标题和歌手立即补上；拿不到 session 时这层兜底也能用。
     */
    private void refreshFromActiveNotifications() {
        if (!featureOn) return;
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active == null) return;
            int media = 0;
            for (StatusBarNotification sbn : active) {
                Notification n = sbn.getNotification();
                if (n == null || n.extras == null) continue;
                Bundle ex = n.extras;
                if (!ex.containsKey(Notification.EXTRA_MEDIA_SESSION)
                        && !Notification.CATEGORY_TRANSPORT.equals(n.category)) continue;
                onNotificationPosted(sbn);
                media++;
            }
            if (media > 0) {
                requestRefreshNow();
                ClusterDebug.e("CLUSTERLAB-MEDIA", "active media notifications=" + media);
            }
        } catch (Throwable t) {
            lastErr = "activeNotif:" + t.getClass().getSimpleName();
            ClusterDebug.e("CLUSTERLAB-MEDIA", "active notifications err " + t);
        }
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (!featureOn) return;
        try {
            Notification n = sbn.getNotification();
            if (n == null) return;
            Bundle ex = n.extras;
            if (ex == null) return;
            if (!ex.containsKey(Notification.EXTRA_MEDIA_SESSION)
                    && !Notification.CATEGORY_TRANSPORT.equals(n.category)) return;

            String t = str(ex.getCharSequence(Notification.EXTRA_TITLE));
            String s = str(ex.getCharSequence(Notification.EXTRA_TEXT));
            String sub = str(ex.getCharSequence(Notification.EXTRA_SUB_TEXT));
            Bitmap bmp = null;
            Object big = ex.getParcelable(Notification.EXTRA_LARGE_ICON_BIG);
            if (big instanceof Bitmap) bmp = (Bitmap) big;
            if (bmp == null) {
                Object lg = ex.getParcelable(Notification.EXTRA_LARGE_ICON);
                if (lg instanceof Bitmap) bmp = (Bitmap) lg;
            }
            if (t.length() == 0) return;
            boolean titleChanged = !t.equals(fbTitle)
                    || !(s.length() > 0 ? s : sub).equals(fbArtist);
            fbTitle = t;
            fbArtist = s.length() > 0 ? s : sub;
            fbAlbum = sub.length() > 0 && !sub.equals(s) ? sub : "";
            if (bmp != null) fbArt = bmp;
            fbAt = android.os.SystemClock.elapsedRealtime();
            if (titleChanged) requestRefreshNow();
            ClusterDebug.e("CLUSTERLAB-MEDIA", "notif fallback pkg=" + sbn.getPackageName()
                    + " t=" + t + " s=" + s + " art=" + (bmp != null));
        } catch (Throwable t) {
            ClusterDebug.e("CLUSTERLAB-MEDIA", "onNotificationPosted err " + t);
        }
    }

    private void refresh() {
        if (!featureOn) return;
        lastLoopAt = android.os.SystemClock.elapsedRealtime();
        if (msm == null) msm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        if (msm == null) { lastErr = "msm null"; return; }

        List<MediaController> list;
        try {
            list = msm.getActiveSessions(new ComponentName(this, MediaListener.class));
            lastErr = "";
        } catch (Throwable t) {
            // 最常见：监听器没绑定 / 权限被撤销
            lastErr = "getActiveSessions:" + t.getClass().getSimpleName();
            connected = false;
            useFallback();
            return;
        }
        sessions = list.size();

        MediaController best = null;
        for (MediaController c : list) {
            PlaybackState st = c.getPlaybackState();
            MediaMetadata md = c.getMetadata();
            if (st != null && st.getState() == PlaybackState.STATE_PLAYING && md != null
                    && md.getText(MediaMetadata.METADATA_KEY_TITLE) != null) {
                best = c;
                break;
            }
        }
        if (best == null) {
            for (MediaController c : list) {
                MediaMetadata md = c.getMetadata();
                if (md != null && md.getText(MediaMetadata.METADATA_KEY_TITLE) != null) { best = c; break; }
            }
        }
        if (best == null) { useFallback(); return; }

        MediaMetadata md = best.getMetadata();
        if (md == null) { useFallback(); return; }
        String t = str(md, MediaMetadata.METADATA_KEY_TITLE);
        String a = str(md, MediaMetadata.METADATA_KEY_ARTIST);
        if (a.length() == 0) a = str(md, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        String al = str(md, MediaMetadata.METADATA_KEY_ALBUM);
        String mid = str(md, MediaMetadata.METADATA_KEY_MEDIA_ID);
        long dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (t.length() == 0) { useFallback(); return; }

        // 同一首歌/同一封面只取一次 Bitmap；稳定键也供 CardActivity 的静态层判断。
        // 封面暂缺时允许继续重试，避免播放器晚到图后一直空白。
        String newArtKey = mid + "|" + t + "|" + a + "|" + al;
        boolean keyChanged = !newArtKey.equals(artKey);
        boolean artChanged = false;
        Bitmap bmp = null;
        if (keyChanged || art == null) {
            bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            if (keyChanged || bmp != null) {
                art = bmp;
                artChanged = true;
            }
            if (keyChanged) artKey = newArtKey;
        }

        PlaybackState st = best.getPlaybackState();
        boolean pl = st != null && st.getState() == PlaybackState.STATE_PLAYING;
        if (st != null) {
            // 车机播放器经常上报 0（根本没实现）或 2.0（实现错），直接拿去外推位置
            // 会让歌词整段冻结或双倍速跑飞，听感就是"越听越不同步"。夹到合理区间。
            float sp = st.getPlaybackSpeed();
            if (!(sp > 0.5f) || sp > 2.0f) sp = 1f;
            playSpeed = sp;
            long nowRt = android.os.SystemClock.elapsedRealtime();
            long raw = st.getPosition();
            // 先把上报值补齐到"当前时刻"，再当作基准；否则每次上报都会落后一个上报周期
            long adjusted = raw;
            if (pl && playSpeed > 0f) {
                long d = nowRt - st.getLastPositionUpdateTime();
                if (d > 0) adjusted = raw + (long) (d * playSpeed);
            }
            long predicted = basePosition + (long) ((nowRt - baseTime) * playSpeed);
            if (baseTime == 0 || Math.abs(adjusted - predicted) > 500) {
                basePosition = adjusted;
                baseTime = nowRt;
            }
            position = adjusted;
        }

        lastMetaAt = android.os.SystemClock.elapsedRealtime();
        if (!t.equals(title) || !a.equals(artist)) LyricLoader.reset();
        boolean changed = !t.equals(title) || !a.equals(artist) || !al.equals(album)
                || artChanged || pl != playing || fromNotification;
        fromNotification = false;
        title = t; artist = a; album = al; playing = pl;
        duration = dur;
        if (changed) revision++;
    }

    /**
     * 实时播放位置。
     *
     * poll() 只在收到媒体事件时才更新 position（两次上报之间 position 是常量），
     * 画歌词的每帧直接读它就只能看到阶梯，再自己外推又和基准打架，表现为歌词一跳一跳。
     * 这里统一用 poll() 已经维护好的 basePosition/baseTime 现算：连续、平滑，
     * 且漂移仍由 poll() 里 500ms 死区的对账兜住。暂停时不外推。
     */
    public static long positionNow() {
        if (!playing) return position;
        if (baseTime <= 0L) return position;
        long d = android.os.SystemClock.elapsedRealtime() - baseTime;
        if (d <= 0L) return basePosition;
        float sp = playSpeed;
        if (!(sp > 0.5f) || sp > 2.0f) sp = 1f;
        return basePosition + (long) (d * sp);
    }

    /** session 没数据时用通知里的信息顶上（10 秒内有效）。 */
    private void useFallback() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (fbTitle.length() == 0 || now - fbAt > 10000) {
            if (title.length() > 0 && now - lastMetaAt > 15000) {
                // 长时间没有任何数据：清空，避免显示上一首歌
                title = ""; artist = ""; album = ""; art = null; artKey = ""; playing = false;
                fromNotification = false;
            }
            return;
        }
        String fbKey = "fb|" + fbTitle + "|" + fbArtist + "|" + fbAlbum;
        boolean keyChanged = !fbKey.equals(artKey);
        boolean artChanged = false;
        if (keyChanged || art == null) {
            if (keyChanged || fbArt != null) {
                art = fbArt;
                artChanged = true;
            }
            if (keyChanged) artKey = fbKey;
        }
        boolean changed = !fbTitle.equals(title) || !fbArtist.equals(artist)
                || artChanged || !fromNotification;
        if (!fbTitle.equals(title) || !fbArtist.equals(artist)) LyricLoader.reset();
        title = fbTitle;
        artist = fbArtist;
        album = fbAlbum;
        playing = true;
        fromNotification = true;
        lastMetaAt = now;
        if (changed) revision++;
    }

    /** 状态变化立即写，状态不变最长 5 秒写一次；供「仪表助理」诊断。 */
    private void heartbeat() {
        if (!featureOn) return;
        long now = android.os.SystemClock.elapsedRealtime();
        String state = "connected=" + (connected ? 1 : 0)
                + "|sessions=" + sessions
                + "|title=" + title
                + "|artist=" + artist
                + "|playing=" + (playing ? 1 : 0)
                + "|art=" + (art == null ? 0 : 1)
                + "|lyricLines=" + LyricLoader.lines.size()
                + "|fallback=" + (fromNotification ? 1 : 0)
                + "|err=" + lastErr;
        if (state.equals(lastHeartbeatState) && now - lastDumpAt < 5000) return;
        lastDumpAt = now;
        lastHeartbeatState = state;
        String dump = "ts=" + System.currentTimeMillis() + "\n"
                + "connected=" + (connected ? 1 : 0) + "\n"
                + "sessions=" + sessions + "\n"
                + "title=" + title + "\n"
                + "artist=" + artist + "\n"
                + "playing=" + (playing ? 1 : 0) + "\n"
                + "art=" + (art == null ? 0 : 1) + "\n"
                + "posMs=" + position + "\n"
                + "lyricLines=" + LyricLoader.lines.size() + "\n"
                + "fallback=" + (fromNotification ? 1 : 0) + "\n"
                + "ageMs=" + (lastMetaAt == 0 ? -1 : (now - lastMetaAt)) + "\n"
                + "err=" + lastErr + "\n";
        try {
            java.io.FileWriter w = new java.io.FileWriter(HEARTBEAT);
            w.write(dump);
            w.close();
        } catch (Throwable t) {
            // 没有存储权限就跳过，不影响主流程
            if (now - lastDumpAt > 60000) ClusterDebug.e("CLUSTERLAB-MEDIA", "heartbeat fail " + t);
        }
    }

    private String str(MediaMetadata md, String key) {
        CharSequence cs = md.getText(key);
        return cs == null ? "" : cs.toString();
    }

    private String str(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }
}
