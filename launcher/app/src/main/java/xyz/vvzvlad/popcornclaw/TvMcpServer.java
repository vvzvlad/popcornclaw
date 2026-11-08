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
 * with their verbatim Russian descriptions -- over JSON-RPC 2.0 on a bound {@link ServerSocket}, shaped exactly
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

    // --- verbatim tool descriptions (src/server.py) --------------------------------------------------------
    // Copied byte for byte from the Python server, including the spaces that joined its adjacent string
    // literals: every character of these lands in the model's context, so the port must not change them.
    // One departure: SEARCH's first sentence names no Jackett trackers, as without Jackett it searches RuTor.
    // PLAYER's tail from sub_add on, TRANSCRIBE and SYNC_SUBTITLES are this server's own.

    private static final String SEARCH =
            "Найти раздачу фильма или сериала (через Jackett или поиск RuTor в TorrServer). "
            + "query — только название, без сезона и серии: со словами «третий сезон», «season 3» трекеры "
            + "ничего не находят; сезон и серию передай в prepare и play. "
            + "У названия с ремейками добавь в запрос год («Сёгун 2024»). "
            + "Первые строки «id N: в TorrServer, размер: название» — раздачи, которые уже стоят на сервере "
            + "(то, что пользователь смотрит), с «последний просмотренный файл — K» (номер файла, как в "
            + "files), если он есть: когда нужный сериал там есть, бери его id, а не другую раздачу. "
            + "Дальше до 10 строк «id N: сиды, размер, трекер: название»; для play бери id из строки, "
            + "а не её место в списке. id — служебный номер, пользователю его не называй: говори о раздаче "
            + "по сути (качество, озвучка или субтитры, размер, сезон). subtitles — код языка субтитров "
            + "(«ru») или два через запятую («ru,en»), audio — код языка озвучки («en»): с любым из них "
            + "первые 5 раздач с 5 и больше сидами проверяются на дорожки этих языков (обычно 1–2 минуты; "
            + "если раздачи не читаются — до 12 минут), раздачи, где есть всё запрошенное, поднимаются "
            + "наверх, их id не меняются; «надписи» — надписи на экране, песни или комментарии, это не "
            + "полные субтитры.";

    private static final String FILES =
            "Видеофайлы раздачи id из search по порядку: «N: путь, размер». Нужен, когда серию не найти "
            + "по season и episode (в названиях файлов нет номеров вида S01E05): выбери файл по названию и "
            + "передай его N в prepare и play как file. Ставит раздачу в TorrServer, телевизор не трогает.";

    private static final String PREPARE =
            "Подготовить серию раздачи id из search к play, телевизор не трогает: ставит её в TorrServer "
            + "на скачивание с прелоадом (season, episode или file — как в play) и до 90 с проверяет, "
            + "успевает ли раздача. Готово, когда скорость за 10 с в 1,5 раза выше, чем нужно серии, или "
            + "прелоад серии докачан или уже лежит в кэше: ответ называет серию и скорость (или что прелоад уже в кэше) и, если кэш был "
            + "неполным, сколько в нём — тогда play с теми же id, season, episode или file. Не успевает или "
            + "нет пиров 30 с — ошибка с цифрами: скажи их и предложи другую раздачу. Перед prepare и перед "
            + "play скажи пользователю, что делаешь.";

    private static final String PLAY =
            "Включить на телевизоре (mpv на приставке) раздачу id из search; mpv выводится на экран (и "
            + "запускается, если не запущен). season и episode — номер сезона и серии; указана только "
            + "серия — сезон 1; серия не указана — первый видеофайл (или первый файл сезона season). file — "
            + "номер видеофайла из files вместо season и episode. mpv "
            + "получает все серии раздачи плейлистом и дальше идёт по порядку. subtitles — код языка "
            + "(«ru») или два через запятую («ru,en»): полные субтитры первого языка внизу, второго — "
            + "сверху (только текстовые); audio — код языка озвучки («en»). Выбор держится за раздачей — "
            + "той же темой трекера (и когда в ней вышла новая серия) или тем же торрентом с другого "
            + "трекера: на следующих сериях, после stop и при play её серии без subtitles или audio, до "
            + "перезапуска сервиса; субтитры сбрасывают только player subtitles off, выбор по номеру "
            + "дорожки, которая не полные субтитры или без языка, и play другой раздачи без subtitles, "
            + "озвучку — player audio с дорожкой без языка и play другой раздачи без audio. "
            + "Обычно после prepare. Ждёт начала воспроизведения до 2 минут; если за 30 с к раздаче не "
            + "подключился ни один пир, останавливает mpv и отвечает, что раздача мёртвая; если mpv не "
            + "смог открыть серию или не включил её за 2 минуты, останавливает его и отвечает ошибкой с "
            + "пирами и скоростью раздачи.";

    private static final String PLAYER =
            "Управление тем, что играет в mpv. status — что играет, время, дорожки субтитров и звука с "
            + "номерами и разрешение видео (ширина×высота, кадры/с: так видно, реально ли это 4К); "
            + "subtext — субтитры, которые сейчас на экране (строка снизу и, если стоят два языка, верхняя): "
            + "прочитать реплику, не перематывая вслепую; "
            + "pause — пауза или продолжить; stop — остановить (mpv закрывается); subtitles — "
            + "value: код языка или два через запятую, как в play, «off» или номер дорожки (она внизу, "
            + "верхние выключаются); audio — value: код языка, часть названия дорожки или её номер, язык "
            + "выбранной дорожки держится за раздачей, как audio в play; "
            + "seek — перемотка, value: «+N» или «-N» секунд от текущего места («+600» — на 10 минут "
            + "вперёд) либо время от начала «Ч:ММ:СС» или «ММ:СС» («45:00»); episode — другая серия того, "
            + "что играет, без search и id: value «next», «prev» или номер серии в текущем сезоне; ответ и "
            + "ошибки — как в play, только без проверки пиров за 30 с: мёртвая раздача — ошибка через 2 минуты. "
            + "sub_add — подключить файл субтитров, value — путь, place «top» — верхней строкой (по умолчанию "
            + "снизу), язык — из имени файла («серия.ru.srt»); sub_delay, top_sub_delay, audio_delay — сдвиг нижних "
            + "сабов, верхних сабов, звука: value «+N» или «-N» секунд к текущему сдвигу либо число — весь сдвиг "
            + "(«0»), плюс — позже; ненулевые сдвиги видны в status.";

    private static final String TRANSCRIBE =
            "Распознать речь того, что играет в mpv, в субтитры .srt (Whisper). at — места начала кусков через "
            + "запятую: «Ч:ММ:СС», «ММ:СС» или секунды («2:00,7:00,12:00»); seconds — длина куска, по умолчанию "
            + "60 (10–300); audio — язык или номер звуковой дорожки, как в player status (по умолчанию та, что "
            + "играет). Минутный кусок — около 10 с рядом с местом, где стоит плеер, и до 2–3 минут там, где "
            + "торрент ещё не качал. Пишет /data/local/popcornclaw/subs/<название>.speech.<язык>.srt; повторный "
            + "вызов добавляет куски в тот же файл, распознанное в тех же местах заменяется. Ответ — путь, по куску "
            + "«ММ:СС–ММ:СС: N фраз» и фразы «ММ:СС.с текст»; не вышедший кусок назван с причиной. Нужен, когда "
            + "с субтитрами проблема: найти реплику без внешних субтитров, образец для sync_subtitles (для него — "
            + "куски через каждые ~5 минут по всей серии).";

    private static final String SYNC_SUBTITLES =
            "Подогнать тайминг субтитров subtitles под образец reference — обычно .srt из transcribe: находит "
            + "сдвиг каждого участка (он может меняться ступеньками) и пишет "
            + "/data/local/popcornclaw/subs/<имя>.sync.<язык>.srt. subtitles и reference — путь к файлу или номер "
            + "внешней дорожки сабов из player status. Ответ — путь, сдвиги по участкам и куски образца, которые "
            + "не сошлись, и где сдвиг угадан, потому что ни один кусок образца там не сошёлся, — где есть место, "
            + "с местами для новых кусков transcribe, после которых вызови sync_subtitles заново. mpv не трогает: результат "
            + "подключает player sub_add.";

    private static final String FIND_SUBTITLES =
            "Найти субтитры фильма или серии в базах OpenSubtitles и SubDL. query — оригинальное (обычно "
            + "английское) название без сезона и серии: по русскому названию базы находят не всё. language — "
            + "код языка («ru»). season и episode — для серии; указана только серия — сезон 1. Ответ — до 5 "
            + "субтитров от каждой базы: какое название (и год, у серии — SxxEyy) нашлось, имена релизов, у "
            + "OpenSubtitles — число загрузок, SDH — для слабослышащих. Это только список найденного: скачать "
            + "и включить такие субтитры пока нельзя.";

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
                if (target == null || reference == null) throw new ToolError("Нужны subtitles и reference");
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
                throw new ToolError("tv_mcp не настроен: нет " + TV_SETTINGS_PATH + " или в нём нет torrserver_url");
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
                throw new ToolError("tv_mcp не настроен: нет " + TV_SETTINGS_PATH
                        + " или в нём нет opensubtitles_api_key/subdl_api_key");
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
                if ("status".equals(action) || "subtext".equals(action)) return "Ничего не играет";
                throw new ToolError("Ничего не играет");
            }
            try {
                if ("status".equals(action)) return status(ipc);
                if ("subtext".equals(action)) return subtext(ipc);
                if ("pause".equals(action)) {
                    ipc.command("cycle", "pause");
                    return TvFormat.truthy(ipc.get("pause")) ? "Пауза" : "Играет";
                }
                if ("stop".equals(action)) {
                    // The languages stay chosen: a play of this release's next episode puts them on again
                    // (Python player stop: _unfollow then _stop).
                    unfollow();
                    stop(ipc);
                    return "Остановлено";
                }
                String value = args.optString("value", "");
                if (value.isEmpty()) throw new ToolError("Для " + action + " нужен value");
                value = value.trim();
                if ("seek".equals(action)) return seek(ipc, value);
                if ("sub_add".equals(action)) return subAdd(ipc, value, optArg(args, "place"));
                if ("sub_delay".equals(action)) return delay(ipc, action, "sub-delay", "Сдвиг сабов", value);
                if ("top_sub_delay".equals(action)) {
                    return delay(ipc, action, "secondary-sub-delay", "Сдвиг сабов сверху", value);
                }
                if ("audio_delay".equals(action)) return delay(ipc, action, "audio-delay", "Сдвиг звука", value);
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
        String paused = TvFormat.truthy(ipc.get("pause")) ? ", пауза" : "";
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
        lines.add("Играет: " + String.valueOf(ipc.get("media-title")) + paused + ", "
                + TvFormat.clock(ipc.get("time-pos")) + " из " + TvFormat.clock(ipc.get("duration")) + video);
        lines.add(sid != null ? TvFormat.describeEnabled(sid) : "Сабы: выкл");
        if (top != null) lines.add("Сабы сверху: " + TvFormat.describeFull(top));
        String subLine = joinSubtitles(subs);
        lines.add("Дорожки сабов: " + (subLine.isEmpty() ? "нет" : subLine));
        lines.add(aid != null ? "Звук: " + TvFormat.describeAudio(aid) : "Звук: выкл");
        lines.add("Дорожки звука: " + TvFormat.describeAudios(voices));
        addDelay(lines, ipc, "sub-delay", "Сдвиг сабов");
        addDelay(lines, ipc, "secondary-sub-delay", "Сдвиг сабов сверху");
        addDelay(lines, ipc, "audio-delay", "Сдвиг звука");
        return TvFormat.join(lines);
    }

    /** A status line «label: +0,5 с» for a delay property that is not zero. */
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
            throw new ToolError("Для " + action + " нужен value «+N» или «-N» секунд к текущему сдвигу либо число — "
                    + "весь сдвиг, а не «" + value + "»");
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

    /** A delay as «+0,5 с», «-1,25 с», «+3,0 с». */
    static String delayText(double seconds) {
        if (Math.abs(seconds) < 0.005) seconds = 0;
        String s = String.format(Locale.ROOT, "%+.2f", seconds);
        if (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s.replace('.', ',') + " с";
    }

    /**
     * sub_add: mpv loads the subtitle file {@code path} as a new track (its language from the name's «.xx.srt»,
     * so player subtitles finds it) and shows it at the bottom (sid) or, for {@code place} «top», the top
     * (secondary-sid). Only the slot asked for changes.
     */
    private static String subAdd(MpvIpc ipc, String path, String place) throws MpvIpc.MpvError, ToolError {
        boolean top;
        if (place == null || "bottom".equals(place)) top = false;
        else if ("top".equals(place)) top = true;
        else throw new ToolError("place — «bottom» или «top», а не «" + place + "»");
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
            throw new ToolError("mpv не открыл " + path + " (" + e.getMessage() + ")");
        }
        JSONObject added = null;
        for (JSONObject t : TvFormat.tracksOfType(TvFormat.asArray(ipc.get("track-list")), "sub")) {
            if (t.optInt("id") > lastId && (added == null || t.optInt("id") > added.optInt("id"))) added = t;
        }
        if (added == null) throw new ToolError("mpv не добавил дорожку из " + path);
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
        lines.add("Подключена дорожка " + TvFormat.describeSubtitle(added));
        lines.add(bottom != null ? TvFormat.describeEnabled(bottom) : "Сабы: выкл");
        if (upper != null) lines.add("Сабы сверху: " + TvFormat.describeFull(upper));
        return TvFormat.join(lines);
    }

    /** Port of Tv._subtext: the subtitle text on screen now. */
    private static String subtext(MpvIpc ipc) throws MpvIpc.MpvError {
        String bottom = strip(ipc.get("sub-text"));
        String top = strip(ipc.get("secondary-sub-text"));
        if (bottom.isEmpty() && top.isEmpty()) return "Субтитров на экране сейчас нет";
        if (!bottom.isEmpty() && !top.isEmpty()) {
            return "Субтитры снизу: «" + bottom + "»\nСубтитры сверху: «" + top + "»";
        }
        if (!top.isEmpty()) return "Субтитры сверху: «" + top + "»";
        return "Субтитры: «" + bottom + "»";
    }

    /** Port of Tv._seek: "+N"/"-N" seconds from the current time, or "H:MM:SS"/"MM:SS" from the start. */
    private static String seek(MpvIpc ipc, String value) throws MpvIpc.MpvError, ToolError {
        Object now = ipc.get("time-pos");
        Object total = ipc.get("duration");
        double target;
        if (value.matches("[+-]\\d+")) {
            if (now == null) throw new ToolError("Серия ещё открывается — перемотать нельзя");
            target = ((Number) now).doubleValue() + Integer.parseInt(value);
        } else if (value.matches("\\d+(:\\d{1,2}){1,2}")) {
            long t = 0;
            for (String part : value.split(":")) t = t * 60 + Integer.parseInt(part);
            target = t;
        } else {
            throw new ToolError("Для seek нужен value «+N» или «-N» секунд либо время «Ч:ММ:СС», а не «"
                    + value + "»");
        }
        if (total instanceof Number && ((Number) total).doubleValue() != 0) {
            target = Math.min(target, ((Number) total).doubleValue());
        }
        target = Math.max(0, target);
        int targetInt = (int) target;
        ipc.command("seek", targetInt, "absolute");
        return "Перемотано: " + TvFormat.clock(targetInt) + " из " + TvFormat.clock(total);
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

    /** The subtitle tracks joined with ", ", or "" when none — the Python _status inline join whose `or 'нет'`
     *  the caller applies, distinct from TvFormat.describeSubtitles' "никаких" fallback. */
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
