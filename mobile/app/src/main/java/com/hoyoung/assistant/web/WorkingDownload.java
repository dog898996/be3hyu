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

/** Download generated files to private storage. Auth cookies never cross their origin. */
public final class WorkingDownload {
    private WorkingDownload() { }
    public static boolean allowed(Uri uri) {
        if (uri == null || !"https".equals(uri.getScheme()) || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
        String host = uri.getHost();
        return "chatgpt.com".equals(host) || "files.oaiusercontent.com".equals(host)
            || "oaiusercontent.com".equals(host) || (host != null && host.endsWith(".oaiusercontent.com"));
    }
    public static JSONObject save(String rawUrl, String disposition, String mime, InternalFiles files) throws Exception {
        URL current = new URL(rawUrl);
        for (int redirects = 0; redirects <= 5; redirects++) {
            Uri uri = Uri.parse(current.toString());
            if (!allowed(uri)) throw new IllegalArgumentException("Unsupported file origin");
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setConnectTimeout(20000); connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(false);
            if ("chatgpt.com".equals(uri.getHost())) {
                String cookies = CookieManager.getInstance().getCookie(current.toString());
                if (cookies != null) connection.setRequestProperty("Cookie", cookies);
            }
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status <= 399) {
                    String next = connection.getHeaderField("Location");
                    if (next == null) throw new java.io.IOException("Missing redirect");
                    current = new URL(current, next); continue;
                }
                if (status != 200 || connection.getContentLengthLong() > InternalFiles.MAX_FILE_BYTES) throw new java.io.IOException("File response not available or too large");
                String name = URLUtil.guessFileName(current.toString(), disposition, mime);
                name = name.replaceAll("[^\\p{L}\\p{N}._ -]", "_");
                if (name.length() > 160) name = name.substring(0, 160);
                String id = "downloads/" + UUID.randomUUID() + "-" + name;
                try (InputStream input = connection.getInputStream()) { return files.writeFile(id, input); }
            } finally { connection.disconnect(); }
        }
        throw new java.io.IOException("Too many redirects");
    }
}
