package xyz.vvzvlad.popcornclaw;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Aligns the timing of one subtitle file (the target) to another (the reference): an .srt of another
 * release, or the recognised speech of what plays. The texts may be in different languages, then only the
 * timing is compared.
 *
 * <p>The reference is cut into pieces of at most {@link #PIECE_MAX} s; for each piece the shift is searched
 * that makes the most target cue starts land on reference cue starts. A piece whose best shift does not stand
 * out is rejected. The target is then cut into sections, one per accepted piece; between two pieces whose
 * shifts differ the cut goes into a pause where the cues stop matching the first shift and start matching the
 * second: on TV releases the offset changes in steps where an ad break was cut out, and that is a pause in the
 * dialogue.
 *
 * <p>Pure Java: no Android or org.json API, so the JVM unit tests run it as is.
 */
final class SubSync {
    private SubSync() {
    }

    // Reference cues further apart than this start a new stretch; a longer stretch is cut into pieces of at
    // most PIECE_MAX seconds.
    private static final double PIECE_GAP = 20;
    private static final double PIECE_MAX = 90;
    // Shifts searched, seconds, and the search step.
    private static final double RANGE = 30;
    private static final double STEP = 0.05;
    // A target cue start counts as landing on a reference cue start this close.
    private static final double TOLERANCE = 0.3;
    // Two texts are the same phrase when their word overlap or edit ratio reaches this.
    private static final double SIMILAR = 0.5;
    // A piece is trusted when it matched at least MIN_MATCHED cues and MIN_SHARE of its reference cues, and
    // its best score is PEAK_RATIO times the best score further than PEAK_EXCLUDE seconds from it.
    private static final int MIN_MATCHED = 4;
    private static final double MIN_SHARE = 0.35;
    private static final double PEAK_RATIO = 1.25;
    private static final double PEAK_EXCLUDE = 1;
    // A piece whose shift is OUTLIER seconds off both neighbours, while those agree within AGREE, is wrong.
    private static final double OUTLIER = 2.5;
    private static final double AGREE = 1;
    // Neighbouring pieces whose shifts differ by less than this are the same offset measured twice.
    static final double SAME_SHIFT = 0.3;
    // Overlapping recognition windows repeat a phrase: the same text this close in time is one cue.
    private static final double DUPLICATE = 1;
    // Texts longer than this are cut before the edit distance, which is quadratic in the length.
    private static final int EDIT_MAX = 200;

    /** One measured stretch of the reference, in reference time. */
    static final class Piece {
        final double from;
        final double to;
        // Cue pairs matched at the shift, and the reference cues of the piece.
        final int matched;
        final int total;
        // Seconds to add to the target's times; NaN when nothing matched at all.
        final double shift;
        final boolean accepted;
        // Why the piece was rejected, null when it was accepted.
        final String reason;

        Piece(double from, double to, int matched, int total, double shift, boolean accepted, String reason) {
            this.from = from;
            this.to = to;
            this.matched = matched;
            this.total = total;
            this.shift = shift;
            this.accepted = accepted;
            this.reason = reason;
        }
    }

    /** A stretch of the target, in target time, moved by one shift; the first starts at -Infinity, the last ends at +Infinity. */
    static final class Section {
        final double from;
        final double to;
        final double shift;

        Section(double from, double to, double shift) {
            this.from = from;
            this.to = to;
            this.shift = shift;
        }
    }

    static final class Result {
        final List<Piece> pieces;
        final List<Section> sections;
        // The target's cues in their order, each moved by the shift of its section.
        final List<Srt.Cue> shifted;

        Result(List<Piece> pieces, List<Section> sections, List<Srt.Cue> shifted) {
            this.pieces = pieces;
            this.sections = sections;
            this.shifted = shifted;
        }
    }

    // A piece's measurement plus what the sections need: the target time span of its matched cues and
    // whether they were matched by text.
    private static final class Measure {
        Piece piece;
        double spanFrom;
        double spanTo;
        boolean byText;
    }

    // One search of the shift: its best score, the runner-up away from it, the matched pairs.
    private static final class Attempt {
        int best;
        int runnerUp;
        double runnerUpShift;
        double shift;
        int matched;
        double spanFrom;
        double spanTo;
    }

    private static final Comparator<Srt.Cue> BY_START = new Comparator<Srt.Cue>() {
        @Override
        public int compare(Srt.Cue a, Srt.Cue b) {
            return Double.compare(a.start, b.start);
        }
    };

    // Not the words of the phrase: sound descriptions in brackets and speaker labels like "HOUSE:".
    private static final Pattern NOISE = Pattern.compile(
            "\\([^)]*\\)|\\[[^\\]]*\\]|^\\s*\\p{Lu}[\\p{Lu} .'-]*:", Pattern.MULTILINE);
    private static final Pattern APOSTROPHE = Pattern.compile("['\u2019`]");
    private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    /**
     * Shifts that move {@code target} onto {@code reference}, per piece of the reference and per section of
     * the target. With no accepted piece the sections are empty and {@code shifted} is the target unchanged.
     */
    static Result align(List<Srt.Cue> target, List<Srt.Cue> reference) {
        List<Srt.Cue> tgt = sorted(target);
        List<Srt.Cue> ref = dedup(sorted(reference));
        boolean textual = cyrillic(tgt) == cyrillic(ref);
        String[] tgtText = textual ? normalize(tgt) : null;
        String[] refText = textual ? normalize(ref) : null;
        double[] tgtStart = starts(tgt);

        List<Measure> measures = new ArrayList<>();
        for (int[] r : split(ref)) {
            measures.add(measure(tgt, tgtStart, tgtText, ref, refText, r[0], r[1]));
        }
        rejectOutliers(measures);

        List<Measure> accepted = new ArrayList<>();
        for (Measure m : measures) if (m.piece.accepted) accepted.add(m);
        List<Section> sections = sections(tgt, tgtStart, tgtText, ref, refText, accepted);

        List<Srt.Cue> shifted = new ArrayList<>(target.size());
        for (Srt.Cue c : target) {
            double d = 0;
            for (Section s : sections) {
                if (c.start >= s.from && c.start < s.to) {
                    d = s.shift;
                    break;
                }
            }
            shifted.add(d == 0 ? c : new Srt.Cue(c.start + d, c.end + d, c.text));
        }
        List<Piece> pieces = new ArrayList<>(measures.size());
        for (Measure m : measures) pieces.add(m.piece);
        return new Result(pieces, sections, shifted);
    }

    // --- pieces --------------------------------------------------------------------------------------------

    // Index ranges [from, to) of the reference's pieces.
    private static List<int[]> split(List<Srt.Cue> ref) {
        List<int[]> pieces = new ArrayList<>();
        int i = 0;
        while (i < ref.size()) {
            int j = i + 1;
            double end = ref.get(i).end;
            while (j < ref.size() && ref.get(j).start - end <= PIECE_GAP) {
                end = Math.max(end, ref.get(j).end);
                j++;
            }
            double from = ref.get(i).start;
            int parts = Math.max(1, (int) Math.ceil((end - from) / PIECE_MAX));
            double len = (end - from) / parts;
            int k = i;
            for (int p = 0; p < parts && k < j; p++) {
                int s = k;
                double limit = p == parts - 1 ? Double.POSITIVE_INFINITY : from + len * (p + 1);
                while (k < j && ref.get(k).start < limit) k++;
                if (k > s) pieces.add(new int[] {s, k});
            }
            i = j;
        }
        return pieces;
    }

    private static Measure measure(List<Srt.Cue> tgt, double[] tgtStart, String[] tgtText,
                                   List<Srt.Cue> ref, String[] refText, int lo, int hi) {
        double from = ref.get(lo).start;
        double to = from;
        for (int j = lo; j < hi; j++) to = Math.max(to, ref.get(j).end);
        int total = hi - lo;
        // Only target cues that some shift in range can bring onto a reference cue of the piece.
        int a = lowerBound(tgtStart, ref.get(lo).start - RANGE - TOLERANCE);
        int b = lowerBound(tgtStart, ref.get(hi - 1).start + RANGE + TOLERANCE + 1e-9);

        Measure m = new Measure();
        Attempt att = null;
        String textReason = null;
        if (tgtText != null) {
            att = attempt(tgt, tgtText, ref, refText, lo, hi, a, b);
            textReason = doubt(att, total);
            m.byText = textReason == null;
        }
        String reason = textReason;
        if (tgtText == null || textReason != null) {
            Attempt timing = attempt(tgt, null, ref, null, lo, hi, a, b);
            String timingReason = doubt(timing, total);
            if (att == null || timingReason == null) att = timing;
            reason = timingReason == null ? null
                    : textReason == null ? timingReason
                    : "по тексту: " + textReason + "; по времени: " + timingReason;
        }
        m.piece = new Piece(from, to, att.matched, total, att.shift, reason == null, reason);
        m.spanFrom = att.spanFrom;
        m.spanTo = att.spanTo;
        return m;
    }

    // Why the attempt cannot be trusted, null when it can.
    private static String doubt(Attempt att, int total) {
        if (att.best == 0) return "ни одно начало реплики не совпало ни при каком сдвиге";
        if (att.matched < MIN_MATCHED) {
            return String.format(Locale.ROOT, "совпало %d реплик, нужно хотя бы %d", att.matched, MIN_MATCHED);
        }
        if (att.matched < MIN_SHARE * total) {
            return String.format(Locale.ROOT, "совпало %d из %d реплик образца, нужно хотя бы %d%%",
                    att.matched, total, Math.round(MIN_SHARE * 100));
        }
        if (att.best < PEAK_RATIO * att.runnerUp) {
            return String.format(Locale.ROOT, "неоднозначно: при сдвиге %+.2f с совпало %d, при %+.2f с — %d",
                    att.shift, att.best, att.runnerUpShift, att.runnerUp);
        }
        return null;
    }

    // Scores every shift by the number of target cues whose start lands on a reference cue start of the
    // piece (with the texts given, only on a cue with a similar text), then matches the cues at the best one.
    private static Attempt attempt(List<Srt.Cue> tgt, String[] tgtText, List<Srt.Cue> ref, String[] refText,
                                   int lo, int hi, int a, int b) {
        int steps = (int) Math.round(2 * RANGE / STEP) + 1;
        int[] score = new int[steps];
        int[] mark = new int[steps];
        Arrays.fill(mark, -1);
        // 0 = not compared yet, 1 = similar, 2 = not.
        byte[] similar = tgtText == null ? null : new byte[(b - a) * (hi - lo)];
        for (int i = a; i < b; i++) {
            double t = tgt.get(i).start;
            for (int j = lo; j < hi; j++) {
                double delta = ref.get(j).start - t;
                if (Math.abs(delta) > RANGE + TOLERANCE) continue;
                if (similar != null && !similar(similar, (i - a) * (hi - lo) + (j - lo), tgtText[i], refText[j])) {
                    continue;
                }
                int k0 = Math.max(0, (int) Math.ceil((delta - TOLERANCE + RANGE) / STEP - 1e-9));
                int k1 = Math.min(steps - 1, (int) Math.floor((delta + TOLERANCE + RANGE) / STEP + 1e-9));
                for (int k = k0; k <= k1; k++) {
                    if (mark[k] != i) {
                        mark[k] = i;
                        score[k]++;
                    }
                }
            }
        }
        // The best score's plateau (a run of equal scores) is centred on the shift, give or take the jitter.
        int first = 0;
        for (int k = 1; k < steps; k++) if (score[k] > score[first]) first = k;
        int last = first;
        while (last + 1 < steps && score[last + 1] == score[first]) last++;
        int centre = (first + last) / 2;
        double d = -RANGE + centre * STEP;

        Attempt att = new Attempt();
        att.best = score[first];
        att.runnerUpShift = Double.NaN;
        for (int k = 0; k < steps; k++) {
            double dk = -RANGE + k * STEP;
            if (Math.abs(dk - d) > PEAK_EXCLUDE && score[k] > att.runnerUp) {
                att.runnerUp = score[k];
                att.runnerUpShift = dk;
            }
        }
        att.shift = Double.NaN;
        if (att.best == 0) return att;

        // Each target cue takes the nearest reference cue at the shift; a reference cue taken twice keeps
        // the closer one.
        int[] owner = new int[hi - lo];
        double[] ownerDist = new double[hi - lo];
        Arrays.fill(owner, -1);
        for (int i = a; i < b; i++) {
            double t = tgt.get(i).start + d;
            int bestJ = -1;
            double bestDist = TOLERANCE + 1e-9;
            for (int j = lo; j < hi; j++) {
                double dist = Math.abs(ref.get(j).start - t);
                if (dist > bestDist) continue;
                if (similar != null && !similar(similar, (i - a) * (hi - lo) + (j - lo), tgtText[i], refText[j])) {
                    continue;
                }
                bestJ = j;
                bestDist = dist;
            }
            if (bestJ < 0) continue;
            int o = bestJ - lo;
            if (owner[o] < 0 || bestDist < ownerDist[o]) {
                owner[o] = i;
                ownerDist[o] = bestDist;
            }
        }
        List<Double> deltas = new ArrayList<>();
        att.spanFrom = Double.POSITIVE_INFINITY;
        att.spanTo = Double.NEGATIVE_INFINITY;
        for (int o = 0; o < owner.length; o++) {
            if (owner[o] < 0) continue;
            double t = tgt.get(owner[o]).start;
            deltas.add(ref.get(lo + o).start - t);
            att.spanFrom = Math.min(att.spanFrom, t);
            att.spanTo = Math.max(att.spanTo, t);
        }
        att.matched = deltas.size();
        att.shift = deltas.isEmpty() ? d : median(deltas);
        return att;
    }

    // A piece whose shift is far from both accepted neighbours while those two agree is a false peak.
    private static void rejectOutliers(List<Measure> measures) {
        List<Measure> acc = new ArrayList<>();
        for (Measure m : measures) if (m.piece.accepted) acc.add(m);
        List<Measure> wrong = new ArrayList<>();
        for (int i = 1; i + 1 < acc.size(); i++) {
            double prev = acc.get(i - 1).piece.shift;
            double cur = acc.get(i).piece.shift;
            double next = acc.get(i + 1).piece.shift;
            if (Math.abs(cur - prev) > OUTLIER && Math.abs(cur - next) > OUTLIER && Math.abs(prev - next) <= AGREE) {
                wrong.add(acc.get(i));
            }
        }
        for (Measure m : wrong) {
            Piece p = m.piece;
            m.piece = new Piece(p.from, p.to, p.matched, p.total, p.shift, false, String.format(Locale.ROOT,
                    "сдвиг %+.2f с далёк от соседних кусков", p.shift));
        }
    }

    // --- sections ------------------------------------------------------------------------------------------

    private static List<Section> sections(List<Srt.Cue> tgt, double[] tgtStart, String[] tgtText,
                                          List<Srt.Cue> ref, String[] refText, List<Measure> accepted) {
        List<Section> sections = new ArrayList<>();
        if (accepted.isEmpty()) return sections;
        double[] refStart = starts(ref);
        double from = Double.NEGATIVE_INFINITY;
        for (int k = 0; k + 1 < accepted.size(); k++) {
            Measure a = accepted.get(k);
            Measure b = accepted.get(k + 1);
            double cut = Math.abs(a.piece.shift - b.piece.shift) < SAME_SHIFT ? (a.spanTo + b.spanFrom) / 2
                    : cut(tgt, tgtStart, a.byText && b.byText ? tgtText : null, refStart, refText, a, b);
            cut = Math.max(cut, from);
            sections.add(new Section(from, cut, a.piece.shift));
            from = cut;
        }
        sections.add(new Section(from, Double.POSITIVE_INFINITY, accepted.get(accepted.size() - 1).piece.shift));
        return sections;
    }

    // Where the shift changes from a's to b's. Each target cue from a's first matched cue to b's last one votes
    // for the shift that brings its start nearer to a reference cue start (within TOLERANCE; with the texts
    // given, of a cue with a similar text). The cut goes where the most cues fall on the side of their vote;
    // of the equally good places, into the longest pause between consecutive cues. Without contrary votes
    // that is the longest pause between the last cue matching a's shift and the first matching b's; a cue that
    // matches a's shift by chance past the step does not drag the cut along.
    private static double cut(List<Srt.Cue> tgt, double[] tgtStart, String[] tgtText, double[] refStart,
                              String[] refText, Measure a, Measure b) {
        double fallback = (a.spanTo + b.spanFrom) / 2;
        int i0 = lowerBound(tgtStart, a.spanFrom);
        int n = lowerBound(tgtStart, b.spanTo + 1e-9) - i0;
        if (n < 2) return fallback;
        // Votes for a's shift among the first k cues, and for b's.
        int[] forA = new int[n + 1];
        int[] forB = new int[n + 1];
        for (int k = 0; k < n; k++) {
            int i = i0 + k;
            String text = tgtText == null ? null : tgtText[i];
            double da = nearest(refStart, refText, tgt.get(i).start + a.piece.shift, text);
            double db = nearest(refStart, refText, tgt.get(i).start + b.piece.shift, text);
            forA[k + 1] = forA[k] + (da < db ? 1 : 0);
            forB[k + 1] = forB[k] + (db < da ? 1 : 0);
        }
        int bestScore = -1;
        double bestGap = Double.NEGATIVE_INFINITY;
        double cut = fallback;
        for (int k = 1; k < n; k++) {
            Srt.Cue prev = tgt.get(i0 + k - 1);
            Srt.Cue next = tgt.get(i0 + k);
            int score = forA[k] + forB[n] - forB[k];
            double gap = next.start - prev.end;
            if (score > bestScore || score == bestScore && gap > bestGap) {
                bestScore = score;
                bestGap = gap;
                // The middle of the pause, kept after the start of the cue before it.
                double mid = (prev.end + next.start) / 2;
                cut = mid > prev.start && mid <= next.start ? mid : next.start;
            }
        }
        return cut;
    }

    // The distance from x to the nearest reference cue start within TOLERANCE (with a text, of a cue with a
    // similar text); +Infinity when there is none.
    private static double nearest(double[] refStart, String[] refText, double x, String text) {
        double best = Double.POSITIVE_INFINITY;
        for (int j = lowerBound(refStart, x - TOLERANCE); j < refStart.length && refStart[j] <= x + TOLERANCE; j++) {
            double dist = Math.abs(refStart[j] - x);
            if (dist < best && (text == null || similarity(text, refText[j]) >= SIMILAR)) best = dist;
        }
        return best;
    }

    // --- text ----------------------------------------------------------------------------------------------

    private static List<Srt.Cue> sorted(List<Srt.Cue> cues) {
        List<Srt.Cue> s = new ArrayList<>(cues);
        Collections.sort(s, BY_START);
        return s;
    }

    private static List<Srt.Cue> dedup(List<Srt.Cue> cues) {
        String[] text = normalize(cues);
        List<Srt.Cue> kept = new ArrayList<>(cues.size());
        List<String> keptText = new ArrayList<>(cues.size());
        for (int i = 0; i < cues.size(); i++) {
            Srt.Cue c = cues.get(i);
            boolean dup = false;
            for (int k = kept.size() - 1; k >= 0 && c.start - kept.get(k).start <= DUPLICATE; k--) {
                if (!text[i].isEmpty() && text[i].equals(keptText.get(k))) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                kept.add(c);
                keptText.add(text[i]);
            }
        }
        return kept;
    }

    // Whether more than half of the letters of the cues are Cyrillic.
    private static boolean cyrillic(List<Srt.Cue> cues) {
        long letters = 0;
        long cyr = 0;
        for (Srt.Cue c : cues) {
            for (int i = 0; i < c.text.length(); i++) {
                char ch = c.text.charAt(i);
                if (!Character.isLetter(ch)) continue;
                letters++;
                if (Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.CYRILLIC) cyr++;
            }
        }
        return cyr * 2 > letters;
    }

    private static String[] normalize(List<Srt.Cue> cues) {
        String[] out = new String[cues.size()];
        for (int i = 0; i < out.length; i++) out[i] = normalize(cues.get(i).text);
        return out;
    }

    // Lower-case words of the phrase separated by single spaces.
    private static String normalize(String text) {
        String s = NOISE.matcher(text).replaceAll(" ");
        s = s.toLowerCase(Locale.ROOT).replace('ё', 'е');
        s = APOSTROPHE.matcher(s).replaceAll("");
        return NOT_WORD.matcher(s).replaceAll(" ").trim();
    }

    private static boolean similar(byte[] cache, int at, String a, String b) {
        if (cache[at] == 0) cache[at] = similarity(a, b) >= SIMILAR ? (byte) 1 : (byte) 2;
        return cache[at] == 1;
    }

    // The larger of the word overlap (Dice coefficient of the word multisets) and the edit ratio.
    private static double similarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        String[] wa = a.split(" ");
        String[] wb = b.split(" ");
        Arrays.sort(wa);
        Arrays.sort(wb);
        int common = 0;
        for (int i = 0, j = 0; i < wa.length && j < wb.length; ) {
            int c = wa[i].compareTo(wb[j]);
            if (c == 0) {
                common++;
                i++;
                j++;
            } else if (c < 0) {
                i++;
            } else {
                j++;
            }
        }
        double dice = 2.0 * common / (wa.length + wb.length);
        if (dice >= SIMILAR) return dice;
        return Math.max(dice, editRatio(a, b));
    }

    // 1 - Levenshtein distance / the longer length.
    private static double editRatio(String a, String b) {
        if (a.length() > EDIT_MAX) a = a.substring(0, EDIT_MAX);
        if (b.length() > EDIT_MAX) b = b.substring(0, EDIT_MAX);
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= b.length(); j++) {
                int sub = prev[j - 1] + (ca == b.charAt(j - 1) ? 0 : 1);
                cur[j] = Math.min(sub, Math.min(prev[j], cur[j - 1]) + 1);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return 1.0 - (double) prev[b.length()] / Math.max(a.length(), b.length());
    }

    // --- numbers -------------------------------------------------------------------------------------------

    private static double[] starts(List<Srt.Cue> cues) {
        double[] s = new double[cues.size()];
        for (int i = 0; i < s.length; i++) s[i] = cues.get(i).start;
        return s;
    }

    private static int lowerBound(double[] xs, double x) {
        int lo = 0;
        int hi = xs.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (xs[mid] < x) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    private static double median(List<Double> xs) {
        List<Double> s = new ArrayList<>(xs);
        Collections.sort(s);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2;
    }
}
