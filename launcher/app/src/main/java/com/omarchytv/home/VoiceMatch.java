package com.omarchytv.home;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Fuzzy matching of a spoken phrase ("apri Netflix", "open YouTube", "metti la tv")
 * against app and web-app names (and the TV tile). Italian + English command words and number words are stripped
 * or normalised; the score mixes exact/prefix/token overlap with an edit-distance ratio.
 */
final class VoiceMatch {

    static final class Candidate {
        final String label;
        final String[] names;   // normalised alternatives (e.g. "rai 1", "1")
        final Runnable action;
        double score;

        Candidate(String label, Runnable action, String... names) {
            this.label = label;
            this.action = action;
            this.names = new String[names.length];
            for (int i = 0; i < names.length; i++) this.names[i] = normalise(names[i]);
        }
    }

    private static final String[] COMMANDS = {
            "apri", "avvia", "lancia", "metti", "vai su", "vai a", "guarda", "mostra", "fammi vedere", "accendi",
            "il canale", "canale", "su", "la", "il", "lo",
            "open", "launch", "start", "play", "watch", "show", "go to", "switch to", "the channel", "channel", "the"};
    private static final String[][] NUMBERS = {
            {"zero", "0"}, {"uno", "1"}, {"una", "1"}, {"due", "2"}, {"tre", "3"}, {"quattro", "4"}, {"cinque", "5"},
            {"sei", "6"}, {"sette", "7"}, {"otto", "8"}, {"nove", "9"}, {"dieci", "10"},
            {"one", "1"}, {"two", "2"}, {"three", "3"}, {"four", "4"}, {"five", "5"}, {"six", "6"},
            {"seven", "7"}, {"eight", "8"}, {"nine", "9"}, {"ten", "10"}, {"plus", "+"}, {"più", "+"}, {"piu", "+"}};

    private VoiceMatch() {
    }

    static String normalise(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        n = n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9+ ]+", " ").replaceAll("\\s+", " ").trim();
        StringBuilder b = new StringBuilder();
        for (String w : n.split(" ")) {
            String out = w;
            for (String[] p : NUMBERS) if (p[0].equals(w)) out = p[1];
            if (b.length() > 0) b.append(' ');
            b.append(out);
        }
        return b.toString();
    }

    /** Strips leading command words ("apri", "open", "canale", ...). */
    static String query(String spoken) {
        String q = " " + normalise(spoken) + " ";
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String c : COMMANDS) {
                String cn = " " + normalise(c) + " ";
                if (q.startsWith(cn) && q.length() > cn.length()) {
                    q = " " + q.substring(cn.length());
                    changed = true;
                }
            }
        }
        return q.trim();
    }

    /** Ranks candidates for all recognizer alternatives; returns the best few with score > 0.45. */
    static List<Candidate> rank(List<String> alternatives, List<Candidate> candidates) {
        for (Candidate c : candidates) {
            c.score = 0;
            for (String alt : alternatives) {
                String q = query(alt);
                if (q.isEmpty()) continue;
                for (String n : c.names) c.score = Math.max(c.score, score(q, n));
            }
        }
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : candidates) if (c.score > 0.45) out.add(c);
        Collections.sort(out, (a, b) -> Double.compare(b.score, a.score));
        return out.size() > 6 ? new ArrayList<>(out.subList(0, 6)) : out;
    }

    static double score(String q, String name) {
        if (name.isEmpty()) return 0;
        if (q.equals(name)) return 1.0;
        if (name.startsWith(q + " ") || q.startsWith(name + " ")) return 0.9;
        String nc = name.replace(" ", ""), qc = q.replace(" ", "");
        if (nc.equals(qc)) return 0.97;
        // token overlap
        String[] qt = q.split(" "), nt = name.split(" ");
        int hit = 0;
        for (String a : qt) for (String b : nt) if (a.equals(b)) {
            hit++;
            break;
        }
        double overlap = hit / (double) Math.max(qt.length, nt.length);
        double ratio = 1.0 - levenshtein(qc, nc) / (double) Math.max(qc.length(), nc.length());
        return Math.max(overlap * 0.85, ratio * 0.95);
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
