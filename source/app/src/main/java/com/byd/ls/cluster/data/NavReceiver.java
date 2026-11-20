package com.byd.ls.cluster.data;

import com.byd.ls.cluster.cfg.ClusterDebug;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

/**
 * 高德车机版的公开开放协议：导航状态变化会发 AUTONAVI_STANDARD_BROADCAST_SEND。
 * 关键字段 EXTRA_STATE：0=无导航，非 0=导航中（不同版本取值不同，按非 0 处理）。
 * 这条不依赖无障碍、不依赖副屏，是唯一能拿到"真·导航状态"的通道。
 */
public final class NavReceiver {
    private static final String ACTION_SEND = "AUTONAVI_STANDARD_BROADCAST_SEND";
    /** 1.4.114：车机/导航 App 的显式"进/出导航"动作 */
    public static final String ACTION_NAVI_ENTER = "android.intent.action.NAVIENTER";
    public static final String ACTION_NAVI_EXIT = "android.intent.action.NAVIEXIT";
    /** 1.4.121：比亚迪导航服务的导航状态广播（实车 logcat 实证，带 mIsBYDMapNaving 标志）。 */
    public static final String ACTION_BYD_NAVI = "NAVI_STANDARD_BROADCAST_SEND";
    private static boolean started = false;

    private static void NavLog2(String msg) {
        ClusterDebug.e("CLUSTERLAB-NAV", msg);
    }

    private NavReceiver() {}

    /**
     * 1.4.121：从广播 extras 里取比亚迪导航标志。1=导航中，0=未导航，-1=这个包没有该字段。
     * 实车 AmapService 打印的 extras 是 mIsBydMap / mIsBYDMapNaving / mIsGAODENaving：
     * 前两个都表达「本车地图导航中」，第三个是「高德 App 自己在导航」，取最大值即可。
     */
    static int bydNavFlag(Bundle b) {
        if (b == null) return -1;
        int v = -1;
        for (String k : new String[]{"mIsBYDMapNaving", "mIsBydMapNaving", "mIsGAODENaving"}) {
            if (!b.containsKey(k)) continue;
            Object o = b.get(k);
            int one = -1;
            if (o instanceof Boolean) one = ((Boolean) o) ? 1 : 0;
            else if (o instanceof Number) one = ((Number) o).intValue() != 0 ? 1 : 0;
            else if (o != null) one = ("true".equalsIgnoreCase(o.toString())
                    || "1".equals(o.toString())) ? 1 : 0;
            if (one < 0) continue;
            v = (v < 0) ? one : Math.max(v, one);
        }
        return v;
    }

    public static void start(Context c) {
        if (started) return;
        started = true;
        try {
            // 高德是隐式广播（可能带包名/不带），签名级权限也常见 → 用 export 方式放宽
            IntentFilter f = new IntentFilter(ACTION_SEND);
            f.addAction("AUTONAVI_STANDARD_BROADCAST_RECEIVE");
            f.addAction("com.autonavi.amapauto.BROADCAST");
            // 1.4.114：车机上确实存在这两个**显式**导航动作（dumpsys activity broadcasts 实证），
            // 它们是"进/出导航"最可靠、零歧义的信号 —— 不再靠像素猜。
            f.addAction(ACTION_NAVI_ENTER);
            f.addAction(ACTION_NAVI_EXIT);
            // 1.4.121：比亚迪自家导航服务（com.byd.automap）的那条 —— 实车 logcat 里每次进/出导航
            // 都会打 AUTONAVI_STANDARD_BROADCAST_SEND,mIsBydMap=true,mIsBYDMapNaving=true,
            // mIsGAODENaving=false，导航标志就在 extras 里，比像素猜想可靠得多。
            f.addAction(ACTION_BYD_NAVI);
            c.registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent it) {
                    try {
                        // 1.4.114：显式进/出导航 —— 直接生效，不进像素状态机。
                        String act = it == null ? null : it.getAction();
                        if (ACTION_NAVI_ENTER.equals(act)) {
                            NavState.noteBroadcast();
                            NavState.force(true, "navi-enter");
                            NavLog2("NAVIENTER 收到 → 小窗");
                            return;
                        }
                        if (ACTION_NAVI_EXIT.equals(act)) {
                            NavState.noteBroadcast();
                            // 2 秒抑制：退出瞬间导航 App 常连发几条，避免抖动
                            NavState.force(false, "navi-exit");
                            NavLog2("NAVIEXIT 收到 → 大窗");
                            return;
                        }
                        Bundle b = it.getExtras();
                        StringBuilder sb = new StringBuilder("高德广播: ");
                        if (b != null) {
                            for (String k : b.keySet()) {
                                sb.append(k).append('=').append(b.get(k)).append("; ");
                            }
                        }
                        ClusterDebug.e("CLUSTERLAB-NAV", sb.toString());

                        // 高德一有动静就提速探测，并让空闲 helper 立即抓一帧；别等 2 秒轮询/60 秒兜底
                        NavState.eventBoost(10000);

                        // 1.4.121：比亚迪导航服务的导航标志 —— 进和出都能直接判定，
                        // 这是唯一能把「结束」立刻翻回大窗的通道（像素要 3 帧 ≈ 7 秒）。
                        int byd = bydNavFlag(b);
                        if (byd >= 0) {
                            NavState.noteBroadcast();
                            NavState.force(byd == 1, byd == 1 ? "byd-native" : "byd-native-exit");
                            NavLog2("BYD 导航标志=" + byd + " → " + (byd == 1 ? "小窗" : "大窗"));
                            return;
                        }

                        if (b != null && b.containsKey("EXTRA_STATE")) {
                            int st = b.getInt("EXTRA_STATE", -1);
                            // 只信「开始」。高德在导航过程中也会发 EXTRA_STATE=0（路况/电子眼等），
                            // 拿它当「导航结束」会让大小窗反复横跳 —— 退出交给像素探测。
                            NavState.noteBroadcast();
                            // 1.4.123：高德/车机用自己的状态码。实车 AmapService 打印
                            // 「EXTRA_STATE=9 → 停止导航 ,status=9」，9 就是退出。
                            // 1..8 是导航中（1 开始、其余是途中刷新），0 是路况/电子眼之类的噪声 —— 不动状态。
                            if (st == 9) NavState.force(false, "broadcast-exit");
                            else if (st >= 1 && st <= 8) NavState.force(true, "broadcast");
                        }
                    } catch (Throwable t) {
                        ClusterDebug.e("CLUSTERLAB-NAV", "广播解析失败: " + t);
                    }
                }
            }, f, Context.RECEIVER_EXPORTED);
            ClusterDebug.e("CLUSTERLAB-NAV", "已注册高德广播监听");
        } catch (Throwable t) {
            ClusterDebug.e("CLUSTERLAB-NAV", "注册失败: " + t);
        }
    }
}
