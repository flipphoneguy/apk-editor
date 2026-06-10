# APK Editor

Live: https://tools.flipphoneguy.duckdns.org/apkeditor

A web tool for editing an Android APK's manifest. Upload an APK, change its
package name, version, SDK levels, app flags, permissions and activities from a
form, and download a re-signed copy that installs on a device.

It does not unpack the whole APK. It reads the single binary
`AndroidManifest.xml` out of the zip, edits and re-encodes just that file, swaps
it back into the archive, then zipaligns and signs. Most edits take a couple of
seconds.

## Features

- Edit package id, version code/name, min/target SDK, app flags (`debuggable`,
  `allowBackup`, ...), permissions, and activities (delete, toggle `exported`,
  make launchable).
- No `aapt`/`aapt2` and no full repack; the manifest is handled in Python.
- Re-signs with `apksigner` (v1/v2/v3) and verifies the output.
- Uses one stable signing key, so re-editing the same app produces a matching
  signature and installs as an update. You can also upload your own keystore.
- Uploaded files are deleted on download, on cancel, and after a timeout.

## How it works

1. Upload the APK. The server reads `AndroidManifest.xml` and returns a JSON
   model of it.
2. The browser edits that model.
3. On build, the edits are applied to the binary manifest with
   [pyaxml](https://gitlab.com/MadSquirrels/mobile/pyaxml), the zip entry is
   replaced (stale signature files dropped), then `zipalign` and `apksigner` run.
4. The signed APK is streamed back and the upload is deleted.

After building, the new manifest is re-decoded and any edit that did not apply is
reported as a warning.

## What it can and can't do

Can: change package name, version, min/target SDK, app boolean flags,
permissions, and activities.

Can't: change anything outside the manifest (code, resources, icons, layouts).
Some specifics:

- A package rename rewrites the manifest only, not `resources.arsc` or bytecode.
  It lets many apps install alongside the original but is not a guaranteed full
  clone. Apps that use their package id at runtime (FileProvider authorities,
  account types, hardcoded `APPLICATION_ID`, and so on) may break.
- An app label that points to a string resource (`@string/...`) can't be changed
  here; only literal labels can.
- Re-signing changes the signature, so an edited APK installs as a separate or
  replacement app and won't get Play Store updates.

## Requirements

- Python 3.10+
- `zipalign`, `apksigner` (Android build-tools) and `keytool` (a JDK) on `PATH`.

On Debian/Ubuntu:

    sudo apt install zipalign apksigner default-jdk

## Running

    git clone https://github.com/flipphoneguy/apk-editor
    cd apk-editor
    python3 -m venv venv && . venv/bin/activate
    pip install -r requirements.txt
    python app.py            # http://localhost:5003

Under gunicorn behind a reverse proxy:

    gunicorn --workers 3 --timeout 120 --bind 127.0.0.1:5003 wsgi:app

Example `systemd` and `nginx` configs are in [deploy/](deploy/).

## Configuration

All optional, via environment variables:

| Variable | Default | Purpose |
|---|---|---|
| `SECRET_KEY` | random per run | Flask session secret |
| `UPLOAD_FOLDER` | `./storage` | where jobs and the signing keystore live |
| `KEYSTORE_PASSWORD` | `apkeditor` | password for the generated server keystore |
| `KEY_ALIAS` | `apkeditor` | alias for the generated server key |
| `KEYSTORE_PATH` | `$UPLOAD_FOLDER/signing.keystore` | server keystore path |
| `MAX_UPLOAD_MB` | `200` | upload size cap |
| `RATE_LIMIT_MAX` / `RATE_LIMIT_WINDOW` | `40` / `600` | per-IP actions per window (seconds) |
| `ZIPALIGN` / `APKSIGNER` / `KEYTOOL` | found on `PATH` | override tool paths |

The server keystore is generated on first build and then reused, so persist
`UPLOAD_FOLDER` across restarts to keep the signature stable. Set a real
`KEYSTORE_PASSWORD` in production.

## Notes

- Intended for personal or self-hosted use. Uploads are rate-limited per IP, but
  signing is CPU-bound, so put it behind your own auth if exposing it widely.
- Each upload is a directory under `UPLOAD_FOLDER/jobs/`, removed on download, on
  cancel, and by a background reaper after the timeout. A user-supplied keystore
  is stored in that directory and deleted with it.

## Layout

    app.py              Flask app and HTTP routes
    wsgi.py             gunicorn entry point
    apkeditor/
      manifest.py       decode / edit / re-encode the manifest (pyaxml)
      builder.py        zip-swap, zipalign, apksigner, keystore generation
      jobs.py           filesystem job dirs, TTL reaper, rate limiting
    templates/
      apkeditor.html    single-page UI (vanilla JS)
    deploy/             example systemd and nginx configs

## License

GPLv3. See [LICENSE](LICENSE).
