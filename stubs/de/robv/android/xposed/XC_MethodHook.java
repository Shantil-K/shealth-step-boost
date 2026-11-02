package de.robv.android.xposed;
public abstract class XC_MethodHook {
  protected void beforeHookedMethod(MethodHookParam p) throws Throwable {}
  protected void afterHookedMethod(MethodHookParam p) throws Throwable {}
  public static class MethodHookParam { public Object thisObject; public Object[] args; public Object getResult() { return null; } public void setResult(Object o) {} }
  public class Unhook { public void unhook() {} }
}
