package com.omarchytv.home;

/**
 * Omarchy look: the theme wallpaper is shown untouched (optional uniform dimming, default off). Readability comes
 * from the windows' own semi-opaque backgrounds (panes, tiles, Waybar, Walker-style menus), like Hyprland
 * windows over the Omarchy desktop, plus a subtle text shadow on text straight on the wallpaper. No blur.
 *
 * Contrast: {@link #plateAlpha} keeps the user's transparency but raises a pane's background just enough that
 * its dimmest text reaches 4.5:1 (WCAG AA) over the brightest wallpaper pixel behind that pane.
 * Pure math, no Android types: unit-tested in SurfacesTest.
 */
final class Surfaces {

    private Surfaces() {
    }

    /** WCAG AA for normal text plus a little headroom for antialiasing. */
    static final double MIN_CONTRAST = 4.5;
    static final double TARGET = 4.7;
    /** Window surface colour: the Omarchy theme background (rgb). */
    static int bg() {
        return Theme.cur().bg & 0xFFFFFF;
    }
    /** Dimmest text colour we use (hints, counts, comments): 5.2:1 on opaque bg-dark. */
    static final int TEXT_DIM = 0x7F88B3;
    static final int TEXT_FG = 0xC0CAF5;
    /** Settings → Layout → Transparency, percent. */
    static final int[] STEPS = {5, 15, 25, 35, 45, 60};   // opacity 0.95 … 0.40 (v2.9 spec)
    /**
     * Omarchy's transparent windows: opacity "0.985 0.96" (active / inactive), default/hypr/helpers.lua
     * o.transparent_window. Inactive = 4 % transparency; the focused tile uses the active 0.985.
     */
    static final int DEFAULT = 25;   // v2.9 spec: opacity 0.75 (range 0.40-0.95)
    static final float ACTIVE_ALPHA = 0.985f;
    /** Settings → Layout → Wallpaper dimming: off (default, the theme wallpaper untouched), light, medium. */
    static final String[] DIM = {"off", "light", "medium"};

    static int dimLevel(String v) {
        for (int i = 0; i < DIM.length; i++) if (DIM[i].equals(v)) return i;
        return 0;
    }

    /** Uniform dim over the wallpaper for a level: 0, 10 %, 25 %. */
    static float globalDim(int level) {
        return level <= 0 ? 0f : level == 1 ? 0.10f : 0.25f;
    }

    /**
     * Background alpha for a pane (an Omarchy window) over wallpaper whose brightest pixel behind it is
     * {@code wallRgb}: the user's transparency, raised only as far as the pane's dimmest text needs for TARGET.
     */
    static float plateAlpha(int wallRgb, int transparencyPct, int... textColors) {
        double text = Double.MAX_VALUE;
        for (int c : textColors) text = Math.min(text, luminance(c));
        for (float a = surfaceAlpha(transparencyPct); a < 1f; a += 0.01f) {
            if (contrast(text, luminance(over(bg(), a, wallRgb))) >= TARGET) return Math.min(1f, a);
        }
        return 1f;
    }
    /** Omarchy shell [menu] background-alpha = 1.0 (default/themed/shell.toml.tpl). */
    static final float MENU_MIN = 1.0f;

    /** Background alpha 0..1 for panes / walker / Waybar for a transparency percentage (0 = opaque). */
    static float surfaceAlpha(int transparencyPct) {
        return 1f - clamp(transparencyPct, 0, 60) / 100f;
    }

    /** ARGB of bg-dark at the surface alpha. */
    static int surface(int transparencyPct) {
        return (Math.round(255 * surfaceAlpha(transparencyPct)) << 24) | bg();
    }

    static int menu(int transparencyPct) {
        return (Math.round(255 * Math.max(MENU_MIN, surfaceAlpha(transparencyPct))) << 24) | bg();
    }

    /** sRGB "src over" of colour {@code top} at alpha {@code a} onto opaque {@code bottom}. */
    static int over(int top, float a, int bottom) {
        int r = Math.round(((top >> 16) & 255) * a + ((bottom >> 16) & 255) * (1 - a));
        int g = Math.round(((top >> 8) & 255) * a + ((bottom >> 8) & 255) * (1 - a));
        int b = Math.round((top & 255) * a + (bottom & 255) * (1 - a));
        return (r << 16) | (g << 8) | b;
    }

    static double luminance(int rgb) {
        return 0.2126 * lin((rgb >> 16) & 255) + 0.7152 * lin((rgb >> 8) & 255) + 0.0722 * lin(rgb & 255);
    }

    static double contrast(double l1, double l2) {
        return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
    }

    private static double lin(int c) {
        double s = c / 255.0;
        return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
