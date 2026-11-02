package de.robv.android.xposed;
public final class XSharedPreferences {
  public XSharedPreferences(String packageName, String prefFileName) {}
  public java.io.File getFile() { return null; }
  public boolean hasFileChanged() { return false; }
  public void reload() {}
  public boolean contains(String key) { return false; }
  public int getInt(String key, int def) { return def; }
  public float getFloat(String key, float def) { return def; }
  public boolean getBoolean(String key, boolean def) { return def; }
  public String getString(String key, String def) { return def; }
}
