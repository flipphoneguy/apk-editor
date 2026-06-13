package com.flipphoneguy.apkeditor;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ApkInstaller {

    public static class LatestRelease {
        public String tag;
        public String apkUrl;
    }

    public static LatestRelease checkLatest(String repo) throws Exception {
        String body = fetchApi(
            "https://api.github.com/repos/" + repo + "/releases/latest");

        Matcher tagM = Pattern.compile(
            "\"tag_name\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        Matcher urlM = Pattern.compile(
            "\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.apk)\"").matcher(body);

        LatestRelease release = new LatestRelease();
        if (tagM.find()) release.tag = tagM.group(1);
        if (urlM.find()) release.apkUrl = urlM.group(1);
        return release;
    }

    public static int compareVersions(String a, String b) {
        String cleanA = a == null ? "" : a.replaceFirst("^[vV]", "");
        String cleanB = b == null ? "" : b.replaceFirst("^[vV]", "");
        String[] partsA = cleanA.split("\\.");
        String[] partsB = cleanB.split("\\.");
        int len = Math.max(partsA.length, partsB.length);
        for (int i = 0; i < len; i++) {
            int va = i < partsA.length ? parseIntSafe(partsA[i]) : 0;
            int vb = i < partsB.length ? parseIntSafe(partsB[i]) : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    public static File download(Context ctx, String apkUrl) throws Exception {
        File out = new File(ctx.getCacheDir(), "update.apk");
        HttpURLConnection conn = (HttpURLConnection)
            new URL(apkUrl).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);

        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
        } finally {
            conn.disconnect();
        }
        return out;
    }

    public static void installRoot(File apk) throws Exception {
        String tmp = "/data/local/tmp/update.apk";
        String cmd = "cp " + apk.getAbsolutePath() + " " + tmp
            + " && chmod 644 " + tmp
            + " && pm install -r " + tmp
            + " && rm -f " + tmp;

        Process proc = Runtime.getRuntime().exec(
            new String[]{"su", "-c", cmd});
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(proc.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null)
            sb.append(line).append('\n');
        int exit = proc.waitFor();
        String output = sb.toString();

        apk.delete();

        if (exit != 0 || !output.contains("Success"))
            throw new Exception("pm install failed: " + output.trim());
    }

    public static void installSystem(Context ctx, File apk) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        if (Build.VERSION.SDK_INT >= 24) {
            Uri uri = ApkFileProvider.getUri(ctx, apk);
            intent.setDataAndType(uri,
                "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            intent.setDataAndType(Uri.fromFile(apk),
                "application/vnd.android.package-archive");
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
    }

    private static String fetchApi(String apiUrl) throws IOException {
        HttpURLConnection c = (HttpURLConnection)
            new URL(apiUrl).openConnection();
        c.setRequestProperty("User-Agent", "AppTemplate/1.0");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        try {
            int code = c.getResponseCode();
            if (code != 200)
                throw new IOException("GitHub API HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            in.close();
            return bos.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }
}
