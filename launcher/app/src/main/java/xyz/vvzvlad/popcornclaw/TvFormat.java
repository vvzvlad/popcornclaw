package xyz.vvzvlad.popcornclaw;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
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
    // list from peers), and seconds after prepare starts the preload until one without an active peer does.
    static final int DEAD_TIMEOUT = 30;
    // prepare: seconds it waits for the release to prove it keeps up, the seconds its download speed is
    // averaged over, and how many times the episode's bitrate that speed must reach.
    static final int PREPARE_TIMEOUT = 90;
    static final int SPEED_WINDOW = 10;
    static final double SPEED_MARGIN = 1.5;
    static final int REQUEST_TIMEOUT = 60;
    // Longer than TorrServer's own limit on one ffprobe run, 5 min in MatriX.133t, so TorrServer answers
    // /ffp before this side would give up.
    static final int FFPROBE_TIMEOUT = 330;
    static final int POLL_INTERVAL = 1;
    // TorrServer's states of a torrent running a preload and of one something is reading
    // (server/torr/state/state.go).
    static final int TORRENT_PRELOAD = 2;
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

    // src/tv.py lines 99-103: S01E02 / 1x02 season+episode patterns, then the E01-style episode-only one.
    static final Pattern[] SEASON_EPISODE = {
            Pattern.compile("s(\\d{1,2})[ ._-]?e(\\d{1,3})(?!\\d)", Pattern.CASE_INSENSITIVE),  // S01E02, S01.E02
            Pattern.compile("(?<!\\d)(\\d{1,2})x(\\d{2,3})(?!\\d)", Pattern.CASE_INSENSITIVE),   // 1x02, not 1920x1080
    };
    static final Pattern EPISODE_ONLY =
            Pattern.compile("(?<![a-z\\d])(?:e|ep|episode)[ ._-]?(\\d{1,3})(?!\\d)", Pattern.CASE_INSENSITIVE);

    // Divisors for human sizes, exactly as the Python 2**30, 2**20 and 2**10.
    private static final double GIB = 1L << 30;
    static final double MIB = 1L << 20;
    private static final double KIB = 1L << 10;

    // --- subtitle-kind machinery (src/tv.py, lines 85-97, 135-149) --------------------------------------
    // NOT_FULL: subtitle kinds that are never full subtitles, by markers in the (lowercased) title; the keys
    // are the kind names describe_subtitle prints, and their iteration order (signs, songs, commentary)
    // decides ties.
    private static final String[] NOT_FULL_SIGNS = {"forc", "forsed", "форс", "надпис", "sign"};
    private static final String[] NOT_FULL_SONGS = {"песн", "song", "lyric", "текст"};
    private static final String[] NOT_FULL_COMMENTARY = {"коммент", "comment"};
    private static final String KIND_SIGNS = "signs";
    private static final String KIND_SONGS = "songs";
    private static final String KIND_COMMENTARY = "commentary";
    private static final String[] FULL_MARKERS = {"full", "полн"};
    private static final String[] SDH_MARKERS = {"sdh", "слабослыш", "hearing"};
    private static final String FULL = "full";
    private static final String PLAIN = "";
    private static final String SDH = "SDH";
    // Among the full/plain/SDH kinds a subtitle slot prefers the full one, then the plain one, then SDH
    // (Python SUBTITLE_RANK = {FULL: 0, PLAIN: 1, SDH: 2}); a never-full kind is not ranked (null here).
    // Picture subtitles: mpv renders them where the picture puts them, so only text tracks go on top.
    static final String[] BITMAP_SUBTITLES = {"hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle", "xsub"};
    // Track names mp4 muxers write when nobody named the track.
    static final String[] GENERIC_NAMES = {"SoundHandler", "VideoHandler", "SubtitleHandler", "TextHandler"};

    /** The rank of a subtitle kind for a slot's pick (FULL &lt; PLAIN &lt; SDH), or null for a never-full kind. */
    static Integer subtitleRank(String kind) {
        if (FULL.equals(kind)) return 0;
        if (PLAIN.equals(kind)) return 1;
        if (SDH.equals(kind)) return 2;
        return null;
    }

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
            throw new ToolError("Subtitles take one or two languages (\"en\" or \"en,ru\"), not \"" + value + "\"");
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

    // --- episode parsing (src/tv.py, lines 173-180) -----------------------------------------------------

    /** The Python parse_episode return: (season, episode); season is null for an E01-style name. */
    static final class Episode {
        final Integer season;
        final int episode;

        Episode(Integer season, int episode) {
            this.season = season;
            this.episode = episode;
        }
    }

    /** Port of parse_episode: (season, episode) from a file name; season is null for an E01-style name. */
    static Episode parseEpisode(String name) {
        for (Pattern pattern : SEASON_EPISODE) {
            Matcher m = pattern.matcher(name);
            if (m.find()) {
                return new Episode(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
            }
        }
        Matcher m = EPISODE_ONLY.matcher(name);
        return m.find() ? new Episode(null, Integer.parseInt(m.group(1))) : null;
    }

    /** posixpath.basename: the substring after the last "/" (the whole string when there is none). */
    static String basename(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
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
        if (videos.isEmpty()) throw new ToolError("The release has no video files");
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
                    .append(gib(f.optLong("length", 0) / GIB)).append(" GB");
        }
        return sb.toString();
    }

    /** Port of pick_file (src/tv.py, lines 199-225): the file `file`, else the one of the episode asked for. */
    static JSONObject pickFile(List<JSONObject> files, Integer season, Integer episode, Integer file) throws ToolError {
        List<JSONObject> videos = videoFiles(files);
        if (videos.isEmpty()) throw new ToolError("The release has no video files");
        if (file != null) {
            for (JSONObject f : videos) {
                if (f.optInt("id") == file) return f;
            }
            throw new ToolError("The release has no video file " + file + "; file numbers are in files");
        }
        if (season == null && episode == null) return videos.get(0);
        int seasonNum = season == null ? 1 : season;
        LinkedHashMap<Integer, List<Integer>> found = new LinkedHashMap<>();
        for (JSONObject video : videos) {
            Episode parsed = parseEpisode(basename(video.optString("path", "")));
            if (parsed == null) continue;
            boolean seasonOk = parsed.season == null || parsed.season == seasonNum;
            boolean episodeOk = episode == null || episode == parsed.episode;
            if (seasonOk && episodeOk) return video;
            List<Integer> list = found.get(parsed.season);
            if (list == null) {
                list = new ArrayList<>();
                found.put(parsed.season, list);
            }
            list.add(parsed.episode);
        }
        String wanted = String.format(Locale.ROOT, "S%02d", seasonNum)
                + (episode == null ? "" : String.format(Locale.ROOT, "E%02d", episode));
        List<Map.Entry<Integer, List<Integer>>> entries = new ArrayList<>(found.entrySet());
        // sorted(..., key=lambda kv: (kv[0] is None, kv[0] or 0)): None season last, then by season value.
        Collections.sort(entries, (a, b) -> {
            int an = a.getKey() == null ? 1 : 0;
            int bn = b.getKey() == null ? 1 : 0;
            if (an != bn) return Integer.compare(an, bn);
            int av = a.getKey() == null ? 0 : a.getKey();
            int bv = b.getKey() == null ? 0 : b.getKey();
            return Integer.compare(av, bv);
        });
        StringBuilder have = new StringBuilder();
        for (Map.Entry<Integer, List<Integer>> e : entries) {
            String label = e.getKey() == null ? "no season" : String.format(Locale.ROOT, "S%02d", e.getKey());
            if (have.length() > 0) have.append(", ");
            have.append(label).append(": ").append(Collections.min(e.getValue()))
                    .append("–").append(Collections.max(e.getValue()));
        }
        String haveStr = have.length() > 0 ? have.toString() : "only files without numbers";
        throw new ToolError("The release has no episode " + wanted + "; it has " + haveStr
                + ". The file list with numbers is files; pass the file needed as file");
    }

    // --- result lines (src/tv.py, lines 271-294) --------------------------------------------------------

    /** Port of short_title. */
    static String shortTitle(String title) {
        return title.length() > TITLE_LIMIT ? title.substring(0, TITLE_LIMIT - 1) + "…" : title;
    }

    /** Port of format_result. */
    static String formatResult(int resultId, JSONObject result) {
        // "id N:", not "N.": results with the languages asked for move up, so the lines are not numbered in order.
        return "id " + resultId + ": " + result.optInt("Seeders") + " seeders, "
                + gib(result.optLong("Size", 0) / GIB) + " GB, "
                + result.optString("TrackerId") + ": " + shortTitle(result.optString("Title"));
    }

    /** Port of format_saved. */
    static String formatSaved(int resultId, JSONObject result, Integer lastViewed) {
        long sizeBytes = result.optLong("Size", 0);
        String size = sizeBytes != 0 ? ", " + gib(sizeBytes / GIB) + " GB" : "";
        return "id " + resultId + ": in TorrServer" + size + ": " + shortTitle(result.optString("Title"))
                + (lastViewed == null ? "" : "; last watched file — " + lastViewed);
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
        if (streams == null || streams.isEmpty()) return new Note(false, "tracks could not be read");
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
        if (mine > 0) return new Note(true, "audio " + lang + ": " + mine);
        TreeSet<String> others = new TreeSet<>();
        for (JSONObject s : voices) others.add(languageOrQuestion(s));
        String extra = others.isEmpty() ? "" : " (available: " + joinComma(others) + ")";
        return new Note(false, "no " + lang + " audio" + extra);
    }

    /** Port of subtitles_note: (has full subtitles in `lang`, note); "signs" counts every never-full kind. */
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
                    ? new Note(false, "no " + lang + " subtitles (no subtitles at all)")
                    : new Note(false, "no " + lang + " subtitles (available: " + joinComma(others) + ")");
        }
        if (full == 0) {
            return new Note(false, "subtitles " + lang + ": signs only (" + never + ")");
        }
        String titlePart = titles.isEmpty() ? "" : " (" + joinComma(titles) + ")";
        String neverPart = never > 0 ? ", signs: " + never : "";
        return new Note(true, "subtitles " + lang + ": " + full + titlePart + neverPart);
    }

    // --- prepare helpers (src/tv.py, lines 459-475) -----------------------------------------------------

    /**
     * Port of needed_rate: bytes per second a file plays at, from TorrServer's /ffp answer: ffprobe's format
     * bit_rate over 8, else the file's length over its duration; null when ffprobe gave neither.
     */
    static Double neededRate(JSONObject probe, long length) {
        JSONObject fmt = probe == null ? null : probe.optJSONObject("format");
        if (fmt == null) fmt = new JSONObject();
        // float(fmt["bit_rate"]) / 8
        if (fmt.has("bit_rate") && !fmt.isNull("bit_rate")) {
            try {
                return Double.parseDouble(fmt.get("bit_rate").toString()) / 8;
            } catch (NumberFormatException | org.json.JSONException ignored) {
                // Falls through to duration, as the Python `except ...: pass`.
            }
        }
        // length / float(fmt["duration"])
        if (fmt.has("duration") && !fmt.isNull("duration")) {
            try {
                double duration = Double.parseDouble(fmt.get("duration").toString());
                return duration == 0 ? null : length / duration;  // ZeroDivisionError -> None
            } catch (NumberFormatException | org.json.JSONException ignored) {
                return null;
            }
        }
        return null;
    }

    /** Port of describe_speed. */
    static String describeSpeed(double speed, Double rate) {
        String need = (rate != null && rate != 0.0)
                ? "the episode needs about " + fmt0(rate / KIB) + " KB/s"
                : "what the episode needs is unknown";
        return "downloading at " + fmt0(speed / KIB) + " KB/s, " + need;
    }

    /** A rounded whole number, matching the Python {@code f"{x:.0f}"}. */
    static String fmt0(double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }

    // --- mpv track formatters (src/tv.py, lines 152-163, 386-417, 452-456, 478-492) ---------------------
    // Moved here from TvMcpServer so the player (status/subtext) and play's _apply (Tv) share one copy.

    /** Port of track_name: the title, else the container's name, unless that is a muxer's generic one. */
    static String trackName(JSONObject track) {
        String name = optStr(track, "title");
        JSONObject metadata = track.optJSONObject("metadata");
        if (name == null && metadata != null) name = optStr(metadata, "name");
        if (name == null && metadata != null) name = optStr(metadata, "handler_name");
        if (name != null) {
            for (String generic : GENERIC_NAMES) {
                if (generic.equals(name)) return null;
            }
        }
        return name;
    }

    /** Port of track_kind: subtitle_kind of an mpv subtitle track. */
    static String trackKind(JSONObject track) {
        return subtitleKind(trackName(track), track.optBoolean("forced", false),
                track.optBoolean("commentary", false), track.optBoolean("hearing-impaired", false));
    }

    /** Port of bitmap: a picture subtitle track. */
    static boolean bitmap(JSONObject track) {
        String codec = track.optString("codec");
        for (String c : BITMAP_SUBTITLES) {
            if (c.equals(codec)) return true;
        }
        return false;
    }

    /** `track.get("lang") or "?"`. */
    static String lang(JSONObject track) {
        String v = track.optString("lang", "");
        return v.isEmpty() ? "?" : v;
    }

    /** Port of describe_subtitle. */
    static String describeSubtitle(JSONObject track) {
        String name = trackName(track);
        String nameStr = name != null ? " \"" + name + "\"" : "";
        String kind = trackKind(track);
        String marks = (isNotFull(kind) ? " (" + kind + ")" : "") + (bitmap(track) ? " (image)" : "");
        return "#" + track.optInt("id") + " " + lang(track) + nameStr + marks;
    }

    /** Port of describe_subtitles: the tracks joined with ", ", or "none" when none. */
    static String describeSubtitles(List<JSONObject> tracks) {
        if (tracks.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder();
        for (JSONObject t : tracks) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(describeSubtitle(t));
        }
        return sb.toString();
    }

    /** Port of describe_full: a subtitle track, saying whether it is full. */
    static String describeFull(JSONObject track) {
        return describeSubtitle(track) + (isNotFull(trackKind(track)) ? "" : " (full)");
    }

    /** Port of describe_enabled. */
    static String describeEnabled(JSONObject track) {
        return "Subtitles on: " + describeFull(track);
    }

    /** Port of describe_audio. */
    static String describeAudio(JSONObject track) {
        String name = trackName(track);
        String nameStr = name != null ? " \"" + name + "\"" : "";
        int ch = track.optInt("demux-channel-count", 0);
        String channels = ch != 0 ? " " + ch + "ch" : "";
        return "#" + track.optInt("id") + " " + lang(track) + " " + track.optString("codec") + channels + nameStr;
    }

    /** Port of describe_audios. */
    static String describeAudios(List<JSONObject> tracks) {
        if (tracks.isEmpty()) return "none";
        StringBuilder sb = new StringBuilder();
        for (JSONObject t : tracks) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(describeAudio(t));
        }
        return sb.toString();
    }

    /** Port of selected(tracks, kind, order): the track mpv shows in slot `order` (sub 0 = sid, 1 = secondary). */
    static JSONObject selected(JSONArray tracks, String kind, int order) {
        if (tracks == null) return null;
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject t = tracks.optJSONObject(i);
            if (t == null) continue;
            if (kind.equals(t.optString("type")) && t.optBoolean("selected", false)
                    && t.optInt("main-selection", 0) == order) {
                return t;
            }
        }
        return null;
    }

    /** Port of describe_video: "; video W×H<, FPS fps>", "" when mpv has decoded no frame yet. */
    static String describeVideo(JSONObject params, Object fps) {
        if (params == null) return "";
        int w = params.optInt("w", 0);
        int h = params.optInt("h", 0);
        if (w == 0 || h == 0) return "";
        String rate = truthy(fps) ? ", " + stripFps(((Number) fps).doubleValue()) + " fps" : "";
        return "; video " + w + "×" + h + rate;
    }

    /** fps with its trailing zeros stripped (Python f'{fps:.3f}'.rstrip('0').rstrip('.')). */
    static String stripFps(double fps) {
        String s = String.format(Locale.ROOT, "%.3f", fps);
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') end--;
        if (end > 0 && s.charAt(end - 1) == '.') end--;
        return s.substring(0, end);
    }

    /** Port of clock: "h:MM:SS", "?" for a missing value. */
    static String clock(Object seconds) {
        if (seconds == null) return "?";
        long s = (long) ((Number) seconds).doubleValue();
        return (s / 3600) + ":" + String.format(Locale.ROOT, "%02d", (s % 3600) / 60)
                + ":" + String.format(Locale.ROOT, "%02d", s % 60);
    }

    // --- track selection for play._apply (src/tv.py, lines 420-449) -------------------------------------

    /** Port of full_track. */
    static boolean fullTrack(JSONObject track, String lang) {
        return track != null && sameLanguage(optStr(track, "lang"), lang) && !isNotFull(trackKind(track));
    }

    /** Port of no_full_subtitles. */
    static String noFullSubtitles(List<JSONObject> subs, String lang) {
        boolean any = false;
        for (JSONObject t : subs) {
            if (sameLanguage(optStr(t, "lang"), lang)) { any = true; break; }
        }
        String what = any ? "No full " : "No ";
        return what + lang + " subtitles; available: " + describeSubtitles(subs);
    }

    /** Port of pick_subtitle: the track a subtitle slot shows for `lang` (`textOnly` for the top one). */
    static JSONObject pickSubtitle(List<JSONObject> tracks, String lang, JSONObject current, boolean textOnly) {
        List<JSONObject> mine = new ArrayList<>();
        for (JSONObject t : tracks) {
            if (sameLanguage(optStr(t, "lang"), lang) && !(textOnly && bitmap(t))) mine.add(t);
        }
        List<JSONObject> ranked = new ArrayList<>();
        for (JSONObject t : mine) {
            if (subtitleRank(trackKind(t)) != null) ranked.add(t);
        }
        // Stable sort by rank: ties keep the track-list order, as Python's sorted() does.
        Collections.sort(ranked, (a, b) -> Integer.compare(subtitleRank(trackKind(a)), subtitleRank(trackKind(b))));
        if (!ranked.isEmpty()) {
            int best = subtitleRank(trackKind(ranked.get(0)));
            if (current != null && ranked.contains(current) && subtitleRank(trackKind(current)) == best) {
                return current;
            }
            return ranked.get(0);
        }
        return (current != null && mine.contains(current)) ? current : null;
    }

    /** Port of pick_audio: `current` when it is in `lang`, else the first track in it, else null. */
    static JSONObject pickAudio(List<JSONObject> tracks, String lang, JSONObject current) {
        if (current != null && sameLanguage(optStr(current, "lang"), lang)) return current;
        for (JSONObject track : tracks) {
            if (sameLanguage(optStr(track, "lang"), lang)) return track;
        }
        return null;
    }

    // --- playlist helpers (src/tv.py, lines 228-239) ----------------------------------------------------

    /** Port of m3u_entries: the entry URLs of an m3u playlist, in order, skipping blank and #-comment lines. */
    static List<String> m3uEntries(String text) {
        List<String> out = new ArrayList<>();
        for (String line : splitLines(text)) {
            String stripped = strip(line);
            if (!stripped.isEmpty() && !line.startsWith("#")) out.add(stripped);
        }
        return out;
    }

    /** Port of playlist_position: position of a TorrServer file in its torrent's playlist (index=&lt;file id&gt;). */
    static int playlistPosition(List<String> urls, int fileId) throws ToolError {
        String want = String.valueOf(fileId);
        for (int position = 0; position < urls.size(); position++) {
            List<String> index = queryValues(urls.get(position), "index");
            if (index.size() == 1 && index.get(0).equals(want)) return position;
        }
        throw new ToolError("File " + fileId + " is not in TorrServer's playlist");
    }

    /** Port of entry_label: an mpv playlist item by name — its #EXTINF title, the file name, else the URL name. */
    static String entryLabel(JSONObject entry) {
        String title = optStr(entry, "title");
        if (title != null) return title;
        String filename = optStr(entry, "filename");
        return urlPathBasename(filename == null ? "" : filename);
    }

    /**
     * Port of episode_position: the playlist position {@code player episode} opens — the next or previous video
     * item, or the video item of episode {@code value} in the playing item's season (season 1 for an E01-style
     * name). Only video items count: a torrent's playlist also lists its external audio tracks, whose names
     * carry the episode too.
     */
    static int episodePosition(List<String> labels, int position, String value) throws ToolError {
        List<Integer> videos = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            String label = labels.get(i);
            if (endsWithAny((label == null ? "" : label).toLowerCase(Locale.ROOT), VIDEO_EXTENSIONS)) videos.add(i);
        }
        Integer target = null;
        if ("next".equals(value)) {
            for (int i : videos) {
                if (i > position) { target = i; break; }
            }
        } else if ("prev".equals(value)) {
            for (int k = videos.size() - 1; k >= 0; k--) {
                if (videos.get(k) < position) { target = videos.get(k); break; }
            }
        } else if (isDigits(value)) {
            Episode current = position < labels.size()
                    ? parseEpisode(labels.get(position) == null ? "" : labels.get(position)) : null;
            int season = (current != null && current.season != null) ? current.season : 1;
            int wanted = Integer.parseInt(value);
            for (int i : videos) {
                Episode found = parseEpisode(labels.get(i));
                if (found != null && found.episode == wanted
                        && (found.season == null || found.season == season)) {
                    target = i;
                    break;
                }
            }
        } else {
            throw new ToolError("episode needs value: an episode number, \"next\" or \"prev\", not \"" + value + "\"");
        }
        if (target == null) {
            throw new ToolError("No such episode in the playlist; it has " + videos.size() + " video files. "
                    + "play with file plays an episode by its file; file numbers are in files");
        }
        return target;
    }

    // --- track container helpers (shared by TvMcpServer.status and Tv._apply) ----------------------------

    static JSONArray asArray(Object o) {
        return o instanceof JSONArray ? (JSONArray) o : null;
    }

    static JSONObject asObject(Object o) {
        return o instanceof JSONObject ? (JSONObject) o : null;
    }

    static List<JSONObject> tracksOfType(JSONArray tracks, String type) {
        List<JSONObject> out = new ArrayList<>();
        if (tracks == null) return out;
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject t = tracks.optJSONObject(i);
            if (t != null && type.equals(t.optString("type"))) out.add(t);
        }
        return out;
    }

    /** Python truthiness of an mpv property value: null/0/""/false are falsy. */
    static boolean truthy(Object v) {
        if (v == null || v == JSONObject.NULL) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).doubleValue() != 0;
        if (v instanceof String) return !((String) v).isEmpty();
        return true;
    }

    static String join(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    // --- internal helpers -------------------------------------------------------------------------------

    /** Python str.splitlines() for the line breaks an m3u uses (\n, \r, \r\n). */
    private static List<String> splitLines(String text) {
        List<String> out = new ArrayList<>();
        int n = text.length(), start = 0;
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                out.add(text.substring(start, i));
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') i++;
                start = i + 1;
            }
        }
        if (start < n) out.add(text.substring(start));
        return out;
    }

    /** Python str.strip(): trims ASCII and Unicode whitespace (including space separators like U+00A0). */
    private static String strip(String s) {
        if (s == null) return "";
        int start = 0, end = s.length();
        while (start < end && isWhitespace(s.charAt(start))) start++;
        while (end > start && isWhitespace(s.charAt(end - 1))) end--;
        return s.substring(start, end);
    }

    private static boolean isWhitespace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    /**
     * The decoded values of query parameter `key` in `url`, in order (mirrors parse_qs(urlsplit(url).query)
     * [key]); blank values and pairs without '=' are dropped, as parse_qs does by default.
     */
    static List<String> queryValues(String url, String key) {
        List<String> out = new ArrayList<>();
        if (url == null) return out;
        String s = url;
        int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(0, hash);
        int qm = s.indexOf('?');
        if (qm < 0) return out;
        for (String pair : s.substring(qm + 1).split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            if (eq < 0) continue;            // no '=' -> blank value, dropped by parse_qs
            String v = pair.substring(eq + 1);
            if (v.isEmpty()) continue;        // keep_blank_values=False
            if (urlDecode(pair.substring(0, eq)).equals(key)) out.add(urlDecode(v));
        }
        return out;
    }

    /** posixpath.basename(unquote(urlsplit(url).path)): the decoded file name of a URL's path. The follower's
     *  log line names the item it set the languages on this way. */
    static String urlPathBasename(String url) {
        return basename(urlDecode(urlPath(url)));
    }

    /** urlsplit(url).path: the path component of a URL, without scheme/authority, query or fragment. A string
     *  with no scheme (no "://") is treated as all path, as urlsplit does for a relative reference. */
    private static String urlPath(String url) {
        if (url == null) return "";
        String s = url;
        int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(0, hash);
        int qm = s.indexOf('?');
        if (qm >= 0) s = s.substring(0, qm);
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            int slash = s.indexOf('/', scheme + 3);
            s = slash >= 0 ? s.substring(slash) : "";
        }
        return s;
    }

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

    /** Python str.isdigit(): a non-empty string of digit characters. */
    private static boolean isDigits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
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
