package com.omarchytv.home;

import java.util.HashMap;
import java.util.Map;

/**
 * An original 5-row block font for the OmarchyTV wordmark, drawn the way terminal ASCII logos are:
 * every '#' is one full-block cell (U+2588) on a 1:2 character grid. Used by the home-screen logo
 * ({@link WordmarkView}) and the screensaver ({@link OmarchyDream}), so both stay pixel-identical.
 */
final class BlockFont {

    static final int ROWS = 5;
    private static final Map<Character, String[]> GLYPHS = new HashMap<>();

    static {
        GLYPHS.put('O', new String[]{" #### ", "##  ##", "##  ##", "##  ##", " #### "});
        GLYPHS.put('M', new String[]{"##   ##", "### ###", "## # ##", "##   ##", "##   ##"});
        GLYPHS.put('A', new String[]{" #### ", "##  ##", "######", "##  ##", "##  ##"});
        GLYPHS.put('R', new String[]{"##### ", "##  ##", "##### ", "## ## ", "##  ##"});
        GLYPHS.put('C', new String[]{" #####", "##    ", "##    ", "##    ", " #####"});
        GLYPHS.put('H', new String[]{"##  ##", "##  ##", "######", "##  ##", "##  ##"});
        GLYPHS.put('Y', new String[]{"##  ##", "##  ##", " #### ", "  ##  ", "  ##  "});
        GLYPHS.put('T', new String[]{"######", "  ##  ", "  ##  ", "  ##  ", "  ##  "});
        GLYPHS.put('V', new String[]{"##  ##", "##  ##", "##  ##", " #### ", "  ##  "});
        GLYPHS.put(' ', new String[]{"  ", "  ", "  ", "  ", "  "});
    }

    private BlockFont() {
    }

    /**
     * Lays the text out as a cell grid. Returns rows of equal length; '#' is a filled cell, ' ' empty.
     * Letters are separated by one empty column.
     */
    static char[][] render(String text) {
        StringBuilder[] rows = new StringBuilder[ROWS];
        for (int r = 0; r < ROWS; r++) rows[r] = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            String[] g = GLYPHS.get(Character.toUpperCase(text.charAt(i)));
            if (g == null) g = GLYPHS.get(' ');
            for (int r = 0; r < ROWS; r++) {
                if (i > 0) rows[r].append(' ');
                rows[r].append(g[r]);
            }
        }
        char[][] out = new char[ROWS][];
        for (int r = 0; r < ROWS; r++) out[r] = rows[r].toString().toCharArray();
        return out;
    }

    /** Column where the second word starts (after the first space), or -1. Used to colour "TV" apart. */
    static int splitColumn(String text) {
        int space = text.indexOf(' ');
        if (space < 0) return -1;
        return render(text.substring(0, space + 1))[0].length + 1;
    }
}
