package com.omarchytv.home;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * The ASCII logos shared by the home wordmark and the screensaver. Config value ("logo"):
 * <ul>
 * <li>"omarchy-tv" (default): popcornclaw: our POPCORNCLAW wordmark (assets/popcornclaw/wordmark.txt).</li>
 * <li>"omarchy": Omarchy's logo.txt unchanged (MIT, Copyright (c) David Heinemeier Hansson).</li>
 * <li>"original": OmarchyTV's own block font ({@link BlockFont}), no Omarchy artwork at all.</li>
 * </ul>
 */
final class Art {

    private Art() {
    }

    static String normalise(String which) {
        if ("omarchy".equals(which)) return "omarchy";
        if ("aquarium".equals(which)) return "aquarium";
        if ("aquarium-classic".equals(which)) return "aquarium-classic";
        if ("original".equals(which) || "omarchytv".equals(which)) return "original";
        return "omarchy-tv";
    }

    static String load(Context c, String which) {
        switch (normalise(which)) {
            case "omarchy": return raw(c, R.raw.omarchy_logo);
            case "original": return blockFontArt();
            default: return asset(c, "popcornclaw/wordmark.txt");   // popcornclaw: our wordmark
        }
    }

    /** Column where the second colour zone starts, or -1. */
    static int split(String which) {
        switch (normalise(which)) {
            case "omarchy": return -1;
            case "original": return BlockFont.splitColumn(WordmarkView.TEXT);
            default: return -1;
        }
    }

    /** True when columns BEFORE {@link #split(String)} get the accent gradient and columns AFTER get foreground. */
    static boolean isPrefixAccent(String which) {
        return false;
    }

    private static String blockFontArt() {
        char[][] g = BlockFont.render(WordmarkView.TEXT);
        StringBuilder sb = new StringBuilder();
        for (char[] row : g) sb.append(new String(row).replace('#', '\u2588')).append('\n');
        return sb.toString();
    }

    private static String asset(Context c, String path) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open(path), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    private static String raw(Context c, int res) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getResources().openRawResource(res), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    /** Splits art into trimmed lines (blank lines at both ends removed, inner spacing kept). */
    static String[] lines(String text) {
        String[] lines = text.split("\n");
        int first = 0, last = lines.length - 1;
        while (first <= last && lines[first].trim().isEmpty()) first++;
        while (last >= first && lines[last].trim().isEmpty()) last--;
        String[] out = new String[Math.max(0, last - first + 1)];
        System.arraycopy(lines, first, out, 0, out.length);
        return out;
    }
}
