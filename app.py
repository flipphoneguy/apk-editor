"""Standalone APK manifest editor (Flask).

Upload an APK, edit its AndroidManifest.xml (package, version, SDK levels, app
flags, permissions, activities) through a UI, and download a re-signed copy. The
APK is never fully unpacked — only AndroidManifest.xml is decoded/re-encoded
in-process with pyaxml, then the zip entry is swapped, zipaligned and signed
with apksigner. See apkeditor/ for the engine.

Configuration (all optional, via environment variables):
  SECRET_KEY          Flask session secret (a random one is used if unset)
  UPLOAD_FOLDER       where jobs + the signing keystore live (default ./storage)
  KEYSTORE_PASSWORD   password for the auto-generated server keystore
  KEY_ALIAS           alias for the auto-generated server key
  KEYSTORE_PATH       override the server keystore path
  ZIPALIGN/APKSIGNER/KEYTOOL  override tool paths (else found on PATH)

External tools required on PATH: zipalign, apksigner, keytool (a JDK), plus the
Android build-tools. On Debian/Ubuntu: apt install zipalign apksigner default-jdk
"""
import json
import os
import secrets
import time
import zipfile
from datetime import date

from flask import (Flask, render_template, request, jsonify, abort,
                   Response, stream_with_context)
from werkzeug.utils import secure_filename

from apkeditor import manifest as mf
from apkeditor import builder
from apkeditor import jobs

# Per-IP rate limiting for heavy actions (upload / build). Backed by the
# filesystem (jobs.rate_ok) so the limit is shared across gunicorn workers.
_RL_MAX = int(os.environ.get("RATE_LIMIT_MAX", "40"))
_RL_WINDOW = int(os.environ.get("RATE_LIMIT_WINDOW", "600"))  # seconds


def _client_ip():
    return request.headers.get("X-Real-IP", request.remote_addr) or "?"


def create_app():
    base = os.path.dirname(os.path.abspath(__file__))
    app = Flask(__name__,
                template_folder=os.path.join(base, "templates"),
                static_folder=os.path.join(base, "static"),
                static_url_path="/static")
    app.secret_key = os.environ.get("SECRET_KEY", secrets.token_hex(16))

    storage = os.environ.get("UPLOAD_FOLDER", os.path.join(base, "storage"))
    app.config["STORAGE"] = storage
    app.config["MAX_CONTENT_LENGTH"] = int(os.environ.get("MAX_UPLOAD_MB", "200")) * 1024 * 1024
    os.makedirs(storage, exist_ok=True)

    # Signing config (server keystore; lazily generated on first build).
    app.config["KS_PATH"] = os.environ.get(
        "KEYSTORE_PATH", os.path.join(storage, "signing.keystore"))
    app.config["KS_PASS"] = os.environ.get("KEYSTORE_PASSWORD", "apkeditor")
    app.config["KS_ALIAS"] = os.environ.get("KEY_ALIAS", "apkeditor")

    jobs.start_reaper(storage)

    @app.context_processor
    def inject_globals():
        return {"current_year": date.today().year}

    @app.route("/")
    def index():
        return render_template("apkeditor.html")

    @app.route("/apkeditor/upload", methods=["POST"])
    def upload():
        if not jobs.rate_ok(storage, _client_ip(), _RL_MAX, _RL_WINDOW):
            return jsonify(error="Rate limit reached. Please wait a few minutes."), 429

        f = request.files.get("apk")
        if not f or f.filename == "":
            return jsonify(error="No file uploaded."), 400
        if not f.filename.lower().endswith(".apk"):
            return jsonify(error="That doesn't look like an APK."), 400

        token, d = jobs.new_job(storage)
        orig = os.path.join(d, "orig.apk")
        f.save(orig)

        try:
            raw = mf.read_manifest(orig)
        except KeyError:
            jobs.delete_job(storage, token)
            return jsonify(error="No AndroidManifest.xml found — not a valid APK."), 400
        except zipfile.BadZipFile:
            jobs.delete_job(storage, token)
            return jsonify(error="File is not a valid APK/zip."), 400

        try:
            model = mf.parse_model(raw)
        except Exception as e:  # noqa: BLE001
            jobs.delete_job(storage, token)
            return jsonify(error="Could not parse the manifest: %s" % e), 400

        meta = {"filename": secure_filename(f.filename) or "app.apk",
                "package": model.get("package")}
        with open(os.path.join(d, "meta.json"), "w") as m:
            json.dump(meta, m)

        return jsonify(token=token, model=model, filename=meta["filename"])

    @app.route("/apkeditor/build", methods=["POST"])
    def build():
        if not jobs.rate_ok(storage, _client_ip(), _RL_MAX, _RL_WINDOW):
            return jsonify(error="Rate limit reached. Please wait a few minutes."), 429

        token = request.form.get("token", "")
        d = jobs.job_dir(storage, token)
        if not d:
            return jsonify(error="Session expired or invalid. Please re-upload."), 410
        orig = os.path.join(d, "orig.apk")
        if not os.path.isfile(orig):
            return jsonify(error="Uploaded APK is gone. Please re-upload."), 410

        try:
            edits = json.loads(request.form.get("model", "{}"))
        except ValueError:
            return jsonify(error="Bad edit payload."), 400

        try:
            with open(os.path.join(d, "meta.json")) as m:
                meta = json.load(m)
        except OSError:
            meta = {"filename": "app.apk"}
        stem = os.path.splitext(meta.get("filename", "app.apk"))[0] or "app"
        out_name = stem + "-edited.apk"

        try:
            raw = mf.read_manifest(orig)
            packed, warnings = mf.build_manifest(raw, edits)
        except Exception as e:  # noqa: BLE001
            return jsonify(error="Failed to rebuild manifest: %s" % e), 500

        # Signing: optional user keystore, else the server keystore.
        ks_file = request.files.get("keystore")
        try:
            if ks_file and ks_file.filename:
                ks = os.path.join(d, "user.keystore")
                ks_file.save(ks)
                storepass = request.form.get("ks_pass", "")
                keypass = request.form.get("key_pass") or storepass
                alias = request.form.get("ks_alias") or None
            else:
                builder.ensure_server_keystore(
                    app.config["KS_PATH"], app.config["KS_PASS"],
                    app.config["KS_PASS"], app.config["KS_ALIAS"])
                ks = app.config["KS_PATH"]
                storepass = keypass = app.config["KS_PASS"]
                alias = app.config["KS_ALIAS"]
        except Exception as e:  # noqa: BLE001
            return jsonify(error="Keystore error: %s" % e), 400

        t0 = time.time()
        try:
            out_apk = builder.build_signed_apk(
                orig, packed, d, ks, storepass, keypass, alias, out_name)
        except Exception as e:  # noqa: BLE001
            return jsonify(error="Signing failed: %s" % e), 500
        elapsed = time.time() - t0

        with open(os.path.join(d, "out.txt"), "w") as o:
            o.write(out_name)

        return jsonify(
            download="/apkeditor/download/%s" % token,
            filename=out_name,
            size=os.path.getsize(out_apk),
            warnings=warnings,
            elapsed=round(elapsed, 2),
        )

    @app.route("/apkeditor/download/<token>")
    def download(token):
        d = jobs.job_dir(storage, token)
        if not d:
            abort(410)
        try:
            with open(os.path.join(d, "out.txt")) as o:
                out_name = o.read().strip()
        except OSError:
            abort(404)
        path = os.path.join(d, out_name)
        if not os.path.isfile(path):
            abort(404)
        size = os.path.getsize(path)

        def generate():
            try:
                with open(path, "rb") as fh:
                    while True:
                        chunk = fh.read(65536)
                        if not chunk:
                            break
                        yield chunk
            finally:
                jobs.delete_job(storage, token)

        resp = Response(stream_with_context(generate()),
                        mimetype="application/vnd.android.package-archive")
        resp.headers["Content-Length"] = str(size)
        resp.headers["Content-Disposition"] = 'attachment; filename="%s"' % out_name
        return resp

    @app.route("/apkeditor/cancel/<token>", methods=["POST"])
    def cancel(token):
        jobs.delete_job(storage, token)
        return jsonify(ok=True)

    @app.errorhandler(413)
    def too_large(e):
        return jsonify(error="APK too large."), 413

    return app


if __name__ == "__main__":
    create_app().run(host="0.0.0.0", port=int(os.environ.get("PORT", "5003")))
