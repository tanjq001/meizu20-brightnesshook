package de.robv.android.xposed;

public class XposedHelpers {
    public static Class findClass(String className, ClassLoader classLoader) {
        return null;
    }
    public static XC_MethodHook.Unhook findAndHookMethod(Class cls, String methodName, Object... parameterTypesAndCallback) {
        return null;
    }
    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader classLoader, String methodName, Object... parameterTypesAndCallback) {
        return null;
    }
    public static void setLongField(Object obj, String fieldName, long value) {}
    public static long getLongField(Object obj, String fieldName) { return 0L; }
}
