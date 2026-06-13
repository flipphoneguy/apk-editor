package com.flipphoneguy.apkeditor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;

/**
 * The editable manifest model exchanged with the backend.
 *
 * {@link #fromJson} parses the model returned by {@code POST /apkeditor/upload};
 * {@link #toEdits} produces the edits payload for {@code POST /apkeditor/build},
 * applying the same tri-state logic as the web UI so flags/exported the user
 * never touched are left exactly as they were.
 */
public class ManifestModel {

    /** App-level boolean flags, in the order the backend exposes them. */
    public static final String[] FLAG_ORDER = {
        "debuggable", "allowBackup", "usesCleartextTraffic",
        "extractNativeLibs", "largeHeap"
    };

    public String pkg;
    public String versionCode;
    public String versionName;
    public String minSdkVersion;
    public String targetSdkVersion;

    public String labelValue;
    public boolean labelEditable;

    /** flag name -> desired state; null value means "unset / leave as-is". */
    public final LinkedHashMap<String, Boolean> flags = new LinkedHashMap<>();
    public final ArrayList<String> permissions = new ArrayList<>();
    public final ArrayList<Activity> activities = new ArrayList<>();

    public static class Activity {
        public String name;
        public Boolean exported;   // null = unset
        public boolean launchable;

        Activity(String name, Boolean exported, boolean launchable) {
            this.name = name;
            this.exported = exported;
            this.launchable = launchable;
        }
    }

    // ── Parse the upload model ──────────────────────────────────────────────

    public static ManifestModel fromJson(JSONObject o) throws JSONException {
        ManifestModel m = new ManifestModel();
        m.pkg = optStr(o, "package");
        m.versionCode = optStr(o, "versionCode");
        m.versionName = optStr(o, "versionName");
        m.minSdkVersion = optStr(o, "minSdkVersion");
        m.targetSdkVersion = optStr(o, "targetSdkVersion");

        JSONObject lab = o.optJSONObject("label");
        if (lab != null) {
            m.labelValue = optStr(lab, "value");
            m.labelEditable = lab.optBoolean("editable", false);
        }

        JSONObject fl = o.optJSONObject("flags");
        for (String k : FLAG_ORDER) {
            if (fl != null && fl.has(k) && !fl.isNull(k)) {
                m.flags.put(k, fl.optBoolean(k, false));
            } else if (fl != null && fl.has(k)) {
                m.flags.put(k, null);          // explicit unset
            }
            // a flag the backend didn't mention is simply absent
        }
        // include any flag the backend sent that isn't in FLAG_ORDER
        if (fl != null) {
            for (java.util.Iterator<String> it = fl.keys(); it.hasNext(); ) {
                String k = it.next();
                if (!m.flags.containsKey(k)) {
                    m.flags.put(k, fl.isNull(k) ? null : fl.optBoolean(k, false));
                }
            }
        }

        JSONArray perms = o.optJSONArray("permissions");
        if (perms != null) {
            for (int i = 0; i < perms.length(); i++) {
                String p = perms.optString(i, null);
                if (p != null && !p.isEmpty()) m.permissions.add(p);
            }
        }

        JSONArray acts = o.optJSONArray("activities");
        if (acts != null) {
            for (int i = 0; i < acts.length(); i++) {
                JSONObject a = acts.optJSONObject(i);
                if (a == null) continue;
                String name = optStr(a, "name");
                if (name == null) continue;
                Boolean exported = a.has("exported") && !a.isNull("exported")
                    ? a.optBoolean("exported", false) : null;
                boolean launchable = a.optBoolean("launchable", false);
                m.activities.add(new Activity(name, exported, launchable));
            }
        }
        return m;
    }

    /** Deep copy (used for the "revert to original" working/original split). */
    public ManifestModel copy() {
        ManifestModel m = new ManifestModel();
        m.pkg = pkg;
        m.versionCode = versionCode;
        m.versionName = versionName;
        m.minSdkVersion = minSdkVersion;
        m.targetSdkVersion = targetSdkVersion;
        m.labelValue = labelValue;
        m.labelEditable = labelEditable;
        m.flags.putAll(flags);
        m.permissions.addAll(permissions);
        for (Activity a : activities) {
            m.activities.add(new Activity(a.name, a.exported, a.launchable));
        }
        return m;
    }

    // ── Build the edits payload ─────────────────────────────────────────────

    /**
     * Serialize this (the working state) into the edits payload, diffing flags
     * and activity {@code exported} against {@code original} so untouched
     * tri-state values stay untouched. Mirrors the web UI's tri() logic.
     */
    public JSONObject toEdits(ManifestModel original) throws JSONException {
        JSONObject e = new JSONObject();
        e.put("package", trim(pkg));
        e.put("versionCode", trim(versionCode));
        e.put("versionName", trim(versionName));
        e.put("minSdkVersion", trim(minSdkVersion));
        e.put("targetSdkVersion", trim(targetSdkVersion));

        JSONObject label = new JSONObject();
        if (labelEditable) {
            label.put("editable", true);
            label.put("value", labelValue == null ? "" : labelValue);
        } else {
            label.put("editable", false);
            label.put("value", original.labelValue == null
                ? JSONObject.NULL : original.labelValue);
        }
        e.put("label", label);

        JSONObject fl = new JSONObject();
        for (String k : flags.keySet()) {
            boolean cur = Boolean.TRUE.equals(flags.get(k));
            Boolean orig = original.flags.get(k);
            fl.put(k, tri(cur, orig));
        }
        e.put("flags", fl);

        JSONArray perms = new JSONArray();
        for (String p : permissions) perms.put(p);
        e.put("permissions", perms);

        JSONArray acts = new JSONArray();
        for (Activity a : activities) {
            Activity o = findByName(original.activities, a.name);
            JSONObject ao = new JSONObject();
            ao.put("name", a.name);
            ao.put("exported", tri(a.exported == Boolean.TRUE, o == null ? null : o.exported));
            ao.put("launchable", a.launchable);
            acts.put(ao);
        }
        e.put("activities", acts);
        return e;
    }

    /**
     * If the current bool matches the original's truthiness, keep the original
     * (which may be JSON null = unset); otherwise take the current bool.
     */
    private static Object tri(boolean cur, Boolean orig) {
        boolean origIsTrue = Boolean.TRUE.equals(orig);
        if (cur == origIsTrue) return orig == null ? JSONObject.NULL : orig;
        return cur;
    }

    private static Activity findByName(ArrayList<Activity> list, String name) {
        for (Activity a : list) if (a.name.equals(name)) return a;
        return null;
    }

    private static String optStr(JSONObject o, String key) {
        if (!o.has(key) || o.isNull(key)) return null;
        return o.optString(key, null);
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }
}
