package xyz.vvzvlad.popcornclaw;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Pure, stateless helpers and constants shared by the player (chunk 1) and the search/files tools (chunk 2),
 * ported 1:1 from tv_mcp's src/tv.py. Two groups live here:
 *
 * <ul>
 *   <li>the subtitle-kind machinery ({@link #subtitleKind}, {@link #isNotFull}, {@link #containsAny} and the
 *       marker/kind constants), moved out of {@link TvMcpServer} so both the player's track describers and
 *       the search probe's stream describers can share it;</li>
 *   <li>the search/files helpers: language codes, video-file listing, result lines, bencode info-hash /
 *       magnet-hash parsing, TorrServer streaming state and the ffprobe probe notes.</li>
 * </ul>
 *
 * <p>Nothing here touches mpv, the network or any instance state: every method is a pure function of its
 * arguments, as in the Python module.
 */
final class TvFormat {
    private TvFormat() {
    }

    // --- constants (src/tv.py, lines 24-109) --------------------------------------------------------------

    static final int SEARCH_LIMIT = 10;
    static final int PROBE_LIMIT = 5;
    // Jackett asks the trackers live, so a seeder count is current; a result below this one is listed
    // without a probe.
    static final int PROBE_MIN_SEEDERS = 5;
    static final int PROBE_CONCURRENCY = 3;
    static final int TITLE_LIMIT = 140;
    // Seconds after `add` returns until a torrent without a file list counts as dead (a magnet gets the
    // list from peers).
    static final int DEAD_TIMEOUT = 30;
    static final int REQUEST_TIMEOUT = 60;
    // Longer than TorrServer's own limit on one ffprobe run, 5 min in MatriX.133t, so TorrServer answers
    // /ffp before this side would give up.
    static final int FFPROBE_TIMEOUT = 330;
    static final int POLL_INTERVAL = 1;
    // TorrServer's state of a torrent something is reading (server/torr/state/state.go).
    static final int TORRENT_WORKING = 3;

    static final String[] VIDEO_EXTENSIONS = {".mkv", ".mp4", ".avi", ".m4v", ".mov", ".ts", ".m2ts",
            ".wmv", ".webm", ".mpg", ".mpeg"};

    // ISO 639-2 codes, both the bibliographic and the terminology form, mapped to ISO 639-1 for the
    // languages known to differ from the first two letters (rum would otherwise read as ru).
    static final Map<String, String> ISO_639_2 = buildIso6392();

    private static Map<String, String> buildIso6392() {
        Map<String, String> m = new HashMap<>();
        m.put("jpn", "ja"); m.put("spa", "es"); m.put("ger", "de"); m.put("deu", "de"); m.put("chi", "zh");
        m.put("zho", "zh"); m.put("por", "pt"); m.put("dut", "nl"); m.put("nld", "nl"); m.put("cze", "cs");
        m.put("ces", "cs"); m.put("gre", "el"); m.put("ell", "el"); m.put("rum", "ro"); m.put("ron", "ro");
        m.put("est", "et"); m.put("slo", "sk"); m.put("slk", "sk"); m.put("tur", "tr"); m.put("pol", "pl");
        m.put("swe", "sv"); m.put("per", "fa"); m.put("fas", "fa");
        return Collections.unmodifiableMap(m);
    }

    static final Pattern BTIH_HEX = Pattern.compile("[0-9a-f]{40}", Pattern.CASE_INSENSITIVE);
    static final Pattern BTIH_BASE32 = Pattern.compile("[a-z2-7]{32}", Pattern.CASE_INSENSITIVE);

    // Divisor for human sizes, exactly as the Python 2**30.
    private static final double GIB = 1L << 30;

    // --- subtitle-kind machinery (src/tv.py, lines 85-97, 135-149) --------------------------------------
    // NOT_FULL: subtitle kinds that are never full subtitles, by markers in the (lowercased) title; the keys
    // are the kind names describe_subtitle prints, and their iteration order (надписи, песни, комментарии)
    // decides ties.
    private static final String[] NOT_FULL_SIGNS = {"forc", "forsed", "форс", "надпис", "sign"};
    private static final String[] NOT_FULL_SONGS = {"песн", "song", "lyric", "текст"};
    private static final String[] NOT_FULL_COMMENTARY = {"коммент", "comment"};
    private static final String KIND_SIGNS = "надписи";
    private static final String KIND_SONGS = "песни";
    private static final String KIND_COMMENTARY = "комментарии";
    private static final String[] FULL_MARKERS = {"full", "полн"};
    private static final String[] SDH_MARKERS = {"sdh", "слабослыш", "hearing"};
    private static final String FULL = "полные";
    private static final String PLAIN = "";
    private static final String SDH = "SDH";

    /** Port of subtitle_kind. */
    static String subtitleKind(String title, boolean forced, boolean commentary, boolean hearing) {
        String t = (title == null ? "" : title).toLowerCase(Locale.ROOT);
        if (containsAny(t, NOT_FULL_SIGNS)) return KIND_SIGNS;
        if (containsAny(t, NOT_FULL_SONGS)) return KIND_SONGS;
        if (containsAny(t, NOT_FULL_COMMENTARY)) return KIND_COMMENTARY;
        if (forced) return KIND_SIGNS;
        if (commentary) return KIND_COMMENTARY;
        if (hearing || containsAny(t, SDH_MARKERS)) return SDH;
        return containsAny(t, FULL_MARKERS) ? FULL : PLAIN;
    }

    /** Whether `kind` is one of the NOT_FULL kinds (Python `kind in NOT_FULL`, the dict keys). */
    static boolean isNotFull(String kind) {
        return KIND_SIGNS.equals(kind) || KIND_SONGS.equals(kind) || KIND_COMMENTARY.equals(kind);
    }

    static boolean containsAny(String haystack, String[] markers) {
        for (String marker : markers) {
            if (haystack.contains(marker)) return true;
        }
        return false;
    }

    // --- languages (src/tv.py, lines 112-132) -----------------------------------------------------------

    /** Port of same_language. */
    static boolean sameLanguage(String a, String b) {
        return a != null && !a.isEmpty() && b != null && !b.isEmpty()
                && languageCode(a).equals(languageCode(b));
    }

    /** Port of language_code. */
    static String languageCode(String code) {
        code = code.toLowerCase(Locale.ROOT);
        String mapped = ISO_639_2.get(code);
        return mapped != null ? mapped : code.substring(0, Math.min(2, code.length()));
    }

    /** Port of languages: subtitle languages from "ru" or "ru,en"; the bottom one, then the top one. */
    static List<String> languages(String value) throws ToolError {
        List<String> codes = new ArrayList<>();
        String s = (value == null ? "" : value).trim().toLowerCase(Locale.ROOT);
        for (String code : s.split("[\\s,;]+")) {
            if (code.isEmpty()) continue;
            boolean dup = false;
            for (String known : codes) {
                if (sameLanguage(code, known)) { dup = true; break; }
            }
            if (!dup) codes.add(code);
        }
        if (codes.size() > 2) {
            throw new ToolError("Субтитров — один или два языка («ru» или «ru,en»), а не «" + value + "»");
        }
        return codes;
    }

    // --- ffprobe stream kind (src/tv.py, lines 166-170) -------------------------------------------------

    /** Port of stream_kind: subtitle_kind of an ffprobe subtitle stream. */
    static String streamKind(JSONObject stream) {
        JSONObject disposition = stream.optJSONObject("disposition");
        JSONObject tags = stream.optJSONObject("tags");
        String title = tags == null ? null : optStr(tags, "title");
        boolean forced = disposition != null && disposition.optInt("forced", 0) != 0;
        boolean comment = disposition != null && disposition.optInt("comment", 0) != 0;
        boolean hearing = disposition != null && disposition.optInt("hearing_impaired", 0) != 0;
        return subtitleKind(title, forced, comment, hearing);
    }

    // --- video files (src/tv.py, lines 183-196) ---------------------------------------------------------

    /** Port of video_files. */
    static List<JSONObject> videoFiles(List<JSONObject> files) {
        List<JSONObject> out = new ArrayList<>();
        for (JSONObject f : files) {
            String path = f.optString("path", "");
            String lower = path.toLowerCase(Locale.ROOT);
            if (endsWithAny(lower, VIDEO_EXTENSIONS)) out.add(f);
        }
        return out;
    }

    /** Port of describe_files. */
    static String describeFiles(List<JSONObject> files) throws ToolError {
        List<JSONObject> videos = videoFiles(files);
        if (videos.isEmpty()) throw new ToolError("В раздаче нет видеофайлов");
        List<String[]> paths = new ArrayList<>();
        for (JSONObject f : videos) paths.add(f.optString("path", "").split("/", -1));
        boolean shared = true;
        String firstHead = paths.get(0)[0];
        for (String[] p : paths) {
            if (!(p.length > 1 && p[0].equals(firstHead))) { shared = false; break; }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < videos.size(); i++) {
            JSONObject f = videos.get(i);
            String[] p = paths.get(i);
            String joined = joinPath(p, shared ? 1 : 0);
            if (sb.length() > 0) sb.append('\n');
            sb.append(f.optInt("id")).append(": ").append(joined).append(", ")
                    .append(gib(f.optLong("length", 0) / GIB)).append(" ГБ");
        }
        return sb.toString();
    }

    // --- result lines (src/tv.py, lines 271-294) --------------------------------------------------------

    /** Port of short_title. */
    static String shortTitle(String title) {
        return title.length() > TITLE_LIMIT ? title.substring(0, TITLE_LIMIT - 1) + "…" : title;
    }

    /** Port of format_result. */
    static String formatResult(int resultId, JSONObject result) {
        // "id N:", not "N.": results with the languages asked for move up, so the lines are not numbered in order.
        return "id " + resultId + ": " + result.optInt("Seeders") + " сид, "
                + gib(result.optLong("Size", 0) / GIB) + " ГБ, "
                + result.optString("TrackerId") + ": " + shortTitle(result.optString("Title"));
    }

    /** Port of format_saved. */
    static String formatSaved(int resultId, JSONObject result, Integer lastViewed) {
        long sizeBytes = result.optLong("Size", 0);
        String size = sizeBytes != 0 ? ", " + gib(sizeBytes / GIB) + " ГБ" : "";
        return "id " + resultId + ": в TorrServer" + size + ": " + shortTitle(result.optString("Title"))
                + (lastViewed == null ? "" : "; последний просмотренный файл — " + lastViewed);
    }

    /** Port of topic: the result's tracker topic, with rutor/megapeer's title slug stripped. */
    static String topic(JSONObject result) {
        String details = optStr(result, "Details");
        return details == null ? null : details.replaceAll("(/torrent/\\d+)/.*", "$1");
    }

    // --- bencode / magnet hashing (src/tv.py, lines 297-341) --------------------------------------------

    /** A malformed .torrent: the Java stand-in for the Python ValueError that bencode parsing raises. */
    static final class BencodeException extends Exception {
        BencodeException(String m) {
            super(m);
        }
    }

    /** Port of bencode_end: offset just past the bencoded value that starts at `pos`. */
    static int bencodeEnd(byte[] data, int pos) throws BencodeException {
        int kind = (pos >= 0 && pos < data.length) ? (data[pos] & 0xFF) : -1;
        if (kind == 'i') {
            return indexOf(data, (byte) 'e', pos) + 1;
        }
        if (kind == 'l' || kind == 'd') {
            pos += 1;
            while (!(pos < data.length && data[pos] == 'e')) {
                pos = bencodeEnd(data, pos);
            }
            return pos + 1;
        }
        if (kind >= '0' && kind <= '9') {
            int colon = indexOf(data, (byte) ':', pos);
            int len = parseLength(data, pos, colon);
            int end = colon + 1 + len;
            if (end > data.length) throw new BencodeException("truncated bencode string");
            return end;
        }
        throw new BencodeException("no bencode value at offset " + pos);
    }

    /** Port of torrent_info_hash: info hash of a .torrent, sha1 of its `info` dict byte for byte. */
    static String torrentInfoHash(byte[] torrent) throws BencodeException {
        if (!(torrent.length >= 1 && torrent[0] == 'd')) throw new BencodeException("not a bencoded dict");
        int pos = 1;
        while (!(pos < torrent.length && torrent[pos] == 'e')) {
            int value = bencodeEnd(torrent, pos);  // `pos` is at a key, a bencoded string
            int colon = indexOf(torrent, (byte) ':', pos);
            byte[] key = Arrays.copyOfRange(torrent, colon + 1, value);
            int end = bencodeEnd(torrent, value);
            if (Arrays.equals(key, INFO_KEY)) {
                return sha1Hex(Arrays.copyOfRange(torrent, value, end));
            }
            pos = end;
        }
        throw new BencodeException("no info dict");
    }

    private static final byte[] INFO_KEY = {'i', 'n', 'f', 'o'};

    /** Port of magnet_hash: the magnet's urn:btih (hex or base32) as lowercase hex, or null. */
    static String magnetHash(String magnet) {
        for (String xt : magnetXt(magnet)) {
            if (!xt.toLowerCase(Locale.ROOT).startsWith("urn:btih:")) continue;
            String btih = xt.substring("urn:btih:".length());
            if (BTIH_HEX.matcher(btih).matches()) {
                return btih.toLowerCase(Locale.ROOT);
            }
            if (BTIH_BASE32.matcher(btih).matches()) {
                return hex(base32Decode(btih.toUpperCase(Locale.ROOT)));
            }
        }
        return null;
    }

    // --- TorrServer streaming state (src/tv.py, lines 344-347) ------------------------------------------

    /** Port of streaming: whether TorrServer streams this torrent now (working with connected peers). */
    static boolean streaming(JSONObject torrent) {
        return torrent != null && torrent.optInt("stat", 0) == TORRENT_WORKING
                && torrent.optInt("active_peers", 0) != 0;
    }

    // --- probe notes (src/tv.py, lines 350-383) ---------------------------------------------------------

    /** A probe note: (found, text), the Python tuple[bool, str]. */
    static final class Note {
        final boolean found;
        final String text;

        Note(boolean found, String text) {
            this.found = found;
            this.text = text;
        }
    }

    /** Port of describe_probe: (has every language asked for, note) for one video file's ffprobe streams. */
    static Note describeProbe(List<JSONObject> streams, List<String> langs, String audio) {
        if (streams == null || streams.isEmpty()) return new Note(false, "дорожки не прочитались");
        List<Note> parts = new ArrayList<>();
        for (String lang : langs) parts.add(subtitlesNote(streams, lang));
        if (audio != null && !audio.isEmpty()) parts.add(audioNote(streams, audio));
        boolean all = true;
        StringBuilder sb = new StringBuilder();
        for (Note p : parts) {
            if (!p.found) all = false;
            if (sb.length() > 0) sb.append("; ");
            sb.append(p.text);
        }
        return new Note(all, sb.toString());
    }

    /** Port of audio_note: (has an audio stream in `lang`, note). */
    static Note audioNote(List<JSONObject> streams, String lang) {
        List<JSONObject> voices = new ArrayList<>();
        for (JSONObject s : streams) if ("audio".equals(optStr(s, "codec_type"))) voices.add(s);
        int mine = 0;
        for (JSONObject s : voices) if (sameLanguage(tagLanguage(s), lang)) mine++;
        if (mine > 0) return new Note(true, "звук " + lang + ": " + mine);
        TreeSet<String> others = new TreeSet<>();
        for (JSONObject s : voices) others.add(languageOrQuestion(s));
        String extra = others.isEmpty() ? "" : " (есть: " + joinComma(others) + ")";
        return new Note(false, "звука " + lang + " нет" + extra);
    }

    /** Port of subtitles_note: (has full subtitles in `lang`, note); «надписи» counts every never-full kind. */
    static Note subtitlesNote(List<JSONObject> streams, String lang) {
        List<JSONObject> subs = new ArrayList<>();
        for (JSONObject s : streams) if ("subtitle".equals(optStr(s, "codec_type"))) subs.add(s);
        List<JSONObject> mine = new ArrayList<>();
        for (JSONObject s : subs) if (sameLanguage(tagLanguage(s), lang)) mine.add(s);
        int never = 0;
        int full = 0;
        List<String> titles = new ArrayList<>();
        for (JSONObject s : mine) {
            if (isNotFull(streamKind(s))) {
                never++;
            } else {
                full++;
                JSONObject tags = s.optJSONObject("tags");
                String title = tags == null ? null : optStr(tags, "title");
                if (title != null) titles.add(title);
            }
        }
        if (mine.isEmpty()) {
            TreeSet<String> others = new TreeSet<>();
            for (JSONObject s : subs) others.add(languageOrQuestion(s));
            return others.isEmpty()
                    ? new Note(false, "нет " + lang + " (сабов нет)")
                    : new Note(false, "нет " + lang + " (есть: " + joinComma(others) + ")");
        }
        if (full == 0) {
            return new Note(false, "сабы " + lang + ": только надписи (" + never + ")");
        }
        String titlePart = titles.isEmpty() ? "" : " (" + joinComma(titles) + ")";
        String neverPart = never > 0 ? ", надписи: " + never : "";
        return new Note(true, "сабы " + lang + ": " + full + titlePart + neverPart);
    }

    // --- internal helpers -------------------------------------------------------------------------------

    /** `(s.get("tags") or {}).get("language")`: the stream's tag language, or null when absent/empty. */
    private static String tagLanguage(JSONObject stream) {
        JSONObject tags = stream.optJSONObject("tags");
        return tags == null ? null : optStr(tags, "language");
    }

    /** `(s.get("tags") or {}).get("language") or "?"`: the tag language, "?" when absent or empty. */
    private static String languageOrQuestion(JSONObject stream) {
        String l = tagLanguage(stream);
        return (l == null || l.isEmpty()) ? "?" : l;
    }

    /** The urn:btih carrier values of a magnet's `xt` query params, in order (Python parse_qs .get("xt")). */
    private static List<String> magnetXt(String magnet) {
        List<String> out = new ArrayList<>();
        String s = magnet == null ? "" : magnet;
        int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(0, hash);
        int qm = s.indexOf('?');
        if (qm < 0) return out;
        String query = s.substring(qm + 1);
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            if (eq < 0) continue;  // parse_qs drops blank values by default
            String key = urlDecode(pair.substring(0, eq));
            String value = pair.substring(eq + 1);
            if (value.isEmpty()) continue;
            if (key.equals("xt")) out.add(urlDecode(value));
        }
        return out;
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static int indexOf(byte[] data, byte b, int from) throws BencodeException {
        for (int i = Math.max(from, 0); i < data.length; i++) {
            if (data[i] == b) return i;
        }
        throw new BencodeException("subsection not found");
    }

    private static int parseLength(byte[] data, int start, int end) throws BencodeException {
        try {
            return Integer.parseInt(new String(data, start, end - start, java.nio.charset.StandardCharsets.US_ASCII).trim());
        } catch (NumberFormatException e) {
            throw new BencodeException("bad bencode length");
        }
    }

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private static byte[] base32Decode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (int i = 0; i < s.length(); i++) {
            int val = BASE32_ALPHABET.indexOf(s.charAt(i));
            if (val < 0) continue;  // the regex already restricted the input
            buffer = (buffer << 5) | val;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }

    private static String sha1Hex(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);  // SHA-1 is always present
        }
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(HEX[(x >> 4) & 0xF]).append(HEX[x & 0xF]);
        }
        return sb.toString();
    }

    private static boolean endsWithAny(String s, String[] suffixes) {
        for (String suffix : suffixes) {
            if (s.endsWith(suffix)) return true;
        }
        return false;
    }

    /** Joins path parts from `from` with "/", mirroring Python `'/'.join(p[from:])`. */
    private static String joinPath(String[] parts, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < parts.length; i++) {
            if (sb.length() > 0) sb.append('/');
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    private static String joinComma(Iterable<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(item);
        }
        return sb.toString();
    }

    /** A human size with one decimal, matching the Python {@code f"{x:.1f}"}. */
    private static String gib(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /** A JSON string value, or null when absent, JSON null or empty (so Python's truthy `or` chains match). */
    private static String optStr(JSONObject o, String key) {
        String v = o.optString(key, "");
        return v.isEmpty() ? null : v;
    }
}
