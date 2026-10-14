package com.hoyoung.assistant.web;

import android.net.Uri;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import com.hoyoung.assistant.store.InternalFiles;
import org.json.JSONObject;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;
import java.util.function.Function;

/** Download generated files to private storage. Auth cookies never cross their origin. */
public final class WorkingDownload {
    private WorkingDownload() { }
    public static boolean allowed(Uri uri) {
        if (uri == null || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) return false;
        String host = uri.getHost();
        int port = uri.getPort();
        return host != null && !host.isEmpty() && (port == -1 || port > 0 && port <= 65535);
    }
    public static JSONObject save(String rawUrl, String disposition, String mime, InternalFiles files) throws Exception {
        return save(rawUrl, disposition, mime, files, null);
    }
    public static JSONObject save(String rawUrl, String disposition, String mime, InternalFiles files, String browserUserAgent) throws Exception {
        return save(rawUrl, disposition, mime, files, browserUserAgent,
                url -> (HttpURLConnection) url.openConnection(), url -> CookieManager.getInstance().getCookie(url));
    }

    interface Connections { HttpURLConnection open(URL url) throws Exception; }

    /** Test seam keeps the actual redirect/cookie/write logic while avoiding real accounts or network. */
    static JSONObject save(String rawUrl, String disposition, String mime, InternalFiles files, String browserUserAgent,
            Connections connections, Function<String, String> cookiesForUrl) throws Exception {
        URL current = new URL(rawUrl);
        for (int redirects = 0; redirects <= 5; redirects++) {
            Uri uri = Uri.parse(current.toString());
            if (!allowed(uri)) throw new IllegalArgumentException("Use a valid HTTP or HTTPS file URL");
            HttpURLConnection connection = connections.open(current);
            connection.setConnectTimeout(20000); connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept-Encoding", "identity");
            // Reuse the actual WebView's default UA for this download; never invent
            // a desktop/browser identity to get past a blocked authentication flow.
            if (browserUserAgent != null && browserUserAgent.length() <= 512 && !browserUserAgent.contains("\r") && !browserUserAgent.contains("\n"))
                connection.setRequestProperty("User-Agent", browserUserAgent);
            // Lookup cookies for this exact URL every time. A redirect starts a new
            // connection: no Cookie header from the previous host is forwarded.
            String cookies = cookiesForUrl.apply(current.toString());
            if (cookies != null && !cookies.isEmpty() && !cookies.contains("\r") && !cookies.contains("\n"))
                connection.setRequestProperty("Cookie", cookies);
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status <= 399) {
                    String next = connection.getHeaderField("Location");
                    if (next == null) throw new java.io.IOException("Missing redirect");
                    current = new URL(current, next); continue;
                }
                if (status != 200 || connection.getContentLengthLong() > InternalFiles.MAX_FILE_BYTES) throw new java.io.IOException("File response not available or too large");
                String actualDisposition = connection.getHeaderField("Content-Disposition");
                String actualMime = connection.getContentType();
                if (!isDownloadResponse(actualMime, actualDisposition)) throw new java.io.IOException("Response is a web page, not a verified downloadable file");
                String name = URLUtil.guessFileName(current.toString(), actualDisposition != null ? actualDisposition : disposition, actualMime != null ? actualMime : mime);
                name = name.replaceAll("[^\\p{L}\\p{N}._ -]", "_");
                if (name.length() > 160) name = name.substring(0, 160);
                String id = "downloads/" + UUID.randomUUID() + "-" + name;
                try (InputStream input = new ExpectedLengthInputStream(connection.getInputStream(), connection.getContentLengthLong())) { return files.writeFile(id, input); }
            } finally { connection.disconnect(); }
        }
        throw new java.io.IOException("Too many redirects");
    }

    /** A sign-in/error HTML page must never be reported as a saved work artifact. */
    static boolean isDownloadResponse(String contentType, String contentDisposition) {
        String type = contentType == null ? "" : contentType.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
        if (!"text/html".equals(type) && !"application/xhtml+xml".equals(type)) return true;
        return contentDisposition != null && contentDisposition.trim().toLowerCase(java.util.Locale.ROOT).startsWith("attachment")
            && contentDisposition.toLowerCase(java.util.Locale.ROOT).contains("filename");
    }

    /** A premature EOF must fail the atomic write, rather than publish a short file. */
    static final class ExpectedLengthInputStream extends java.io.FilterInputStream {
        private final long expected;
        private long received;
        ExpectedLengthInputStream(InputStream input, long expected) { super(input); this.expected = expected; }
        private void count(int amount) throws java.io.IOException {
            if (amount >= 0) received += amount;
            if (expected >= 0 && (received > expected || amount < 0 && received != expected)) throw new java.io.IOException("Download ended before its declared content was complete");
        }
        @Override public int read() throws java.io.IOException { int value = in.read(); count(value < 0 ? -1 : 1); return value; }
        @Override public int read(byte[] bytes, int offset, int length) throws java.io.IOException { int amount = in.read(bytes, offset, length); count(amount); return amount; }
    }
}
