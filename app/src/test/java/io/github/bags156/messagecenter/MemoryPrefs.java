package io.github.bags156.messagecenter;

import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** SharedPreferences kept in memory, for unit tests of classes that store their state there. */
final class MemoryPrefs implements SharedPreferences {
    private final Map<String, Object> values = new HashMap<>();

    public Map<String, ?> getAll() { return new HashMap<>(values); }
    public String getString(String k, String d) { Object v = values.get(k); return v instanceof String ? (String) v : d; }
    @SuppressWarnings("unchecked") public Set<String> getStringSet(String k, Set<String> d) { Object v = values.get(k); return v instanceof Set ? (Set<String>) v : d; }
    public int getInt(String k, int d) { Object v = values.get(k); return v instanceof Integer ? (Integer) v : d; }
    public long getLong(String k, long d) { Object v = values.get(k); return v instanceof Long ? (Long) v : d; }
    public float getFloat(String k, float d) { Object v = values.get(k); return v instanceof Float ? (Float) v : d; }
    public boolean getBoolean(String k, boolean d) { Object v = values.get(k); return v instanceof Boolean ? (Boolean) v : d; }
    public boolean contains(String k) { return values.containsKey(k); }
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
    public Editor edit() {
        return new Editor() {
            public Editor putString(String k, String v) { values.put(k, v); return this; }
            public Editor putStringSet(String k, Set<String> v) { values.put(k, v); return this; }
            public Editor putInt(String k, int v) { values.put(k, v); return this; }
            public Editor putLong(String k, long v) { values.put(k, v); return this; }
            public Editor putFloat(String k, float v) { values.put(k, v); return this; }
            public Editor putBoolean(String k, boolean v) { values.put(k, v); return this; }
            public Editor remove(String k) { values.remove(k); return this; }
            public Editor clear() { values.clear(); return this; }
            public boolean commit() { return true; }
            public void apply() {}
        };
    }
}
