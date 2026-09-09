package com.brctl.brightnesshook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;

public class Main implements IXposedHookLoadPackage {

    private static final String TAG = "BrightnessHook";
    private static Method sysPropGet = null;
    private static Method sysPropSet = null;
    private static int luxLogCount = 0;

    // 光感历史记录（用于平滑 + 延迟）
    private static final ArrayList<Long> sLuxTimes = new ArrayList<Long>();
    private static final ArrayList<Float> sLuxValues = new ArrayList<Float>();

    private static String getProp(String key, String def) {
        try {
            if (sysPropGet == null) {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                sysPropGet = sp.getMethod("get", String.class, String.class);
            }
            Object v = sysPropGet.invoke(null, key, def);
            return v == null ? def : v.toString();
        } catch (Throwable t) {
            return def;
        }
    }

    private static float getPropFloat(String key, float def) {
        try {
            return Float.parseFloat(getProp(key, String.valueOf(def)));
        } catch (Throwable t) {
            return def;
        }
    }

    private static float clamp(float v, float min, float max) {
        if (v < min) {
            return min;
        }
        if (v > max) {
            return max;
        }
        return v;
    }

    private static void setProp(String key, String value) {
        try {
            if (sysPropSet == null) {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                sysPropSet = sp.getMethod("set", String.class, String.class);
            }
            sysPropSet.invoke(null, key, value);
        } catch (Throwable t) {
            // 忽略
        }
    }

    /**
     * 对光感读数做中值/均值滤波（时间窗内）。
     *
     * @param now 当前事件时间戳（uptimeMillis）
     * @param lux 当前（已乘 lux_inc 的）光感值
     * @return 滤波后的值
     */
    private static synchronized float filterLux(long now, float lux) {
        float windowMs = clamp(getPropFloat("persist.brctl.smooth_window", 4000f), 0f, 60000f);
        String mode = getProp("persist.brctl.filter_mode", "median");
        if (windowMs <= 0f) {
            return lux;
        }
        long window = (long) windowMs;

        sLuxTimes.add(Long.valueOf(now));
        sLuxValues.add(Float.valueOf(lux));

        // 清理太旧的数据
        long cutoff = now - window;
        while (!sLuxTimes.isEmpty() && sLuxTimes.get(0).longValue() < cutoff) {
            sLuxTimes.remove(0);
            sLuxValues.remove(0);
        }

        // 收集 [now - window, now] 窗口内的值
        long start = now - window;
        long end = now;
        ArrayList<Float> vals = new ArrayList<Float>();
        for (int i = 0; i < sLuxTimes.size(); i++) {
            long t = sLuxTimes.get(i).longValue();
            if (t >= start && t <= end) {
                vals.add(sLuxValues.get(i));
            }
        }
        if (vals.isEmpty()) {
            return lux; // 历史数据不足，直接返回当前值
        }
        if ("mean".equals(mode)) {
            float sum = 0f;
            for (int i = 0; i < vals.size(); i++) {
                sum += vals.get(i).floatValue();
            }
            return sum / vals.size();
        }
        // 默认中值滤波
        Collections.sort(vals);
        int n = vals.size();
        if (n % 2 == 1) {
            return vals.get(n / 2).floatValue();
        }
        return (vals.get(n / 2 - 1).floatValue() + vals.get(n / 2).floatValue()) / 2f;
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"android".equals(lpparam.packageName)) {
            return;
        }
        XposedBridge.log(TAG + ": hooking system framework");
        try {
            final ClassLoader cl = lpparam.classLoader;
            final String cls = "com.android.server.display.AutomaticBrightnessController";

            // ===== Hook 1：拦截光感 lux 输入（倍率 + 平滑 + 延迟） =====
            XposedHelpers.findAndHookMethod(cls, cl, "handleLightSensorEvent",
                    long.class, float.class, new XC_MethodHook() {
                        @Override
                        public void beforeHookedMethod(MethodHookParam param) {
                            Object[] args = param.args;
                            if (args.length < 2 || !(args[1] instanceof Float)) {
                                return;
                            }
                            long now = ((Long) args[0]).longValue();
                            float rawLux = ((Float) args[1]).floatValue();
                            float lux = rawLux;
                            float luxMin = getPropFloat("persist.brctl.lux_min", 0f);
                            if (lux < luxMin) {
                                lux = luxMin; // 光感最小值：低于该值按该值算
                            }
                            float luxInc = getPropFloat("persist.brctl.lux_inc", 1.3f);
                            float newLux = lux * luxInc;
                            newLux = filterLux(now, newLux);
                            // 实时暴露光感值给界面
                            setProp("sys.brctl.raw_lux", String.valueOf(rawLux));
                            setProp("sys.brctl.cur_lux", String.valueOf(newLux));
                            if (luxLogCount < 50) {
                                luxLogCount++;
                                XposedBridge.log(TAG + ": lux " + rawLux + " -> " + newLux
                                        + " (inc=" + luxInc + ")");
                            }
                            args[1] = Float.valueOf(newLux);
                        }
                    });
            XposedBridge.log(TAG + ": hooked handleLightSensorEvent (lux input) OK");

            // ===== Hook 3：修改防抖时间 =====
            final Class<?> abcClass = XposedHelpers.findClass(cls, cl);
            XposedBridge.hookAllConstructors(abcClass, new XC_MethodHook() {
                @Override
                public void afterHookedMethod(MethodHookParam param) {
                    try {
                        long brighten = (long) getPropFloat("persist.brctl.brighten_debounce", 1000f);
                        long darken = (long) getPropFloat("persist.brctl.darken_debounce", 2000f);
                        XposedHelpers.setLongField(param.thisObject, "mBrighteningLightDebounceConfig", brighten);
                        XposedHelpers.setLongField(param.thisObject, "mDarkeningLightDebounceConfig", darken);
                        XposedBridge.log(TAG + ": debounce set brighten=" + brighten + " darken=" + darken);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": set debounce failed");
                        XposedBridge.log(t);
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked constructors (debounce) OK");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook failed");
            XposedBridge.log(t);
        }
    }
}
