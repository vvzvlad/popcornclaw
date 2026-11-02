package com.omarchytv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SurfacesTest {

    private static final int ACCENT = 0x7AA2F7;

    private static double on(int text, int behind) {
        return Surfaces.contrast(Surfaces.luminance(text), Surfaces.luminance(behind));
    }

    @Test
    public void textOnOpaqueBgPasses() {
        assertTrue(on(Surfaces.TEXT_DIM, Surfaces.bg()) >= 4.5);
        assertTrue(on(Surfaces.TEXT_FG, Surfaces.bg()) >= 4.5);
    }

    @Test
    public void wallpaperUntouchedByDefault() {
        assertEquals(0, Surfaces.dimLevel("off"));
        assertEquals(0, Surfaces.dimLevel("bogus"));
        assertEquals(0f, Surfaces.globalDim(Surfaces.dimLevel("off")), 0f);
        assertTrue(Surfaces.globalDim(Surfaces.dimLevel("light")) <= 0.10f);
        assertEquals(0.75f, Surfaces.surfaceAlpha(Surfaces.DEFAULT), 1e-6);   // v2.9 spec: default opacity 0.75
    }

    @Test
    public void panesStayReadableOnTheBrightestWallpaper() {
        for (int t : Surfaces.STEPS) {
            float a = Surfaces.plateAlpha(0xFFFFFF, t, Surfaces.TEXT_DIM, Surfaces.TEXT_FG, ACCENT);
            int behind = Surfaces.over(Surfaces.bg(), a, 0xFFFFFF);
            for (int c : new int[]{Surfaces.TEXT_DIM, Surfaces.TEXT_FG, ACCENT}) {
                assertTrue("t=" + t + " c=" + Integer.toHexString(c), on(c, behind) >= Surfaces.MIN_CONTRAST);
            }
        }
    }

    @Test
    public void panesKeepTheUserTransparencyOnDarkWallpaper() {
        for (int t : Surfaces.STEPS) {
            assertEquals(Surfaces.surfaceAlpha(t), Surfaces.plateAlpha(0x1A1B26, t, Surfaces.TEXT_DIM, ACCENT), 1e-6);
        }
    }

    @Test
    public void menusPassOverWhiteBanners() {
        for (int t : Surfaces.STEPS) {
            int behind = Surfaces.over(Surfaces.bg(), (Surfaces.menu(t) >>> 24) / 255f, 0xFFFFFF);
            assertTrue(on(Surfaces.TEXT_DIM, behind) >= 4.5);
        }
    }
}
