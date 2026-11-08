package xyz.vvzvlad.popcornclaw;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.SocketFactory;

import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The file side of tv_mcp's subtitle tools: {@code transcribe} (speech of what mpv plays, cut with the bundled
 * ffmpeg and recognised by Groq's Whisper, into an .srt) and {@code sync_subtitles} (one .srt aligned to another
 * with {@link SubSync}). Their files go to {@link #SUBS_DIR}, root-written and mode 644, so mpv (another uid)
 * reads them.
 */
final class SubTools {
    private static final String TAG = "PopcornClaw-subs";
    static final String SUBS_DIR = "/data/local/popcornclaw/subs";

    private static final String GROQ_URL = "https://api.groq.com/openai/v1/audio/transcriptions";
    private static final String GROQ_MODEL = "whisper-large-v3-turbo";
    private static final MediaType FLAC = MediaType.parse("audio/flac");
    // Groq answers in 1-2 s; the rest is the ~2 MB upload of a minute through the proxy.
    private static final int GROQ_TIMEOUT = 120;
    // ffmpeg's wait for one piece: 180 s plus twice its length. A minute took 7-143 s on the box, the slow end
    // where the torrent had not been downloaded yet.
    private static final int FFMPEG_BASE_TIMEOUT = 180;
    private static final long ROOT_TIMEOUT_MS = 15_000;
    private static final int BASE_LIMIT = 100;
    // A phrase starting this close to its piece's start began before the cut, one ending this close to its end
    // goes on after it: either is partial.
    private static final double CUT_ONSET = 0.5;

    // A subtitle file's language: the two- or three-letter code before its extension, after ".", "_" or "-"
    // ("house416.ru.srt", "House, M.D. - S04E15-en.srt"); fileLang keeps only real language codes of it.
    private static final Pattern FILE_LANG = Pattern.compile("[._-]([A-Za-z]{2,3})\\.[A-Za-z0-9]+$");
    private static final Pattern START = Pattern.compile("\\d{1,6}(:\\d{1,2}){0,2}");
    private static final Pattern SPOKEN = Pattern.compile("[\\p{L}\\p{N}]");

    private final String ffmpeg;
    private final File cacheDir;

    SubTools(String ffmpeg, File cacheDir) {
        this.ffmpeg = ffmpeg;
        this.cacheDir = cacheDir;
    }

    // --- transcribe ----------------------------------------------------------------------------------------

    /**
     * Pieces of {@code seconds} s from each start of {@code at}, of the audio track {@code audio} (language or
     * track number; null = the one playing) of mpv's item, recognised into cues with absolute times and merged
     * into {@code SUBS_DIR/<title>.speech.<lang>.srt}: its cues in the span each piece's whole phrases cover (from
     * CUT_ONSET after the piece's start to the end of its last whole phrase) are replaced, the rest kept.
     */
    String transcribe(String at, int seconds, String audio) throws ToolError {
        if (seconds < 10 || seconds > 300) throw new ToolError("seconds is 10 to 300, not " + seconds);
        List<Integer> starts = parseStarts(at);
        String media;
        String title;
        int ffIndex;
        String lang;
        MpvIpc ipc = MpvIpc.open();
        if (ipc == null) throw new ToolError("Nothing is playing");
        try {
            Object path = ipc.get("path");
            if (path == null) throw new ToolError("Nothing is playing");
            media = path.toString();
            Object t = ipc.get("media-title");
            title = t != null ? t.toString() : TvFormat.urlPathBasename(media);
            JSONObject track = audioTrack(TvFormat.asArray(ipc.get("track-list")), audio);
            if (!track.has("ff-index")) {
                throw new ToolError("The audio track " + TvFormat.describeAudio(track) + " has no stream number in the file");
            }
            ffIndex = track.optInt("ff-index");
            lang = groqLanguage(track.optString("lang", ""));
        } catch (MpvIpc.MpvError e) {
            throw new ToolError(e.getMessage());
        } finally {
            ipc.close();
        }
        String key = ControlServer.ymlExtract(ControlServer.YML_ASR, TvMcpServer.readFileRoot(ControlServer.SECURITY_PATH));
        if (key.isEmpty()) throw new ToolError("No Groq key: groq-asr in " + ControlServer.SECURITY_PATH);
        String proxy = asrProxy();
        OkHttpClient client = groqClient(proxy);
        boolean proxied = !proxy.trim().isEmpty();
        String file = SUBS_DIR + "/" + baseName(title) + ".speech." + (lang == null ? "und" : lang) + ".srt";

        List<String> lines = new ArrayList<>();
        List<Srt.Cue> fresh = new ArrayList<>();
        List<double[]> ranges = new ArrayList<>();
        for (int start : starts) {
            String range = clock(start) + "–" + clock(start + seconds);
            File flac;
            try {
                flac = File.createTempFile("transcribe", ".flac", cacheDir);
            } catch (IOException e) {
                throw new ToolError("Cannot create a temporary file in " + cacheDir + ": " + e.getMessage());
            }
            try {
                String failed = cut(media, ffIndex, start, seconds, flac);
                if (failed != null) {
                    lines.add(range + ": " + failed);
                    continue;
                }
                List<Srt.Cue> cues = whole(cues(groq(client, key, flac, lang, proxied), start), start, seconds);
                double[] covered = covered(cues, start);
                ranges.add(covered);
                // Pieces of one call may overlap: the later one replaces what it covers, nothing is written twice.
                fresh = merge(fresh, cues, Collections.singletonList(covered));
                lines.add(range + ": " + cues.size() + (cues.size() == 1 ? " phrase" : " phrases"));
                for (Srt.Cue c : cues) lines.add(clockTenths(c.start) + " " + c.text.replace('\n', ' '));
            } catch (ToolError e) {
                lines.add(range + ": " + e.getMessage());
            } finally {
                if (!flac.delete()) Log.w(TAG, "transcribe: cannot delete " + flac);
            }
        }
        if (ranges.isEmpty()) throw new ToolError(TvFormat.join(lines));
        byte[] old = readRoot(file, true);
        List<Srt.Cue> merged = merge(old == null ? Collections.<Srt.Cue>emptyList() : Srt.parse(old), fresh, ranges);
        writeSubs(file, Srt.format(merged));
        lines.add(0, "File: " + file);
        return TvFormat.join(lines);
    }

    /** {@code cues} of the piece [start, start + seconds) without the phrases its end cuts: their text is partial. */
    static List<Srt.Cue> whole(List<Srt.Cue> cues, int start, int seconds) {
        List<Srt.Cue> out = new ArrayList<>();
        for (Srt.Cue c : cues) {
            if (c.end <= start + seconds - CUT_ONSET) out.add(c);
        }
        return out;
    }

    /**
     * The span a piece's whole phrases vouch for: from CUT_ONSET after its start (earlier phrases were cut by
     * the start) to the end of its last whole phrase. Older phrases outside it keep their whole versions.
     */
    static double[] covered(List<Srt.Cue> whole, int start) {
        double until = start + CUT_ONSET;
        for (Srt.Cue c : whole) until = Math.max(until, c.end);
        return new double[]{start + CUT_ONSET, until};
    }

    /** The start positions of {@code at}: comma-separated "H:MM:SS", "MM:SS" or seconds. */
    static List<Integer> parseStarts(String at) throws ToolError {
        List<Integer> starts = new ArrayList<>();
        for (String part : (at == null ? "" : at).trim().split("[,;\\s]+")) {
            if (part.isEmpty()) continue;
            if (!START.matcher(part).matches()) {
                throw new ToolError("at is a comma-separated list of places: \"H:MM:SS\", \"MM:SS\" or seconds, not \"" + part + "\"");
            }
            int t = 0;
            for (String p : part.split(":")) t = t * 60 + Integer.parseInt(p);
            starts.add(t);
        }
        if (starts.isEmpty()) throw new ToolError("transcribe needs at: a comma-separated list of places (\"2:00,10:00\")");
        return starts;
    }

    /** The audio track {@code audio} names (a track number or a language), or the one playing when null. */
    static JSONObject audioTrack(JSONArray tracks, String audio) throws ToolError {
        List<JSONObject> voices = TvFormat.tracksOfType(tracks, "audio");
        JSONObject playing = TvFormat.selected(tracks, "audio", 0);
        if (audio == null || audio.trim().isEmpty()) {
            if (playing == null) throw new ToolError("Audio is off; audio tracks: " + TvFormat.describeAudios(voices));
            return playing;
        }
        String v = audio.trim();
        JSONObject track = null;
        if (v.matches("\\d+")) {
            for (JSONObject t : voices) {
                if (String.valueOf(t.optInt("id")).equals(v)) { track = t; break; }
            }
        } else {
            track = TvFormat.pickAudio(voices, v, playing);
        }
        if (track == null) throw new ToolError("No audio track \"" + v + "\"; there are: " + TvFormat.describeAudios(voices));
        return track;
    }

    /** Whisper's ISO-639-1 language for a track's lang (eng → en, jpn → ja), or null when it is not known. */
    static String groqLanguage(String lang) {
        if (lang == null || lang.isEmpty() || "und".equalsIgnoreCase(lang)) return null;
        // An unknown code cut to two letters is another language ("kaz" -> "ka", Georgian): send none then,
        // Whisper detects the language itself.
        return iso6391(lang);
    }

    /** The ISO 639-1 code of a two- or three-letter language code when it is a real one, else null. */
    static String iso6391(String code) {
        // A BCP 47 tag ("en-US") names its language first.
        String c = code.toLowerCase(Locale.ROOT).split("[-_]")[0];
        String mapped = TvFormat.ISO_639_2.get(c);
        if (mapped != null) return mapped;
        if (c.length() == 2) return Arrays.asList(Locale.getISOLanguages()).contains(c) ? c : null;
        if (c.length() != 3) return null;
        String two = c.substring(0, 2);
        try {
            return new Locale(two).getISO3Language().equals(c) ? two : null;
        } catch (MissingResourceException e) {
            return null;
        }
    }

    /** Cuts {@code seconds} s of stream {@code ffIndex} of {@code media} from {@code start} into {@code out} as
     *  16 kHz mono FLAC, running ffmpeg as the app's own uid; null when it did, else what went wrong. */
    private String cut(String media, int ffIndex, int start, int seconds, File out) {
        long timeoutMs = (FFMPEG_BASE_TIMEOUT + 2L * seconds) * 1000L;
        Process proc = null;
        try {
            proc = new ProcessBuilder(ffmpeg, "-nostdin", "-v", "error", "-y", "-ss", String.valueOf(start),
                    "-i", media, "-t", String.valueOf(seconds), "-map", "0:" + ffIndex, "-vn", "-ac", "1",
                    "-ar", "16000", "-c:a", "flac", out.getPath()).redirectErrorStream(true).start();
            proc.getOutputStream().close();
            Run run = drain(proc, timeoutMs);
            if (run.timedOut) return "ffmpeg did not finish in " + (timeoutMs / 1000) + " s";
            if (run.code != 0) {
                String said = lastLine(new String(run.out, StandardCharsets.UTF_8).trim());
                return "ffmpeg: exit code " + run.code + (said.isEmpty() ? "" : ": " + said);
            }
            if (out.length() == 0) return "ffmpeg: no audio in the piece";
            return null;
        } catch (IOException e) {
            if (proc != null) proc.destroy();
            return "ffmpeg did not start: " + e.getMessage();
        }
    }

    /** Groq's verbose_json answer for the FLAC piece; ToolError with the HTTP code (or the network failure). */
    static JSONObject groq(OkHttpClient client, String key, File flac, String language, boolean proxied)
            throws ToolError {
        MultipartBody.Builder form = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", GROQ_MODEL)
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart("timestamp_granularities[]", "word")
                .addFormDataPart("timestamp_granularities[]", "segment")
                .addFormDataPart("temperature", "0");
        if (language != null) form.addFormDataPart("language", language);
        form.addFormDataPart("file", "piece.flac", RequestBody.create(flac, FLAC));
        Request req = new Request.Builder().url(GROQ_URL).header("Authorization", "Bearer " + key)
                .post(form.build()).build();
        try (Response resp = client.newCall(req).execute()) {
            ResponseBody body = resp.body();
            String text = body == null ? "" : body.string();
            if (!resp.isSuccessful()) throw new ToolError("Groq HTTP " + resp.code() + groqMessage(text));
            try {
                return new JSONObject(text);
            } catch (JSONException e) {
                throw new ToolError("Groq did not answer with JSON");
            }
        } catch (IOException e) {
            // A failed proxy connection's message names the proxy's host and port: keep it out of the reply.
            throw new ToolError(proxied ? "Groq: no connection through the groq-asr proxy (" + e.getClass().getSimpleName() + ")"
                    : "Groq: " + e.getMessage());
        }
    }

    /** ": <error.message>" of a Groq error body, cut to 200 characters; "" when it has none. */
    private static String groqMessage(String body) {
        try {
            JSONObject error = new JSONObject(body).optJSONObject("error");
            String m = error == null ? "" : error.optString("message", "");
            if (m.length() > 200) m = m.substring(0, 200) + "…";
            return m.isEmpty() ? "" : ": " + m;
        } catch (JSONException e) {
            return "";
        }
    }

    /**
     * Cues of a Groq verbose_json answer, shifted by {@code offset}: each segment's text from its first word's
     * start to its last word's end. Onsets come from words only: segment starts were whole seconds in several
     * windows, up to 4.3 s off, so a segment no word falls in is left out, and so is one starting in the piece's
     * first CUT_ONSET s, cut by the piece's start. Words go to segments in order when their counts agree (only
     * tokens with a letter or digit count: Whisper merges lone punctuation into a neighbour), so a segment's wrong
     * bounds do not move a word into its neighbour; otherwise a word belongs to the segment its middle falls in,
     * else to the nearest one.
     */
    static List<Srt.Cue> cues(JSONObject answer, double offset) {
        JSONArray segments = answer.optJSONArray("segments");
        JSONArray words = answer.optJSONArray("words");
        int n = segments == null ? 0 : segments.length();
        double[] first = new double[n];
        double[] last = new double[n];
        for (int i = 0; i < n; i++) {
            first[i] = Double.NaN;
            last[i] = Double.NaN;
        }
        List<JSONObject> spoken = new ArrayList<>();
        for (int k = 0; words != null && k < words.length(); k++) {
            JSONObject w = words.optJSONObject(k);
            if (w == null || Double.isNaN(w.optDouble("start")) || Double.isNaN(w.optDouble("end"))) continue;
            if (SPOKEN.matcher(w.optString("word", "")).find()) spoken.add(w);
        }
        int[] counts = new int[n];
        int total = 0;
        for (int i = 0; i < n; i++) {
            JSONObject s = segments.optJSONObject(i);
            for (String token : (s == null ? "" : s.optString("text", "")).trim().split("\\s+")) {
                if (SPOKEN.matcher(token).find()) counts[i]++;
            }
            total += counts[i];
        }
        // The segment of each spoken word, in order, when the counts agree.
        int[] owner = null;
        if (total == spoken.size()) {
            owner = new int[total];
            int k = 0;
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < counts[i]; j++) owner[k++] = i;
            }
        }
        for (int k = 0; k < spoken.size(); k++) {
            JSONObject w = spoken.get(k);
            double ws = w.optDouble("start");
            double we = w.optDouble("end");
            int i = owner != null ? owner[k] : segmentOf(segments, (ws + we) / 2);
            if (i < 0) continue;
            first[i] = Double.isNaN(first[i]) ? ws : Math.min(first[i], ws);
            last[i] = Double.isNaN(last[i]) ? we : Math.max(last[i], we);
        }
        List<Srt.Cue> cues = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            JSONObject s = segments.optJSONObject(i);
            if (s == null) continue;
            String text = s.optString("text", "").trim();
            if (text.isEmpty() || Double.isNaN(first[i]) || first[i] < CUT_ONSET) continue;
            cues.add(new Srt.Cue(offset + first[i], offset + Math.max(first[i], last[i]), text));
        }
        return cues;
    }

    /** The segment whose [start, end) holds {@code t}, else the nearest one; -1 when there are none. */
    private static int segmentOf(JSONArray segments, double t) {
        int best = -1;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int i = 0; segments != null && i < segments.length(); i++) {
            JSONObject s = segments.optJSONObject(i);
            if (s == null) continue;
            double start = s.optDouble("start", 0);
            double end = s.optDouble("end", start);
            if (t >= start && t < end) return i;
            double d = t < start ? start - t : t - end;
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    /** {@code old} without its cues that start inside one of {@code ranges} ([from, to) s), plus {@code fresh}. */
    static List<Srt.Cue> merge(List<Srt.Cue> old, List<Srt.Cue> fresh, List<double[]> ranges) {
        List<Srt.Cue> out = new ArrayList<>();
        for (Srt.Cue c : old) {
            boolean replaced = false;
            for (double[] r : ranges) {
                if (c.start >= r[0] && c.start < r[1]) { replaced = true; break; }
            }
            if (!replaced) out.add(c);
        }
        out.addAll(fresh);
        return out;
    }

    // --- sync_subtitles ------------------------------------------------------------------------------------

    /**
     * {@code subtitles} aligned to {@code reference} (each a file path or an external subtitle track's number in
     * mpv), written to {@code SUBS_DIR/<base>.sync.<lang>.srt}. mpv is only read, for the track numbers.
     */
    String sync(String subtitles, String reference) throws ToolError {
        String targetPath = subtitles.trim();
        String referencePath = reference.trim();
        String trackLang = null;
        if (targetPath.matches("\\d+") || referencePath.matches("\\d+")) {
            MpvIpc ipc = MpvIpc.open();
            if (ipc == null) throw new ToolError("Nothing is playing: a subtitle track number refers to nothing");
            try {
                if (ipc.get("path") == null) throw new ToolError("Nothing is playing: a subtitle track number refers to nothing");
                JSONArray tracks = TvFormat.asArray(ipc.get("track-list"));
                if (targetPath.matches("\\d+")) {
                    JSONObject t = externalTrack(tracks, targetPath, "it needs no aligning: take an external file");
                    targetPath = t.optString("external-filename");
                    String l = t.optString("lang", "");
                    trackLang = l.isEmpty() ? null : l;
                }
                if (referencePath.matches("\\d+")) {
                    referencePath = externalTrack(tracks, referencePath, "the reference must be an external file")
                            .optString("external-filename");
                }
            } catch (MpvIpc.MpvError e) {
                throw new ToolError(e.getMessage());
            } finally {
                ipc.close();
            }
        }
        List<Srt.Cue> target = Srt.parse(readRoot(targetPath, false));
        if (target.isEmpty()) throw new ToolError("No lines in " + targetPath);
        List<Srt.Cue> ref = Srt.parse(readRoot(referencePath, false));
        if (ref.isEmpty()) throw new ToolError("No lines in " + referencePath);
        SubSync.Result result = SubSync.align(target, ref);
        List<String> rejected = new ArrayList<>();
        boolean accepted = false;
        for (SubSync.Piece p : result.pieces) {
            if (p.accepted) accepted = true;
            else rejected.add(clock(p.from) + "–" + clock(p.to) + ": " + p.reason);
        }
        if (!accepted) {
            throw new ToolError("No reference piece matched the subtitles"
                    + (rejected.isEmpty() ? "" : ": " + join(rejected, "; ")));
        }
        String lang = fileLang(targetPath);
        if (lang == null) lang = trackLang;
        String file = SUBS_DIR + "/" + srtBase(targetPath) + ".sync." + (lang == null ? "und" : lang) + ".srt";
        writeSubs(file, Srt.format(result.shifted));
        List<String> lines = new ArrayList<>();
        lines.add("File: " + file);
        lines.add("Shifts: " + sections(result.sections));
        if (!rejected.isEmpty()) lines.add("Did not match: " + join(rejected, "; "));
        List<double[]> guessed = unmeasured(result.pieces, result.shifted);
        if (!guessed.isEmpty()) {
            List<String> spans = new ArrayList<>();
            List<String> at = new ArrayList<>();
            for (double[] g : guessed) {
                spans.add(clock(g[0]) + "–" + clock(g[1]) + (g[2] != 0 ? " (a step somewhere here)" : ""));
                if (!Double.isNaN(g[3])) at.add(clock(g[3]));
            }
            lines.add("Stretches where no reference piece matched and the shift is a guess: " + join(spans, "; ")
                    + (at.isEmpty() ? "" : ". Recognise one more piece in each (transcribe at=" + join(at, ",")
                    + ") and align again to what was recognised; do not repeat a place where transcribe recognised nothing"));
        }
        return TvFormat.join(lines);
    }

    // A stretch without an accepted piece of the reference longer than this has its shift guessed.
    private static final double UNCOVERED = 300;
    // Between two accepted pieces whose shifts differ (SubSync puts the step into a pause of the subtitles
    // there), a gap longer than this leaves its place a guess.
    private static final double STEP_UNPINNED = 120;
    // A new piece needs this much room no reference piece touches: its default 60 s with 30 s on each side,
    // more than SubSync's 20 s gap, or it merges into its neighbours.
    private static final double ROOM = 120;

    /**
     * The stretches of the item ({from, to, 1 when a step lies inside else 0, where to start a 60 s
     * transcribe piece or NaN}, item time) whose shift no accepted piece measured: a gap of more than
     * STEP_UNPINNED s between pieces with different shifts, and any other stretch without a piece longer than
     * UNCOVERED s, from the first shifted cue to the last. The piece goes into the middle of the longest part
     * of the stretch that no piece, rejected ones included, touches: a place that gave a piece, even a rejected
     * one, is not offered again; one where nothing was recognised is.
     */
    static List<double[]> unmeasured(List<SubSync.Piece> pieces, List<Srt.Cue> shifted) {
        List<double[]> out = new ArrayList<>();
        for (double[] g : stretches(pieces, shifted)) {
            out.add(new double[]{g[0], g[1], g[2], position(g[0], g[1], pieces)});
        }
        return out;
    }

    // Where in [from, to) a 60 s piece fits best, NaN when no part of it free of pieces has ROOM.
    private static double position(double from, double to, List<SubSync.Piece> pieces) {
        double bestFrom = from;
        double bestTo = from;
        double cur = from;
        for (SubSync.Piece p : pieces) {
            if (p.to <= from || p.from >= to) continue;
            if (p.from - cur > bestTo - bestFrom) {
                bestFrom = cur;
                bestTo = p.from;
            }
            cur = Math.max(cur, p.to);
        }
        if (to - cur > bestTo - bestFrom) {
            bestFrom = cur;
            bestTo = to;
        }
        return bestTo - bestFrom < ROOM ? Double.NaN : (bestFrom + bestTo) / 2 - 30;
    }

    private static List<double[]> stretches(List<SubSync.Piece> pieces, List<Srt.Cue> shifted) {
        List<SubSync.Piece> acc = new ArrayList<>();
        for (SubSync.Piece p : pieces) if (p.accepted) acc.add(p);
        List<double[]> out = new ArrayList<>();
        if (acc.isEmpty() || shifted.isEmpty()) return out;
        double first = Double.POSITIVE_INFINITY;
        double last = Double.NEGATIVE_INFINITY;
        for (Srt.Cue c : shifted) {
            first = Math.min(first, c.start);
            last = Math.max(last, c.end);
        }
        if (acc.get(0).from - first > UNCOVERED) out.add(new double[]{first, acc.get(0).from, 0});
        for (int k = 0; k + 1 < acc.size(); k++) {
            SubSync.Piece a = acc.get(k);
            SubSync.Piece b = acc.get(k + 1);
            double gap = b.from - a.to;
            boolean step = Math.abs(a.shift - b.shift) >= SubSync.SAME_SHIFT;
            if (step ? gap > STEP_UNPINNED : gap > UNCOVERED) out.add(new double[]{a.to, b.from, step ? 1 : 0});
        }
        SubSync.Piece end = acc.get(acc.size() - 1);
        if (last - end.to > UNCOVERED) out.add(new double[]{end.to, last, 0});
        return out;
    }

    /** The subtitle track {@code number} of {@code tracks}, which must be an external file ({@code internal}
     *  says what to do instead). */
    private static JSONObject externalTrack(JSONArray tracks, String number, String internal) throws ToolError {
        List<JSONObject> subs = TvFormat.tracksOfType(tracks, "sub");
        for (JSONObject t : subs) {
            if (!String.valueOf(t.optInt("id")).equals(number)) continue;
            if (!t.optBoolean("external", false) || t.optString("external-filename", "").isEmpty()) {
                throw new ToolError("Subtitle track #" + number + " is inside the video file itself, " + internal);
            }
            return t;
        }
        throw new ToolError("No subtitle track #" + number + "; there are: " + TvFormat.describeSubtitles(subs));
    }

    /** "until 12:03 +3.0 s; 12:03–17:12 +1.8 s; from 17:12 -0.4 s", "whole file +3.0 s" for one section. */
    static String sections(List<SubSync.Section> sections) {
        if (sections.size() == 1) return "whole file " + shift(sections.get(0).shift);
        List<String> parts = new ArrayList<>();
        for (SubSync.Section s : sections) {
            String where;
            if (Double.isInfinite(s.from) && Double.isInfinite(s.to)) where = "whole file";
            else if (Double.isInfinite(s.from)) where = "until " + clock(s.to);
            else if (Double.isInfinite(s.to)) where = "from " + clock(s.from);
            else where = clock(s.from) + "–" + clock(s.to);
            parts.add(where + " " + shift(s.shift));
        }
        return join(parts, "; ");
    }

    /** A shift as "+3.0 s". */
    private static String shift(double seconds) {
        return String.format(Locale.ROOT, "%+.1f", seconds) + " s";
    }

    // --- names ---------------------------------------------------------------------------------------------

    /** A subtitle file's language from its name («house416.ru.srt» → ru), lowercased; null without one. */
    static String fileLang(String path) {
        Matcher m = FILE_LANG.matcher(TvFormat.basename(path));
        if (!m.find()) return null;
        // Release tags in that place («.WEB.srt», «.lol.srt») are not languages.
        String code = m.group(1).toLowerCase(Locale.ROOT);
        return iso6391(code) != null ? code : null;
    }

    /** The name part of a subtitle file, without its extension and language («house416.ru.srt» → house416). */
    static String srtBase(String path) {
        String name = TvFormat.basename(path);
        Matcher m = FILE_LANG.matcher(name);
        if (m.find() && fileLang(name) != null) {
            name = name.substring(0, m.start());
        } else {
            int dot = name.lastIndexOf('.');
            if (dot > 0) name = name.substring(0, dot);
        }
        return sanitise(name);
    }

    /** A media title as a file name: without its video extension, only letters, digits, «.», «-» and «_». */
    static String baseName(String title) {
        String t = title == null ? "" : title.trim();
        String lower = t.toLowerCase(Locale.ROOT);
        for (String ext : TvFormat.VIDEO_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                t = t.substring(0, t.length() - ext.length());
                break;
            }
        }
        return sanitise(t);
    }

    private static String sanitise(String name) {
        String s = name.replaceAll("[^\\p{L}\\p{N}._-]+", "_").replaceAll("^[._]+|[._]+$", "");
        if (s.length() > BASE_LIMIT) s = s.substring(0, BASE_LIMIT);
        return s.isEmpty() ? "media" : s;
    }

    /** "MM:SS", "H:MM:SS" from an hour on. */
    static String clock(double seconds) {
        long t = (long) Math.floor(Math.max(0, seconds));
        String mmss = String.format(Locale.ROOT, "%02d:%02d", t / 60 % 60, t % 60);
        return t >= 3600 ? (t / 3600) + ":" + mmss : mmss;
    }

    /** "MM:SS.s": the clock with tenths of a second. */
    static String clockTenths(double seconds) {
        long tenths = Math.round(Math.max(0, seconds) * 10);
        return clock(tenths / 10) + "." + (tenths % 10);
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(p);
        }
        return sb.toString();
    }

    private static String lastLine(String s) {
        int i = s.lastIndexOf('\n');
        return i < 0 ? s : s.substring(i + 1);
    }

    // --- Groq through the groq-asr proxy -------------------------------------------------------------------

    /** The groq-asr model's proxy from config.json, "" for none. */
    private static String asrProxy() throws ToolError {
        String text = TvMcpServer.readFileRoot(ControlServer.CONFIG_PATH);
        if (text == null) throw new ToolError("Cannot read " + ControlServer.CONFIG_PATH);
        try {
            JSONObject model = ControlServer.modelEntry(new JSONObject(text), "groq-asr");
            return model == null ? "" : model.optString("proxy", "");
        } catch (JSONException e) {
            // The parser's message quotes the file, which holds MCP headers: say nothing of it.
            throw new ToolError("Cannot parse " + ControlServer.CONFIG_PATH);
        }
    }

    /**
     * An OkHttp client for Groq, through {@code proxy} when it is set: socks5:// (socks5h:// alike) or
     * http://, with or without user:pass, as the gateway's Go client takes them. The proxy's value never goes
     * into a message.
     */
    static OkHttpClient groqClient(String proxy) throws ToolError {
        OkHttpClient.Builder b = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(GROQ_TIMEOUT, TimeUnit.SECONDS)
                .writeTimeout(GROQ_TIMEOUT, TimeUnit.SECONDS);
        if (proxy == null || proxy.trim().isEmpty()) return b.proxy(Proxy.NO_PROXY).build();
        URI u;
        try {
            u = new URI(proxy.trim());
        } catch (URISyntaxException e) {
            throw new ToolError("Cannot parse the groq-asr proxy in config.json");
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        String host = u.getHost();
        if (host == null) throw new ToolError("Cannot parse the groq-asr proxy in config.json");
        String user = null;
        String pass = null;
        String info = u.getUserInfo();
        if (info != null) {
            int colon = info.indexOf(':');
            user = colon < 0 ? info : info.substring(0, colon);
            pass = colon < 0 ? "" : info.substring(colon + 1);
        }
        switch (scheme) {
            case "socks5":
            case "socks5h": {
                // The proxy resolves the host: the socket gets its name, never a local lookup.
                return b.proxy(Proxy.NO_PROXY)
                        .dns(hostname -> Collections.singletonList(InetAddress.getByAddress(hostname, new byte[4])))
                        .socketFactory(new Socks5(host, u.getPort() < 0 ? 1080 : u.getPort(), user, pass))
                        .build();
            }
            case "http": {
                b.proxy(new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host,
                        u.getPort() < 0 ? 80 : u.getPort())));
                if (user != null) {
                    String credential = Credentials.basic(user, pass);
                    b.proxyAuthenticator((route, response) -> response.request().header("Proxy-Authorization") != null
                            ? null
                            : response.request().newBuilder().header("Proxy-Authorization", credential).build());
                }
                return b.build();
            }
            default:
                throw new ToolError("groq-asr proxy: the scheme \"" + scheme + "://\" is not supported, only socks5:// and http://");
        }
    }

    /**
     * Sockets whose connect goes through a SOCKS5 proxy (RFC 1928), asking for the endpoint by its host name,
     * with username/password authentication (RFC 1929) when a user is given.
     */
    static final class Socks5 extends SocketFactory {
        private final String host;
        private final int port;
        private final String user;
        private final String pass;

        Socks5(String host, int port, String user, String pass) {
            this.host = host;
            this.port = port;
            this.user = user;
            this.pass = pass;
        }

        @Override
        public Socket createSocket() {
            return new Socket() {
                @Override
                public void connect(SocketAddress endpoint, int timeout) throws IOException {
                    super.connect(new InetSocketAddress(host, port), timeout);
                    handshake(this, (InetSocketAddress) endpoint, timeout);
                }
            };
        }

        @Override
        public Socket createSocket(String h, int p) throws IOException {
            Socket s = createSocket();
            s.connect(InetSocketAddress.createUnresolved(h, p));
            return s;
        }

        @Override
        public Socket createSocket(String h, int p, InetAddress localHost, int localPort) throws IOException {
            return createSocket(h, p);
        }

        @Override
        public Socket createSocket(InetAddress a, int p) throws IOException {
            Socket s = createSocket();
            s.connect(new InetSocketAddress(a, p));
            return s;
        }

        @Override
        public Socket createSocket(InetAddress a, int p, InetAddress localAddress, int localPort) throws IOException {
            return createSocket(a, p);
        }

        private void handshake(Socket s, InetSocketAddress target, int timeout) throws IOException {
            int oldTimeout = s.getSoTimeout();
            s.setSoTimeout(timeout);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            out.write(user != null ? new byte[]{5, 2, 0, 2} : new byte[]{5, 1, 0});
            out.flush();
            byte[] method = readFully(in, 2);
            if (method[0] != 5) throw new IOException("SOCKS5: the proxy did not answer in SOCKS5");
            if (method[1] == 2 && user != null) {
                byte[] u = user.getBytes(StandardCharsets.UTF_8);
                byte[] p = pass.getBytes(StandardCharsets.UTF_8);
                ByteArrayOutputStream auth = new ByteArrayOutputStream();
                auth.write(1);
                auth.write(u.length);
                auth.write(u, 0, u.length);
                auth.write(p.length);
                auth.write(p, 0, p.length);
                out.write(auth.toByteArray());
                out.flush();
                if (readFully(in, 2)[1] != 0) throw new IOException("SOCKS5: the proxy did not accept the login and password");
            } else if (method[1] != 0) {
                throw new IOException("SOCKS5: the proxy did not accept the authentication method");
            }
            byte[] name = target.getHostString().getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream req = new ByteArrayOutputStream();
            req.write(new byte[]{5, 1, 0, 3}, 0, 4);
            req.write(name.length);
            req.write(name, 0, name.length);
            req.write(target.getPort() >> 8 & 0xff);
            req.write(target.getPort() & 0xff);
            out.write(req.toByteArray());
            out.flush();
            byte[] reply = readFully(in, 4);
            if (reply[1] != 0) {
                throw new IOException("SOCKS5: the proxy did not connect to " + target.getHostString() + " (code " + reply[1] + ")");
            }
            int skip;
            if (reply[3] == 1) skip = 4;
            else if (reply[3] == 4) skip = 16;
            else if (reply[3] == 3) skip = readFully(in, 1)[0] & 0xff;
            else throw new IOException("SOCKS5: an unknown answer from the proxy");
            readFully(in, skip + 2);
            s.setSoTimeout(oldTimeout);
        }

        private static byte[] readFully(InputStream in, int n) throws IOException {
            byte[] b = new byte[n];
            int off = 0;
            while (off < n) {
                int r = in.read(b, off, n - off);
                if (r < 0) throw new IOException("SOCKS5: the proxy closed the connection");
                off += r;
            }
            return b;
        }
    }

    // --- files as root -------------------------------------------------------------------------------------

    /** The file's bytes read as root; null when {@code missingOk} and it does not exist. */
    private static byte[] readRoot(String path, boolean missingOk) throws ToolError {
        String q = Tv.shellQuote(path);
        Run r = root("[ -e " + q + " ] || exit 3\ncat " + q + "\n", null);
        if (r.code == 3) {
            if (missingOk) return null;
            throw new ToolError("No file " + path);
        }
        if (r.code != 0) throw new ToolError("Cannot read " + path + ": " + lastLine(new String(r.out, StandardCharsets.UTF_8).trim()));
        return r.out;
    }

    /** Writes {@code content} to {@code path} under SUBS_DIR as root (the directory made when missing), mode
     *  644 in a 755 directory, so mpv's uid reads it; through a temporary file, so a cut write leaves none. */
    private static void writeSubs(String path, String content) throws ToolError {
        String q = Tv.shellQuote(path);
        String tmp = Tv.shellQuote(path + ".tmp");
        Run r = root("mkdir -p " + SUBS_DIR + " && chmod 755 " + SUBS_DIR + " && cat > " + tmp + " && chmod 644 " + tmp
                + " && mv " + tmp + " " + q + "\n", content.getBytes(StandardCharsets.UTF_8));
        if (r.code != 0) throw new ToolError("Cannot write " + path + ": " + lastLine(new String(r.out, StandardCharsets.UTF_8).trim()));
    }

    /** Runs {@code script} as root through {@code su --mount-master -c}, {@code stdin} fed to it. */
    private static Run root(String script, byte[] stdin) throws ToolError {
        Process proc = null;
        try {
            proc = new ProcessBuilder(Su.argv("--mount-master", "-c", script)).redirectErrorStream(true).start();
            OutputStream os = proc.getOutputStream();
            if (stdin != null) os.write(stdin);
            os.close();
            Run run = drain(proc, ROOT_TIMEOUT_MS);
            if (run.timedOut) throw new ToolError("su did not answer in " + (ROOT_TIMEOUT_MS / 1000) + " s");
            return run;
        } catch (IOException e) {
            if (proc != null) proc.destroy();
            throw new ToolError("su: " + e.getMessage());
        }
    }

    /** Reads the process's output to its end and its exit code; a watchdog destroys it past {@code timeoutMs}. */
    private static Run drain(Process proc, long timeoutMs) throws IOException {
        final long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        final AtomicBoolean killed = new AtomicBoolean(false);
        Thread wd = new Thread(() -> {
            while (Procs.alive(proc)) {
                if (System.nanoTime() - deadline > 0) {
                    killed.set(true);
                    proc.destroy();
                    return;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "pcx-subs-wd");
        wd.setDaemon(true);
        wd.start();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (InputStream in = proc.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        }
        int code;
        try {
            code = proc.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroy();
            throw new IOException("interrupted");
        } finally {
            wd.interrupt();
        }
        return new Run(code, bos.toByteArray(), killed.get());
    }

    private static final class Run {
        final int code;
        final byte[] out;
        final boolean timedOut;

        Run(int code, byte[] out, boolean timedOut) {
            this.code = code;
            this.out = out;
            this.timedOut = timedOut;
        }
    }
}
