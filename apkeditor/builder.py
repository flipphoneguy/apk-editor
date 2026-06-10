"""Swap the edited manifest back into the APK, align, and sign.

Only AndroidManifest.xml is replaced; every other zip entry is copied through
byte-for-byte (we just drop the old v1 signature files, which apksigner
regenerates). Then `zipalign -p 4` and `apksigner` produce an installable APK.
"""
import os
import re
import shutil
import subprocess
import zipfile


def _tool(env, name):
    """Resolve an external tool: explicit env override, else PATH, else bare name."""
    return os.environ.get(env) or shutil.which(name) or name


ZIPALIGN = _tool("ZIPALIGN", "zipalign")
APKSIGNER = _tool("APKSIGNER", "apksigner")
KEYTOOL = _tool("KEYTOOL", "keytool")

# Old JAR-signature files; apksigner regenerates these on v1 signing.
_SIG_RE = re.compile(r"^META-INF/.*\.(SF|RSA|DSA|EC)$|^META-INF/MANIFEST\.MF$", re.I)


def _run(cmd):
    res = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    if res.returncode != 0:
        raise RuntimeError((res.stderr or res.stdout or "command failed").strip())
    return res


def swap_manifest(src_apk, manifest_bytes, out_apk):
    """Copy src_apk to out_apk, replacing AndroidManifest.xml and dropping
    stale v1 signature files."""
    with zipfile.ZipFile(src_apk) as zin, zipfile.ZipFile(out_apk, "w") as zout:
        for item in zin.infolist():
            if item.filename == "AndroidManifest.xml" or _SIG_RE.match(item.filename):
                continue
            zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
            zi.compress_type = item.compress_type
            zi.external_attr = item.external_attr
            zout.writestr(zi, zin.read(item.filename))
        mi = zipfile.ZipInfo("AndroidManifest.xml")
        mi.compress_type = zipfile.ZIP_DEFLATED
        zout.writestr(mi, manifest_bytes)


def align(in_apk, out_apk):
    _run([ZIPALIGN, "-p", "-f", "4", in_apk, out_apk])


def ensure_server_keystore(path, storepass, keypass, alias):
    """Lazily generate the server signing keystore if it doesn't exist."""
    if os.path.exists(path):
        return path
    os.makedirs(os.path.dirname(path), exist_ok=True)
    _run([KEYTOOL, "-genkeypair", "-keystore", path, "-alias", alias,
          "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
          "-storepass", storepass, "-keypass", keypass,
          "-dname", "CN=apkeditor, O=flipphoneguy tools"])
    return path


def sign(in_apk, out_apk, keystore, storepass, keypass, alias=None):
    cmd = [APKSIGNER, "sign", "--ks", keystore,
           "--ks-pass", "pass:" + storepass,
           "--key-pass", "pass:" + keypass,
           "--out", out_apk]
    if alias:
        cmd += ["--ks-key-alias", alias]
    cmd.append(in_apk)
    _run(cmd)


def build_signed_apk(src_apk, manifest_bytes, work_dir, keystore,
                     storepass, keypass, alias, out_name):
    """Full pipeline: swap manifest -> align -> sign. Returns the output path."""
    unsigned = os.path.join(work_dir, "_unsigned.apk")
    aligned = os.path.join(work_dir, "_aligned.apk")
    out_apk = os.path.join(work_dir, out_name)

    swap_manifest(src_apk, manifest_bytes, unsigned)
    align(unsigned, aligned)
    sign(aligned, out_apk, keystore, storepass, keypass, alias)

    for tmp in (unsigned, aligned):
        try:
            os.remove(tmp)
        except OSError:
            pass
    return out_apk
