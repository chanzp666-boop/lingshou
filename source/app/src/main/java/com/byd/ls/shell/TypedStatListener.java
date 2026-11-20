package com.byd.ls.shell;

import android.content.Context;
import android.hardware.IBYDAutoEvent;
import android.hardware.bydauto.statistic.AbsBYDAutoStatisticListener;
import android.hardware.bydauto.statistic.BYDAutoStatisticDevice;
import android.hardware.bydauto.tyre.AbsBYDAutoTyreListener;
import android.hardware.bydauto.tyre.BYDAutoTyreDevice;

/**
 * 继承 BYD 的 Abs*Listener 拿具名回调（1.4.49）。
 *
 * 为什么不能用 Proxy（这是 2026-11-09 从迪友 com.dy.launcher 反编译确认的）：
 * 迪友的 a4.m 直接 `BYDAutoStatisticDevice.getInstance(ctx).registerListener(this)`，
 * 而它自己 `extends AbsBYDAutoStatisticListener`——框架把内部 event 号翻译成
 * **具名回调**（onLastFuelConPHMChanged(double) 等）再分发；只实现 IBYDAutoListener
 * 的 Proxy 拿不到具名分发，只能拿到 onDataChanged(IBYDAutoEvent)，而实测该通道里
 * getValue()/getDoubleValue() 恒为 0。
 *
 * 编译期靠 helper/stub 的 stub.jar（bootclasspath 真实类在运行时装上），
 * 与 helper 里的 StatListener 同一套做法，只是搬到 App 进程内。
 */
public final class TypedStatListener extends AbsBYDAutoStatisticListener {

    public interface Sink { void on(String key, Object value); }

    private static volatile Sink sink;

    public static void setSink(Sink s) { sink = s; }

    private static void rec(String k, Object v) {
        Sink s = sink;
        if (s != null) {
            try { s.on(k, v); } catch (Throwable ignored) {}
        }
    }

    @Override public void onDataChanged(IBYDAutoEvent event) {
        try {
            Object ev = event.getClass().getMethod("getEventType").invoke(event);
            Object v = event.getClass().getMethod("getValue").invoke(event);
            Object dv = event.getClass().getMethod("getDoubleValue").invoke(event);
            rec("raw ev=" + ev + " v=" + v + " dv=" + dv, v);
        } catch (Throwable ignored) {}
    }

    @Override public void onLast50KmEqualFuelConValueChanged(double v) { rec("eq50Fuel", v); }
    @Override public void onLastFuelConPHMChanged(double v) { rec("lastFuelPHM", v); }
    @Override public void onLastElecConPHMChanged(double v) { rec("lastElecPHM", v); }
    @Override public void onTotalFuelConChanged(double v) { rec("totalFuel", v); }
    @Override public void onTotalFuelConPHMChanged(double v) { rec("totalFuelPHM", v); }
    @Override public void onTotalElecConChanged(double v) { rec("totalElec", v); }
    @Override public void onTotalElecConPHMChanged(double v) { rec("totalElecPHM", v); }
    @Override public void onAverageFuelConsumptionChanged(int t, double v) { rec("avgFuel[" + t + "]", v); }
    @Override public void onAverageElectricConsumptionChanged(int t, double v) { rec("avgElec[" + t + "]", v); }
    @Override public void onInstantFuelConChanged(double v) { rec("instantFuel", v); }
    @Override public void onInstantElecConChanged(double v) { rec("instantElec", v); }
    @Override public void onTotalMileageValueChanged(int v) { rec("totalMileage", v); }
    @Override public void onMileageNumberChanged(int t, int v) { rec("mileage[" + t + "]", v); }
    @Override public void onEVDrivingMileageModeChanged(int v) { rec("evMileageMode", v); }
    @Override public void onEVMileageValueChanged(int v) { rec("evMileage", v); }
    @Override public void onHEVMileageValueChanged(int v) { rec("hevMileage", v); }
    @Override public void onSpeedSignalVDisValueChanged(int v) { rec("speed", v); }
    @Override public void onAverageSpeedChanged(int t, double v) { rec("avgSpeed[" + t + "]", v); }
    @Override public void onElecPercentageChanged(double v) { rec("elecPct", v); }
    @Override public void onSOCBatteryPercentageChanged(int v) { rec("soc", v); }
    @Override public void onFuelPercentageChanged(int v) { rec("fuelPct", v); }
    @Override public void onFuelDrivingRangeChanged(int v) { rec("fuelRange", v); }
    @Override public void onElecDrivingRangeChanged(int v) { rec("elecRange", v); }
    @Override public void onWaterTemperatureChanged(int v) { rec("waterTemp", v); }
    @Override public void onRemainingBatteryPowerChanged(int v) { rec("remainPower", v); }
    @Override public void onDrivingTimeChanged(double v) { rec("drivingTime", v); }
    @Override public void onError(int code, String msg) { rec("error", code + ":" + msg); }

    /** 在 App 进程内注册（与迪友同一路径：getInstance + registerListener）。 */
    public static String attach(Context ctx) {
        try {
            BYDAutoStatisticDevice dev = BYDAutoStatisticDevice.getInstance(ctx);
            if (dev == null) return "statistic=null";
            dev.registerListener(new TypedStatListener());
            return "statistic=ok";
        } catch (Throwable t) {
            return "statistic=ERR " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    /** 胎压：迪友 a4.n 同样 getInstance + registerListener（另配 getTyrePressureValue(1..4)）。 */
    public static String attachTyre(Context ctx) {
        try {
            BYDAutoTyreDevice dev = BYDAutoTyreDevice.getInstance(ctx);
            if (dev == null) return "tyre=null";
            dev.registerListener(new AbsBYDAutoTyreListener() {
                @Override public void onDataChanged(IBYDAutoEvent event) {
                    try {
                        Object ev = event.getClass().getMethod("getEventType").invoke(event);
                        Object v = event.getClass().getMethod("getValue").invoke(event);
                        rec("tyreRaw ev=" + ev + " v=" + v, v);
                    } catch (Throwable ignored) {}
                }
            });
            return "tyre=ok";
        } catch (Throwable t) {
            return "tyre=ERR " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }
}