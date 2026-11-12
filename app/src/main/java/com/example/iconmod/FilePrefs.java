package com.example.iconmod;

import android.content.SharedPreferences;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Read-only SharedPreferences backed by a plain text file ("key TAB type TAB value" per line).
 * The app writes it into the launcher's own data dir (with root) on every Save, so the hook reads its OWN file:
 * no dependency on LSPosed's shared-prefs access, file ownership or SELinux labels of the module's config.xml.
 */
class FilePrefs implements SharedPreferences {
    private final Map<String, Object> map = new HashMap<>();

    static String esc(String s) { return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n"); }

    static String unesc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                b.append(n == 't' ? '\t' : n == 'n' ? '\n' : n);
            } else b.append(c);
        }
        return b.toString();
    }

    /** Serializes a SharedPreferences.getAll() map. */
    static String serialize(Map<String, ?> all) {
        StringBuilder sb = new StringBuilder("# IconMod config\n");
        for (Map.Entry<String, ?> e : all.entrySet()) {
            Object v = e.getValue();
            String type, val;
            if (v instanceof Boolean) { type = "b"; val = v.toString(); }
            else if (v instanceof Integer) { type = "i"; val = v.toString(); }
            else if (v instanceof Long) { type = "l"; val = v.toString(); }
            else if (v instanceof Float) { type = "f"; val = v.toString(); }
            else if (v instanceof Set) {
                type = "S";
                StringBuilder j = new StringBuilder();
                for (Object o : (Set<?>) v) { if (j.length() > 0) j.append('\u0001'); j.append(o); }
                val = j.toString();
            } else if (v instanceof String) { type = "s"; val = (String) v; }
            else continue;
            sb.append(esc(e.getKey())).append('\t').append(type).append('\t').append(esc(val)).append('\n');
        }
        return sb.toString();
    }

    /** null if the file doesn't exist / can't be read / is empty. */
    static FilePrefs load(File f) {
        try {
            if (f == null || !f.isFile() || !f.canRead()) return null;
            FilePrefs p = new FilePrefs();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("#")) continue;
                    String[] t = line.split("\t", 3);
                    if (t.length < 3) continue;
                    String k = unesc(t[0]), v = unesc(t[2]);
                    switch (t[1]) {
                        case "b": p.map.put(k, Boolean.valueOf(v)); break;
                        case "i": p.map.put(k, Integer.valueOf(v)); break;
                        case "l": p.map.put(k, Long.valueOf(v)); break;
                        case "f": p.map.put(k, Float.valueOf(v)); break;
                        case "S": {
                            Set<String> s = new HashSet<>();
                            if (!v.isEmpty()) for (String x : v.split("\u0001")) s.add(x);
                            p.map.put(k, s);
                            break;
                        }
                        default: p.map.put(k, v);
                    }
                }
            }
            return p.map.isEmpty() ? null : p;
        } catch (Throwable t) { return null; }
    }

    int size() { return map.size(); }

    @Override public Map<String, ?> getAll() { return new HashMap<>(map); }
    @Override public String getString(String k, String d) { Object v = map.get(k); return v instanceof String ? (String) v : d; }
    @SuppressWarnings("unchecked")
    @Override public Set<String> getStringSet(String k, Set<String> d) { Object v = map.get(k); return v instanceof Set ? (Set<String>) v : d; }
    @Override public int getInt(String k, int d) { Object v = map.get(k); return v instanceof Integer ? (Integer) v : d; }
    @Override public long getLong(String k, long d) { Object v = map.get(k); return v instanceof Long ? (Long) v : d; }
    @Override public float getFloat(String k, float d) { Object v = map.get(k); return v instanceof Float ? (Float) v : d; }
    @Override public boolean getBoolean(String k, boolean d) { Object v = map.get(k); return v instanceof Boolean ? (Boolean) v : d; }
    @Override public boolean contains(String k) { return map.containsKey(k); }
    @Override public Editor edit() { throw new UnsupportedOperationException("read-only"); }
    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
}
