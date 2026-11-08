package xyz.vvzvlad.popcornclaw;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The box's own tv_mcp: the Java port of the Python tv_mcp MCP server (src/server.py, src/tv.py), run in the
 * launcher app so the box no longer has to carry a Python service. It speaks the same MCP surface -- the six
 * tools {@code search}, {@code files}, {@code prepare}, {@code play}, {@code player}, {@code find_subtitles}
 * with their descriptions -- over JSON-RPC 2.0 on a bound {@link ServerSocket}, shaped exactly
 * like {@link ControlServer}: an accept loop, one thread per connection and raw HTTP parsing.
 *
 * <p>All six tools are ported; {@code transcribe} and {@code sync_subtitles} ({@link SubTools}) are this server's
 * own. mpv is driven locally through {@link MpvIpc} (an Android LocalSocket to mpv's
 * abstract IPC, replacing the Python side's adb bridge); TorrServer and Jackett are still reached over the
 * network. Backend endpoints and keys come from {@code tv_mcp.json} on the box, not the gateway's config.
 *
 * <p>Routes:
 * <ul>
 *   <li>{@code POST /mcp} -- one JSON-RPC 2.0 request or notification, stateless Streamable HTTP.</li>
 *   <li>{@code GET /health} -- "ok" (parity with the Python server's /health).</li>
 *   <li>{@code GET}/{@code DELETE} on {@code /mcp} -- 405 Method Not Allowed.</li>
 *   <li>anything else -- 404.</li>
 * </ul>
 */
public final class TvMcpServer {
    private static final String TAG = "PopcornClaw-tv";
    /**
     * ScreenStreamServer owns 8080, ControlServer 8081; this is the ported tv_mcp. Bound on 127.0.0.1 only:
     * its one client is the gateway on the box ({@code http://127.0.0.1:8082/mcp}).
     */
    public static final int PORT = 8082;

    private static final String MCP_PROTOCOL = "2025-06-18";

    // --- tool descriptions (translated from src/server.py) ------------------------------------------------
    // Every character of these lands in the model's context. One departure from the Python server: SEARCH's
    // first sentence names no Jackett trackers, as without Jackett it searches RuTor.
    // PLAYER's tail from sub_add on, TRANSCRIBE and SYNC_SUBTITLES are this server's own.

    private static final String SEARCH =
            "Find a release of a movie or series (through Jackett or TorrServer's RuTor search). "
            + "query is the title only, without season or episode: with words like \"third season\" or "
            + "\"season 3\" the trackers find nothing; pass the season and episode to prepare and play. "
            + "For a title that has remakes, add the year to the query (\"Shogun 2024\"). "
            + "The first lines \"id N: in TorrServer, size: title\" are releases already on the server "
            + "(what the user watches), with \"last watched file — K\" (the file number, as in files) when "
            + "there is one: when the series asked for is there, take its id, not another release. "
            + "Then up to 10 lines \"id N: seeders, size, tracker: title\"; for play take the id from the "
            + "line, not its place in the list. The id is an internal number, never tell it to the user: "
            + "describe the release by what it is (quality, audio or subtitles, size, season). subtitles is "
            + "a subtitle language code (\"en\") or two separated by a comma (\"en,ru\"), audio is an audio "
            + "language code (\"en\"): with either of them the first 5 releases with 5 or more seeders are "
            + "checked for tracks in those languages (usually 1–2 minutes; up to 12 minutes when releases "
            + "cannot be read), releases that have everything asked for move to the top, their ids do not "
            + "change; \"signs\" means on-screen signs, songs or commentary, not full subtitles.";

    private static final String FILES =
            "The video files of release id from search, in order: \"N: path, size\". Needed when the episode "
            + "cannot be found by season and episode (the file names carry no numbers like S01E05): pick the "
            + "file by its name and pass its N to prepare and play as file. Adds the release to TorrServer, "
            + "does not touch the TV.";

    private static final String PREPARE =
            "Prepare an episode of release id from search for play, without touching the TV: adds it to "
            + "TorrServer for download with a preload (season, episode or file — as in play) and for up to "
            + "90 s checks whether the release keeps up. Ready when the speed over 10 s is 1.5 times what the "
            + "episode needs, or the episode's preload is downloaded or already in the cache: the reply names "
            + "the episode and the speed (or that the preload is already cached) and, if the cache was "
            + "incomplete, how much is in it — then call play with the same id, season, episode or file. Does "
            + "not keep up, or no peers for 30 s — an error with the numbers: tell them and offer another "
            + "release. Before prepare and before play, tell the user what you are doing.";

    private static final String PLAY =
            "Play release id from search on the TV (mpv on the box); mpv is brought to the screen (and "
            + "started if it is not running). season and episode are the season and episode numbers; only "
            + "episode given — season 1; no episode given — the first video file (or the first file of the "
            + "given season). file is a video file number from files instead of season and episode. mpv "
            + "gets all the release's episodes as a playlist and goes on through them in order. subtitles is "
            + "a language code (\"en\") or two separated by a comma (\"en,ru\"): full subtitles of the first "
            + "language at the bottom, of the second on top (text ones only); audio is an audio language code "
            + "(\"en\"). The choice sticks to the release — the same tracker topic (also when a new episode "
            + "came out in it) or the same torrent from another tracker: on the following episodes, after "
            + "stop and on a play of its episodes without subtitles or audio, until the service restarts; "
            + "subtitles are reset only by player subtitles off, a choice by number of a track that is not "
            + "full subtitles or has no language, and a play of another release without subtitles; audio "
            + "only by player audio with a track that has no language and a play of another release without "
            + "audio. Usually after prepare. Waits up to 2 minutes for playback to start; if no peer "
            + "connected to the release within 30 s, stops mpv and replies that the release is dead; if mpv "
            + "could not open the episode or did not start it within 2 minutes, stops it and replies with an "
            + "error giving the release's peers and speed.";

    private static final String PLAYER =
            "Control what plays in mpv. status — what plays, the time, the subtitle and audio tracks with "
            + "their numbers and the video resolution (width×height, frames/s: shows whether it really is "
            + "4K); subtext — the subtitles on screen now (the bottom line and, when two languages are on, "
            + "the top one): to read a line without seeking blindly; "
            + "pause — pause or resume; stop — stop (mpv closes); subtitles — "
            + "value: a language code or two separated by a comma, as in play, \"off\" or a track number (it "
            + "goes to the bottom, the top ones are turned off); audio — value: a language code, part of a "
            + "track name or its number, the chosen track's language sticks to the release, like audio in "
            + "play; seek — value: \"+N\" or \"-N\" seconds from the current position (\"+600\" is 10 "
            + "minutes forward) or a time from the start \"H:MM:SS\" or \"MM:SS\" (\"45:00\"); episode — "
            + "another episode of what plays, without search and id: value \"next\", \"prev\" or an episode "
            + "number in the current season; the reply and errors are as in play, only without the 30 s "
            + "peer check: a dead release is an error after 2 minutes. "
            + "sub_add — load a subtitle file, value: its path, place \"top\" puts it on the top line (the bottom "
            + "by default), the language comes from the file name (\"episode.ru.srt\"); sub_delay, top_sub_delay, "
            + "audio_delay — the delay of the bottom subtitles, the top subtitles, the audio: value \"+N\" or "
            + "\"-N\" seconds added to the current delay, or a number as the whole delay (\"0\"); plus is later; "
            + "non-zero delays show in status.";

    private static final String TRANSCRIBE =
            "Recognise the speech of what plays in mpv into .srt subtitles (Whisper). at — where the pieces start, "
            + "separated by commas: \"H:MM:SS\", \"MM:SS\" or seconds (\"2:00,7:00,12:00\"); seconds — the length "
            + "of a piece, 60 by default (10–300); audio — the language or number of the audio track, as in player "
            + "status (by default the one playing). A one-minute piece takes about 10 s near where the player is, "
            + "and up to 2–3 minutes where the torrent has not downloaded yet. Writes "
            + "/data/local/popcornclaw/subs/<title>.speech.<lang>.srt; a repeated call adds pieces to the same "
            + "file, what was recognised at the same places is replaced. The reply is the path, per piece "
            + "\"MM:SS–MM:SS: N phrases\" and the phrases \"MM:SS.s text\"; a piece that failed is named with the "
            + "reason. Needed when something is wrong with the subtitles: to find a line without external "
            + "subtitles, or as the reference for sync_subtitles (for it, pieces every ~5 minutes across the whole "
            + "episode).";

    private static final String SYNC_SUBTITLES =
            "Align the timing of subtitles to the reference — usually the .srt from transcribe: finds the shift "
            + "of each stretch (it can change in steps) and writes "
            + "/data/local/popcornclaw/subs/<name>.sync.<lang>.srt. subtitles and reference are a file path or "
            + "the number of an external subtitle track from player status. The reply is the path, the shifts by "
            + "stretch, the reference pieces that did not match, and where the shift is a guess because no "
            + "reference piece matched there — where there is room, with places for new transcribe pieces, after "
            + "which call sync_subtitles again. Does not touch mpv: player sub_add loads the result.";

    private static final String FIND_SUBTITLES =
            "Find subtitles for a movie or an episode in the OpenSubtitles and SubDL databases. query is the "
            + "original (usually English) title without season or episode: by a translated title the "
            + "databases do not find everything. language is a language code (\"en\"). season and episode "
            + "are for an episode; only episode given — season 1. The reply is up to 5 subtitles from each "
            + "database: which title (and year, for an episode SxxEyy) was found, the release names, for "
            + "OpenSubtitles the download count, SDH — for the hearing impaired. This is only a list of what "
            + "was found: such subtitles cannot be downloaded and turned on yet.";

    // --- track machinery (src/tv.py) ----------------------------------------------------------------------
    // The subtitle-kind machinery and the track/describe formatters (describe*, selected, trackName, bitmap,
    // clock, describeVideo, BITMAP_SUBTITLES/GENERIC_NAMES, ...) now live in TvFormat so the player here and
    // play's _apply in Tv share one copy; the player calls TvFormat.* for all of them.

    // --- tv_mcp settings ----------------------------------------------------------------------------------
    /** The box file that carries the TorrServer (and optional Jackett) endpoints; read lazily on first use. */
    private static final String TV_SETTINGS_PATH = "/data/local/popcornclaw/tv_mcp.json";

    // The started server, for reloadSettings(); AgentService runs one at a time.
    private static volatile TvMcpServer current;

    private volatile boolean running;
    // Assigned on the accept thread, read by stop() on the caller's thread: volatile for the handoff.
    private volatile ServerSocket serverSocket;
    private Thread acceptThread;
    // The search/files logic, built on the first search/files from TV_SETTINGS_PATH; null until then, so the
    // server starts and player works without that file. tvLock guards the one-time build across conn threads.
    private Tv tv;
    private final Object tvLock = new Object();
    // The subtitle search, built on the first find_subtitles from TV_SETTINGS_PATH; null until then, so the
    // server starts and the other tools work without the two API keys. subtitlesLock guards the build.
    private Subtitles subtitles;
    private final Object subtitlesLock = new Object();
    // transcribe and sync_subtitles: the bundled ffmpeg and the app's cache dir for its pieces.
    private final SubTools subTools;

    public TvMcpServer(Context context) {
        subTools = new SubTools(context.getApplicationInfo().nativeLibraryDir + "/libffmpeg.so", context.getCacheDir());
    }

    public void start() {
        if (running) return;
        running = true;
        current = this;
        acceptThread = new Thread(this::accept, "pcx-tv-accept");
        acceptThread.start();
        Log.i(TAG, "tv_mcp server starting on port " + PORT);
    }

    public void stop() {
        running = false;
        if (current == this) current = null;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
    }

    private void accept() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            // IPv4 127.0.0.1, not InetAddress.getLoopbackAddress(): on Android that is always ::1, which does not
            // take the gateway's connections to 127.0.0.1.
            serverSocket.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), PORT));
        } catch (IOException e) {
            Log.e(TAG, "cannot bind port " + PORT, e);
            running = false;
            return;
        }
        while (running) {
            final Socket s;
            try {
                s = serverSocket.accept();
            } catch (IOException e) {
                if (running) Log.w(TAG, "accept failed", e);
                break;
            }
            new Thread(() -> handle(s), "pcx-tv-conn").start();
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            String reqLine = readLine(in);
            if (reqLine == null) return;
            String[] parts = reqLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String path = parts.length > 1 ? parts[1] : "/";

            int contentLength = 0;
            for (String line; (line = readLine(in)) != null && !line.isEmpty(); ) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                    try {
                        contentLength = Integer.parseInt(line.substring(colon + 1).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            String route = path;
            int q = route.indexOf('?');
            if (q >= 0) route = route.substring(0, q);

            if ("POST".equals(method) && "/mcp".equals(route)) {
                handleMcp(out, readBody(in, contentLength));
            } else if ("GET".equals(method) && "/health".equals(route)) {
                respond(out, "200 OK", "text/plain", "ok".getBytes(StandardCharsets.UTF_8));
            } else if (("GET".equals(method) || "DELETE".equals(method)) && "/mcp".equals(route)) {
                respond(out, "405 Method Not Allowed", "text/plain",
                        "method not allowed".getBytes(StandardCharsets.UTF_8));
            } else {
                respond(out, "404 Not Found", "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            // A client that walks away mid-request is normal; not worth a stack trace.
            Log.d(TAG, "connection ended: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    // --- MCP (JSON-RPC 2.0, stateless Streamable HTTP) -----------------------------------------------------

    private void handleMcp(OutputStream out, byte[] body) throws IOException {
        JSONObject req;
        try {
            req = new JSONObject(new String(body, StandardCharsets.UTF_8));
        } catch (JSONException e) {
            respondJson(out, rpcError(JSONObject.NULL, -32700, "parse error"));
            return;
        }
        // No "id" means a notification (e.g. notifications/initialized): acknowledge with an empty 202.
        if (!req.has("id")) {
            respond(out, "202 Accepted", "text/plain", new byte[0]);
            return;
        }
        Object id = req.opt("id");
        String method = req.optString("method", "");
        JSONObject response;
        try {
            switch (method) {
                case "initialize":
                    response = rpcResult(id, new JSONObject()
                            .put("protocolVersion", MCP_PROTOCOL)
                            .put("capabilities", new JSONObject()
                                    .put("tools", new JSONObject().put("listChanged", false)))
                            .put("serverInfo", new JSONObject()
                                    .put("name", "tv_mcp").put("version", "1")));
                    break;
                case "tools/list":
                    response = rpcResult(id, new JSONObject().put("tools", toolsList()));
                    break;
                case "tools/call":
                    response = handleToolsCall(id, req);
                    break;
                default:
                    response = rpcError(id, -32601, "method not found");
                    break;
            }
        } catch (JSONException e) {
            response = rpcError(id, -32603, "internal error: " + e.getMessage());
        }
        respondJson(out, response);
    }

    private JSONObject handleToolsCall(Object id, JSONObject req) throws JSONException {
        JSONObject params = req.optJSONObject("params");
        if (params == null) return rpcError(id, -32602, "invalid params: missing params");
        String name = params.optString("name", "");
        if (name.isEmpty()) return rpcError(id, -32602, "invalid params: missing tool name");
        JSONObject args = params.optJSONObject("arguments");
        if (args == null) args = new JSONObject();
        String text;
        boolean isError = false;
        try {
            text = callTool(name, args);
        } catch (ToolError e) {
            text = e.getMessage() == null ? "tool error" : e.getMessage();
            isError = true;
        } catch (RuntimeException e) {
            // A tool must never turn into a JSON-RPC error; surface the failure as an error result instead.
            text = "internal error: " + e;
            isError = true;
        }
        JSONObject content = new JSONObject().put("type", "text").put("text", text);
        JSONObject result = new JSONObject().put("content", new JSONArray().put(content)).put("isError", isError);
        return rpcResult(id, result);
    }

    private static JSONObject rpcResult(Object id, JSONObject result) throws JSONException {
        return new JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result);
    }

    private static JSONObject rpcError(Object id, int code, String message) {
        try {
            return new JSONObject().put("jsonrpc", "2.0").put("id", id)
                    .put("error", new JSONObject().put("code", code).put("message", message));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    // --- tools ---------------------------------------------------------------------------------------------

    private JSONArray toolsList() throws JSONException {
        JSONArray tools = new JSONArray();

        JSONObject searchProps = new JSONObject();
        searchProps.put("query", prop("string"));
        searchProps.put("subtitles", prop("string"));
        searchProps.put("audio", prop("string"));
        tools.put(tool("search", SEARCH, schema(searchProps, "query")));

        JSONObject filesProps = new JSONObject();
        filesProps.put("id", prop("integer"));
        tools.put(tool("files", FILES, schema(filesProps, "id")));

        JSONObject prepareProps = new JSONObject();
        prepareProps.put("id", prop("integer"));
        prepareProps.put("season", prop("integer"));
        prepareProps.put("episode", prop("integer"));
        prepareProps.put("file", prop("integer"));
        tools.put(tool("prepare", PREPARE, schema(prepareProps, "id")));

        JSONObject playProps = new JSONObject();
        playProps.put("id", prop("integer"));
        playProps.put("season", prop("integer"));
        playProps.put("episode", prop("integer"));
        playProps.put("subtitles", prop("string"));
        playProps.put("audio", prop("string"));
        playProps.put("file", prop("integer"));
        tools.put(tool("play", PLAY, schema(playProps, "id")));

        JSONObject playerProps = new JSONObject();
        JSONObject action = prop("string");
        action.put("enum", new JSONArray().put("status").put("subtext").put("pause").put("stop")
                .put("subtitles").put("audio").put("seek").put("episode")
                .put("sub_add").put("sub_delay").put("top_sub_delay").put("audio_delay"));
        playerProps.put("action", action);
        playerProps.put("value", prop("string"));
        JSONObject place = prop("string");
        place.put("enum", new JSONArray().put("bottom").put("top"));
        playerProps.put("place", place);
        tools.put(tool("player", PLAYER, schema(playerProps, "action")));

        JSONObject findProps = new JSONObject();
        findProps.put("query", prop("string"));
        findProps.put("language", prop("string"));
        findProps.put("season", prop("integer"));
        findProps.put("episode", prop("integer"));
        tools.put(tool("find_subtitles", FIND_SUBTITLES, schema(findProps, "query", "language")));

        JSONObject transcribeProps = new JSONObject();
        transcribeProps.put("at", prop("string"));
        transcribeProps.put("seconds", prop("integer"));
        transcribeProps.put("audio", prop("string"));
        tools.put(tool("transcribe", TRANSCRIBE, schema(transcribeProps, "at")));

        JSONObject syncProps = new JSONObject();
        syncProps.put("subtitles", prop("string"));
        syncProps.put("reference", prop("string"));
        tools.put(tool("sync_subtitles", SYNC_SUBTITLES, schema(syncProps, "subtitles", "reference")));

        return tools;
    }

    private String callTool(String name, JSONObject args) throws ToolError {
        switch (name) {
            case "player":
                return toolPlayer(args);
            case "search":
                return toolSearch(args);
            case "files":
                return toolFiles(args);
            case "prepare":
                return toolPrepare(args);
            case "play":
                return toolPlay(args);
            case "find_subtitles":
                return toolFindSubtitles(args);
            case "transcribe":
                return subTools.transcribe(args.optString("at", ""), args.optInt("seconds", 60), optArg(args, "audio"));
            case "sync_subtitles": {
                String target = optArg(args, "subtitles");
                String reference = optArg(args, "reference");
                if (target == null || reference == null) throw new ToolError("subtitles and reference are required");
                return subTools.sync(target, reference);
            }
            default:
                throw new ToolError("unknown tool: " + name);
        }
    }

    // --- search / files (ported from tv.py search/files via Tv) --------------------------------------------

    private String toolSearch(JSONObject args) throws ToolError {
        // query required (string); subtitles/audio optional, null when absent or blank (Python str | None).
        return tv().search(args.optString("query", ""), optArg(args, "subtitles"), optArg(args, "audio"));
    }

    private String toolFiles(JSONObject args) throws ToolError {
        // id required (integer).
        return tv().files(args.optInt("id", 0));
    }

    private String toolPrepare(JSONObject args) throws ToolError {
        // id required (integer); season/episode/file optional, null when absent (Python int | None).
        return tv().prepare(args.optInt("id", 0), optInt(args, "season"), optInt(args, "episode"),
                optInt(args, "file"));
    }

    private String toolPlay(JSONObject args) throws ToolError {
        // id required (integer); season/episode/file optional ints; subtitles/audio optional strings, null when
        // absent or blank (Python int | None / str | None).
        return tv().play(args.optInt("id", 0), optInt(args, "season"), optInt(args, "episode"),
                optArg(args, "subtitles"), optArg(args, "audio"), optInt(args, "file"));
    }

    /**
     * The {@link Tv} instance, built on first use from TV_SETTINGS_PATH. ToolError when the file is missing,
     * unparseable or lacks torrserver_url, so the server still starts and player works without it. Without
     * jackett_url the search goes through TorrServer's own RuTor search.
     */
    private Tv tv() throws ToolError {
        synchronized (tvLock) {
            if (tv != null) return tv;
            String jackettUrl = "";
            String torrserverUrl = null;
            String jackettApiKey = "";
            String text = readFileRoot(TV_SETTINGS_PATH);
            if (text != null) {
                try {
                    JSONObject o = new JSONObject(text);
                    jackettUrl = o.optString("jackett_url", "");
                    torrserverUrl = o.optString("torrserver_url", "");
                    jackettApiKey = o.optString("jackett_api_key", "");
                } catch (JSONException ignored) {
                    // Unparseable: handled as "not configured" below.
                }
            }
            if (isBlank(torrserverUrl)) {
                throw new ToolError("tv_mcp is not set up: no " + TV_SETTINGS_PATH + " or no torrserver_url in it");
            }
            tv = new Tv(isBlank(jackettUrl) ? "" : jackettUrl, jackettApiKey, torrserverUrl);
            return tv;
        }
    }

    /**
     * Drops the cached {@link Tv} and {@link Subtitles}, so the next tool call reads TV_SETTINGS_PATH anew; the
     * settings panel calls it after saving that file. The old Tv's follower is stopped (as player stop does);
     * its search ids and chosen languages go with it, as on a restart. A no-op while no server runs.
     */
    public static void reloadSettings() {
        TvMcpServer server = current;
        if (server == null) return;
        Tv old;
        synchronized (server.tvLock) {
            old = server.tv;
            server.tv = null;
        }
        if (old != null) old.unfollow();
        synchronized (server.subtitlesLock) {
            server.subtitles = null;
        }
        Log.i(TAG, "tv_mcp settings dropped; the next tool call reads " + TV_SETTINGS_PATH);
    }

    // --- find_subtitles (ported from subtitles.py via Subtitles) -------------------------------------------

    private String toolFindSubtitles(JSONObject args) throws ToolError {
        // query and language required (strings); season/episode optional ints, null when absent (Python int | None).
        return subtitles().find(args.optString("query", ""), args.optString("language", ""),
                optInt(args, "season"), optInt(args, "episode"));
    }

    /**
     * The {@link Subtitles} instance, built on first use from TV_SETTINGS_PATH. ToolError when the file is
     * missing, unparseable or lacks either API key, so the server still starts and the other tools work without
     * the two subtitle keys (parity with {@link #tv()}).
     */
    private Subtitles subtitles() throws ToolError {
        synchronized (subtitlesLock) {
            if (subtitles != null) return subtitles;
            String opensubtitlesApiKey = null;
            String subdlApiKey = null;
            String text = readFileRoot(TV_SETTINGS_PATH);
            if (text != null) {
                try {
                    JSONObject o = new JSONObject(text);
                    opensubtitlesApiKey = o.optString("opensubtitles_api_key", "");
                    subdlApiKey = o.optString("subdl_api_key", "");
                } catch (JSONException ignored) {
                    // Unparseable: handled as "not configured" below.
                }
            }
            if (isBlank(opensubtitlesApiKey) || isBlank(subdlApiKey)) {
                throw new ToolError("tv_mcp is not set up: no " + TV_SETTINGS_PATH
                        + " or no opensubtitles_api_key/subdl_api_key in it");
            }
            subtitles = new Subtitles(opensubtitlesApiKey, subdlApiKey);
            return subtitles;
        }
    }

    /** A tool argument as a string, null when absent, JSON null or empty (Python's str | None). */
    private static String optArg(JSONObject args, String key) {
        if (args.isNull(key)) return null;
        String v = args.optString(key, "");
        return v.isEmpty() ? null : v;
    }

    /** A tool argument as an Integer, null when absent or JSON null (Python's int | None). */
    private static Integer optInt(JSONObject args, String key) {
        if (!args.has(key) || args.isNull(key)) return null;
        return args.optInt(key);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * The whole file read as root through {@code su}, or null when it cannot be read (no root grant, missing
     * file, a read error or a timeout). The config files are root-owned, mode 600 (the Jackett key is a secret);
     * this app runs as its own uid and cannot open them directly, so it reads them as root -- the same way
     * {@link ControlServer} reads config.json/.security.yml.
     */
    static String readFileRoot(String path) {
        Process p = null;
        try {
            // --mount-master: the same global mount namespace the gateway runs in (parity with AgentService).
            p = new ProcessBuilder(Su.argv("--mount-master", "-c", "cat " + path)).redirectErrorStream(true).start();
            p.getOutputStream().close();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            }
            if (!Procs.waitFor(p, 8_000)) {
                p.destroy();
                return null;
            }
            // Non-zero exit = missing file or no root grant; the merged stdout then holds cat's error, not JSON.
            return p.exitValue() == 0 ? new String(bos.toByteArray(), StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // --- player (ported from tv.py player/_status/_subtext/_seek) ------------------------------------------

    private String toolPlayer(JSONObject args) throws ToolError {
        String action = args.optString("action", "");
        if (action.isEmpty()) throw new ToolError("action is required");
        MpvIpc ipc = MpvIpc.open();
        try {
            Object playingPath = null;
            if (ipc != null) {
                try {
                    playingPath = ipc.get("path");
                } catch (MpvIpc.MpvError e) {
                    throw new ToolError(e.getMessage());
                }
            }
            if (ipc == null || playingPath == null) {
                if ("status".equals(action) || "subtext".equals(action)) return "Nothing is playing";
                throw new ToolError("Nothing is playing");
            }
            try {
                if ("status".equals(action)) return status(ipc);
                if ("subtext".equals(action)) return subtext(ipc);
                if ("pause".equals(action)) {
                    ipc.command("cycle", "pause");
                    return TvFormat.truthy(ipc.get("pause")) ? "Paused" : "Playing";
                }
                if ("stop".equals(action)) {
                    // The languages stay chosen: a play of this release's next episode puts them on again
                    // (Python player stop: _unfollow then _stop).
                    unfollow();
                    stop(ipc);
                    return "Stopped";
                }
                String value = args.optString("value", "");
                if (value.isEmpty()) throw new ToolError(action + " needs a value");
                value = value.trim();
                if ("seek".equals(action)) return seek(ipc, value);
                if ("sub_add".equals(action)) return subAdd(ipc, value, optArg(args, "place"));
                if ("sub_delay".equals(action)) return delay(ipc, action, "sub-delay", "Subtitle delay", value);
                if ("top_sub_delay".equals(action)) {
                    return delay(ipc, action, "secondary-sub-delay", "Top subtitle delay", value);
                }
                if ("audio_delay".equals(action)) return delay(ipc, action, "audio-delay", "Audio delay", value);
                if ("episode".equals(action)) return tv().episode(ipc, value.toLowerCase(Locale.ROOT));
                // Before the command sets its own kind, so the follower has the playlist and the other kind.
                tv().adopt(ipc);
                if ("audio".equals(action)) return tv().selectAudio(ipc, value);
                return tv().selectSubtitles(ipc, value.toLowerCase(Locale.ROOT));
            } catch (MpvIpc.MpvError e) {
                throw new ToolError(e.getMessage());
            }
        } finally {
            if (ipc != null) ipc.close();
        }
    }

    /** Port of Tv._status: the playing item, position, tracks and video resolution. */
    private static String status(MpvIpc ipc) throws MpvIpc.MpvError {
        String paused = TvFormat.truthy(ipc.get("pause")) ? ", paused" : "";
        Object fps = ipc.get("container-fps");
        if (!TvFormat.truthy(fps)) fps = ipc.get("estimated-vf-fps");
        String video = TvFormat.describeVideo(TvFormat.asObject(ipc.get("video-params")), fps);
        JSONArray tracks = TvFormat.asArray(ipc.get("track-list"));
        List<JSONObject> subs = TvFormat.tracksOfType(tracks, "sub");
        List<JSONObject> voices = TvFormat.tracksOfType(tracks, "audio");
        JSONObject sid = TvFormat.selected(tracks, "sub", 0);
        JSONObject top = TvFormat.selected(tracks, "sub", 1);
        JSONObject aid = TvFormat.selected(tracks, "audio", 0);
        List<String> lines = new ArrayList<>();
        lines.add("Playing: " + String.valueOf(ipc.get("media-title")) + paused + ", "
                + TvFormat.clock(ipc.get("time-pos")) + " of " + TvFormat.clock(ipc.get("duration")) + video);
        lines.add(sid != null ? TvFormat.describeEnabled(sid) : "Subtitles: off");
        if (top != null) lines.add("Top subtitles: " + TvFormat.describeFull(top));
        String subLine = joinSubtitles(subs);
        lines.add("Subtitle tracks: " + (subLine.isEmpty() ? "none" : subLine));
        lines.add(aid != null ? "Audio: " + TvFormat.describeAudio(aid) : "Audio: off");
        lines.add("Audio tracks: " + TvFormat.describeAudios(voices));
        addDelay(lines, ipc, "sub-delay", "Subtitle delay");
        addDelay(lines, ipc, "secondary-sub-delay", "Top subtitle delay");
        addDelay(lines, ipc, "audio-delay", "Audio delay");
        return TvFormat.join(lines);
    }

    /** A status line "label: +0.5 s" for a delay property that is not zero. */
    private static void addDelay(List<String> lines, MpvIpc ipc, String prop, String label) throws MpvIpc.MpvError {
        Object v;
        try {
            v = ipc.get(prop);
        } catch (MpvIpc.IpcClosed e) {
            throw e;
        } catch (MpvIpc.MpvError e) {
            // An mpv without this property (secondary-sub-delay is recent) loses one line, not the whole status.
            return;
        }
        if (v instanceof Number && Math.abs(((Number) v).doubleValue()) >= 0.005) {
            lines.add(label + ": " + delayText(((Number) v).doubleValue()));
        }
    }

    private static final Pattern DELAY = Pattern.compile("([+-]?)(\\d+(?:[.,]\\d+)?)");

    /** sub_delay/top_sub_delay/audio_delay: "+N"/"-N" seconds added to {@code prop}, a plain number set as it. */
    private static String delay(MpvIpc ipc, String action, String prop, String label, String value)
            throws MpvIpc.MpvError, ToolError {
        Matcher m = DELAY.matcher(value);
        if (!m.matches()) {
            throw new ToolError(action + " needs value \"+N\" or \"-N\" seconds added to the current delay, or a "
                    + "number as the whole delay, not \"" + value + "\"");
        }
        double n = Double.parseDouble(m.group(2).replace(',', '.'));
        double target = n;
        if (!m.group(1).isEmpty()) {
            Object now = ipc.get(prop);
            target = (now instanceof Number ? ((Number) now).doubleValue() : 0) + ("-".equals(m.group(1)) ? -n : n);
        }
        ipc.set(prop, target);
        Object now = ipc.get(prop);
        return label + ": " + delayText(now instanceof Number ? ((Number) now).doubleValue() : target);
    }

    /** A delay as "+0.5 s", "-1.25 s", "+3.0 s". */
    static String delayText(double seconds) {
        if (Math.abs(seconds) < 0.005) seconds = 0;
        String s = String.format(Locale.ROOT, "%+.2f", seconds);
        if (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s + " s";
    }

    /**
     * sub_add: mpv loads the subtitle file {@code path} as a new track (its language from the name's ".xx.srt",
     * so player subtitles finds it) and shows it at the bottom (sid) or, for {@code place} "top", the top
     * (secondary-sid). Only the slot asked for changes.
     */
    private static String subAdd(MpvIpc ipc, String path, String place) throws MpvIpc.MpvError, ToolError {
        boolean top;
        if (place == null || "bottom".equals(place)) top = false;
        else if ("top".equals(place)) top = true;
        else throw new ToolError("place is \"bottom\" or \"top\", not \"" + place + "\"");
        JSONArray before = TvFormat.asArray(ipc.get("track-list"));
        JSONObject bottomBefore = TvFormat.selected(before, "sub", 0);
        int lastId = 0;
        for (JSONObject t : TvFormat.tracksOfType(before, "sub")) lastId = Math.max(lastId, t.optInt("id"));
        String lang = SubTools.fileLang(path);
        try {
            if (lang != null) ipc.command("sub-add", path, "auto", TvFormat.basename(path), lang);
            else ipc.command("sub-add", path, "auto");
        } catch (MpvIpc.IpcClosed e) {
            throw e;
        } catch (MpvIpc.MpvError e) {
            throw new ToolError("mpv could not open " + path + " (" + e.getMessage() + ")");
        }
        JSONObject added = null;
        for (JSONObject t : TvFormat.tracksOfType(TvFormat.asArray(ipc.get("track-list")), "sub")) {
            if (t.optInt("id") > lastId && (added == null || t.optInt("id") > added.optInt("id"))) added = t;
        }
        if (added == null) throw new ToolError("mpv added no track from " + path);
        int id = added.optInt("id");
        if (top) {
            // mpv may pick the new track for the bottom by itself; a slot ignores a track the other one shows.
            JSONObject bottomNow = TvFormat.selected(TvFormat.asArray(ipc.get("track-list")), "sub", 0);
            if (bottomNow != null && bottomNow.optInt("id") == id) {
                ipc.set("sid", bottomBefore != null ? (Object) bottomBefore.optInt("id") : (Object) Boolean.FALSE);
            }
        }
        ipc.set(top ? "secondary-sid" : "sid", id);
        JSONArray tracks = TvFormat.asArray(ipc.get("track-list"));
        JSONObject bottom = TvFormat.selected(tracks, "sub", 0);
        JSONObject upper = TvFormat.selected(tracks, "sub", 1);
        List<String> lines = new ArrayList<>();
        lines.add("Loaded track " + TvFormat.describeSubtitle(added));
        lines.add(bottom != null ? TvFormat.describeEnabled(bottom) : "Subtitles: off");
        if (upper != null) lines.add("Top subtitles: " + TvFormat.describeFull(upper));
        return TvFormat.join(lines);
    }

    /** Port of Tv._subtext: the subtitle text on screen now. */
    private static String subtext(MpvIpc ipc) throws MpvIpc.MpvError {
        String bottom = strip(ipc.get("sub-text"));
        String top = strip(ipc.get("secondary-sub-text"));
        if (bottom.isEmpty() && top.isEmpty()) return "No subtitles on screen now";
        if (!bottom.isEmpty() && !top.isEmpty()) {
            return "Bottom subtitles: \"" + bottom + "\"\nTop subtitles: \"" + top + "\"";
        }
        if (!top.isEmpty()) return "Top subtitles: \"" + top + "\"";
        return "Subtitles: \"" + bottom + "\"";
    }

    /** Port of Tv._seek: "+N"/"-N" seconds from the current time, or "H:MM:SS"/"MM:SS" from the start. */
    private static String seek(MpvIpc ipc, String value) throws MpvIpc.MpvError, ToolError {
        Object now = ipc.get("time-pos");
        Object total = ipc.get("duration");
        double target;
        if (value.matches("[+-]\\d+")) {
            if (now == null) throw new ToolError("The episode is still opening — cannot seek yet");
            target = ((Number) now).doubleValue() + Integer.parseInt(value);
        } else if (value.matches("\\d+(:\\d{1,2}){1,2}")) {
            long t = 0;
            for (String part : value.split(":")) t = t * 60 + Integer.parseInt(part);
            target = t;
        } else {
            throw new ToolError("seek needs value \"+N\" or \"-N\" seconds or a time \"H:MM:SS\", not \""
                    + value + "\"");
        }
        if (total instanceof Number && ((Number) total).doubleValue() != 0) {
            target = Math.min(target, ((Number) total).doubleValue());
        }
        target = Math.max(0, target);
        int targetInt = (int) target;
        ipc.command("seek", targetInt, "absolute");
        return "Seeked to " + TvFormat.clock(targetInt) + " of " + TvFormat.clock(total);
    }

    private static void stop(MpvIpc ipc) {
        try {
            ipc.command("stop");
        } catch (MpvIpc.MpvError ignored) {
            // mpv may close the connection before it replies to stop; that is a successful stop.
        }
    }

    /**
     * Stop the follower before mpv is stopped (Python player stop: _unfollow then _stop). {@link #tv()} throws
     * ToolError when tv_mcp.json is absent; with no {@link Tv} built there is no follower to stop, so that is
     * swallowed and player stop still stops mpv.
     */
    private void unfollow() {
        try {
            tv().unfollow();
        } catch (ToolError ignored) {
            // tv_mcp not configured: no Tv instance, so no follower runs.
        }
    }

    // --- player-only helpers (the track/describe formatters now live in TvFormat) --------------------------

    /** The subtitle tracks joined with ", ", or "" when none — the Python _status inline join whose `or 'none'`
     *  the caller applies, distinct from TvFormat.describeSubtitles' own "none" fallback. */
    private static String joinSubtitles(List<JSONObject> subs) {
        StringBuilder sb = new StringBuilder();
        for (JSONObject t : subs) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(TvFormat.describeSubtitle(t));
        }
        return sb.toString();
    }

    /** The string value stripped of leading/trailing whitespace, or "" when absent (Python
     *  `(await ipc.get(...) or "").strip()`). Covers Unicode spaces such as U+00A0 the way Python
     *  str.strip() does, which String.trim() misses; String.strip() is Java 11, unavailable on API 28. */
    private static String strip(Object v) {
        if (!(v instanceof String)) return "";
        String s = (String) v;
        int start = 0, end = s.length();
        while (start < end && isWhitespace(s.charAt(start))) start++;
        while (end > start && isWhitespace(s.charAt(end - 1))) end--;
        return s.substring(start, end);
    }

    /** Python str.isspace() semantics: ASCII/Unicode whitespace plus the space separators
     *  (U+00A0 etc.) that Character.isWhitespace() alone excludes. */
    private static boolean isWhitespace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    // --- small helpers -------------------------------------------------------------------------------------

    private static JSONObject tool(String name, String description, JSONObject inputSchema) throws JSONException {
        return new JSONObject().put("name", name).put("description", description).put("inputSchema", inputSchema);
    }

    private static JSONObject prop(String type) throws JSONException {
        return new JSONObject().put("type", type);
    }

    private static JSONObject schema(JSONObject properties, String... required) throws JSONException {
        JSONArray req = new JSONArray();
        for (String r : required) req.put(r);
        return new JSONObject().put("type", "object").put("properties", properties).put("required", req);
    }

    // --- raw HTTP helpers ----------------------------------------------------------------------------------

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private static byte[] readBody(InputStream in, int len) throws IOException {
        if (len <= 0) return new byte[0];
        byte[] b = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(b, off, len - off);
            if (n < 0) break;
            off += n;
        }
        return Arrays.copyOf(b, off);
    }

    private static void respondJson(OutputStream out, JSONObject body) throws IOException {
        respond(out, "200 OK", "application/json", body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(OutputStream out, String status, String contentType, byte[] body)
            throws IOException {
        String header = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-cache, no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }
}
