package xyz.vvzvlad.tvagentpanel;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Follows one PicoClaw session file (JSON Lines, appended while a turn runs) and renders its
 * entries verbatim as console lines. Only the tail of the file is ever parsed.
 */
final class Transcript {
    static final int WHITE = 0xFFE8E8E8;
    static final int DIM = 0xFF80808C;
    static final int ACCENT = 0xFFFFB86C;
    private static final int OUTPUT = 0xFFB0B0B0;

    // Enough lines to hold a whole turn; the screen shows only what fits at the bottom.
    private static final int MAX_LINES = 400;
    // A tool call and its output each take about one panel line: the call is cut to that width,
    // the output is shown as its first line only, cut the same way.
    private static final int RESULT_LINES = 1;
    private static final int LINE_CHARS = 100;
    private static final int FIRST_CHUNK = 256 * 1024;
    private static final int MAX_CHUNK = 8 * 1024 * 1024;

    // ⏺ (U+23FA) exists only in the colour emoji font on Android 9 and ignores the text colour,
    // and ⎿ (U+23BF) is in no Android 9 font at all, so the nearest glyphs that are present stand in.
    private static final String CALL_MARK = "● ";
    private static final String RESULT_MARK = "  └ ";

    private static final class Line {
        final String text;
        final int color;
        final boolean bold;

        Line(String text, int color, boolean bold) {
            this.text = text;
            this.color = color;
            this.bold = bold;
        }
    }

    private final ArrayDeque<Line> lines = new ArrayDeque<>();
    private File file;
    private long size = -1;
    private long mtime = -1;
    private long offset;
    private long lastUserMs;
    private long lastEntryMs;
    private boolean finished;
    private boolean empty = true;

    boolean isEmpty() {
        return empty;
    }

    /** A turn runs until the last entry is an assistant message without tool calls. */
    boolean inProgress() {
        return !empty && !finished;
    }

    long lastUserMs() {
        return lastUserMs;
    }

    long lastEntryMs() {
        return lastEntryMs;
    }

    /** Catches up with the given session file; returns true when the lines changed. */
    boolean poll(File newest) {
        if (newest == null) {
            boolean had = file != null;
            reset();
            file = null;
            return had;
        }
        long len = newest.length();
        long mt = newest.lastModified();
        if (newest.equals(file) && len == size && mt == mtime) {
            return false;
        }
        try {
            // Anything but a plain append (another file, a truncation, a rewrite) starts over.
            if (!newest.equals(file) || len < offset || len - offset > MAX_CHUNK
                    || !endsLineAt(newest, offset)) {
                reload(newest, len, mt);
            } else {
                append(newest, len, mt);
            }
            file = newest;
            size = len;
            mtime = mt;
        } catch (IOException e) {
            // The file changed under the read; the next poll starts over.
            reset();
            file = null;
        }
        return true;
    }

    CharSequence render() {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (Line l : lines) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            int start = sb.length();
            sb.append(l.text);
            if (l.text.isEmpty()) {
                continue;
            }
            sb.setSpan(new ForegroundColorSpan(l.color), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (l.bold) {
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return sb;
    }

    private void reset() {
        lines.clear();
        offset = 0;
        lastUserMs = 0;
        lastEntryMs = 0;
        finished = false;
        empty = true;
    }

    /** Re-reads a growing tail of the file until it reaches back to the start of the turn. */
    private void reload(File f, long len, long mt) throws IOException {
        for (int chunk = FIRST_CHUNK; ; chunk = Math.min(chunk * 4, MAX_CHUNK)) {
            long start = Math.max(0, len - chunk);
            byte[] buf = read(f, start, (int) (len - start));
            // A tail that starts mid-file starts mid-line: skip to the first full line.
            int from = start > 0 ? indexOf(buf, 0) + 1 : 0;
            int end = lastIndexOf(buf) + 1;
            List<JSONObject> entries = parse(buf, from, end);
            if (start == 0 || chunk == MAX_CHUNK || hasUser(entries)) {
                reset();
                for (JSONObject m : entries) {
                    apply(m, mt);
                }
                offset = start + end;
                return;
            }
        }
    }

    private void append(File f, long len, long mt) throws IOException {
        byte[] buf = read(f, offset, (int) (len - offset));
        // A line still being written is left for the next poll.
        int end = lastIndexOf(buf) + 1;
        for (JSONObject m : parse(buf, 0, end)) {
            apply(m, mt);
        }
        offset += end;
    }

    private static boolean endsLineAt(File f, long off) throws IOException {
        return off == 0 || read(f, off - 1, 1)[0] == '\n';
    }

    private static byte[] read(File f, long pos, int n) throws IOException {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            r.seek(pos);
            byte[] b = new byte[n];
            r.readFully(b);
            return b;
        }
    }

    private static int indexOf(byte[] b, int from) {
        for (int i = from; i < b.length; i++) {
            if (b[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexOf(byte[] b) {
        for (int i = b.length - 1; i >= 0; i--) {
            if (b[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    /** Parses the complete lines in [from, end); '\n' never occurs inside a UTF-8 sequence. */
    private static List<JSONObject> parse(byte[] buf, int from, int end) {
        List<JSONObject> out = new ArrayList<>();
        int pos = from;
        while (pos < end) {
            int nl = indexOf(buf, pos);
            String s = new String(buf, pos, nl - pos, StandardCharsets.UTF_8).trim();
            pos = nl + 1;
            if (s.isEmpty()) {
                continue;
            }
            try {
                out.add(new JSONObject(s));
            } catch (JSONException e) {
                // Not a JSON line; skipped.
            }
        }
        return out;
    }

    private static boolean hasUser(List<JSONObject> entries) {
        for (JSONObject m : entries) {
            if ("user".equals(str(m, "role"))) {
                return true;
            }
        }
        return false;
    }

    private void apply(JSONObject m, long fileMtime) {
        long ts = parseTime(str(m, "created_at"));
        if (ts <= 0) {
            ts = fileMtime;
        }
        lastEntryMs = ts;
        empty = false;
        finished = false;
        switch (str(m, "role")) {
            case "user":
                lastUserMs = ts;
                block();
                addText("› ", "  ", clean(text(m, "content")), WHITE, true);
                break;
            case "assistant":
                String reasoning = clean(str(m, "reasoning_content"));
                if (!reasoning.isEmpty()) {
                    block();
                    addText("", "", reasoning, DIM, false);
                }
                String content = clean(text(m, "content"));
                if (!content.isEmpty()) {
                    block();
                    addText("", "", content, WHITE, false);
                }
                JSONArray calls = m.optJSONArray("tool_calls");
                if (calls == null || calls.length() == 0) {
                    finished = true;
                } else {
                    for (int i = 0; i < calls.length(); i++) {
                        JSONObject call = calls.optJSONObject(i);
                        if (call != null) {
                            block();
                            toolCall(call);
                        }
                    }
                }
                break;
            case "tool":
                toolResult(clean(text(m, "content")));
                break;
            default:
                break;
        }
    }

    /** "● tool(command)" or "● tool({compact args})" on one cut line, then "  # status". */
    private void toolCall(JSONObject call) {
        JSONObject fn = call.optJSONObject("function");
        String name = fn != null ? str(fn, "name") : "";
        Object raw = fn != null ? fn.opt("arguments") : null;
        String detail = raw == null ? "" : String.valueOf(raw);
        String status = "";
        try {
            JSONObject args = raw instanceof JSONObject ? (JSONObject) raw : new JSONObject(detail);
            status = str(args, "status");
            Object command = args.opt("command");
            if (command instanceof String) {
                detail = (String) command;
            } else {
                args.remove("status");
                // Android's org.json escapes every '/', which turns paths into noise.
                detail = args.toString().replace("\\/", "/");
            }
        } catch (JSONException e) {
            // Arguments that are not a JSON object are shown as they came.
        }
        if (status.isEmpty()) {
            JSONObject extra = call.optJSONObject("extra_content");
            if (extra != null) {
                status = str(extra, "tool_feedback_explanation");
            }
        }
        String oneLine = detail.replace('\n', ' ');
        add(CALL_MARK + name + "(" + cut(oneLine, Math.max(10, LINE_CHARS - name.length() - 2)) + ")", ACCENT, false);
        if (!status.isEmpty()) {
            addText("  # ", "    ", status, DIM, false);
        }
    }

    /** "  └ " and the first lines of the output as they are, then "  … +N lines". */
    private void toolResult(String s) {
        int count = 0;
        int pos = 0;
        while (true) {
            int nl = s.indexOf('\n', pos);
            int end = nl < 0 ? s.length() : nl;
            if (count < RESULT_LINES) {
                add((count == 0 ? RESULT_MARK : "    ") + cut(s.substring(pos, end), LINE_CHARS), OUTPUT, false);
            }
            count++;
            if (nl < 0) {
                break;
            }
            pos = nl + 1;
        }
        if (count > RESULT_LINES) {
            add("  … +" + (count - RESULT_LINES) + " lines", DIM, false);
        }
    }

    /** Separates blocks with one blank line, the way a console transcript does. */
    private void block() {
        if (!lines.isEmpty() && !lines.peekLast().text.isEmpty()) {
            add("", WHITE, false);
        }
    }

    private void addText(String first, String rest, String text, int color, boolean bold) {
        String[] parts = text.split("\n", -1);
        for (int i = 0; i < parts.length; i++) {
            add((i == 0 ? first : rest) + parts[i], color, bold);
        }
    }

    private void add(String text, int color, boolean bold) {
        lines.addLast(new Line(text, color, bold));
        while (lines.size() > MAX_LINES) {
            lines.removeFirst();
        }
    }

    /** The field when it is a string, "" otherwise: Android's optString turns JSON null into "null". */
    private static String str(JSONObject o, String key) {
        Object v = o.opt(key);
        return v instanceof String ? (String) v : "";
    }

    /** Content is a string, or a list of parts whose "text" fields carry it. */
    private static String text(JSONObject m, String key) {
        Object o = m.opt(key);
        if (o instanceof String) {
            return (String) o;
        }
        if (o instanceof JSONArray) {
            JSONArray a = (JSONArray) o;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < a.length(); i++) {
                Object p = a.opt(i);
                String t = p instanceof JSONObject ? str((JSONObject) p, "text") : p instanceof String ? (String) p : "";
                if (!t.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(t);
                }
            }
            return sb.toString();
        }
        return "";
    }

    /** Drops carriage returns, leading blank lines and trailing whitespace; the rest stays as is. */
    private static String clean(String s) {
        s = s.replace("\r", "");
        int e = s.length();
        while (e > 0 && Character.isWhitespace(s.charAt(e - 1))) {
            e--;
        }
        int b = 0;
        while (b < e && s.charAt(b) == '\n') {
            b++;
        }
        return s.substring(b, e);
    }

    private static String cut(String s, int n) {
        if (s.length() <= n) {
            return s;
        }
        int k = Character.isHighSurrogate(s.charAt(n - 1)) ? n - 1 : n;
        return s.substring(0, k) + "…";
    }

    /** created_at is RFC 3339 with nanoseconds; returns 0 when absent or unreadable. */
    private static long parseTime(String s) {
        if (s.isEmpty()) {
            return 0;
        }
        try {
            return OffsetDateTime.parse(s).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
