package xyz.vvzvlad.popcornclaw;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * The box's PIN for the LAN surfaces: the control panel and its MCP ({@link ControlServer}, :8081) and the screen
 * view ({@link ScreenStreamServer}, :8080). Six random digits, created on first use and kept in the app's private
 * files dir, so it survives restarts and updates and changes only when the app's data is cleared. The home
 * screen's {@link SetupQr} card shows it and carries it in the QR. Browsers keep it in the {@link #COOKIE} cookie;
 * MCP clients send it as {@code Authorization: Bearer <PIN>}.
 */
final class Pin {
    private static final String TAG = "PopcornClaw-pin";
    private static final String FILE = "control_pin";
    /** The browser cookie that carries the PIN on both ports (cookies are per host, not per port). */
    static final String COOKIE = "pcpin";
    // Ten years: the PIN only changes with the app's data, and then the old cookie simply stops matching.
    private static final long COOKIE_MAX_AGE_S = 315_360_000L;

    // The PIN once read or created; every caller in the process (the two servers, the QR card) shares it.
    private static volatile String cached;

    private Pin() {
    }

    /** The PIN, created (and stored) on the first call ever. Never null. */
    static String get(Context c) {
        String p = cached;
        if (p != null) return p;
        synchronized (Pin.class) {
            if (cached != null) return cached;
            File f = new File(c.getFilesDir(), FILE);
            String v = read(f);
            if (v == null) {
                v = String.format(Locale.ROOT, "%06d", new SecureRandom().nextInt(1_000_000));
                write(f, v);
            }
            cached = v;
            return v;
        }
    }

    /** Whether {@code candidate} is the PIN; constant-time, null-safe. */
    static boolean matches(Context c, String candidate) {
        return candidate != null && same(get(c), candidate);
    }

    /** Whether the non-null {@code candidate}, trimmed, is {@code pin}; constant-time. */
    static boolean same(String pin, String candidate) {
        return MessageDigest.isEqual(pin.getBytes(StandardCharsets.UTF_8),
                candidate.trim().getBytes(StandardCharsets.UTF_8));
    }

    /** The full {@code Set-Cookie} header line (CRLF included) that stores the PIN in the browser. */
    static String setCookieHeader(Context c) {
        return "Set-Cookie: " + COOKIE + "=" + get(c) + "; Path=/; Max-Age=" + COOKIE_MAX_AGE_S
                + "; SameSite=Lax; HttpOnly\r\n";
    }

    /** The {@link #COOKIE} value out of a request's Cookie header(s) ("a=1; pcpin=123456"); null when absent. */
    static String fromCookies(String cookieHeader) {
        if (cookieHeader == null) return null;
        for (String part : cookieHeader.split(";")) {
            String s = part.trim();
            if (s.startsWith(COOKIE + "=")) return s.substring(COOKIE.length() + 1);
        }
        return null;
    }

    /** The token of an {@code Authorization: Bearer <token>} header value; null for any other scheme. */
    static String fromBearer(String authorization) {
        if (authorization == null) return null;
        String s = authorization.trim();
        if (s.length() < 7 || !s.substring(0, 7).equalsIgnoreCase("Bearer ")) return null;
        return s.substring(7).trim();
    }

    /** The stored PIN, or null when the file is absent or does not hold six digits. */
    private static String read(File f) {
        if (!f.isFile()) return null;
        byte[] buf = new byte[64];
        int n;
        try (FileInputStream in = new FileInputStream(f)) {
            n = in.read(buf);
        } catch (IOException e) {
            Log.w(TAG, "cannot read " + f, e);
            return null;
        }
        if (n <= 0) return null;
        String s = new String(buf, 0, n, StandardCharsets.UTF_8).trim();
        return s.matches("\\d{6}") ? s : null;
    }

    /** Writes through a temp file and a rename, so a crash mid-write never leaves a torn PIN behind. */
    private static void write(File f, String pin) {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(pin.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            // Still usable for this run from the cache; the next start creates another one.
            Log.e(TAG, "cannot store the PIN in " + f, e);
            return;
        }
        if (!tmp.renameTo(f)) Log.e(TAG, "cannot move " + tmp + " to " + f);
    }
}
