"""Decode / edit / re-encode an APK's AndroidManifest.xml with pyaxml.

The whole speed premise of the editor is that we never unpack the APK. We pull
the single binary AndroidManifest.xml out of the zip, decode it to an editable
model, apply the user's edits, and re-encode it back to binary AXML in-process
(pure Python, no JVM, no aapt). pyaxml rebuilds the resource map from its own
internal table of framework attribute IDs, so adding attributes the original
manifest never used (e.g. android:debuggable) works and is correctly typed.

pyaxml's ET is lxml.etree; android attributes are addressed in Clark notation
("{<ns>}name").
"""
import zipfile

import pyaxml
from pyaxml import ET

ANDROID_NS = "http://schemas.android.com/apk/res/android"
A = "{%s}" % ANDROID_NS  # Clark-notation prefix for android: attributes

# App-level boolean flags exposed as tri-state (unset / true / false).
APP_BOOL_FLAGS = ["debuggable", "allowBackup", "usesCleartextTraffic",
                  "extractNativeLibs", "largeHeap"]


def _a(name):
    return A + name


# ── Decode ────────────────────────────────────────────────────────────────

def read_manifest(apk_path):
    """Return the raw binary AndroidManifest.xml bytes from an APK.

    Raises KeyError if the entry is missing and zipfile.BadZipFile if the file
    isn't a zip at all.
    """
    with zipfile.ZipFile(apk_path) as z:
        return z.read("AndroidManifest.xml")


def _decode(raw):
    return pyaxml.AXML.from_axml(raw).to_xml()


def _is_resource_ref(val):
    return isinstance(val, str) and val.startswith("@")


def _is_launcher(act):
    """True if the activity has a MAIN/LAUNCHER intent-filter."""
    for intf in act.findall("intent-filter"):
        has_main = any(a.get(_a("name")) == "android.intent.action.MAIN"
                       for a in intf.findall("action"))
        has_launcher = any(c.get(_a("name")) == "android.intent.category.LAUNCHER"
                           for c in intf.findall("category"))
        if has_main and has_launcher:
            return True
    return False


def parse_model(raw):
    """Decode binary AXML into an editable, JSON-able model."""
    root = _decode(raw)
    app = root.find("application")
    usesdk = root.find("uses-sdk")

    # label: editable only when it's a literal string (not a @resource ref)
    label_val = app.get(_a("label")) if app is not None else None
    label = {
        "value": label_val,
        "editable": label_val is not None and not _is_resource_ref(label_val),
    }

    flags = {}
    for f in APP_BOOL_FLAGS:
        v = app.get(_a(f)) if app is not None else None
        flags[f] = (None if v is None else str(v).lower() == "true")

    permissions = sorted({
        p.get(_a("name")) for p in root.findall("uses-permission")
        if p.get(_a("name"))
    })

    activities = []
    for act in root.findall("application/activity"):
        name = act.get(_a("name"))
        if not name:
            continue
        exported = act.get(_a("exported"))
        activities.append({
            "name": name,
            "exported": (None if exported is None else str(exported).lower() == "true"),
            "launchable": _is_launcher(act),
        })

    return {
        "package": root.get("package"),
        "versionCode": root.get(_a("versionCode")),
        "versionName": root.get(_a("versionName")),
        "minSdkVersion": usesdk.get(_a("minSdkVersion")) if usesdk is not None else None,
        "targetSdkVersion": usesdk.get(_a("targetSdkVersion")) if usesdk is not None else None,
        "label": label,
        "flags": flags,
        "permissions": permissions,
        "activities": activities,
    }


# ── Edit ──────────────────────────────────────────────────────────────────

def _set_or_remove(el, attr, value):
    """Set android:attr to value, or remove it when value is None/empty."""
    key = _a(attr)
    if value is None or value == "":
        if key in el.attrib:
            del el.attrib[key]
    else:
        el.set(key, str(value))


def _set_bool(el, attr, state):
    """state True/False sets the attr to true/false; None removes it."""
    key = _a(attr)
    if state is None:
        if key in el.attrib:
            del el.attrib[key]
    else:
        el.set(key, "true" if state else "false")


def _apply_launchable(act, want):
    is_l = _is_launcher(act)
    if want and not is_l:
        intf = ET.SubElement(act, "intent-filter")
        ET.SubElement(intf, "action").set(_a("name"), "android.intent.action.MAIN")
        ET.SubElement(intf, "category").set(_a("name"), "android.intent.category.LAUNCHER")
        # launcher activities must be exported on Android 12+
        act.set(_a("exported"), "true")
    elif not want and is_l:
        for intf in list(act.findall("intent-filter")):
            has_main = any(a.get(_a("name")) == "android.intent.action.MAIN"
                           for a in intf.findall("action"))
            has_launcher = any(c.get(_a("name")) == "android.intent.category.LAUNCHER"
                               for c in intf.findall("category"))
            if has_main and has_launcher:
                act.remove(intf)


# Component elements whose android:name is resolved relative to the manifest
# package (an Android `ComponentName`).
_COMPONENT_TAGS = ("activity", "activity-alias", "service", "receiver", "provider")


def _abs_class(old_pkg, name):
    """Expand a relative component/class name against the original package.

    Android resolves ".Foo" as <pkg>.Foo and a bare "Foo" as <pkg>.Foo. If the
    package is renamed, those relative names would re-resolve against the *new*
    package and point at classes that don't exist (crash on launch). Anchoring
    them to the original package keeps them pointing at the real bytecode.
    """
    if not name or not old_pkg:
        return name
    if name.startswith("."):
        return old_pkg + name
    if "." not in name:
        return old_pkg + "." + name
    return name


def _absolutize_components(root, old_pkg):
    """Rewrite relative class names to absolute (used when the package changes)."""
    app = root.find("application")
    if app is None:
        return
    name_key = _a("name")
    target_key = _a("targetActivity")

    app_name = app.get(name_key)
    if app_name is not None:
        app.set(name_key, _abs_class(old_pkg, app_name))

    for tag in _COMPONENT_TAGS:
        for el in app.findall(tag):
            nm = el.get(name_key)
            if nm is not None:
                el.set(name_key, _abs_class(old_pkg, nm))
            ta = el.get(target_key)
            if ta is not None:
                el.set(target_key, _abs_class(old_pkg, ta))


def apply_model(root, edits):
    """Mutate the lxml tree toward the desired-state `edits` model."""
    old_pkg = root.get("package")
    new_pkg = str(edits["package"]).strip() if edits.get("package") else None
    if new_pkg:
        root.set("package", new_pkg)

    _set_or_remove(root, "versionCode", edits.get("versionCode"))
    _set_or_remove(root, "versionName", edits.get("versionName"))

    # uses-sdk (create the element if missing and an sdk value is provided)
    minsdk = edits.get("minSdkVersion")
    tgtsdk = edits.get("targetSdkVersion")
    usesdk = root.find("uses-sdk")
    if (minsdk or tgtsdk) and usesdk is None:
        usesdk = ET.SubElement(root, "uses-sdk")
    if usesdk is not None:
        _set_or_remove(usesdk, "minSdkVersion", minsdk)
        _set_or_remove(usesdk, "targetSdkVersion", tgtsdk)

    app = root.find("application")

    # label (only when the caller marked it editable)
    label = edits.get("label")
    if app is not None and isinstance(label, dict) and label.get("editable"):
        _set_or_remove(app, "label", label.get("value"))

    # app-level boolean flags
    if app is not None:
        for f, state in (edits.get("flags") or {}).items():
            if f in APP_BOOL_FLAGS:
                _set_bool(app, f, state)

    # permissions: reconcile to the desired set
    desired_perms = {p for p in (edits.get("permissions") or []) if p}
    current = {p.get(_a("name")): p for p in root.findall("uses-permission")}
    for nm, el in current.items():
        if nm not in desired_perms:
            root.remove(el)
    for nm in desired_perms:
        if nm not in current:
            ET.SubElement(root, "uses-permission").set(_a("name"), nm)

    # activities: reconcile by name (omitted == deleted)
    if app is not None and edits.get("activities") is not None:
        desired = {a["name"]: a for a in edits["activities"] if a.get("name")}
        for act in list(app.findall("activity")):
            nm = act.get(_a("name"))
            if nm not in desired:
                app.remove(act)
                continue
            spec = desired[nm]
            _set_bool(act, "exported", spec.get("exported"))
            _apply_launchable(act, bool(spec.get("launchable")))

    # A package rename re-anchors relative component names; expand them to the
    # original package so they keep pointing at the real (unchanged) bytecode.
    if new_pkg and new_pkg != old_pkg:
        _absolutize_components(root, old_pkg)

    return root


def build_manifest(raw, edits):
    """Apply edits to the original manifest and return (packed_bytes, warnings)."""
    root = _decode(raw)
    apply_model(root, edits)
    axml = pyaxml.AXML()
    axml.from_xml(root)
    packed = axml.pack()
    return packed, _verify(packed, edits)


def _verify(packed, edits):
    """Re-decode the rebuilt manifest and confirm key edits survived.

    A safety net: pyaxml is reliable, but this turns any silent drop on an
    unusual APK into an honest warning rather than a wrong download.
    """
    warnings = []
    try:
        root = _decode(packed)
    except Exception as e:  # noqa: BLE001 - report, don't crash the build
        return ["Could not re-parse the rebuilt manifest (%s)." % e]

    if edits.get("package") and root.get("package") != str(edits["package"]).strip():
        warnings.append("Package name may not have applied correctly.")

    got_perms = {p.get(_a("name")) for p in root.findall("uses-permission")}
    for nm in (edits.get("permissions") or []):
        if nm and nm not in got_perms:
            warnings.append("Permission '%s' could not be added." % nm)

    for key in ("versionName", "versionCode"):
        want = edits.get(key)
        if want and root.get(_a(key)) != str(want):
            warnings.append("%s may not have applied correctly." % key)

    return warnings
