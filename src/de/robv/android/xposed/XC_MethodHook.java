package de.robv.android.xposed;

public abstract class XC_MethodHook {
    public static class MethodHookParam {
        public Object[] args;
        public Object thisObject;
        public Object getResult() { return null; }
        public void setResult(Object result) {}
        public Throwable getThrowable() { return null; }
    }
    public static class Unhook {
        public void unhook() {}
    }
    public void beforeHookedMethod(MethodHookParam param) {}
    public void afterHookedMethod(MethodHookParam param) {}
}
