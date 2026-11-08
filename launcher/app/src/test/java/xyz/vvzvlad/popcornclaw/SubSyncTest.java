package xyz.vvzvlad.popcornclaw;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * SubSync on synthetic subtitles: a reference made from the target with known stepped shifts, jitter, and
 * dropped and merged cues, the way recognised speech differs from a subtitle file; and Srt's reading and writing.
 */
public class SubSyncTest {
    private static final String LATIN = "abcdefghijklmnopqrstuvwxyz";
    private static final String CYRILLIC = "абвгдежзиклмнопрстуфхцчшщыэюя";
    // The reference's timing error. Every matched pair then lies within it of the true shift, and so does
    // their median, the measured shift.
    private static final double JITTER = 0.15;

    // A synthetic episode: its cues, the true shift of each cue, and the pauses where the shift changes.
    private static final class Episode {
        final List<Srt.Cue> cues = new ArrayList<>();
        final List<Double> shift = new ArrayList<>();
        final List<double[]> stepPauses = new ArrayList<>();
    }

    private static String words(Random rnd, String alphabet, int n) {
        StringBuilder sb = new StringBuilder();
        for (int w = 0; w < n; w++) {
            if (w > 0) sb.append(' ');
            int len = 3 + rnd.nextInt(6);
            for (int i = 0; i < len; i++) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    // About 40 minutes of dialogue; the shift is shifts[k] after the k-th of stepsAt, and each step sits in a
    // 12 s pause, longer than any other pause of the dialogue.
    private static Episode episode(long seed, double[] stepsAt, double[] shifts) {
        Random rnd = new Random(seed);
        Episode ep = new Episode();
        double t = 5;
        int k = 0;
        double prevEnd = 0;
        while (t < 2400) {
            if (k < stepsAt.length && t >= stepsAt[k]) {
                t = prevEnd + 12;
                ep.stepPauses.add(new double[] {prevEnd, t});
                k++;
            }
            double dur = 1 + rnd.nextDouble() * 3;
            ep.cues.add(new Srt.Cue(t, t + dur, words(rnd, LATIN, 2 + rnd.nextInt(6))));
            ep.shift.add(shifts[k]);
            prevEnd = t + dur;
            double r = rnd.nextDouble();
            t = prevEnd + (r < 0.8 ? 0.1 + rnd.nextDouble() * 1.4 : r < 0.95 ? 2 + rnd.nextDouble() * 2
                    : 5 + rnd.nextDouble() * 3);
        }
        return ep;
    }

    // The reference: every cue moved by its shift with up to JITTER s of jitter, 15% of the cues dropped and 10%
    // merged with the next one; in Cyrillic placeholder text when `otherScript`, so only the timing matches.
    private static List<Srt.Cue> reference(Episode ep, long seed, boolean otherScript) {
        Random rnd = new Random(seed);
        List<Srt.Cue> ref = new ArrayList<>();
        for (int i = 0; i < ep.cues.size(); i++) {
            Srt.Cue c = ep.cues.get(i);
            double d = ep.shift.get(i);
            double r = rnd.nextDouble();
            if (r < 0.15) continue;
            Srt.Cue last = c;
            String text = c.text;
            if (r < 0.25 && i + 1 < ep.cues.size() && ep.shift.get(i + 1) == d) {
                last = ep.cues.get(++i);
                text = c.text + " " + last.text;
            }
            if (otherScript) text = words(rnd, CYRILLIC, 3);
            ref.add(new Srt.Cue(c.start + d + jitter(rnd), last.end + d + jitter(rnd), text));
        }
        return ref;
    }

    private static double jitter(Random rnd) {
        return (rnd.nextDouble() * 2 - 1) * JITTER;
    }

    private static void assertSectionsWellFormed(List<SubSync.Section> sections) {
        assertFalse(sections.isEmpty());
        assertEquals(Double.NEGATIVE_INFINITY, sections.get(0).from, 0);
        assertEquals(Double.POSITIVE_INFINITY, sections.get(sections.size() - 1).to, 0);
        for (int i = 0; i + 1 < sections.size(); i++) {
            assertEquals(sections.get(i).to, sections.get(i + 1).from, 0);
            assertTrue(sections.get(i).from <= sections.get(i).to);
        }
    }

    // Every target cue is moved by its true shift, and every change of shift lies in a step pause.
    private static void assertRecovered(Episode ep, SubSync.Result res) {
        assertSectionsWellFormed(res.sections);
        assertEquals(ep.cues.size(), res.shifted.size());
        for (int i = 0; i < ep.cues.size(); i++) {
            Srt.Cue c = ep.cues.get(i);
            Srt.Cue s = res.shifted.get(i);
            assertEquals("cue at " + c.start, ep.shift.get(i), s.start - c.start, JITTER + 1e-9);
            assertEquals(s.start - c.start, s.end - c.end, 1e-9);
            assertEquals(c.text, s.text);
        }
        for (int i = 0; i + 1 < res.sections.size(); i++) {
            SubSync.Section a = res.sections.get(i);
            SubSync.Section b = res.sections.get(i + 1);
            if (Math.abs(a.shift - b.shift) < 0.5) continue;
            boolean inPause = false;
            for (double[] p : ep.stepPauses) inPause |= a.to >= p[0] && a.to <= p[1];
            assertTrue("boundary at " + a.to + " is not in a step pause", inPause);
        }
        for (SubSync.Piece p : res.pieces) {
            if (p.accepted) {
                assertNull(p.reason);
            } else {
                assertNotNull(p.reason);
            }
        }
    }

    @Test
    public void steppedShiftsFromMatchingText() {
        Episode ep = episode(1, new double[] {600, 1500}, new double[] {2.0, 0.7, -1.5});
        SubSync.Result res = SubSync.align(ep.cues, reference(ep, 2, false));
        assertRecovered(ep, res);
        int accepted = 0;
        for (SubSync.Piece p : res.pieces) if (p.accepted) accepted++;
        assertTrue(accepted >= res.pieces.size() - 2);
    }

    @Test
    public void steppedShiftsFromTimingAlone() {
        Episode ep = episode(3, new double[] {700, 1300, 2000}, new double[] {-4.0, -2.6, 1.2, 0.0});
        assertRecovered(ep, SubSync.align(ep.cues, reference(ep, 4, true)));
    }

    @Test
    public void unorderedInputKeepsItsOrder() {
        Episode ep = episode(5, new double[] {900}, new double[] {1.5, -0.5});
        List<Srt.Cue> target = new ArrayList<>(ep.cues);
        java.util.Collections.reverse(target);
        List<Srt.Cue> ref = reference(ep, 6, false);
        java.util.Collections.shuffle(ref, new Random(7));
        SubSync.Result res = SubSync.align(target, ref);
        for (int i = 0; i < target.size(); i++) assertEquals(target.get(i).text, res.shifted.get(i).text);
    }

    @Test
    public void farOutlierPieceIsRejected() {
        // Three stretches a minute apart; the middle one's reference is 6 s further off than its neighbours'.
        Random rnd = new Random(8);
        List<Srt.Cue> target = new ArrayList<>();
        List<Srt.Cue> ref = new ArrayList<>();
        double[][] stretches = {{10, 200, 2.0}, {260, 300, 8.0}, {360, 550, 2.0}};
        for (double[] s : stretches) {
            for (double t = s[0]; t < s[1]; ) {
                double dur = 1 + rnd.nextDouble() * 2;
                String text = words(rnd, LATIN, 3);
                target.add(new Srt.Cue(t, t + dur, text));
                ref.add(new Srt.Cue(t + s[2] + jitter(rnd), t + dur + s[2], text));
                t += dur + 0.2 + rnd.nextDouble() * 1.5;
            }
        }
        SubSync.Result res = SubSync.align(target, ref);
        boolean rejected = false;
        for (SubSync.Piece p : res.pieces) {
            if (p.from > 230 && p.to < 330) {
                assertFalse(p.accepted);
                assertEquals(8.0, p.shift, JITTER + 1e-9);
                assertNotNull(p.reason);
                rejected = true;
            }
        }
        assertTrue(rejected);
        assertSectionsWellFormed(res.sections);
        for (int i = 0; i < target.size(); i++) {
            assertEquals(2.0, res.shifted.get(i).start - target.get(i).start, JITTER + 1e-9);
        }
    }

    @Test
    public void nothingMatchesLeavesTheTargetAlone() {
        Episode ep = episode(9, new double[0], new double[] {0});
        List<Srt.Cue> ref = Arrays.asList(new Srt.Cue(5000, 5001, "far"), new Srt.Cue(5003, 5004, "away"));
        SubSync.Result res = SubSync.align(ep.cues, ref);
        assertTrue(res.sections.isEmpty());
        assertEquals(1, res.pieces.size());
        assertFalse(res.pieces.get(0).accepted);
        assertNotNull(res.pieces.get(0).reason);
        assertEquals(ep.cues.size(), res.shifted.size());
        for (int i = 0; i < ep.cues.size(); i++) {
            assertEquals(ep.cues.get(i).start, res.shifted.get(i).start, 0);
            assertEquals(ep.cues.get(i).end, res.shifted.get(i).end, 0);
            assertEquals(ep.cues.get(i).text, res.shifted.get(i).text);
        }
        assertTrue(SubSync.align(ep.cues, new ArrayList<Srt.Cue>()).pieces.isEmpty());
    }

    // --- Srt -----------------------------------------------------------------------------------------------

    @Test
    public void formatThenParseRoundTrips() {
        List<Srt.Cue> cues = Arrays.asList(
                new Srt.Cue(3725.5, 3727.25, "Second\nof two lines"),
                new Srt.Cue(1.001, 2.999, "First"),
                new Srt.Cue(36000.042, 36001, "Ten hours in"));
        String text = Srt.format(cues);
        assertEquals("1\n00:00:01,001 --> 00:00:02,999\nFirst\n\n"
                + "2\n01:02:05,500 --> 01:02:07,250\nSecond\nof two lines\n\n"
                + "3\n10:00:00,042 --> 10:00:01,000\nTen hours in\n\n", text);
        List<Srt.Cue> back = Srt.parse(text.getBytes(StandardCharsets.UTF_8));
        assertEquals(3, back.size());
        assertEquals(1.001, back.get(0).start, 1e-9);
        assertEquals(2.999, back.get(0).end, 1e-9);
        assertEquals("Second\nof two lines", back.get(1).text);
        assertEquals(36000.042, back.get(2).start, 1e-9);
        assertEquals(text, Srt.format(back));
    }

    @Test
    public void negativeTimesAreWrittenAsZero() {
        assertEquals("1\n00:00:00,000 --> 00:00:00,000\nEarly\n\n2\n00:00:00,000 --> 00:00:01,500\nLate\n\n",
                Srt.format(Arrays.asList(new Srt.Cue(-0.5, 1.5, "Late"), new Srt.Cue(-2, -1, "Early"))));
    }

    @Test
    public void windows1251WhenNotUtf8() {
        Charset cp1251 = Charset.forName("windows-1251");
        byte[] data = "1\r\n00:00:01,000 --> 00:00:02,500\r\nЁлки, всё хорошо\r\n".getBytes(cp1251);
        List<Srt.Cue> cues = Srt.parse(data);
        assertEquals(1, cues.size());
        assertEquals("Ёлки, всё хорошо", cues.get(0).text);
        // The same text as UTF-8 stays UTF-8.
        assertEquals("Ёлки, всё хорошо",
                Srt.parse("1\n00:00:01,000 --> 00:00:02,500\nЁлки, всё хорошо\n".getBytes(StandardCharsets.UTF_8))
                        .get(0).text);
    }

    @Test
    public void bomMarkupAndBrokenBlocks() {
        String srt = "﻿1\r\n00:00:05,000 --> 00:00:06,000\r\n{\\an8}<i>Hello</i> <font color=\"#ffffff\">world</font>\r\n\r\n"
                + "2\r\nnot a timing line\r\nlost text\r\n\r\n"
                + "3\r\n00:00:01,5 --> 00:00:02.25\r\nEarlier, odd fraction\r\n"
                + "4\r\n00:00:03,000 --> 00:00:04,000\r\nNo blank line before me\r\n\r\n"
                + "5\r\n00:00:07,000 --> 00:00:08,000\r\n<i></i>\r\n\r\n";
        List<Srt.Cue> cues = Srt.parse(srt.getBytes(StandardCharsets.UTF_8));
        assertEquals(3, cues.size());
        assertEquals(1.5, cues.get(0).start, 1e-9);
        assertEquals(2.25, cues.get(0).end, 1e-9);
        assertEquals("Earlier, odd fraction", cues.get(0).text);
        assertEquals("No blank line before me", cues.get(1).text);
        assertEquals(5, cues.get(2).start, 1e-9);
        assertEquals("Hello world", cues.get(2).text);
    }
}
