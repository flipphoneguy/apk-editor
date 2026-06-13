package com.flipphoneguy.apkeditor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal multipart/form-data writer with a precomputable content length (so we
 * can use fixed-length streaming for large APK uploads) and a byte-level
 * progress callback. No external dependency — just writes the RFC 7578 framing
 * to an OutputStream.
 */
public class MultipartBody {

    public interface Progress { void onProgress(long done, long total); }

    /** Opens a fresh stream for a file part at write time. */
    public interface StreamOpener { InputStream open() throws IOException; }

    private static final byte[] CRLF = {'\r', '\n'};

    private final String boundary =
        "----apkeditor" + Long.toHexString(System.nanoTime());
    private final List<Part> parts = new ArrayList<>();

    private abstract static class Part {
        byte[] header;
        abstract long bodyLength();
        abstract void writeBody(OutputStream out, long[] done, long total,
                                Progress p) throws IOException;
        long length() { return header.length + bodyLength() + CRLF.length; }
    }

    public MultipartBody field(String name, final String value) {
        Part part = new Part() {
            final byte[] body = (value == null ? "" : value)
                .getBytes(StandardCharsets.UTF_8);
            long bodyLength() { return body.length; }
            void writeBody(OutputStream out, long[] done, long total, Progress p)
                    throws IOException {
                out.write(body);
                done[0] += body.length;
            }
        };
        part.header = ("--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8);
        parts.add(part);
        return this;
    }

    public MultipartBody file(String name, String filename, String contentType,
                              final StreamOpener opener, final long size) {
        Part part = new Part() {
            long bodyLength() { return size; }
            void writeBody(OutputStream out, long[] done, long total, Progress p)
                    throws IOException {
                InputStream in = opener.open();
                try {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        done[0] += n;
                        if (p != null) p.onProgress(done[0], total);
                    }
                } finally {
                    try { in.close(); } catch (IOException ignored) {}
                }
            }
        };
        part.header = ("--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"" + name
            + "\"; filename=\"" + filename + "\"\r\n"
            + "Content-Type: " + contentType + "\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8);
        parts.add(part);
        return this;
    }

    public String contentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    public long contentLength() {
        long total = 0;
        for (Part p : parts) total += p.length();
        total += ("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8).length;
        return total;
    }

    public void writeTo(OutputStream out, Progress progress) throws IOException {
        long total = contentLength();
        long[] done = {0};
        for (Part p : parts) {
            out.write(p.header);
            done[0] += p.header.length;
            p.writeBody(out, done, total, progress);
            out.write(CRLF);
            done[0] += CRLF.length;
            if (progress != null) progress.onProgress(done[0], total);
        }
        byte[] end = ("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        out.write(end);
        out.flush();
        if (progress != null) progress.onProgress(total, total);
    }
}
