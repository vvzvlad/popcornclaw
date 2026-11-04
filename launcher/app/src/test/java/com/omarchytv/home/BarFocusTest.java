package com.omarchytv.home;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.Test;

public class BarFocusTest {
    // Same order as the top bar: menu, 5 workspace slots, then the right-hand icons.
    private static final List<String> BAR = Arrays.asList(
            "menu", "ws1", "ws2", "ws3", "ws4", "ws5", "wifi", "bt", "vol", "input", "screen");

    private static Predicate<String> hidden(String... gone) {
        Set<String> g = new HashSet<>(Arrays.asList(gone));
        return s -> !g.contains(s);
    }

    private static String step(String from, boolean right, Predicate<String> usable) {
        int to = BarFocus.next(BAR, from, right, usable);
        return to < 0 ? null : BAR.get(to);
    }

    @Test
    public void rightFromTheLastVisibleWorkspaceReachesTheIcons() {
        // 4 workspaces (home, apps, pc, system), ws5 hidden: "4" → the first icon, not stuck on "4".
        assertEquals("wifi", step("ws4", true, hidden("ws5")));
    }

    @Test
    public void hiddenSlotsAreSkippedBothWays() {
        Predicate<String> u = hidden("ws2", "ws3", "ws5");
        assertEquals("ws4", step("ws1", true, u));
        assertEquals("ws1", step("ws4", false, u));
        assertEquals("ws4", step("wifi", false, u));
    }

    @Test
    public void allVisibleIsPlainStepping() {
        Predicate<String> u = s -> true;
        assertEquals("ws1", step("menu", true, u));
        assertEquals("ws5", step("ws4", true, u));
        assertEquals("wifi", step("ws5", true, u));
        assertEquals("vol", step("input", false, u));
    }

    @Test
    public void nextDoesNotWrapAtTheEnds() {
        Predicate<String> u = hidden("ws5");
        assertEquals(null, step("menu", false, u));
        assertEquals(null, step("screen", true, u));
        // nothing usable further left of a hidden-only stretch: stay
        assertEquals(null, step("ws1", false, hidden("menu")));
    }

    @Test
    public void wrapGoesToTheOppositeEnd() {
        Predicate<String> u = hidden("ws5");
        assertEquals("menu", BAR.get(BarFocus.wrap(BAR, "screen", true, u)));
        assertEquals("screen", BAR.get(BarFocus.wrap(BAR, "menu", false, u)));
        // the first/last usable item is found past hidden slots
        assertEquals("ws1", BAR.get(BarFocus.wrap(BAR, "screen", true, hidden("menu"))));
        assertEquals("input", BAR.get(BarFocus.wrap(BAR, "ws1", false, hidden("menu", "screen"))));
    }

    @Test
    public void wrapOnlyAtAnEnd() {
        assertEquals(-1, BarFocus.wrap(BAR, "ws2", true, hidden()));
        assertEquals(-1, BarFocus.wrap(BAR, "ws2", false, hidden()));
        assertEquals(-1, BarFocus.wrap(BAR, "menu", true, hidden()));
        assertEquals(-1, BarFocus.wrap(BAR, "screen", false, hidden()));
        assertEquals(-1, BarFocus.wrap(BAR, "clock", true, hidden()));
    }

    @Test
    public void wrapWithOneUsableItemStays() {
        Predicate<String> only = s -> s.equals("menu");
        assertEquals(-1, BarFocus.wrap(BAR, "menu", true, only));
        assertEquals(-1, BarFocus.wrap(BAR, "menu", false, only));
    }

    @Test
    public void unknownCurrentStays() {
        assertEquals(-1, BarFocus.next(BAR, "clock", true, s -> true));
    }
}
