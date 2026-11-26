package com.byd.ls.cluster.data;

import com.byd.ls.cluster.cfg.ClusterDebug;
import com.byd.ls.cluster.FeatureGate;


import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;

/**
 * 车辆数据。
 *
 * 油耗口径：不再用「本次行程」，改成**累计寄存器 + 50km 滚动窗口**。
 * 原因：tripStatistics 的 fuelDelta 只有 0.1L 刻度，分母 1.5km 时误差就有 ±6.7 L/100km；
 * 而累计寄存器（总电耗/总燃油消耗/里程值，都是 0.1 刻度）用 50km 窗口时误差只有 ±0.2。
 *
 * 采样：每 60s 往 /sdcard/clusterlab_fuel.csv 追加一行 km,kwh,L，只保留最近 120km。
 * 窗口不足 5km（刚上车 / 刚装车）时退回全生命周期均值，并置 estimated=true 让界面加 ≈。
 */
public class VehicleData {
    public static volatile int[] pressure = new int[]{-1, -1, -1, -1};
    public static volatile double elecPHM = 0;     // kWh/100km
    public static volatile double fuelPHM = 0;     // L/100km
    public static volatile double totalPHM = 0;    // L/100km（综合）
    public static volatile boolean ok = false;
    public static volatile boolean estimated = true;
    public static volatile double winKm = 0;       // 实际参与计算的里程跨度
    public static volatile String lastError = "";

    /** 从迪友桌面卡片抓到的「最近能耗」（每百公里），2 分钟内有效。 */
    public static volatile double dyElec = -1;
    public static volatile long dyAt = 0L;
    /** 迪友数据超过 2 分钟没更新（只用于日志提示，不再回落）。 */

    /** 迪友值的持久化文件：迪友崩溃/重启期间用它顶住，避免开机先显示错的迪加口径。 */
    /** 胎压上次读取时刻（单独节流）。 */
    private static long lastPressureAt = 0L;
    private static boolean dyLoaded = false;

    /** 遥测库最近行驶窗口（100km 内），迪友卡片失效后的替代口径。 */
    public static volatile double winElec = 0;
    public static volatile double winFuel = 0;

    // ---- 显示迟滞（1.2.34）：数字要稳，不能每次采样抖 0.1 ----
    // 规则：与当前显示值相差 <0.1 一律不动；>=0.1 必须连续两次采样都同向才改。
    private static double shownFuel = -1, shownElec = -1;

    // 1.4.119：删掉 displayOffsetPHM（默认 +0.1 的「油耗偏置」）。
    // 原意是自算口径比仪表低 0.1–0.2 时补一点，但从 1.4.55 起综合油耗就只用车机直读值
    // （1246785586 / fid fuelConsumption），偏置早已不参与任何计算，只是一个远程可改的
    // 死开关 —— 留着反而会让人以为「显示是加了 0.1 的」。见 docs/13 §11。

    /**
     * 数据来源（1.4.16）："车机" = 直读 BYDAutoStatisticDevice.getLastFuelConPHMValue()；
     * "自算" = 本类用总燃油/总电耗寄存器折算。车机口径下**不再叠加**电耗折算与显示偏置，
     * 否则就是"拿车机的值再加一遍偏置"，必然比仪表高。
     */
    public static volatile String statSource = "自算";
    private static volatile double directTotal = -1;
    private static int fuelHold = 0, elecHold = 0;

    /** 统一出口：任何口径算出来的油耗/电耗都从这里落到显示值上。 */
    private static synchronized void setDisplay(double f, double e) {
        if (shownFuel < 0) shownFuel = f;
        else if (Math.abs(f - shownFuel) >= 0.1) { if (++fuelHold >= 2) { shownFuel = f; fuelHold = 0; } }
        else fuelHold = 0;
        if (shownElec < 0) shownElec = e;
        else if (Math.abs(e - shownElec) >= 0.1) { if (++elecHold >= 2) { shownElec = e; elecHold = 0; } }
        else elecHold = 0;
        fuelPHM = shownFuel;
        elecPHM = shownElec;
        // 1.4.55：综合油耗只用车机值（1246785586）；读不到时保留上次，绝不自算
        if (!Double.isNaN(nFuelPHM)) totalPHM = nFuelPHM;
    }
    public static volatile long winAt = 0L;

    private static final String HIST = "/sdcard/clusterlab_fuel.csv";
    /** 迪加写的遥测库（全球可读），用来给窗口做"历史预热"，装好就有 50km 数据。 */
    /**
     * 窗口长度（km）。1.4.15 起可配，0 = 用「生命周期累计」口径。
     *
     * 现场反馈「比仪表低很多」：仪表那个数通常是**总累计 / 小计**口径，
     * 而我们固定 50km 窗口，在近期高速/电驱占比高时会明显偏低。
     * 远程用 `cfg.fuelWindow value=<0|20|50|100|200>` 调，0 为累计口径。
     */
    public static volatile double windowKm = 50.0;
    private static final double DEFAULT_WINDOW_KM = 50.0;
    /** 只要有 0.3km 的跨度就开始出数（原来 5km 的门槛会让人以为没在算）。 */
    private static final double MIN_KM = 0.3;
    /**
     * 先验权重（等效公里数）：窗口值按跨度加权，和生命周期均值做收缩。
     * 0.1L 的刻度在 0.3km 上等于 33L/100km，不收缩就没法看；
     * 跨度越大越接近窗口真实值（50km 时窗口占 91%）。
     */
    private static final double PRIOR_KM = 5.0;
    private static final long STATS_MS = 3000L;    // 计数器轮询
    private static final long SAMPLE_MS = 60000L;   // 锚点落盘
    private static final double KWH_PER_L = 3.5;

    /** 每行 [里程 km, 总电耗 kWh, 总燃油 L]，按里程升序。 */
    private static final java.util.ArrayList<double[]> SAMPLES = new java.util.ArrayList<double[]>();
    private static boolean loaded = false;
    private static long lastSampleAt = 0L;
    private static boolean computed = false;
    private static long lastFuelDbAt = 0L;
    private static boolean fuelDbOk = false;
    private static double fuelDbValue = -1;
    private static double elecDbValue = 0;
    private static double curKm = 0, curKwh = 0, curL = 0;
    private static long lastTry = 0L;
    private static volatile boolean busy = false;
    private static volatile long lastApiLogAt = 0L;
    private static volatile long lastFidLogAt = 0L;
    private static double lifeE = 0, lifeF = 0;
    private static long lastTripAt = 0;
    private static boolean tripOk = false;
    private static long bydLogAt = 0L;

    private static volatile long busySince = 0L;

    public static void refresh(final android.content.Context ctx) {
        // 1.5.3beta2：油耗/胎压全关时，连 800ms 的取数节拍都不再进入。
        if (!FeatureGate.card() || !FeatureGate.vehicle()) return;
        long now = android.os.SystemClock.elapsedRealtime();
        // 看门狗：真机上遇到过刷新线程卡住（原生读取或文件打开被占），
        // busy 卡在 true 就再也不刷新，界面整片空白。卡超过 15 秒就强制解锁。
        if (busy && now - busySince > 15000L) {
            ClusterDebug.e("CLUSTERLAB", "刷新卡死 " + (now - busySince) + "ms，强制解锁");
            busy = false;
        }
        // 600ms：和迪友抓取周期对齐，减少无谓的 Shizuku/HTTP 往返
        if (busy || now - lastTry < 800) return;
        lastTry = now;
        busy = true;
        busySince = now;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (FeatureGate.tyre()) {
                        // 胎压：迪加优先；5s 无完整返回才允许原生 FID/映射表；迪加锁死后不再读映射。
                        long nowP = android.os.SystemClock.elapsedRealtime();
                        TyreSource.maybeFallback();
                        if (nowP - lastPressureAt >= 2000) {
                            lastPressureAt = nowP;
                            TyreSource.poll();
                        }
                        applyTyreSource();
                    }

                    // 1.4.118 / 1.4.164：整表 fid 后台轮询（1 s 缓存）；ADAS 两条 fid 另有 300 ms 快通道。
                    try {
                        // 界面每 800 ms 轮一次；整表快照 1 s 换新，ADAS 状态由 refreshAdas 快通道补上。
                        com.byd.ls.shell.FidDirect.Snapshot fs = com.byd.ls.shell.FidDirect.refresh(ctx, false);
                        if (fs != null && fs.at != appliedFidAt) {
                            appliedFidAt = fs.at;
                            applyFidSnapshot(fs);
                        }
                        refreshAdas();
                    } catch (Throwable t) {
                        ClusterDebug.e("CLUSTERLAB", "fid FAIL " + t);
                    }

                    if (FeatureGate.fuel()) pollStats(ctx);
                    // 电耗也走累计口径，不再混用迪加的行程值
                    ok = true;
                    long nowApi = System.currentTimeMillis();
                    if (nowApi - lastApiLogAt >= 30_000L) {
                        lastApiLogAt = nowApi;
                        ClusterDebug.e("CLUSTERLAB", "api p=" + pressure[0] + "/" + pressure[1]
                                + "/" + pressure[2] + "/" + pressure[3]
                                + " e=" + String.format(java.util.Locale.US, "%.1f", elecPHM)
                                + " f=" + String.format(java.util.Locale.US, "%.1f", fuelPHM)
                                + " t=" + String.format(java.util.Locale.US, "%.1f", totalPHM)
                                + (estimated ? " (life)" : (" (win " + String.format(java.util.Locale.US, "%.1f", winKm) + "km)")));
                    }
                } catch (Throwable t) {
                    lastError = String.valueOf(t);
                    ClusterDebug.e("CLUSTERLAB", "api FAIL " + t);
                } finally {
                    busy = false;
                }
            }
        }).start();
    }

    /**
     * 把一次 fid 快照落到各个量上（1.4.118）。
     *
     * 只覆盖「本车确实回了有效值」的项，其余保持原值或 -1／NaN —— 无效 fid 一律不写，
     * 避免把 -1 当成真实读数（用错值比不显示更糟，见 refresh 里的胎压注释）。
     */
    private static volatile long appliedFidAt = -1L;

    /**
     * 1.4.164：把 ADAS 专用快通道（300 ms 两条 fid）并入当前状态。
     * 只覆盖有效值；云辇读不到时保留上次有效值，由绘制层决定是否隐藏。
     */
    public static void refreshAdas() {
        if (!FeatureGate.card() || !FeatureGate.tyre()) return;
        double[] a = com.byd.ls.shell.FidDirect.adasValues();
        if (a == null) return;
        boolean changed = false;
        if (a.length > 0 && !Double.isNaN(a[0]) && a[0] >= 0 && a[0] <= 3 && nSocSave != a[0]) {
            nSocSave = a[0];
            changed = true;
        }
        if (a.length > 1 && !Double.isNaN(a[1]) && a[1] >= 1 && a[1] <= 2 && nSuspension != a[1]) {
            nSuspension = a[1];
            changed = true;
        }
        Runnable r = adasInvalidator;
        if (changed && r != null) r.run();
    }

    /** 1.4.164：ADAS 快通道值变化时通知卡片重绘，别等 700 ms 的 tick。 */
    private static volatile Runnable adasInvalidator;

    public static void setAdasInvalidator(Runnable r) {
        adasInvalidator = r;
    }

    private static void applyFidSnapshot(com.byd.ls.shell.FidDirect.Snapshot s) {
        fidAt = s.at;
        fidCount = s.values.size();
        long nowFid = System.currentTimeMillis();
        if (nowFid - lastFidLogAt >= 30_000L) {
            lastFidLogAt = nowFid;
            ClusterDebug.e("CLUSTERLAB", "fid n=" + s.values.size() + "/" + s.raw.size()
                    + " soc=" + s.value("soc") + " rng=" + s.value("evRangeKm") + "/" + s.value("fuelRangeKm")
                    + " cell=" + s.value("maxCellVoltage") + "/" + s.value("minCellVoltage")
                    + " hv=" + s.value("hvVoltage") + " ins=" + s.value("insulationKohm")
                    + " in/out=" + s.value("insideTemp") + "/" + s.value("exteriorTemp")
                    + " gun=" + s.value("chargeGunState")
                    + " socSave=" + s.value("socSaveMode") + " susp=" + s.value("suspensionMode"));
        }
        double v;
        // 胎压四轮只能有一个源：迪加锁定后，FID 映射表这条线一律不写。
        if (FeatureGate.tyre() && TyreSource.mappingAllowed()) {
            v = s.value("tirePressFL"); if (!Double.isNaN(v) && v > 0) { nTyreLF = v; if (isPlausiblePressure(v)) pressure[0] = (int) v; }
            v = s.value("tirePressFR"); if (!Double.isNaN(v) && v > 0) { nTyreRF = v; if (isPlausiblePressure(v)) pressure[1] = (int) v; }
            v = s.value("tirePressRL"); if (!Double.isNaN(v) && v > 0) { nTyreLB = v; if (isPlausiblePressure(v)) pressure[2] = (int) v; }
            v = s.value("tirePressRR"); if (!Double.isNaN(v) && v > 0) { nTyreRB = v; if (isPlausiblePressure(v)) pressure[3] = (int) v; }
        }
        // 1.4.119：综合油耗直读（50km 等效，仪表口径）。没有偏置、没有电耗折算 —— 车机给什么就是什么。
        v = s.value("fuelConsumption");
        if (!Double.isNaN(v) && v > 0.05 && v < 60) { nFuelPHM = v; totalPHM = v; }
        v = s.value("soc");               if (!Double.isNaN(v)) nSoc = v;
        // 1.4.156：保电开关/云辇模式只接受枚举范围内的值，-1/255 这类哨兵不覆盖已有有效值。
        v = s.value("socSaveMode");       if (!Double.isNaN(v) && v >= 0 && v <= 3) nSocSave = v;
        v = s.value("suspensionMode");    if (!Double.isNaN(v) && v >= 1 && v <= 2) nSuspension = v;
        v = s.value("evRangeKm");         if (!Double.isNaN(v)) nRange = v;
        v = s.value("insideTemp");        if (!Double.isNaN(v)) nInsideTemp = v;
        v = s.value("exteriorTemp");      if (!Double.isNaN(v)) nExteriorTemp = v;
        v = s.value("maxCellVoltage");    if (!Double.isNaN(v)) nCellMaxV = v;
        v = s.value("minCellVoltage");    if (!Double.isNaN(v)) nCellMinV = v;
        v = s.value("maxBatTemp");        if (!Double.isNaN(v)) nCellTempMax = v;
        v = s.value("minBatTemp");        if (!Double.isNaN(v)) nCellTempMin = v;
        v = s.value("hvVoltage");         if (!Double.isNaN(v)) nHvVolt = v;
        // 绝缘：BMS 在高压未就绪时报 65534（0xFFFE 哨兵），实车正常值 18xxx kΩ。
        v = s.value("insulationKohm");    if (!Double.isNaN(v) && v > 0 && v < 60000) nInsulation = v;
        v = s.value("chargeGunState");    if (!Double.isNaN(v)) nChargeGun = (int) v;
    }

    /** 累计寄存器：3s 轮询一次，界面每 1.5s 就能拿到最新值。 */
    /**
     * 迪友的算法：本次行程累计 ÷ 本次里程。
     * 数据来自迪加 /api/tripStatistics 的开放行程（closed=false）。
     */

    /**
     * 1.4.16：直读车机口径（启迪桌面同路径）。
     *
     * `getLastFuelConPHMValue()` 就是仪表上那个"最近段综合油耗"，已经含油+电的折算，
     * 所以只用滞回平滑，绝不再叠加 elecPHM/KWH_PER_L（1.4.119 起连「显示偏置」也删了，
     * 见 docs/13 §11：那个 +0.1 是早期自算口径的补丁，直读后就是错的值）。
     * 读不到（helper 未就绪/类不可加载）返回 false，调用方回退自算。
     */
    // ==== 原生缓存（1.4.18：全部车辆量来自 BYD framework，不再有第三方 App）====
    private static volatile long nativeAt = 0L;
    private static volatile double nFuelPHM = Double.NaN;   // 1246785586 综合油耗（50km 等效，仪表口径）
    private static volatile double nFuelLast = Double.NaN;  // 1032863782 最近段油耗（纯油，本车不下发）
    private static volatile double nFuelInst = Double.NaN;  // 1246760996 瞬时油耗（HEV 模式才推）
    public static volatile double nTyreTempLF = -1, nTyreTempRF = -1, nTyreTempLB = -1, nTyreTempRB = -1;
    private static volatile double nElecPHM = Double.NaN;   // getLastElecConPHMValue
    public static volatile double nFuelTotal = -1;          // 1246760976 累计燃油（L）
    public static volatile double nElecTotal = -1;          // 1032871984 累计电耗（kWh）
    private static volatile double nMileage = -1;           // getTotalMileageValue
    private static volatile boolean nativeOk = false;
    private static volatile boolean seeded = false;
    /** 1.4.72：上次值快照（迪友同款：进程常驻握着快照；我们重启后先从文件恢复，收到实时值即覆盖） */
    // 1.4.46+：监听推送得到的量（刻度见 refreshFromEvents 注释）
    public static volatile double nSpeed = Double.NaN;
    public static volatile double nTyreLF = -1, nTyreRF = -1, nTyreLB = -1, nTyreRB = -1;
    public static volatile double nLowVolt = -1, nHighVolt = -1, nCurrent = -1, nRemainPower = -1, nSoc = -1;
    /** 1.4.156：SOC 保电开关（1009:1186988086）与云辇 C 模式（1023:938475528）。-1 = 未知。 */
    public static volatile double nSocSave = -1, nSuspension = -1;

    /** 保电开关实读 2=强制、1=智能；语义未确认时宁可不显示，避免把哨兵当状态。 */
    public static String socSaveLabel() {
        if (nSocSave == 2) return "强制保电";
        if (nSocSave == 1) return "智能保电";
        return "";
    }

    /** 云辇 C：1=舒适、2=运动（1.4.135 实车定标）。 */
    public static String suspensionLabel() {
        if (nSuspension == 1) return "云辇舒适";
        if (nSuspension == 2) return "云辇运动";
        return "";
    }

    // ==== 1.4.118：fid 直读快照（FidDirect，一次 app_process 读 129 条）====
    /** 最近一次 fid 快照的读取时刻；0 表示从未成功。 */
    public static volatile long fidAt = 0L;
    /** 上一次成功快照里可用的参数条数（诊断用）。 */
    public static volatile int fidCount = 0;
    /** 续航（km）；-1 无值。 */
    public static volatile double nRange = -1;
    /** 车内/车外温（°C）。 */
    public static volatile double nInsideTemp = -1, nExteriorTemp = -1;
    /** 电芯最高/最低电压（V）、最高/最低温度（°C）。 */
    public static volatile double nCellMaxV = -1, nCellMinV = -1, nCellTempMax = -1, nCellTempMin = -1;
    /** 高压母线电压（V）、绝缘电阻（kΩ）。 */
    public static volatile double nHvVolt = -1, nInsulation = -1;
    /** 充电枪连接状态（枚举，见 FidCatalog 的 chargeGunState）。 */
    public static volatile int nChargeGun = -1;
    private static final long NATIVE_MS = 5_000L;

    /**
     * 一次 app_process 批量读全部原生量（`--batch statistic …`），5 秒缓存。
     * 失败只记状态，不抛异常；上层按 NaN/-1 处理。
     */
    // ==== 1.4.32：直读车机（binder txn9），event 从 FeatureIds 运行时常量里自动匹配 ====
    private static volatile boolean evLoaded = false;
    private static volatile int evFuel = -1;
    private static volatile int evMileage = -1;
    private static final int DEV_STATISTIC = 1014;   // BYDAutoStatisticDevice.mDeviceType（实测）

    private static int pickEvent(String dump, String key, int dflt) {
        if (dump == null || dump.isEmpty()) return dflt;
        for (String kv : dump.split(";")) {
            int i = kv.indexOf('=');
            if (i <= 0) continue;
            if (kv.substring(0, i).contains(key)) {
                try { return (int) Double.parseDouble(kv.substring(i + 1).trim()); } catch (Throwable ignored) {}
            }
        }
        return dflt;
    }

    private static void loadEvents(android.content.Context ctx) {
        if (evLoaded && com.byd.ls.shell.BydDirect.isListenerRegistered()) return;
        evLoaded = true;
        // 与迪友同架构：在 App 进程内注册监听（推送路径无权限检查）
        try { com.byd.ls.shell.BydDirect.registerStatisticListener(ctx); } catch (Throwable ignored) {}
        try {
            String dump = com.byd.ls.shell.VehicleBridge.dumpStatics(
                ctx, "android.hardware.bydauto.BYDAutoFeatureIds");
            evFuel = pickEvent(dump, "STATISTIC_LAST_FUEL_CON_PHM", evFuel);
            evMileage = pickEvent(dump, "STATISTIC_MILEAGE", evMileage);
            ClusterDebug.e("CLUSTERLAB-NATIVE",
                "events fuel=" + evFuel + " mileage=" + evMileage + " (" + (dump == null ? "null" : dump.length() + "B") + ")");
        } catch (Throwable t) {
            ClusterDebug.e("CLUSTERLAB-NATIVE", "loadEvents 失败: " + t);
        }
    }

    /**
     * 直读车机综合油耗（binder txn9 getIntArray）。
     * 车机自己的值本身就是准的 → **不做里程增量门控**，直接显示。
     */
    private static boolean pollNativeFuel(android.content.Context ctx) {
        // 1.4.132（第二档第 10 条）：app 域 getter 优先。
        // 实测 shell 域（uid 2000）调同一接口被 SecurityException 拒，app 域 4.5000 与仪表一字不差，
        // 且这条完全不用 helper —— 引擎/通道怎么抖都不影响油耗。
        try {
            // 1.4.151：本次油耗/本次电耗的 fid（1246760976 / 1032871984）在本固件恒 -1，
            // 事件也几乎不推；改走 app 域方法直读「最近段」值，与仪表「最近50公里平均能耗」同源。
            java.util.Map<String, String> m = com.byd.ls.shell.BydDirect.read(
                    ctx, "statistic",
                    "getLast50KmEqualFuelConValue,getLastFuelConPHMValue,getLastElecConPHMValue");
            if (m != null) {
                double lf = parseRate(m.get("getLastFuelConPHMValue"), 60);
                if (!Double.isNaN(lf)) nFuelLast = lf;
                double le = parseRate(m.get("getLastElecConPHMValue"), 100);
                if (!Double.isNaN(le)) nElecPHM = le;
            }
            String raw = m == null ? null : m.get("getLast50KmEqualFuelConValue");
            if (raw != null && !raw.startsWith("ERR")) {
                double av = Double.parseDouble(raw);
                if (av > 0.05 && av < 60) {
                    directTotal = av;
                    statSource = "车机";
                    applyDirect(av);
                    estimated = false;
                    computed = true;
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        loadEvents(ctx);
        if (evFuel <= 0) return false;
        String r = com.byd.ls.shell.VehicleBridge.binderRead(ctx, DEV_STATISTIC, String.valueOf(evFuel));
        Double parsed = parseBinderValue(r);
        if (parsed == null) return false;
        double v = parsed;
        if (v <= 0 || v > 10000) return false;
        // 刻度：车机多为 0.1 L/100km；若值明显是整数倍（>60）按 0.1 折算
        if (v > 60) v = v / 10.0;
        directTotal = v;
        statSource = "车机";
        applyDirect(v);
        estimated = false;
        computed = true;
        return true;
    }

    /** 1.4.151：最近段油耗/电耗转正数；0 是有效值（纯电行驶时油耗就是 0），负数/ERR 才当无值。 */
    private static double parseRate(String raw, double max) {
        if (raw == null || raw.startsWith("ERR")) return Double.NaN;
        try {
            double v = Double.parseDouble(raw.trim());
            return v >= 0 && v <= max ? v : Double.NaN;
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    /** 解析 binder9 回包 "ok=true size=12 ints=result,count,value," → 取 value（成功且非 -10013） */
    private static Double parseBinderValue(String raw) {
        if (raw == null) return null;
        int i = raw.indexOf("ints=");
        if (i < 0) return null;
        String[] parts = raw.substring(i + 5).trim().split(",");
        if (parts.length < 3) return null;
        try {
            int result = (int) Double.parseDouble(parts[0].trim());
            int value = (int) Double.parseDouble(parts[2].trim());
            if (result != 0) return null;
            if (value == -10013) return null;   // 数据未就绪
            return (double) value;
        } catch (Throwable t) { return null; }
    }

    /**
     * 从 App 自己落盘的监听日志读取最新值（1.4.46+，docs/11 §14）。
     *
     * 数据链：BYDAuto 设备 → registerListener(IBYDAutoListener) 推送（**无权限检查**）
     * → BydDirect 落盘 native-events.log（形如 `statistic event=339738632 value=60 dval=0.0`）
     * → 这里读文件尾 16KB 取最新值。**不需要 binder、不需要 helper、不需要任何 BYD 权限**。
     *
     * 刻度来自 framework 的量程常量（编译期常量，dexdump 实证）：
     *   AVERAGE_FUEL_CON_PHM_MAX=51.1 / FUEL_PH_MAX=510 → 油耗 = raw/10
     *   STATISTIC_LAST_ELEC_CON_PHM_MAX=99.9            → 电耗 = raw/10
     *   MILE_TOTAL_MAX=99999999                        → 总里程 = raw/10（实测 486153→48615.3 ✓）
     *   TYRE_PRESSURE_VALUE_MAX=4094                   → 胎压 = raw（kPa）
     */
    private static void refreshFromEvents(android.content.Context ctx) {
        java.io.File f = new java.io.File(ctx.getExternalFilesDir(null), "native-events.log");
        if (!f.exists()) { nativeOk = false; return; }
        long len = f.length();
        if (len <= 0) { nativeOk = false; return; }
        // 1.4.56：**首次全量回灌**。油耗/电耗/四轮胎压这些量只在"值变化"时推，
        // 进程重启后如果不回灌，就会一直是 -1/NaN（实测 20:19 重启后探针全空，
        // 而这些 id 的最后一次推送发生在重启之前，日志里查得到）。
        // 迪友之所以一上车就有数，就是因为它常驻、从不丢这些值——我们用"全量回灌"等价实现。
        snapCtx = ctx;
        restoreSnapshot();
        // 1.4.100：尾巴 64KB 在「高频事件 + 节流」的 47MB 日志里只覆盖几秒，
        // 油耗/胎压的最后一次推送早就在窗口之外 → 回灌等于没做。
        // 先读 512KB；仍缺关键量就全量扫一次（每进程一次）。
        boolean full = !hasCriticalValues() && !fullScanned;
        if (full) fullScanned = true;
        long start = full ? 0 : Math.max(0, len - 524288L);
        String text;
        try {
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
            try {
                raf.seek(start);
                byte[] buf = new byte[(int) (len - start)];
                raf.readFully(buf);
                text = new String(buf, "UTF-8");
            } finally { raf.close(); }
        } catch (Throwable t) {
            nativeOk = false;
            return;
        }
        parseLines(text);
        seeded = true;
        nativeOk = !Double.isNaN(nMileage) && nMileage > 0;
    }

    /**
     * 纯函数解析（1.4.51 抽出，供单测回放真实车机日志行）。
     * 输入形如 `statistic event=1246785586 value=0 dval=4.6`。
     */
    static void parseLines(String text) {
        for (String line : text.split("\n")) {
            int i = line.indexOf(" event=");
            if (i < 0) continue;
            String rest = line.substring(i + 7).trim();
            int sp = rest.indexOf(' ');
            if (sp < 0) continue;
            int ev;
            try { ev = Integer.parseInt(rest.substring(0, sp)); } catch (Throwable t) { continue; }
            int vi = rest.indexOf("value=");
            if (vi < 0) continue;
            String vs = rest.substring(vi + 6).trim();
            int sp2 = vs.indexOf(' ');
            if (sp2 > 0) vs = vs.substring(0, sp2);
            double raw;
            try { raw = Double.parseDouble(vs); } catch (Throwable t) { continue; }
            // 1.4.50：**double 载荷在 dval 里**（2026-11-09 实车实测）：
            //   statistic event=1246785586 value=0 dval=4.6      → 50km 等效油耗 4.6 L/100km
            //   statistic event=1032863794 value=0 dval=16.1     → 最近段电耗 16.1 kWh/100km
            //   statistic event=1032871984 value=0 dval=5396.5   → 累计电耗 5396.5
            //   tyre      event=1020264488 value=0 dval=30.0     → 胎压 3.00 bar（÷10）
            // 只解析 value 会把它们全当 0 → 卡片显示 "—"（1.4.46/1.4.47 的老问题）。
            double dval = Double.NaN;
            int di = rest.indexOf("dval=");
            if (di >= 0) {
                String ds = rest.substring(di + 5).trim();
                int sp3 = ds.indexOf(' ');
                if (sp3 > 0) ds = ds.substring(0, sp3);
                try { dval = Double.parseDouble(ds); } catch (Throwable t) { }
            }
            applyEvent(ev, raw, dval);
        saveSnapshot();
        }
        nativeOk = !Double.isNaN(nMileage) && nMileage > 0;
    }

    /**
     * 监听回调实时入口（1.4.52）：BydDirect 每收到一条推送就调这里。
     *
     * 为什么不能只靠读日志文件尾：油耗事件（1246785586）实测 10 小时才 1 条，
     * 16KB 尾巴经常不含它 → `fuelPHM=NaN`（2026-11-09 19:45 实车就是这么显示的）。
     * 改成"回调即入内存"，见过一次就一直保留。
     */

    // ==== 1.4.78：上次值快照（迪友同款"上车即有数"）====
    // 车机只在值变化时推；进程重启后若还没变化就一直空。落一份最近值，启动先显示（正常色），
    // 收到实时推送立刻覆盖。写/读都用纯 Java，30 秒一次，失败静默。
    private static final String SNAP_NAME = "last-values.txt";
    private static volatile long lastSnapAt = 0L;
    private static volatile boolean snapRestored = false;
    private static volatile android.content.Context snapCtx = null;

    /** 1.4.100：四轮胎压与综合油耗是否都已有有效值（缺一个就值得全量扫一次） */
    private static boolean hasCriticalValues() {
        if (Double.isNaN(nFuelPHM) || nFuelPHM <= 0) return false;
        if (!isPlausiblePressure(nTyreLF) || !isPlausiblePressure(nTyreRF)
                || !isPlausiblePressure(nTyreLB) || !isPlausiblePressure(nTyreRB)) return false;
        return true;
    }

    /** 公开版：只把 >0 且不超过 500 kPa 的值当有效胎压，4094 等哨兵值也走降级。 */
    private static boolean isPlausiblePressure(double v) {
        return v > 0 && v <= 500;
    }

    /**
     * 把仲裁后的胎压源落到显示字段。
     * 迪加锁定后只抄迪加；映射表仅在 5s 超时后写，且迪加一旦成功就不再写。
     */
    private static void applyTyreSource() {
        if (TyreSource.dijiaLocked()) {
            int[] v = TyreSource.current();
            if (TyreSource.complete(v)) {
                nTyreLF = v[0]; nTyreRF = v[1]; nTyreLB = v[2]; nTyreRB = v[3];
                pressure[0] = v[0]; pressure[1] = v[1]; pressure[2] = v[2]; pressure[3] = v[3];
            }
            return;
        }
        if (TyreSource.mappingAllowed()) {
            // 映射表/原生事件只更新 nTyre，这里补 pressure，保证两个显示字段同源。
            if (isPlausiblePressure(nTyreLF)) pressure[0] = (int) nTyreLF;
            if (isPlausiblePressure(nTyreRF)) pressure[1] = (int) nTyreRF;
            if (isPlausiblePressure(nTyreLB)) pressure[2] = (int) nTyreLB;
            if (isPlausiblePressure(nTyreRB)) pressure[3] = (int) nTyreRB;
        }
    }

    private static volatile boolean fullScanned = false;

    private static void saveSnapshot() {
        android.content.Context ctx = snapCtx;
        if (ctx == null) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastSnapAt < 30_000L) return;
        lastSnapAt = now;
        java.io.FileOutputStream fo = null;
        try {
            java.io.File dir = ctx.getExternalFilesDir(null); if (dir == null) dir = ctx.getFilesDir();
            java.io.File f = new java.io.File(dir, SNAP_NAME);
            // 1.4.100：合并写 + 只写有效值。
            // 旧实现把 -1 / NaN 原样写进文件，重启后 restoreSnapshot 的 v<=0 判断又跳过它们，
            // 结果「快照存在但一个有效值都没有」（实车 last-values.txt 就是 -1/NaN）。
            java.util.HashMap<String, Double> m = new java.util.HashMap<>();
            if (f.exists()) {
                java.io.BufferedReader in = null;
                try {
                    in = new java.io.BufferedReader(new java.io.FileReader(f));
                    String line;
                    while ((line = in.readLine()) != null) {
                        int eq = line.indexOf('=');
                        if (eq <= 0) continue;
                        try { m.put(line.substring(0, eq).trim(), Double.parseDouble(line.substring(eq + 1).trim())); } catch (Throwable t) { }
                    }
                } catch (Throwable ignored) {
                } finally { if (in != null) { try { in.close(); } catch (Throwable ignored) { } } }
            }
            putIfValid(m, "tyreLF", nTyreLF);
            putIfValid(m, "tyreRF", nTyreRF);
            putIfValid(m, "tyreLB", nTyreLB);
            putIfValid(m, "tyreRB", nTyreRB);
            putIfValid(m, "fuel", nFuelPHM);
            putIfValid(m, "elec", nElecPHM);
            StringBuilder sb = new StringBuilder();
            for (String k : new String[]{"tyreLF", "tyreRF", "tyreLB", "tyreRB", "fuel", "elec"}) {
                Double v = m.get(k);
                sb.append(k).append('=').append(v == null ? "NaN" : String.valueOf(v)).append('\n');
            }
            fo = new java.io.FileOutputStream(f);
            fo.write(sb.toString().getBytes("UTF-8"));
        } catch (Throwable ignored) {
        } finally {
            if (fo != null) { try { fo.close(); } catch (Throwable ignored) { } }
        }
    }

    /** 1.4.100：只有有效值才允许进快照（-1 / NaN / 0 都不算有效） */
    private static void putIfValid(java.util.HashMap<String, Double> m, String k, double v) {
        if (Double.isNaN(v) || Double.isInfinite(v) || v <= 0) return;
        m.put(k, v);
    }

    /** 启动恢复一次：有值就直接当显示值用（正常色），实时推送到了自然覆盖 */
    private static void restoreSnapshot() {
        if (snapRestored) return;
        snapRestored = true;
        android.content.Context ctx = snapCtx;
        if (ctx == null) return;
        java.io.BufferedReader br = null;
        try {
            java.io.File dir = ctx.getExternalFilesDir(null); if (dir == null) dir = ctx.getFilesDir();
            java.io.File f = new java.io.File(dir, SNAP_NAME);
            if (!f.exists()) return;
            br = new java.io.BufferedReader(new java.io.FileReader(f));
            String line;
            while ((line = br.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim();
                double v;
                try { v = Double.parseDouble(line.substring(eq + 1).trim()); } catch (Throwable t) { continue; }
                if (v <= 0) continue;
                // 胎压不走旧快照恢复：旧快照可能是原生映射表口径（部分车型四轮全错），
                // 先由 TyreSource 在迪加/映射之间选定唯一数据源，避免开机先亮错值。
                if ("tyreLF".equals(k) || "tyreRF".equals(k) || "tyreLB".equals(k) || "tyreRB".equals(k)) continue;
                else if ("fuel".equals(k) && Double.isNaN(nFuelPHM)) { nFuelPHM = v; totalPHM = v; }
                else if ("elec".equals(k) && Double.isNaN(nElecPHM)) { nElecPHM = v; elecPHM = v; }
            }
        } catch (Throwable ignored) {
        } finally {
            if (br != null) { try { br.close(); } catch (Throwable ignored) { } }
        }
    }

    public static void onNativeEvent(int ev, double raw, double dval) {
        applyEvent(ev, raw, dval);
        nativeOk = !Double.isNaN(nMileage) && nMileage > 0;
        // 1.4.116：实时回调也写快照（30s 节流在 saveSnapshot 内）。这是"1.4.114 遗留的胎压显示 bug"的修复：
        // 原来只有"读日志文件"那条路径才写快照 → 快照可能落后几分钟（实车 247 卡住，内存已是 242/245），
        // 重启后 restoreSnapshot 先显示旧值，要等该轮下一次推送才纠正（左后推送本来就稀疏）。
        saveSnapshot();
    }

    /** event 号 → 物理量（映射见 docs/11 §14 的 3112 常量表） */
    private static void applyEvent(int ev, double raw, double dval) {
        // 有 dval（double 载荷）时优先用它：值直接是工程量，不再 ÷10
        boolean hasD = !Double.isNaN(dval) && !(dval == 0.0 && raw != 0.0);
        switch (ev) {
            case 339738632: nSpeed = raw; break;                       // STATISTIC_SPEED_SIG_VDIS
            case 1246765072: nMileage = raw / 10.0; break;              // TOTAL_MILEAGE
            // ==== 1.4.55：回调即直显，显示值 = 车机值，不做任何换算 ====
            case 1246785586: {                                             // LAST_50KM_EQUAL_FUEL_CON（综合油耗/仪表口径）
                nFuelPHM = hasD ? dval : raw / 10.0;
                if (nFuelPHM > 0.05 && nFuelPHM < 60) totalPHM = nFuelPHM;
                break;
            }
            case 1032863782: {                                             // LAST_FUEL_CON_PHM（最近段油耗，纯油）
                nFuelLast = hasD ? dval : raw / 10.0;
                if (nFuelLast > 0.05 && nFuelLast < 60) fuelPHM = nFuelLast;
                break;
            }
            case 1032863794: {                                             // LAST_ELEC_CON_PHM（最近段电耗）
                nElecPHM = hasD ? dval : raw / 10.0;
                if (nElecPHM > 0 && nElecPHM < 100) elecPHM = nElecPHM;
                break;
            }
            case 1246760976: nFuelTotal = raw / 10.0; break;            // TOTAL_FUEL_CONSUMPTION
            // 1.4.57：HEV 模式才推的两组量（2026-11-09 20:3x 实车）
            case 1246760996: if (hasD) nFuelInst = dval; break;          // STATISTIC_INSTANT_FUEL_CONSUME（瞬时油耗）
            case 1246797848: nTyreTempLF = raw; break;                  // INSTRUMENT LF TYRE_TEMPERATURE
            case 1246797860: nTyreTempRF = raw; break;
            case 1246797872: nTyreTempLB = raw; break;
            case 1246797884: nTyreTempRB = raw; break;
            case 1032871984: nElecTotal = hasD ? dval : raw / 10.0; break; // TOTAL_ELEC_CONSUMPTION（dval=5396.5）
            // 1.4.53：**删掉** 1020264488 → nTyreLF 的映射。
            // 该事件是 tyre 设备的 dval（29.0/30.0，×10 后 290/300），与 instrument 的 LF
            // （1246781456，245/290 kPa）抢同一个字段 → 左上胎压忽大忽小，用户实测为「错的」。
            // 轮位一律只认 instrument 的四个 id（INSTRUMENT_2IN1_*_TYRE_PRESSURE，kPa）。
            case 1246781456: if (FeatureGate.tyre() && TyreSource.mappingAllowed()) nTyreLF = raw; break; // INSTRUMENT LF TYRE PRESSURE (kPa)
            case 1246781468: if (FeatureGate.tyre() && TyreSource.mappingAllowed()) nTyreRF = raw; break;
            case 1246781480: if (FeatureGate.tyre() && TyreSource.mappingAllowed()) nTyreLB = raw; break;
            case 1246781492: if (FeatureGate.tyre() && TyreSource.mappingAllowed()) nTyreRB = raw; break;
            case 1147142160: if (raw > 0) nLowVolt = raw / 10.0; break; // LOWEST_BATTERY_VOLTAGE
            case 1147142192: if (raw > 0) nHighVolt = raw / 10.0; break;
            case 1151336480: nCurrent = raw / 10.0; break;             // INSTANTANEOUS_CURRENT
            case 1148190760: nRemainPower = raw; break;                 // REMAINING_BATTERY_POWER
            // 1.4.54：**删掉 1134559272 → nSoc**。实测值 11/30/51/78/138 与 SOC 无关
            // （探针里随车速乱跳），且 BYDAutoFeatureIds 里它不叫 SOC。宁缺勿错，先不显示。
            // 真 SOC 应从 statistic 的 SOC 系 event 或 instrument 的 BATTERY_PERCENT 取，待确证。
            default: break;
        }
    }

    private static volatile long nativeScanAt = 0L;

    private static void refreshNative(android.content.Context ctx) {
        // 被动监听已经收到过推送时，不再每 800ms 回读 512KB 的 native-events.log。
        // 文件只用于冷启动回灌/兜底，30s 一次；这样正常行驶时不再持续磁盘 I/O。
        long now = android.os.SystemClock.elapsedRealtime();
        if (seeded && nativeOk && now - nativeScanAt < 30_000L) return;
        nativeScanAt = now;
        refreshFromEvents(ctx);
    }

    /** 远程探针（1.4.51）：强制刷一次事件日志并回显解出来的量，验收"监听→卡片"整条链。 */
    public static String probeNative(android.content.Context ctx) {
        refreshFromEvents(ctx);
        return snapshot() + " source=" + statSource;
    }

    /** 解出的量的快照（不依赖 Context；探针与单测共用）。 */
    static String snapshot() {
        return "nativeOk=" + nativeOk
            + " speed=" + nSpeed
            + " mileage=" + nMileage
            + " totalPHM=" + nFuelPHM      // 综合油耗（1246785586）
            + " fuelPHM=" + nFuelLast      // 最近段油耗（1032863782）
            + " elecPHM=" + nElecPHM       // 最近段电耗（1032863794）
            + " elecTotal=" + nElecTotal
            + " tyreLF=" + nTyreLF
            + " tyreRF=" + nTyreRF
            + " tyreLB=" + nTyreLB
            + " tyreRB=" + nTyreRB
            + " instFuel=" + nFuelInst
            + " tyreTemp=" + nTyreTempLF + "/" + nTyreTempRF + "/" + nTyreTempLB + "/" + nTyreTempRB;
    }

    public static boolean isNativeOk() { return nativeOk; }

    /** 1.4.151：最近段纯油（L/100km）与最近段电耗（kW·h/100km），供仪表卡只读。 */
    public static double fuelLastRate() { return nFuelLast; }
    public static double elecLastRate() { return nElecPHM; }

    private static boolean pollStatistic(android.content.Context ctx) {
        try {
            if (pollNativeFuel(ctx)) return true;
            double v = nFuelPHM;
            if (Double.isNaN(v) || v <= 0.05 || v > 60) {
                if (statSource.equals("车机")) statSource = "自算";
                return false;
            }
            directTotal = v;
            statSource = "车机";
            applyDirect(v);   // applyDirect 会把 fuelPHM 也置成综合值，下面按纯油/电耗各自的车机值改回来
            if (!Double.isNaN(nFuelLast) && nFuelLast > 0.05 && nFuelLast < 60) fuelPHM = nFuelLast;
            if (!Double.isNaN(nElecPHM) && nElecPHM > 0) elecPHM = nElecPHM;
            estimated = false;
            computed = true;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 直接落显示值（1.4.53：**读到就用原值**，不再迟滞、不再叠加）。
     *
     * 用户口径：车机给的就是仪表口径（50km 等效油耗），能读到就显示读到的数，
     * 不要自己算——迟滞会让它比仪表慢半拍、看起来"差 0.1"。
     */
    private static synchronized void applyDirect(double total) {
        shownFuel = total;
        fuelHold = 0;
        fuelPHM = total;
        totalPHM = total;
    }

    private static void pollStats(android.content.Context ctx) {
        // 0) 原生批量刷新（一次 app_process，5s 缓存）
        refreshNative(ctx);
        // 1) 最优先：车机自己的「最近段」值（仪表同源）
        if (pollStatistic(ctx)) return;
        statSource = "自算";
        // 综合油耗 = 油耗(L/100km) + 电耗(kWh/100km) / 3.5，两个量都必须是「一段里程上的平均」。
        // 只用迪加自己记下来的真实数据：vehicle_telemetry.db 的 CAN 分段、/api/currentTrip。
        // v4.9.3：口径统一成「一段里程上的平均」，并把迪加「百公里电耗」彻底踢出主路径。
        // 实测同一次刷新里它会给到 49.3 kWh/100km，而最近 50km 的 CAN 分段是 15.9，
        // 两者来回切换就是用户看到的「综合油耗一直跳」；而且它只是电耗，压根没算油耗。
        // 顺序：1) 最近 50km 分段（稳定、覆盖够）→ 2) 本次行程（里程短时）→ 3) 都取不到就保留上次显示值。
        if (totalPHM < 0 && !Double.isNaN(nFuelPHM)) totalPHM = nFuelPHM;
        estimated = false;
    }

    /** 本次行程口径：直接读迪加 /api/currentTrip 的真实 delta。 */



    private static void pollWindow(android.content.Context ctx) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (lastSampleAt != 0 && now - lastSampleAt < STATS_MS) return;
        lastSampleAt = now;

        double kwh = valD("总电耗");
        double lit = valD("总燃油消耗");
        double km = valD("里程值");
        if (km <= 0 || kwh < 0 || lit < 0) return;

        curKm = km;
        curKwh = kwh;
        curL = lit;
        lifeE = kwh / km * 100.0;
        lifeF = lit / km * 100.0;

        if (!loaded) {
            load();
            loaded = true;
        }

        // 锚点：每走 0.2km 落一条；原地不动时只刷新最后一条，
        // 否则停车吹空调/怠速的耗电会全部算进"每百公里"里，数字直接起飞。
        if (SAMPLES.isEmpty()) {
            SAMPLES.add(new double[]{km, kwh, lit});
            save();
        } else {
            double[] last = SAMPLES.get(SAMPLES.size() - 1);
            double moved = km - last[0];
            if (moved >= 0.2) {
                SAMPLES.add(new double[]{km, kwh, lit});
                prune();
                save();
            } else if (moved < 0.05) {
                last[1] = kwh;
                last[2] = lit;
            }
        }
        recompute();
    }


    /** 用遥测库最近 120km 的分段，把累计寄存器锚点补出来。 */
    /**
     * 油耗：直接用遥测库最近 50km 的分段算，**只算 distance>0 的分段**，
     * 把停车怠速那段（有燃油、没里程）剔掉，否则停车吹空调会把每百公里顶到 28 L。
     */

    /** limit>0：最近 limit*0.1km；limit<0：整库。只统计 distance>0 的分段。 */
    private static double[] telemWindow(android.database.sqlite.SQLiteDatabase db, int limit) {
        android.database.Cursor c = null;
        try {
            String where = limit > 0
                    ? "startMileage >= (select max(endMileage) from VehicleSegment) - " + limit + " and distance > 0"
                    : "distance > 0";
            c = db.rawQuery("select sum(distance), sum(fuelDelta), sum(electricNetDelta) "
                    + "from VehicleSegment where " + where, null);
            if (c.moveToFirst() && !c.isNull(0)) {
                return new double[]{c.getDouble(0), c.getDouble(1), c.getDouble(2)};
            }
        } catch (Throwable t) {
            ClusterDebug.e("CLUSTERLAB-TELEM", "窗口查询失败: " + t);
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignored) {}
        }
        return null;
    }


    private static void recompute() {
        // 1.4.53：车机能读到就**不许再自算**（用户口径）。原生值新鲜时直接退出，
        // 避免"读到 4.5、显示 4.6"这种自算覆盖。
        if (!Double.isNaN(nFuelPHM) && nFuelPHM > 0.05 && nFuelPHM < 60) return;
        if (SAMPLES.isEmpty() || curKm <= 0) return;
        // 锚点里找窗口起点，差值用**当前读数**算 —— 读数一变数字就跟着变
        // 0 = 累计口径：直接用车机自己的两个累计寄存器（总燃油 / 总里程）
        if (windowKm <= 0) {
            winKm = curKm;
            setDisplay(lifeF, lifeE);
            estimated = false;
            computed = true;
            if (!Double.isNaN(nFuelPHM)) totalPHM = nFuelPHM;
            return;
        }
        double[] base = null;
        for (int i = SAMPLES.size() - 1; i >= 0; i--) {
            double[] s = SAMPLES.get(i);
            if (curKm - s[0] <= windowKm) base = s;
            else break;
        }
        if (base == null) base = SAMPLES.get(0);
        double dk = curKm - base[0];
        winKm = dk;
        if (dk >= MIN_KM) {
            double de = curKwh - base[1];
            double df = curL - base[2];
            if (de < 0) de = 0;      // 净回收
            if (df < 0) df = 0;
            double rawE = de / dk * 100.0;
            double rawF = df / dk * 100.0;
            double w = dk / (dk + PRIOR_KM);      // 窗口权重，随里程从 0 收敛到 1
            setDisplay(rawF * w + lifeF * (1 - w), rawE * w + lifeE * (1 - w));
            estimated = false;
            computed = true;
        } else if (!computed) {
            // 还没跑起来（刚装/刚清历史）：先用生命周期均值顶一下
            setDisplay(lifeF, lifeE);
            estimated = true;
        }
        // dk 不足 0.3km 且已经算过：保持上一次的值，不用停车时的漂移去污染它
        if (!Double.isNaN(nFuelPHM)) totalPHM = nFuelPHM;
    }

    /** 供远程调口径后立即生效（不阻塞，内部只是重算一次）。 */
    public static void forceRecompute() {
        try { recompute(); } catch (Throwable ignored) {}
    }

    private static void prune() {
        if (SAMPLES.isEmpty()) return;
        double newest = SAMPLES.get(SAMPLES.size() - 1)[0];
        // 保留「窗口 + 20km」的历史，窗口可调到 200km 时也不至于算到边界外
        double keep = Math.max(120.0, windowKm + 20.0);
        while (SAMPLES.size() > 2 && SAMPLES.get(0)[0] < newest - keep) SAMPLES.remove(0);
        while (SAMPLES.size() > 400) SAMPLES.remove(0);
    }

    private static void load() {
        File f = new File(HIST);
        if (!f.exists()) return;
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(f));
            String line;
            while ((line = br.readLine()) != null) {
                String[] p = line.split(",");
                if (p.length < 3) continue;
                SAMPLES.add(new double[]{
                        Double.parseDouble(p[0].trim()),
                        Double.parseDouble(p[1].trim()),
                        Double.parseDouble(p[2].trim())
                });
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (br != null) br.close(); } catch (Throwable ignored) {}
        }
        recompute();
    }

    private static void save() {
        try {
            StringBuilder sb = new StringBuilder();
            for (double[] s : SAMPLES) {
                sb.append(String.format(java.util.Locale.US, "%.1f,%.1f,%.1f", s[0], s[1], s[2])).append('\n');
            }
            FileWriter w = new FileWriter(HIST);
            w.write(sb.toString());
            w.close();
        } catch (Throwable ignored) {
        }
    }

    /** 直接读迪加参数；∞ / 负数 / 失败都返回 -1。 */


    /** 中文名 → 原生方法（1.4.18：不再请求任何第三方 App） */
    private static double valD(String name) {
        switch (name) {
            case "总电耗": return nElecTotal;
            case "总燃油消耗": return nFuelTotal;
            case "里程值": return nMileage;
            case "最近油耗": return nFuelPHM;
            case "最近电耗": return nElecPHM;
            default: return -1;
        }
    }

}
