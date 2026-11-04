package com.omarchytv.home;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Panes the TV fetches itself, for data that has no server on the LAN to push it (e.g. market prices from a
 * server): omarchytv.json "remotePanes": {"url": "https://…/panes.json", "refreshSec": 300}. The body is a
 * JSON object with a "panes" array, text only (a URL cannot add shortcuts or actions), checked with the
 * limits in {@link #cleanPanes}. HTTPS, or plain http:// only to a literal private / link-local LAN address
 * (the web tiles' rule, {@link #urlAllowed}; e.g. Node-RED at home), no redirects, at most 64 KiB, fetched on a
 * background thread at most every refreshSec, only while the home screen is resumed; the last good answer is cached in the files dir, so the
 * panes survive a restart and go stale on their own ttl (dim after ttl, hidden after 2 x ttl).
 */
final class RemotePanes {

    static final int MAX_BYTES = 64 * 1024;
    private static final Pattern WIDGET_ID = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,47}$");
    private static final Set<String> LINE_TONES = new HashSet<>(Arrays.asList("up", "down"));
    private static volatile boolean fetching;
    private static volatile long lastAttemptMs;

    private RemotePanes() {
    }

    /**
     * omarchytv.json "remotePanes" url: https://, or http:// whose host is a literal private / link-local address
     * ({@link LanHttpDataSource#lanHost}, no DNS names, never the internet). At most 512 chars.
     */
    static boolean urlAllowed(String url) {
        if (url == null || url.length() > 512) return false;
        if (url.startsWith("https://")) return true;
        if (!url.startsWith("http://")) return false;
        try {
            return httpLan(new URL(url));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean httpLan(URL u) {
        return "http".equals(u.getProtocol()) && LanHttpDataSource.lanHost(u.getHost());
    }

    static File cacheFile(Context c) {
        return new File(c.getFilesDir(), "remote-panes.json");
    }

    /** Cached {url, fetchedAt (ms), panes} for this url, or null. */
    static JSONObject cached(Context c, String url) {
        if (url == null || url.isEmpty()) return null;
        File f = cacheFile(c);
        if (!f.isFile() || f.length() > 2L * MAX_BYTES) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            JSONObject o = new JSONObject(new String(readAll(in, 2 * MAX_BYTES), StandardCharsets.UTF_8));
            return url.equals(o.optString("url")) ? o : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Unix seconds the pane's data is from: its own "at", else when it was fetched. */
    static long paneAt(JSONObject cache, JSONObject pane) {
        long at = pane.optLong("at", 0L);
        return at > 0 ? at : cache.optLong("fetchedAt", 0L) / 1000L;
    }

    static void refreshIfStale(Context ctx, String url, int refreshSec, Runnable onDone) {
        if (url == null || url.isEmpty() || fetching) return;
        long now = System.currentTimeMillis();
        if (now - lastAttemptMs < refreshSec * 1000L) return;
        final Context c = ctx.getApplicationContext();
        JSONObject old = cached(c, url);
        if (lastAttemptMs == 0 && old != null && now - old.optLong("fetchedAt", 0L) < refreshSec * 1000L) {
            lastAttemptMs = old.optLong("fetchedAt", 0L);   // fresh from before a restart: don't fetch again yet
            return;
        }
        lastAttemptMs = now;
        fetching = true;
        new Thread(() -> {
            HttpURLConnection con = null;
            try {
                URL u = new URL(url);
                if (!"https".equals(u.getProtocol()) && !httpLan(u)) return;   // checked again at fetch time
                con = (HttpURLConnection) u.openConnection();
                con.setInstanceFollowRedirects(false);   // only the configured URL, never where it points to
                con.setConnectTimeout(8000);
                con.setReadTimeout(8000);
                con.setRequestProperty("Accept", "application/json");
                if (con.getResponseCode() != 200) return;
                byte[] body;
                try (InputStream in = con.getInputStream()) {
                    body = readAll(in, MAX_BYTES);
                }
                JSONArray in = new JSONObject(new String(body, StandardCharsets.UTF_8)).optJSONArray("panes");
                String[] why = new String[2];
                JSONArray panes = cleanPanes(in != null ? in : new JSONArray(), System.currentTimeMillis() / 1000L, why);
                if (panes == null) return;
                JSONObject out = new JSONObject().put("url", url).put("fetchedAt", System.currentTimeMillis()).put("panes", panes);
                File f = cacheFile(c), tmp = new File(f.getPath() + ".tmp");
                try (FileOutputStream o = new FileOutputStream(tmp)) {
                    o.write(out.toString().getBytes(StandardCharsets.UTF_8));
                }
                if (!tmp.renameTo(f)) tmp.delete();
            } catch (Exception ignored) {
            } finally {
                if (con != null) con.disconnect();
                fetching = false;
                new Handler(Looper.getMainLooper()).post(onDone);
            }
        }, "omarchytv-remote-panes").start();
    }

    /** Reads at most {@code max} bytes; more is an error (a pane file is small). */
    private static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (b.size() + n > max) throw new IOException("too large");
            b.write(buf, 0, n);
        }
        return b.toByteArray();
    }

    /** Printable text only (no control characters), trimmed, at most {@code max} code points. */
    private static String printable(String s, int max) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (int i = 0; i < s.length() && n < max; ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT && cp != 0x200D) continue;
            b.appendCodePoint(cp);
            n++;
        }
        return b.toString().trim();
    }

    /**
     * The fetched panes, checked and cleaned (text only, bounded): at most 8 panes, 8 lines each, with an id,
     * a title, optional per-line "tone" (up/down) and an optional "at" timestamp. Returns null with
     * {code, message} in {@code why}.
     */
    static JSONArray cleanPanes(JSONArray panesIn, long nowSec, String[] why) throws JSONException {
        if (panesIn.length() > 8) {
            why[0] = "too_many";
            why[1] = "8 panes";
            return null;
        }
        JSONArray panes = new JSONArray();
        for (int i = 0; i < panesIn.length(); i++) {
            JSONObject p = panesIn.optJSONObject(i);
            if (p == null || !WIDGET_ID.matcher(p.optString("id", "")).matches()) {
                why[0] = "bad_widgets";
                why[1] = "pane id";
                return null;
            }
            JSONArray lines = p.optJSONArray("lines");
            if (lines != null && lines.length() > 8) {
                why[0] = "too_many";
                why[1] = "8 lines per pane";
                return null;
            }
            JSONArray outLines = new JSONArray();
            for (int k = 0; lines != null && k < lines.length(); k++) {
                JSONObject l = lines.optJSONObject(k);
                if (l == null) continue;
                JSONObject line = new JSONObject().put("label", printable(l.optString("label", ""), 16))
                        .put("value", printable(l.optString("value", ""), 64));
                // Optional "tone": "up" | "down" colours the value (e.g. a price change); anything else is dropped.
                String tone = l.optString("tone", "");
                if (LINE_TONES.contains(tone)) line.put("tone", tone);
                outLines.put(line);
            }
            int ttl = p.optInt("ttl", 180);
            JSONObject pane = new JSONObject().put("id", p.getString("id")).put("title", printable(p.optString("title", p.getString("id")), 48))
                    .put("icon", printable(p.optString("icon", ""), 16)).put("ttl", Math.max(10, Math.min(86_400, ttl))).put("lines", outLines);
            // Optional "at" (unix seconds): when these live stats were taken; absent = the fetch time.
            long paneAt = p.optLong("at", 0L);
            if (paneAt > 0 && paneAt <= nowSec + 300) pane.put("at", paneAt);
            panes.put(pane);
        }
        return panes;
    }
}
