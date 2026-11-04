package com.omarchytv.home.link;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * User themes stored under &lt;link dir&gt;/themes/&lt;name&gt;/theme.json + backgrounds/NN-*.{jpg,png,webp},
 * offered in Settings → Themes. The upload side that filled this folder (the PC bridge) is gone; the launcher
 * only reads what is already there.
 */
public final class CustomThemes {
    public static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{0,39}$");
    static final Pattern FILE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}\\.(jpg|jpeg|png|webp)$");

    private CustomThemes() {}

    public static File root(File linkDir) {
        return new File(linkDir, "themes");
    }

    public static String[] names(File linkDir) {
        File[] ts = root(linkDir).listFiles();
        List<String> out = new ArrayList<>();
        if (ts != null) for (File t : ts) if (t.isDirectory() && NAME.matcher(t.getName()).matches() && new File(t, "theme.json").isFile()) out.add(t.getName());
        java.util.Collections.sort(out);
        return out.toArray(new String[0]);
    }

    public static File[] backgrounds(File linkDir, String name) {
        File[] f = new File(new File(root(linkDir), name), "backgrounds").listFiles((d, n) -> FILE.matcher(n).matches());
        if (f == null) return new File[0];
        Arrays.sort(f);
        return f;
    }

    /** Config edit selecting theme {@code name} and its background {@code bg} (no background: keep the wallpaper). */
    public static void select(File linkDir, String name, int bg, JSONObject cfg) throws Exception {
        File dir = new File(root(linkDir), name);
        byte[] b = com.omarchytv.home.ApiCompat.readAllBytes(new File(dir, "theme.json"));
        JSONObject t = new JSONObject(new String(b, StandardCharsets.UTF_8));
        t.put("source", "custom");
        cfg.put("theme", t);
        File[] bgs = backgrounds(linkDir, name);
        if (bgs.length > 0) cfg.put("wallpaper", bgs[Math.floorMod(bg, bgs.length)].getAbsolutePath());
    }
}
