package com.flipphoneguy.apkeditor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Talks to the live APK Editor backend
 * ({@code https://tools.flipphoneguy.duckdns.org/apkeditor}). All methods block
 * and are meant to be called off the main thread; server-side error messages are
 * surfaced as the exception message.
 */
public class ApiClient {

    public static final String BASE = "https://tools.flipphoneguy.duckdns.org";

    private static final String APK_MIME = "application/vnd.android.package-archive";

    // ── Results ─────────────────────────────────────────────────────────────

    public static class UploadResult {
        public String token;
        public String filename;
        public JSONObject model;
    }

    public static class BuildResult {
        public String downloadPath;
        public String filename;
        public long size;
        public double elapsed = -1;
        public final List<String> warnings = new ArrayList<>();
    }

    /** Optional user keystore for signing. */
    public static class Keystore {
        public MultipartBody.StreamOpener opener;
        public long size;
        public String filename;
        public String storePass;
        public String keyPass;
        public String alias;
    }

    // ── Upload ──────────────────────────────────────────────────────────────

    public static UploadResult upload(MultipartBody.StreamOpener apk, long size,
                                      String filename, MultipartBody.Progress p)
            throws IOException, JSONException {
        MultipartBody body = new MultipartBody()
            .file("apk", filename, APK_MIME, apk, size);

        HttpURLConnection c = post(BASE + "/apkeditor/upload");
        c.setFixedLengthStreamingMode(body.contentLength());
        c.setRequestProperty("Content-Type", body.contentType());

        OutputStream out = new BufferedOutputStream(c.getOutputStream());
        body.writeTo(out, p);
        out.close();

        JSONObject j = readJson(c);
        UploadResult r = new UploadResult();
        r.token = j.optString("token", null);
        r.filename = j.optString("filename", filename);
        r.model = j.getJSONObject("model");
        return r;
    }

    // ── Build ───────────────────────────────────────────────────────────────

    public static BuildResult build(String token, JSONObject edits, Keystore ks)
            throws IOException, JSONException {
        MultipartBody body = new MultipartBody()
            .field("token", token)
            .field("model", edits.toString());
        if (ks != null && ks.opener != null) {
            body.file("keystore", ks.filename == null ? "user.keystore" : ks.filename,
                "application/octet-stream", ks.opener, ks.size);
            body.field("ks_pass", ks.storePass == null ? "" : ks.storePass);
            body.field("key_pass", ks.keyPass == null ? "" : ks.keyPass);
            body.field("ks_alias", ks.alias == null ? "" : ks.alias);
        }

        HttpURLConnection c = post(BASE + "/apkeditor/build");
        c.setFixedLengthStreamingMode(body.contentLength());
        c.setRequestProperty("Content-Type", body.contentType());
        c.setReadTimeout(180000);   // signing a large APK can take a while

        OutputStream out = new BufferedOutputStream(c.getOutputStream());
        body.writeTo(out, null);
        out.close();

        JSONObject j = readJson(c);
        BuildResult r = new BuildResult();
        r.downloadPath = j.getString("download");
        r.filename = j.optString("filename", "edited.apk");
        r.size = j.optLong("size", -1);
        r.elapsed = j.has("elapsed") ? j.optDouble("elapsed", -1) : -1;
        JSONArray w = j.optJSONArray("warnings");
        if (w != null) {
            for (int i = 0; i < w.length(); i++) {
                String s = w.optString(i, null);
                if (s != null) r.warnings.add(s);
            }
        }
        return r;
    }

    // ── Download ────────────────────────────────────────────────────────────

    /** Stream the built APK from {@code downloadPath} into {@code dest}. */
    public static void download(String downloadPath, OutputStream dest,
                                long expectedSize, MultipartBody.Progress p)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection)
            new URL(BASE + downloadPath).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(180000);
        c.setInstanceFollowRedirects(true);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("Download failed (HTTP " + code + ")");
            long total = c.getContentLengthLong();
            if (total <= 0) total = expectedSize;
            InputStream in = c.getInputStream();
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                dest.write(buf, 0, n);
                done += n;
                if (p != null) p.onProgress(done, total);
            }
            dest.flush();
        } finally {
            c.disconnect();
        }
    }

    // ── Cancel ──────────────────────────────────────────────────────────────

    public static void cancel(String token) {
        if (token == null) return;
        try {
            HttpURLConnection c = post(BASE + "/apkeditor/cancel/" + token);
            c.setFixedLengthStreamingMode(0);
            c.getOutputStream().close();
            c.getResponseCode();
            c.disconnect();
        } catch (IOException ignored) {}
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    private static HttpURLConnection post(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept", "application/json");
        return c;
    }

    /** Read the response and parse JSON, raising the server's error message. */
    private static JSONObject readJson(HttpURLConnection c)
            throws IOException, JSONException {
        int code;
        try {
            code = c.getResponseCode();
        } finally {
            // nothing; disconnect handled below
        }
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = in == null ? "" : readAll(in);
        c.disconnect();

        JSONObject j;
        try {
            j = new JSONObject(text);
        } catch (JSONException e) {
            throw new IOException(code >= 400
                ? "Server error (HTTP " + code + ")"
                : "Unexpected server response");
        }
        if (code != 200) {
            throw new IOException(j.optString("error", "Request failed (HTTP " + code + ")"));
        }
        return j;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        in.close();
        return bos.toString(StandardCharsets.UTF_8.name());
    }
}
