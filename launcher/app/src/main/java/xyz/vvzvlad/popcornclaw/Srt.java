package xyz.vvzvlad.popcornclaw;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reading and writing SubRip (.srt) files. Pure Java: no Android or org.json API, so the JVM unit tests run it
 * as is.
 */
final class Srt {
    private Srt() {
    }

    /** One subtitle: start and end in seconds from the file's zero, text without markup, lines joined by \n. */
    static final class Cue {
        final double start;
        final double end;
        final String text;

        Cue(double start, double end, String text) {
            this.start = start;
            this.end = end;
            this.text = text;
        }
    }

    // A time is HH:MM:SS,mmm; the hours may be missing and the fraction may use a dot or have 1-3 digits,
    // as files made by hand or by other tools do.
    private static final String TIME = "(?:(\\d+):)?(\\d{1,2}):(\\d{1,2})(?:[,.](\\d{1,3}))?";
    private static final Pattern TIMING = Pattern.compile("^\\s*" + TIME + "\\s*-->\\s*" + TIME);
    private static final Pattern INDEX = Pattern.compile("^\\s*\\d+\\s*$");
    // HTML-like tags (<i>, </i>, <font color=...>) and ASS override blocks ({\an8}, {\i1}).
    private static final Pattern MARKUP = Pattern.compile("<[^>]*>|\\{\\\\[^}]*\\}");

    private static final Comparator<Cue> BY_START = new Comparator<Cue>() {
        @Override
        public int compare(Cue a, Cue b) {
            return Double.compare(a.start, b.start);
        }
    };

    /**
     * Cues of an .srt file sorted by start. The bytes are read as UTF-8 (a BOM is dropped); bytes that are not
     * valid UTF-8 are read as windows-1251, the encoding Russian subtitles often come in. A block without a
     * readable timing line, or whose text is empty once the markup is gone, is skipped.
     */
    static List<Cue> parse(byte[] data) {
        String s = decode(data);
        if (!s.isEmpty() && s.charAt(0) == '﻿') s = s.substring(1);
        String[] lines = s.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<Cue> cues = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            Matcher m = TIMING.matcher(lines[i]);
            if (!m.find()) {
                i++;
                continue;
            }
            double start = seconds(m, 1);
            double end = seconds(m, 5);
            if (end < start) end = start;
            StringBuilder text = new StringBuilder();
            i++;
            // The text runs to a blank line, or to the next block when a file lost the blank line before it.
            while (i < lines.length && !lines[i].trim().isEmpty() && !TIMING.matcher(lines[i]).find()
                    && !(INDEX.matcher(lines[i]).matches() && i + 1 < lines.length
                    && TIMING.matcher(lines[i + 1]).find())) {
                String line = MARKUP.matcher(lines[i]).replaceAll("").trim();
                if (!line.isEmpty()) {
                    if (text.length() > 0) text.append('\n');
                    text.append(line);
                }
                i++;
            }
            if (text.length() > 0) cues.add(new Cue(start, end, text.toString()));
        }
        Collections.sort(cues, BY_START);
        return cues;
    }

    /** Standard SRT numbered from 1 in the order of start; a negative time is written as zero. */
    static String format(List<Cue> cues) {
        List<Cue> sorted = new ArrayList<>(cues);
        Collections.sort(sorted, BY_START);
        StringBuilder sb = new StringBuilder();
        int n = 1;
        for (Cue c : sorted) {
            sb.append(n++).append('\n')
                    .append(stamp(c.start)).append(" --> ").append(stamp(c.end)).append('\n')
                    .append(c.text).append("\n\n");
        }
        return sb.toString();
    }

    private static String decode(byte[] data) {
        CharsetDecoder utf8 = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer chars = utf8.decode(ByteBuffer.wrap(data));
            return chars.toString();
        } catch (CharacterCodingException e) {
            return new String(data, Charset.forName("windows-1251"));
        }
    }

    // The time in seconds from the groups of TIME starting at group `g` (hours, minutes, seconds, fraction).
    private static double seconds(Matcher m, int g) {
        long h = m.group(g) == null ? 0 : Long.parseLong(m.group(g));
        long min = Long.parseLong(m.group(g + 1));
        long sec = Long.parseLong(m.group(g + 2));
        String frac = m.group(g + 3);
        double f = frac == null ? 0 : Long.parseLong(frac) / Math.pow(10, frac.length());
        return h * 3600 + min * 60 + sec + f;
    }

    private static String stamp(double t) {
        long ms = Math.max(0, Math.round(t * 1000));
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d",
                ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000);
    }
}
