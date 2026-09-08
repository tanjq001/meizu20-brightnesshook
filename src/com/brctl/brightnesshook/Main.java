package com.brctl.brightnesshook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Method;

public class Main implements IXposedHookLoadPackage {

    private static final String TAG = "BrightnessHook";
    private static Method sysPropGet = null;
    private static int luxLogCount = 0;

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

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"android".equals(lpparam.packageName)) {
            return;
        }
        XposedBridge.log(TAG + ": hooking system framework");
        try {
            final ClassLoader cl = lpparam.classLoader;
            final String cls = "com.android.server.display.AutomaticBrightnessController";

            // ===== Hook 1：拦截光感 lux 输入（改曲线的"输入"） =====
            XposedHelpers.findAndHookMethod(cls, cl, "handleLightSensorEvent",
                    long.class, float.class, new XC_MethodHook() {
                        @Override
                        public void beforeHookedMethod(MethodHookParam param) {
                            float luxInc = getPropFloat("persist.brctl.lux_inc", 1.0f);
                            if (luxInc == 1.0f) {
                                return;
                            }
                            Object[] args = param.args;
                            if (args.length >= 2 && args[1] instanceof Float) {
                                float lux = ((Float) args[1]).floatValue();
                                float newLux = lux * luxInc;
                                if (luxLogCount < 50) {
                                    luxLogCount++;
                                    XposedBridge.log(TAG + ": lux " + lux + " -> " + newLux + " (inc=" + luxInc + ")");
                                }
                                args[1] = Float.valueOf(newLux);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hooked handleLightSensorEvent (lux input) OK");

            // ===== Hook 2：拦截亮度输出（改曲线的"输出"，分段倍率） =====
            final Class<?> brightnessEventClass = XposedHelpers.findClass(
                    "com.android.server.display.brightness.BrightnessEvent", cl);
            XposedHelpers.findAndHookMethod(cls, cl, "getAutomaticScreenBrightness",
                    brightnessEventClass, new XC_MethodHook() {
                        @Override
                        public void afterHookedMethod(MethodHookParam param) {
                            Object result = param.getResult();
                            if (!(result instanceof Float)) {
                                return;
                            }
                            float b = ((Float) result).floatValue();
                            float percent = b * 100f;
                            float lowMax = getPropFloat("persist.brctl.low_max", 30f);
                            float midMax = getPropFloat("persist.brctl.mid_max", 60f);
                            float lowInc = getPropFloat("persist.brctl.low_inc", 1.0f);
                            float midInc = getPropFloat("persist.brctl.mid_inc", 1.0f);
                            float higInc = getPropFloat("persist.brctl.hig_inc", 1.0f);
                            float mult;
                            if (percent < lowMax) {
                                mult = lowInc;
                            } else if (percent < midMax) {
                                mult = midInc;
                            } else {
                                mult = higInc;
                            }
                            float nb = b * mult;
                            if (nb > 1.0f) {
                                nb = 1.0f;
                            }
                            if (nb < 0.0f) {
                                nb = 0.0f;
                            }
                            param.setResult(Float.valueOf(nb));
                        }
                    });
            XposedBridge.log(TAG + ": hooked getAutomaticScreenBrightness (output) OK");

            // ===== Hook 3：修改防抖时间（解决不灵敏 + 明暗跳跃） =====
            final Class<?> abcClass = XposedHelpers.findClass(cls, cl);
            XposedBridge.hookAllConstructors(abcClass, new XC_MethodHook() {
                @Override
                public void afterHookedMethod(MethodHookParam param) {
                    try {
                        long brighten = (long) getPropFloat("persist.brctl.brighten_debounce", 1000f);
                        long darken = (long) getPropFloat("persist.brctl.darken_debounce", 4000f);
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
