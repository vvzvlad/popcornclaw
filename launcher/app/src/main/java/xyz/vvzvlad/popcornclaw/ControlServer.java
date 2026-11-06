package xyz.vvzvlad.popcornclaw;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The box's own control surface on the LAN: an MCP server any MCP client on the network (a PC, a smart
 * speaker) can drive to do what the on-box agent can -- run shell as root, report status, drive the mpv
 * player, read and write the gateway's config, and drop the agent a one-line message into the shared
 * TV/Telegram dialog -- plus a plain settings form in a browser. Every route but the login needs the box's
 * {@link Pin}: MCP clients send it as {@code Authorization: Bearer <PIN>}, browsers keep it in the cookie the
 * login (or the home-screen QR's {@code ?pin=} link) sets. Shaped exactly like {@link ScreenStreamServer}: a
 * bound {@link ServerSocket}, an accept loop, one thread per connection and raw HTTP parsing.
 *
 * <p>Routes:
 * <ul>
 *   <li>{@code POST /mcp} -- one JSON-RPC 2.0 request or notification, stateless Streamable HTTP; 401 without
 *       the bearer PIN.</li>
 *   <li>{@code GET /} -- the settings form (HTML) with the PIN cookie, else the login page.</li>
 *   <li>{@code GET /?pin=<PIN>} -- sets the cookie and redirects to {@code /}; a wrong PIN gets the login page.</li>
 *   <li>{@code POST /login} -- the login page's PIN; sets the cookie and redirects to {@code /}.</li>
 *   <li>{@code POST /config} -- apply the settings form.</li>
 *   <li>{@code GET}/{@code DELETE} on {@code /mcp} -- 405 Method Not Allowed.</li>
 *   <li>anything else -- 404.</li>
 * </ul>
 */
public final class ControlServer {
    private static final String TAG = "PopcornClaw-ctl";
    /** ScreenStreamServer owns 8080; this is the control MCP + settings form. Bound on all interfaces. */
    public static final int PORT = 8081;

    private static final String MCP_PROTOCOL = "2025-06-18";
    private static final String DIR = "/data/local/popcornclaw";
    private static final String CONFIG_PATH = DIR + "/config.json";
    private static final String SECURITY_PATH = DIR + "/.security.yml";
    private static final String TV_MCP_PATH = DIR + "/tv_mcp.json";
    private static final String HEALTH_URL = "http://127.0.0.1:18790/health";

    // Prepended to every root command: the Termux PATH (socat etc.), the CA store and the working dir, so a
    // tool runs with the same environment the agent's own exec has. The command itself is appended as-is and
    // the whole string is passed to `su -c` as ONE argv element -- never wrapped into a larger sh -c.
    private static final String ENV_PREFIX =
            "export PATH=\"$PATH:/data/data/com.termux/files/usr/bin\"; "
            + "export SSL_CERT_DIR=/system/etc/security/cacerts; "
            + "cd " + DIR + "; ";

    // The endpoint fields live in config.json; the key fields live in .security.yml. FIELD_KEYS is their union,
    // the names used both in the config_set input schema and as the form's input names.
    private static final String[] CFG_KEYS = {
            "model.api_base", "model.model", "model.proxy", "asr.proxy",
            "mcp.tv.url", "telegram.allow_from", "telegram.base_url"};
    private static final String[] KEY_KEYS = {
            "model.api_key", "asr.api_key", "telegram.token", "brave.api_key"};
    private static final String[] FIELD_KEYS = {
            "model.api_base", "model.model", "model.proxy", "model.api_key",
            "asr.proxy", "asr.api_key", "mcp.tv.url",
            "telegram.token", "telegram.allow_from", "telegram.base_url", "brave.api_key"};
    // tv_mcp.json's endpoints and keys (TvMcpServer reads them; the gateway never does). The form's inputs are
    // named "media." + key. The URLs are plain fields, the keys secrets.
    private static final String[] MEDIA_URLS = {"jackett_url", "torrserver_url"};
    private static final String[] MEDIA_KEYS = {"jackett_api_key", "opensubtitles_api_key", "subdl_api_key"};
    // The login form's body is a PIN and nothing else; an unauthenticated client gets no bigger read than this.
    private static final int LOGIN_BODY_MAX = 1024;

    // .security.yml has a fixed layout (see buildSecurityYml); each value is pulled with one regex. \s* spans the
    // newlines/indent between the fixed keys, so these match the one value under each key.
    private static final Pattern YML_MAIN = Pattern.compile("main:\\s*api_keys:\\s*-\\s*\"([^\"]*)\"");
    private static final Pattern YML_ASR = Pattern.compile("groq-asr:\\s*api_keys:\\s*-\\s*\"([^\"]*)\"");
    private static final Pattern YML_TOKEN = Pattern.compile("token:\\s*\"([^\"]*)\"");
    private static final Pattern YML_BRAVE = Pattern.compile("brave:\\s*api_keys:\\s*-\\s*\"([^\"]*)\"");

    private static final Random RNG = new Random();

    private volatile boolean running;
    // Assigned on the accept thread, read by stop() on the caller's thread: volatile for the handoff.
    private volatile ServerSocket serverSocket;
    private Thread acceptThread;
    // Serializes the config read-modify-write across connection threads: two concurrent writers would lose one
    // update, and a reader could catch a half-written file (cat > truncates non-atomically).
    private final Object configLock = new Object();
    // For the PIN and the app's own switches (AppSettings): the service's application context.
    private final Context ctx;

    public ControlServer(Context c) {
        ctx = c.getApplicationContext();
    }

    public void start() {
        if (running) return;
        running = true;
        acceptThread = new Thread(this::accept, "pcx-ctl-accept");
        acceptThread.start();
        Log.i(TAG, "control server starting on port " + PORT);
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
    }

    private void accept() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(PORT));
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
            new Thread(() -> handle(s), "pcx-ctl-conn").start();
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
            String cookies = null;
            String authorization = null;
            for (String line; (line = readLine(in)) != null && !line.isEmpty(); ) {
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if (name.equalsIgnoreCase("Content-Length")) {
                    try {
                        contentLength = Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {
                    }
                } else if (name.equalsIgnoreCase("Cookie")) {
                    cookies = cookies == null ? value : cookies + "; " + value;
                } else if (name.equalsIgnoreCase("Authorization")) {
                    authorization = value;
                }
            }

            String route = path;
            String query = "";
            int q = route.indexOf('?');
            if (q >= 0) {
                query = route.substring(q + 1);
                route = route.substring(0, q);
            }

            // The PIN is checked before an unauthorized request's body is read: such a client gets the login page
            // (or a 401 on /mcp) and nothing else.
            boolean browserOk = Pin.matches(ctx, Pin.fromCookies(cookies));
            if ("/mcp".equals(route)) {
                if (!Pin.matches(ctx, Pin.fromBearer(authorization))) {
                    respond(out, "401 Unauthorized", "text/plain",
                            "unauthorized: send Authorization: Bearer <the PIN shown on the TV>"
                                    .getBytes(StandardCharsets.UTF_8),
                            "WWW-Authenticate: Bearer realm=\"popcornclaw\"\r\n");
                } else if ("POST".equals(method)) {
                    handleMcp(out, readBody(in, contentLength));
                } else if ("GET".equals(method) || "DELETE".equals(method)) {
                    respond(out, "405 Method Not Allowed", "text/plain",
                            "method not allowed".getBytes(StandardCharsets.UTF_8));
                } else {
                    respond(out, "404 Not Found", "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
                }
            } else if ("GET".equals(method) && "/".equals(route)) {
                Map<String, String> params = parseForm(query.getBytes(StandardCharsets.UTF_8));
                if (params.containsKey("pin")) {
                    // The QR's link: a right PIN becomes the cookie and leaves the address bar; a wrong one asks.
                    if (Pin.matches(ctx, params.get("pin"))) redirectHome(out, "302 Found");
                    else serveLogin(out, "401 Unauthorized", true);
                } else if (browserOk) {
                    serveForm(out);
                } else {
                    serveLogin(out, "200 OK", false);
                }
            } else if ("POST".equals(method) && "/login".equals(route)) {
                Map<String, String> form = parseForm(readBody(in, Math.min(contentLength, LOGIN_BODY_MAX)));
                if (Pin.matches(ctx, form.get("pin"))) redirectHome(out, "303 See Other");
                else serveLogin(out, "401 Unauthorized", true);
            } else if ("POST".equals(method) && "/config".equals(route)) {
                if (browserOk) applyForm(out, readBody(in, contentLength));
                else serveLogin(out, "401 Unauthorized", false);
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
                                    .put("name", "popcornclaw-control").put("version", "1")));
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

        JSONObject execProps = new JSONObject();
        execProps.put("command", prop("string", "shell command to run as root on the box"));
        execProps.put("timeout_sec", prop("integer", "timeout in seconds; default 30, max 120"));
        tools.put(tool("exec", "Run a shell command on the box as root; returns combined output with the exit"
                + " code appended.", schema(execProps, "command")));

        tools.put(tool("status", "Short report: foreground activity, gateway health and mpv state.",
                schema(new JSONObject())));

        JSONObject playerProps = new JSONObject();
        JSONObject action = prop("string", "one of status|pause|resume|toggle|seek|set_sub|set_audio|stop");
        action.put("enum", new JSONArray().put("status").put("pause").put("resume").put("toggle")
                .put("seek").put("set_sub").put("set_audio").put("stop"));
        playerProps.put("action", action);
        playerProps.put("value", prop("number",
                "relative seconds for seek, or the track id for set_sub/set_audio"));
        tools.put(tool("player", "Control the mpv player over its JSON IPC socket.",
                schema(playerProps, "action")));

        tools.put(tool("config_get", "Return the gateway's endpoints and keys as a JSON text blob.",
                schema(new JSONObject())));

        JSONObject setProps = new JSONObject();
        setProps.put("model.api_base", prop("string", "main model api_base"));
        setProps.put("model.model", prop("string", "main model name"));
        setProps.put("model.proxy", prop("string", "main model proxy"));
        setProps.put("model.api_key", prop("string", "main model API key"));
        setProps.put("asr.proxy", prop("string", "groq-asr proxy"));
        setProps.put("asr.api_key", prop("string", "groq-asr API key"));
        setProps.put("mcp.tv.url", prop("string", "tv MCP server url"));
        setProps.put("telegram.token", prop("string", "telegram bot token"));
        setProps.put("telegram.allow_from", prop("string", "comma-separated Telegram user IDs the bot answers;"
                + " the first one shares the dialog with the TV, unless the TV is linked to another chat (a group)"));
        setProps.put("telegram.base_url", prop("string", "Telegram Bot API base URL"));
        setProps.put("brave.api_key", prop("string", "brave web-search API key"));
        tools.put(tool("config_set", "Set any subset of the gateway's endpoints and keys; a gateway restart"
                + " is needed to apply them.", schema(setProps)));

        JSONObject askProps = new JSONObject();
        askProps.put("message", prop("string",
                "one line to send the on-box agent in the shared TV/Telegram dialog"));
        tools.put(tool("ask_agent", "Send the on-box agent a one-line message in the shared TV/Telegram"
                + " dialog; the reply lands in that dialog, not here.", schema(askProps, "message")));

        return tools;
    }

    private String callTool(String name, JSONObject args) throws ToolError {
        switch (name) {
            case "exec":
                return toolExec(args);
            case "status":
                return toolStatus();
            case "player":
                return toolPlayer(args);
            case "config_get":
                return toolConfigGet();
            case "config_set":
                return toolConfigSet(args);
            case "ask_agent":
                return toolAskAgent(args);
            default:
                throw new ToolError("unknown tool: " + name);
        }
    }

    private String toolExec(JSONObject args) throws ToolError {
        String command = args.optString("command", "");
        if (command.isEmpty()) throw new ToolError("command is required");
        int timeout = args.optInt("timeout_sec", 30);
        if (timeout < 1) timeout = 1;
        if (timeout > 120) timeout = 120;
        RootResult r = runRoot(command, timeout * 1000L);
        StringBuilder sb = new StringBuilder(r.output == null ? "" : r.output);
        if (r.timedOut) {
            sb.append("\n[timed out after ").append(timeout).append("s]");
        } else {
            sb.append("\n[exit ").append(r.exit).append("]");
        }
        return sb.toString();
    }

    private String toolStatus() {
        StringBuilder sb = new StringBuilder();
        RootResult fg = runRoot("dumpsys activity activities | grep mResumedActivity", 10_000);
        String fgLine = fg.output == null ? "" : fg.output.trim();
        sb.append("foreground: ").append(fgLine.isEmpty() ? "unknown" : fgLine).append('\n');
        sb.append("gateway: ").append(gatewayHealthy() ? "healthy (18790)" : "not responding (18790)").append('\n');
        String mpv = mpvStatusSummary();
        sb.append(mpv == null ? "mpv not running" : mpv);
        return sb.toString();
    }

    private String toolPlayer(JSONObject args) throws ToolError {
        String action = args.optString("action", "");
        if (action.isEmpty()) throw new ToolError("action is required");
        switch (action) {
            case "status": {
                String s = mpvStatusSummary();
                return s == null ? "mpv not running" : s;
            }
            case "pause":
                return mpvCommand(mpvCmd("set_property", "pause", Boolean.TRUE));
            case "resume":
                return mpvCommand(mpvCmd("set_property", "pause", Boolean.FALSE));
            case "toggle":
                return mpvCommand(mpvCmd("cycle", "pause"));
            case "seek":
                if (!args.has("value")) throw new ToolError("seek needs value (relative seconds)");
                return mpvCommand(mpvCmd("seek", args.optDouble("value", 0)));
            case "set_sub":
                if (!args.has("value")) throw new ToolError("set_sub needs value (track id)");
                return mpvCommand(mpvCmd("set_property", "sid", args.optInt("value")));
            case "set_audio":
                if (!args.has("value")) throw new ToolError("set_audio needs value (track id)");
                return mpvCommand(mpvCmd("set_property", "aid", args.optInt("value")));
            case "stop":
                return mpvCommand(mpvCmd("stop"));
            default:
                throw new ToolError("unknown action: " + action);
        }
    }

    private String toolConfigGet() throws ToolError {
        try {
            return readConfigFields().toString(2);
        } catch (JSONException e) {
            throw new ToolError("internal: " + e.getMessage());
        }
    }

    private String toolConfigSet(JSONObject args) throws ToolError {
        JSONObject fields = new JSONObject();
        try {
            for (String k : FIELD_KEYS) if (args.has(k)) fields.put(k, args.optString(k));
        } catch (JSONException e) {
            throw new ToolError(e.getMessage());
        }
        if (fields.length() == 0) {
            throw new ToolError("no known fields given; expected a subset of: " + joinArray(FIELD_KEYS));
        }
        JSONArray changed = applyConfig(fields);
        String head = changed.length() == 0 ? "nothing changed" : "changed: " + join(changed);
        return head + "\nrestart the gateway to apply (it cuts a running turn): `make app restart`,"
                + " or the \"save and restart\" button on the settings form.";
    }

    private String toolAskAgent(JSONObject args) throws ToolError {
        final String message = args.optString("message", "");
        if (message.isEmpty()) throw new ToolError("message is required");
        final String id = Long.toHexString(RNG.nextLong());
        // PicoLink runs on the main looper; the reply is async and arrives in the shared dialog, not here.
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                PicoLink.ask(new JSONObject().put("id", id).put("text", message));
            } catch (Exception e) {
                Log.w(TAG, "ask_agent: send failed", e);
            }
        });
        return "sent to the agent";
    }

    // --- mpv IPC -------------------------------------------------------------------------------------------

    /** Sends one newline-delimited batch of IPC commands to mpv's abstract socket through root + socat. */
    private String mpvSend(List<String> commands) {
        StringBuilder cmd = new StringBuilder("printf '%s\\n'");
        for (String c : commands) cmd.append(" '").append(c).append("'");
        cmd.append(" | socat -t 2 - ABSTRACT-CONNECT:mpv");
        RootResult r = runRoot(cmd.toString(), 8_000);
        return r.output == null ? "" : r.output;
    }

    private String mpvCommand(String json) throws ToolError {
        List<String> l = new ArrayList<>();
        l.add(json);
        String out = mpvSend(l).trim();
        if (out.isEmpty()) throw new ToolError("mpv is not responding (not running?)");
        return out;
    }

    /** {@code {"command":[parts...]}} as a compact JSON string (no single quotes, safe to '...'-wrap). */
    private static String mpvCmd(Object... parts) {
        try {
            JSONArray arr = new JSONArray();
            for (Object p : parts) arr.put(p);
            return new JSONObject().put("command", arr).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String mpvGet(String prop, int reqId) {
        try {
            return new JSONObject()
                    .put("command", new JSONArray().put("get_property").put(prop))
                    .put("request_id", reqId).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A few lines summarising the current file, position, pause and tracks; null if mpv gives nothing. */
    private String mpvStatusSummary() {
        List<String> cmds = new ArrayList<>();
        cmds.add(mpvGet("path", 1));
        cmds.add(mpvGet("time-pos", 2));
        cmds.add(mpvGet("duration", 3));
        cmds.add(mpvGet("pause", 4));
        cmds.add(mpvGet("track-list", 5));
        String raw = mpvSend(cmds);
        Map<Integer, Object> data = new HashMap<>();
        for (String line : raw.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            try {
                JSONObject o = new JSONObject(line);
                if (!o.has("request_id")) continue; // skip event frames
                if (!"success".equals(o.optString("error"))) continue;
                data.put(o.optInt("request_id"), o.opt("data"));
            } catch (JSONException ignored) {
            }
        }
        if (data.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("path: ").append(str(data.get(1))).append('\n');
        sb.append("time: ").append(fmtTime(data.get(2))).append(" / ").append(fmtTime(data.get(3))).append('\n');
        sb.append("pause: ").append(str(data.get(4))).append('\n');
        sb.append("tracks: ").append(summarizeTracks(data.get(5)));
        return sb.toString();
    }

    private static String summarizeTracks(Object tl) {
        if (!(tl instanceof JSONArray)) return "-";
        JSONArray arr = (JSONArray) tl;
        StringBuilder subs = new StringBuilder();
        StringBuilder auds = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject t = arr.optJSONObject(i);
            if (t == null) continue;
            String entry = t.optInt("id") + (t.optBoolean("selected") ? "*" : "")
                    + (t.has("lang") ? " " + t.optString("lang") : "");
            String type = t.optString("type");
            if ("sub".equals(type)) {
                if (subs.length() > 0) subs.append(", ");
                subs.append(entry);
            } else if ("audio".equals(type)) {
                if (auds.length() > 0) auds.append(", ");
                auds.append(entry);
            }
        }
        return "sub[" + subs + "] audio[" + auds + "]";
    }

    private static String fmtTime(Object v) {
        if (!(v instanceof Number)) return "-";
        long s = (long) ((Number) v).doubleValue();
        return String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private static String str(Object v) {
        return (v == null || v == JSONObject.NULL) ? "-" : String.valueOf(v);
    }

    // --- config read / write -------------------------------------------------------------------------------

    /** The fixed fields with their current values: endpoints from config.json, keys from .security.yml. */
    private JSONObject readConfigFields() throws ToolError {
        synchronized (configLock) {
            try {
                JSONObject cfg = parseConfig();
                JSONObject out = new JSONObject();
                for (String k : CFG_KEYS) out.put(k, readCfgField(cfg, k));
                String yml = readFileRoot(SECURITY_PATH);
                out.put("model.api_key", ymlExtract(YML_MAIN, yml));
                out.put("asr.api_key", ymlExtract(YML_ASR, yml));
                out.put("telegram.token", ymlExtract(YML_TOKEN, yml));
                out.put("brave.api_key", ymlExtract(YML_BRAVE, yml));
                return out;
            } catch (JSONException e) {
                throw new ToolError("internal: " + e.getMessage());
            }
        }
    }

    /**
     * Applies the given fields: config.json endpoints changed in place (everything else preserved) and
     * .security.yml regenerated from its four values. Only fields whose value actually differs are written,
     * and each file is rewritten only when at least one of its fields changed. Telegram is switched on exactly
     * when it has a bot token and an allowed ID, and Brave search when it has its key, whenever a submit carries
     * either side (the form always does). Returns the changed field names.
     */
    private JSONArray applyConfig(JSONObject fields) throws ToolError {
        synchronized (configLock) {
            List<String> changed = new ArrayList<>();
            try {
                boolean cfgTouched = false;
                for (String k : CFG_KEYS) if (fields.has(k)) { cfgTouched = true; break; }
                boolean keyTouched = false;
                for (String k : KEY_KEYS) if (fields.has(k)) { keyTouched = true; break; }
                boolean tgTouched = fields.has("telegram.token") || fields.has("telegram.allow_from");
                boolean braveTouched = fields.has("brave.api_key");
                // The enabled switches live in config.json but follow keys in .security.yml: both files needed.
                boolean needCfg = cfgTouched || tgTouched || braveTouched;
                boolean needYml = keyTouched || tgTouched;

                // Read every file that will change BEFORE writing any of them, so a failed read of one aborts the
                // whole submit instead of leaving the other file already rewritten while the reply says "error".
                JSONObject cfg = needCfg ? parseConfig() : null;
                String yml = needYml ? readFileRoot(SECURITY_PATH) : null;

                boolean cfgChanged = false;
                if (cfgTouched) {
                    for (String k : CFG_KEYS) {
                        if (!fields.has(k)) continue;
                        String nv = fields.optString(k);
                        // In the shape readCfgField gives, so "1,2" and "1, 2" are no change.
                        if ("telegram.allow_from".equals(k)) nv = joinList(parseIds(nv));
                        if ("telegram.base_url".equals(k)) nv = nv.trim();
                        if (!nv.equals(readCfgField(cfg, k))) {
                            setCfgField(cfg, k, nv);
                            changed.add(k);
                            cfgChanged = true;
                        }
                    }
                }

                boolean keysChanged = false;
                String modelKey = ymlExtract(YML_MAIN, yml);
                String asrKey = ymlExtract(YML_ASR, yml);
                String token = ymlExtract(YML_TOKEN, yml);
                String braveKey = ymlExtract(YML_BRAVE, yml);
                if (keyTouched) {
                    // An empty submitted key means "leave unchanged": the form pre-fills these password fields, so a
                    // cleared field (or one blank because the current value could not be read) must not blank the secret.
                    if (fields.has("model.api_key")) {
                        String nv = fields.optString("model.api_key");
                        if (!nv.isEmpty() && !nv.equals(modelKey)) { modelKey = nv; changed.add("model.api_key"); keysChanged = true; }
                    }
                    if (fields.has("asr.api_key")) {
                        String nv = fields.optString("asr.api_key");
                        if (!nv.isEmpty() && !nv.equals(asrKey)) { asrKey = nv; changed.add("asr.api_key"); keysChanged = true; }
                    }
                    if (fields.has("telegram.token")) {
                        String nv = fields.optString("telegram.token");
                        if (!nv.isEmpty() && !nv.equals(token)) { token = nv; changed.add("telegram.token"); keysChanged = true; }
                    }
                    if (fields.has("brave.api_key")) {
                        String nv = fields.optString("brave.api_key");
                        if (!nv.isEmpty() && !nv.equals(braveKey)) { braveKey = nv; changed.add("brave.api_key"); keysChanged = true; }
                    }
                }

                if (tgTouched) {
                    JSONObject tg = telegramOpt(cfg);
                    JSONArray ids = tg == null ? null : tg.optJSONArray("allow_from");
                    boolean on = !token.isEmpty() && ids != null && ids.length() > 0;
                    if ((tg != null && tg.optBoolean("enabled", false)) != on) {
                        telegramObj(cfg).put("enabled", on);
                        changed.add("telegram.enabled");
                        cfgChanged = true;
                    }
                }
                if (braveTouched) {
                    JSONObject tools = cfg.optJSONObject("tools");
                    JSONObject web = tools == null ? null : tools.optJSONObject("web");
                    JSONObject brave = web == null ? null : web.optJSONObject("brave");
                    boolean on = !braveKey.isEmpty();
                    if ((brave != null && brave.optBoolean("enabled", false)) != on) {
                        child(child(child(cfg, "tools"), "web"), "brave").put("enabled", on);
                        changed.add("brave.enabled");
                        cfgChanged = true;
                    }
                }

                // Keys first: an "enabled" switched on in config.json is never on disk before the key it needs.
                if (keysChanged) writeFileRoot(SECURITY_PATH, buildSecurityYml(modelKey, asrKey, token, braveKey));
                if (cfgChanged) writeFileRoot(CONFIG_PATH, cfg.toString(2));
                return new JSONArray(changed);
            } catch (JSONException e) {
                throw new ToolError("internal: " + e.getMessage());
            }
        }
    }

    private JSONObject parseConfig() throws ToolError {
        String text = readFileRoot(CONFIG_PATH);
        try {
            return new JSONObject(text);
        } catch (JSONException e) {
            throw new ToolError("cannot parse config.json on the box: " + e.getMessage());
        }
    }

    private static String readCfgField(JSONObject cfg, String key) throws JSONException {
        switch (key) {
            case "model.api_base": {
                JSONObject m = modelEntry(cfg, "main");
                return m == null ? "" : m.optString("api_base", "");
            }
            case "model.model": {
                JSONObject m = modelEntry(cfg, "main");
                return m == null ? "" : m.optString("model", "");
            }
            case "model.proxy": {
                JSONObject m = modelEntry(cfg, "main");
                return m == null ? "" : m.optString("proxy", "");
            }
            case "asr.proxy": {
                JSONObject m = modelEntry(cfg, "groq-asr");
                return m == null ? "" : m.optString("proxy", "");
            }
            case "mcp.tv.url":
                return serverUrl(cfg, "tv");
            case "telegram.allow_from": {
                JSONObject tg = telegramOpt(cfg);
                JSONArray a = tg == null ? null : tg.optJSONArray("allow_from");
                List<String> ids = new ArrayList<>();
                for (int i = 0; a != null && i < a.length(); i++) {
                    String id = a.optString(i).trim();
                    if (!id.isEmpty()) ids.add(id);
                }
                return joinList(ids);
            }
            case "telegram.base_url": {
                JSONObject tg = telegramOpt(cfg);
                JSONObject st = tg == null ? null : tg.optJSONObject("settings");
                return st == null ? "" : st.optString("base_url", "");
            }
            default:
                return "";
        }
    }

    private static void setCfgField(JSONObject cfg, String key, String value) throws JSONException {
        switch (key) {
            case "model.api_base":
                modelEntryRequired(cfg, "main").put("api_base", value);
                break;
            case "model.model":
                modelEntryRequired(cfg, "main").put("model", value);
                break;
            case "model.proxy":
                modelEntryRequired(cfg, "main").put("proxy", value);
                break;
            case "asr.proxy":
                modelEntryRequired(cfg, "groq-asr").put("proxy", value);
                break;
            case "mcp.tv.url":
                serverObj(cfg, "tv").put("url", value);
                break;
            case "telegram.allow_from": {
                List<String> was = parseIds(readCfgField(cfg, key));
                List<String> ids = parseIds(value);
                JSONArray a = new JSONArray();
                for (String id : ids) a.put(id);
                telegramObj(cfg).put("allow_from", a);
                JSONObject links = child(child(cfg, "session"), "identity_links");
                JSONArray owner = ownerLink(links.optJSONArray("owner"), was.isEmpty() ? null : was.get(0), ids);
                if (owner != null) links.put("owner", owner);
                break;
            }
            case "telegram.base_url":
                child(telegramObj(cfg), "settings").put("base_url", value);
                break;
            default:
                break;
        }
    }

    /** channel_list.telegram, or null when absent. */
    private static JSONObject telegramOpt(JSONObject cfg) {
        JSONObject cl = cfg.optJSONObject("channel_list");
        return cl == null ? null : cl.optJSONObject("telegram");
    }

    /** channel_list.telegram, created (with its type) when absent. */
    private static JSONObject telegramObj(JSONObject cfg) throws JSONException {
        JSONObject tg = child(child(cfg, "channel_list"), "telegram");
        if (!tg.has("type")) tg.put("type", "telegram");
        return tg;
    }

    /**
     * The session.identity_links.owner to write when allow_from goes from {@code oldFirstId} (null when it was
     * empty) to {@code newIds}, or null to keep {@code currentOwner}. The TV's dialog is shared with the first
     * allowed ID's Telegram chat (shared-session.patch) while it is linked to no chat or to that one; a link to any
     * other chat (a group) is left as it is.
     */
    static JSONArray ownerLink(JSONArray currentOwner, String oldFirstId, List<String> newIds) {
        String linked = null;
        for (int i = 0; currentOwner != null && i < currentOwner.length(); i++) {
            String s = currentOwner.optString(i);
            if (s.startsWith("telegram:")) {
                linked = s;
                break;
            }
        }
        if (linked != null && (oldFirstId == null || !linked.equals("telegram:" + oldFirstId))) return null;
        JSONArray owner = new JSONArray();
        if (!newIds.isEmpty()) owner.put("telegram:" + newIds.get(0));
        owner.put("pico:tv");
        return owner;
    }

    /** Telegram IDs out of the form's comma-separated text: trimmed, empties and repeats dropped, order kept. */
    static List<String> parseIds(String text) {
        List<String> ids = new ArrayList<>();
        for (String part : text.split("[,\\s]+")) {
            String id = part.trim();
            if (!id.isEmpty() && !ids.contains(id)) ids.add(id);
        }
        return ids;
    }

    private static String joinList(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(l.get(i));
        }
        return sb.toString();
    }

    private static JSONObject modelEntry(JSONObject cfg, String name) throws JSONException {
        JSONArray list = cfg.optJSONArray("model_list");
        if (list == null) return null;
        for (int i = 0; i < list.length(); i++) {
            JSONObject e = list.optJSONObject(i);
            if (e != null && name.equals(e.optString("model_name"))) return e;
        }
        return null;
    }

    private static JSONObject modelEntryRequired(JSONObject cfg, String name) throws JSONException {
        JSONObject e = modelEntry(cfg, name);
        if (e == null) throw new JSONException("model_list entry '" + name + "' not found");
        return e;
    }

    private static String serverUrl(JSONObject cfg, String name) {
        JSONObject s = serverOpt(cfg, name);
        return s == null ? "" : s.optString("url", "");
    }

    private static JSONObject serverOpt(JSONObject cfg, String name) {
        JSONObject tools = cfg.optJSONObject("tools");
        if (tools == null) return null;
        JSONObject mcp = tools.optJSONObject("mcp");
        if (mcp == null) return null;
        JSONObject servers = mcp.optJSONObject("servers");
        if (servers == null) return null;
        return servers.optJSONObject(name);
    }

    private static JSONObject serverObj(JSONObject cfg, String name) throws JSONException {
        return child(child(child(child(cfg, "tools"), "mcp"), "servers"), name);
    }

    private static JSONObject child(JSONObject parent, String key) throws JSONException {
        JSONObject o = parent.optJSONObject(key);
        if (o == null) {
            o = new JSONObject();
            parent.put(key, o);
        }
        return o;
    }

    private static String ymlExtract(Pattern p, String yml) {
        if (yml == null) return "";
        Matcher m = p.matcher(yml);
        return m.find() ? m.group(1) : "";
    }

    /** Regenerates the whole .security.yml from its four values, in its fixed layout. */
    private static String buildSecurityYml(String modelKey, String asrKey, String token, String braveKey) {
        return "model_list:\n"
                + "  main:\n"
                + "    api_keys:\n"
                + "      - \"" + modelKey + "\"\n"
                + "  groq-asr:\n"
                + "    api_keys:\n"
                + "      - \"" + asrKey + "\"\n"
                + "channel_list:\n"
                + "  telegram:\n"
                + "    settings:\n"
                + "      token: \"" + token + "\"\n"
                + "web:\n"
                + "  brave:\n"
                + "    api_keys:\n"
                + "      - \"" + braveKey + "\"\n";
    }

    // --- settings form -------------------------------------------------------------------------------------

    private void serveForm(OutputStream out) throws IOException {
        // A file that could not be read gets no inputs at all (only its error): blank inputs would be submitted
        // and overwrite the values that are really there, e.g. the Telegram IDs and the dialog link they make.
        JSONObject f = null;
        JSONObject servers;
        JSONObject media = null;
        List<String> errs = new ArrayList<>();
        try {
            f = readConfigFields();
        } catch (ToolError e) {
            errs.add(e.getMessage());
        }
        try {
            servers = readServers();
        } catch (ToolError e) {
            servers = new JSONObject();
            errs.add(e.getMessage());
        }
        try {
            media = readMedia();
        } catch (ToolError e) {
            errs.add(e.getMessage());
        }
        StringBuilder b = new StringBuilder();
        b.append(FORM_HEAD);
        for (String e : errs) b.append("<p class=err>").append(escapeHtml(e)).append("</p>");
        b.append("<form method=post action=/config>");

        // Tab bar: one section visible at a time (the panes below). The tabs are plain buttons (type=button) so
        // they never submit the form; every pane's inputs still submit regardless of which tab is shown.
        b.append("<nav class=tabs>");
        b.append("<button type=button class=\"tab active\" data-t=model onclick=\"tab('model')\">Model</button>");
        b.append("<button type=button class=tab data-t=stt onclick=\"tab('stt')\">STT</button>");
        b.append("<button type=button class=tab data-t=mcp onclick=\"tab('mcp')\">MCP</button>");
        b.append("<button type=button class=tab data-t=tg onclick=\"tab('tg')\">Telegram</button>");
        b.append("<button type=button class=tab data-t=web onclick=\"tab('web')\">Web</button>");
        b.append("<button type=button class=tab data-t=media onclick=\"tab('media')\">Media</button>");
        b.append("<button type=button class=tab data-t=screen onclick=\"tab('screen')\">Screen</button>");
        b.append("</nav>");

        b.append(pane("model", true));
        if (f != null) {
            // The gateway is not started without these three (Provisioning.unconfigured); a save that completes
            // them starts it (applyForm).
            if (f.optString("model.model").isEmpty() || f.optString("model.api_base").isEmpty()
                    || f.optString("model.api_key").isEmpty()) {
                b.append("<p class=warn>The agent starts once Model, API base and API key are filled in:"
                        + " fill them in and press Save.</p>");
            }
            b.append(field(f, "model.model", "Model", "text", false));
            b.append(field(f, "model.api_base", "API base", "text", true));
            b.append(field(f, "model.proxy", "Proxy", "text", true));
            b.append(field(f, "model.api_key", "API key", "password", true));
        }
        b.append("</div>");

        b.append(pane("stt", false));
        if (f != null) {
            b.append(field(f, "asr.proxy", "Proxy", "text", true));
            b.append(field(f, "asr.api_key", "API key", "password", true));
        }
        // An unticked checkbox submits nothing: the hidden 0 comes first and the ticked box's 1 after it wins
        // (parseForm keeps the last value of a repeated name), so a missing name means an old form -- keep.
        b.append("<div class=opt><input type=hidden name=voice_remote value=0>"
                + "<label class=ck><input type=checkbox name=voice_remote value=1"
                + (AppSettings.getVoiceRemote(ctx) ? " checked" : "")
                + "> Remote's voice button talks to the agent</label></div>");
        b.append("</div>");

        b.append(pane("mcp", false));
        b.append("<div id=srvlist>");
        int i = 0;
        for (java.util.Iterator<String> it = servers.keys(); it.hasNext(); i++) {
            String nm = it.next();
            JSONObject s = servers.optJSONObject(nm);
            boolean enabled = s != null && s.optBoolean("enabled", false);
            String url = s == null ? "" : s.optString("url", "");
            boolean authSet = false;
            if (s != null) {
                JSONObject h = s.optJSONObject("headers");
                authSet = h != null && !h.optString("Authorization", "").isEmpty();
            }
            b.append(serverRowHtml(String.valueOf(i), nm, enabled, url, authSet));
        }
        b.append("</div>");
        b.append("<button type=button class=add onclick=addServer()>Add server</button>");
        b.append(serverTemplate(i));
        b.append("</div>");

        b.append(pane("tg", false));
        if (f != null) {
            b.append(field(f, "telegram.token", "bot token", "password", true));
            b.append(field(f, "telegram.allow_from", "Allowed user IDs", "text", true));
            b.append("<p class=hint>Comma-separated. The bot is on once it has a token and an ID; the first ID"
                    + " shares the dialog with the TV, unless the TV is linked to another chat (a group).</p>");
            b.append(field(f, "telegram.base_url", "Bot API URL", "text", true));
        }
        b.append("</div>");

        b.append(pane("web", false));
        if (f != null) b.append(field(f, "brave.api_key", "Brave API key", "password", true));
        b.append("</div>");

        // tv_mcp.json: the media search and subtitle backends of the box's own tv MCP server.
        b.append(pane("media", false));
        if (media != null) {
            b.append(field(media, "media.jackett_url", "Jackett URL", "text", true));
            b.append("<p class=hint>Empty: the search goes through TorrServer's RuTor.</p>");
            b.append(field(media, "media.jackett_api_key", "Jackett API key", "password", true));
            b.append(field(media, "media.torrserver_url", "TorrServer URL", "text", true));
            b.append(field(media, "media.opensubtitles_api_key", "OpenSubtitles API key", "password", true));
            b.append(field(media, "media.subdl_api_key", "SubDL API key", "password", true));
        }
        b.append("</div>");

        // Screen view as a tab: the box's :8080 MJPEG page in an iframe. Its src is set only while this tab is
        // open (see tab() below), so the stream does not run in the background.
        b.append(pane("screen", false));
        b.append("<iframe id=scr class=scrframe title=\"box screen\"></iframe>");
        b.append("</div>");

        // Action bar sticks to the bottom so Save/Reboot are always reachable from any tab. Reboot confirms first
        // and never saves: applyForm handles action=reboot before the config apply.
        b.append("<div class=actions>");
        b.append("<button name=action value=save class=primary>Save</button>");
        b.append("<button name=action value=save_restart class=secondary>Save &amp; restart</button>");
        b.append("<button name=action value=reboot class=danger onclick=\"return confirm('Reboot the box now?')\">Reboot box</button>");
        b.append("</div></form>");
        b.append("<script>function tab(n){var p=document.querySelectorAll('.pane');"
                + "for(var i=0;i<p.length;i++)p[i].classList.toggle('hidden',p[i].id!='pane-'+n);"
                + "var t=document.querySelectorAll('.tab');"
                + "for(var j=0;j<t.length;j++)t[j].classList.toggle('active',t[j].dataset.t==n);"
                + "var f=document.getElementById('scr');"
                // The screen server wants the PIN too; this page is only served with it, so it may carry it.
                + "if(f)f.src=(n=='screen')?('http://'+location.hostname+':" + ScreenStreamServer.PORT
                + "/?pin=" + Pin.get(ctx) + "'):'about:blank';}"
                + "function rev(b){var i=b.previousElementSibling;var s=i.type=='password';"
                + "i.type=s?'text':'password';b.textContent=s?'hide':'show';}</script>");
        b.append(FORM_TAIL);
        respond(out, "200 OK", "text/html; charset=utf-8", b.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void applyForm(OutputStream out, byte[] body) throws IOException {
        Map<String, String> form = parseForm(body);
        String action = form.containsKey("action") ? form.get("action") : "save";
        if ("reboot".equals(action)) {
            // Reboot the box. Do it on a short delay on another thread so this response is flushed first -- the
            // browser then gets a page instead of a dropped connection as the box goes down. No config is saved.
            new Thread(() -> {
                try {
                    Thread.sleep(800);
                } catch (InterruptedException ignored) {
                }
                runRoot("reboot", 10_000);
            }, "pcx-reboot").start();
            StringBuilder rb = new StringBuilder();
            rb.append(FORM_HEAD);
            rb.append("<p>Rebooting the box…</p>");
            rb.append("<p><a href=/>← back</a></p>");
            rb.append(FORM_TAIL);
            respond(out, "200 OK", "text/html; charset=utf-8", rb.toString().getBytes(StandardCharsets.UTF_8));
            return;
        }
        JSONObject fields = new JSONObject();
        try {
            for (String k : FIELD_KEYS) if (form.containsKey(k)) fields.put(k, form.get(k));
        } catch (JSONException ignored) {
        }
        String msg;
        boolean restarted = false;
        try {
            // Fixed model/asr/telegram/brave fields go through applyConfig; the dynamic MCP-server rows go
            // through applyServers. Both write config.json; their changed lists are combined. Then the files that
            // are not the gateway's: tv_mcp.json (applyMedia) and the app's own switch.
            List<String> changed = new ArrayList<>();
            JSONArray fixedChanged = applyConfig(fields);
            for (int i = 0; i < fixedChanged.length(); i++) changed.add(fixedChanged.optString(i));
            changed.addAll(applyServers(form));
            boolean gatewayChanged = !changed.isEmpty();
            changed.addAll(applyMedia(form));
            if (form.containsKey("voice_remote")) {
                boolean on = "1".equals(form.get("voice_remote"));
                if (on != AppSettings.getVoiceRemote(ctx)) {
                    AppSettings.setVoiceRemote(ctx, on);
                    changed.add("voice_remote");
                    // AgentService applies the switch: the voice activities, the helpers, the preferred entries.
                    runRoot("am startservice -a " + AgentService.ACTION_VOICE
                            + " -n xyz.vvzvlad.popcornclaw/xyz.vvzvlad.popcornclaw.AgentService", 10_000);
                }
            }
            msg = changed.isEmpty() ? "No changes." : "Changed: " + join(new JSONArray(changed));
            // A plain save that changed the gateway's config while no gateway answers (a fresh box whose model was
            // just filled in) asks for a start too; AgentService checks the config on that action first.
            if ("save_restart".equals(action) || (gatewayChanged && !gatewayHealthy())) {
                runRoot("am startservice -a " + AgentService.ACTION_RESTART
                        + " -n xyz.vvzvlad.popcornclaw/xyz.vvzvlad.popcornclaw.AgentService", 10_000);
                restarted = true;
            }
        } catch (ToolError e) {
            msg = "Error: " + e.getMessage();
        }
        StringBuilder b = new StringBuilder();
        b.append(FORM_HEAD);
        b.append("<p>").append(escapeHtml(msg)).append("</p>");
        if (restarted) b.append("<p>Gateway restart started.</p>");
        b.append("<p><a href=/>← back</a></p>");
        b.append(FORM_TAIL);
        respond(out, "200 OK", "text/html; charset=utf-8", b.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** tv_mcp.json's values as "media." + key; "" for an absent or null key. Throws on a failed read. */
    private JSONObject readMedia() throws ToolError {
        synchronized (configLock) {
            JSONObject o = parseMedia();
            JSONObject out = new JSONObject();
            try {
                for (String k : MEDIA_URLS) out.put("media." + k, jsonStr(o, k));
                for (String k : MEDIA_KEYS) out.put("media." + k, jsonStr(o, k));
            } catch (JSONException e) {
                throw new ToolError("internal: " + e.getMessage());
            }
            return out;
        }
    }

    /**
     * Applies the Media tab to tv_mcp.json: every other key in it is kept, a blank key field keeps the stored key,
     * an empty URL is written as "" (TvMcpServer would read a JSON null as the string "null"). The file is written
     * (mode 600) only when a value changed, and then TvMcpServer drops its cached settings. Returns the changed
     * field names; nothing is read when the submit has no Media fields.
     */
    private List<String> applyMedia(Map<String, String> form) throws ToolError {
        List<String> changed = new ArrayList<>();
        boolean touched = false;
        for (String k : MEDIA_URLS) touched |= form.containsKey("media." + k);
        for (String k : MEDIA_KEYS) touched |= form.containsKey("media." + k);
        if (!touched) return changed;
        synchronized (configLock) {
            JSONObject o = parseMedia();
            try {
                for (String k : MEDIA_URLS) {
                    String name = "media." + k;
                    if (!form.containsKey(name)) continue;
                    String nv = form.get(name).trim();
                    if (!nv.equals(jsonStr(o, k)) || (o.has(k) && o.isNull(k))) {
                        o.put(k, nv);
                        changed.add(name);
                    }
                }
                for (String k : MEDIA_KEYS) {
                    String name = "media." + k;
                    String nv = form.containsKey(name) ? form.get(name).trim() : "";
                    if (!nv.isEmpty() && !nv.equals(jsonStr(o, k))) {
                        o.put(k, nv);
                        changed.add(name);
                    }
                }
                if (changed.isEmpty()) return changed;
                writeFileRoot(TV_MCP_PATH, o.toString(2) + "\n");
            } catch (JSONException e) {
                throw new ToolError("internal: " + e.getMessage());
            }
        }
        TvMcpServer.reloadSettings();
        return changed;
    }

    private JSONObject parseMedia() throws ToolError {
        String text = readFileRoot(TV_MCP_PATH);
        try {
            return new JSONObject(text);
        } catch (JSONException e) {
            throw new ToolError("cannot parse tv_mcp.json on the box: " + e.getMessage());
        }
    }

    /** A string value; "" for an absent key and for JSON null (optString would give "null"). */
    private static String jsonStr(JSONObject o, String key) {
        return o.isNull(key) ? "" : o.optString(key, "");
    }

    /** The login page: one PIN input posting to /login. */
    private static void serveLogin(OutputStream out, String status, boolean wrong) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append(FORM_HEAD);
        b.append("<form class=pane method=post action=/login>");
        if (wrong) b.append("<p class=err>Wrong PIN.</p>");
        b.append("<label><span class=lbl>Enter the PIN shown on the TV</span>"
                + "<input class=mono type=text name=pin inputmode=numeric autocomplete=off maxlength=6 autofocus>"
                + "</label>");
        b.append("<div class=buttons><button class=primary>Open</button></div>");
        b.append("</form>");
        b.append(FORM_TAIL);
        respond(out, status, "text/html; charset=utf-8", b.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** A right PIN: stored in the browser's cookie, then on to the settings form. */
    private void redirectHome(OutputStream out, String status) throws IOException {
        respond(out, status, "text/plain", new byte[0], "Location: /\r\n" + Pin.setCookieHeader(ctx));
    }

    private static Map<String, String> parseForm(byte[] body) {
        Map<String, String> m = new LinkedHashMap<>();
        String s = new String(body, StandardCharsets.UTF_8);
        for (String pair : s.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String v = eq >= 0 ? pair.substring(eq + 1) : "";
            try {
                k = URLDecoder.decode(k, "UTF-8");
                v = URLDecoder.decode(v, "UTF-8");
            } catch (IOException | IllegalArgumentException ignored) {
                // A malformed %-escape (e.g. a lone "%") would otherwise throw IllegalArgumentException out of the
                // handler and drop the connection with no response; keep the raw value instead.
            }
            m.put(k, v);
        }
        return m;
    }

    /**
     * One fixed field. A secret (type "password") renders as an empty input whose placeholder only tells whether
     * a value is stored -- the value itself is computed server-side but never emitted. A non-secret keeps its
     * real value. serveForm closes each section's card with the matching {@code </section>}.
     */
    private static String field(JSONObject f, String key, String label, String type, boolean mono) {
        String cls = mono ? " class=mono" : "";
        String head = "<label><span class=lbl>" + escapeHtml(label) + "</span>";
        if ("password".equals(type)) {
            // A stored secret shows a dot mask (like a filled password field), never its value; empty submit = keep.
            // The "show" button reveals what is being typed (toggles the input type), see rev() in serveForm.
            boolean present = !f.optString(key, "").isEmpty();
            String ph = present ? "••••••••••" : "";
            return head + "<div class=secret><input" + cls + " type=password name=\"" + key + "\" placeholder=\""
                    + escapeHtml(ph) + "\"><button type=button class=reveal onclick=rev(this)>show</button></div></label>";
        }
        String v = f.optString(key, "");
        return head + "<input" + cls + " type=text name=\"" + key + "\" value=\""
                + escapeHtml(v) + "\"></label>";
    }

    private static String pane(String id, boolean active) {
        return "<div class=\"pane" + (active ? "" : " hidden") + "\" id=pane-" + id + ">";
    }

    /**
     * One editable MCP-server row. {@code idx} is the running integer index as a string (or the "__I__" token for
     * the Add-server template). The Authorization field is a secret: rendered empty, its placeholder only tells
     * whether a value is stored (authSet), never the value itself.
     */
    private static String serverRowHtml(String idx, String name, boolean enabled, String url, boolean authSet) {
        String p = "srv." + idx + ".";
        // A stored Authorization shows a dot mask, never its value; empty submit = keep.
        String ph = authSet ? "••••••••••" : "";
        return "<div class=srv>"
                + "<input type=hidden name=\"" + p + "orig\" value=\"" + escapeHtml(name) + "\">"
                // Disabled so it does not submit while the row is kept; the Remove button enables it to delete on save.
                + "<input class=rmflag type=hidden name=\"" + p + "remove\" value=1 disabled>"
                + "<label><span class=lbl>Name</span>"
                + "<input class=mono type=text name=\"" + p + "name\" value=\"" + escapeHtml(name) + "\"></label>"
                + "<label><span class=lbl>URL</span>"
                + "<input class=mono type=text name=\"" + p + "url\" value=\"" + escapeHtml(url) + "\"></label>"
                + "<label><span class=lbl>Authorization</span>"
                + "<div class=secret><input class=mono type=password name=\"" + p + "auth\" placeholder=\"" + escapeHtml(ph) + "\">"
                + "<button type=button class=reveal onclick=rev(this)>show</button></div></label>"
                + "<div class=srvrow>"
                + "<label class=ck><input type=checkbox name=\"" + p + "enabled\" value=1"
                + (enabled ? " checked" : "") + "> enabled</label>"
                + "<button type=button class=rm onclick=rmServer(this)>Remove</button>"
                + "</div></div>";
    }

    /**
     * The hidden template the Add-server button clones, plus the inline JS that does the cloning: it replaces the
     * "__I__" token with a fresh incrementing index (starting past the last rendered row) and appends the row.
     */
    private static String serverTemplate(int start) {
        return "<template id=srvtpl>" + serverRowHtml("__I__", "", true, "", false) + "</template>"
                + "<script>var si=" + start + ";function addServer(){"
                + "var h=document.getElementById('srvtpl').innerHTML.replace(/__I__/g,si);si++;"
                + "var d=document.createElement('div');d.innerHTML=h;"
                + "document.getElementById('srvlist').appendChild(d.firstElementChild);}"
                // Remove: an unsaved (just-added) row is dropped from the DOM; an existing one is marked for
                // deletion (its hidden rmflag enabled so it submits), dimmed, and the button toggles to Undo.
                + "function rmServer(btn){var c=btn.closest('.srv');"
                + "var orig=c.querySelector('input[name$=\".orig\"]');"
                + "if(!orig.value){c.parentNode.removeChild(c);return;}"
                + "var h=c.querySelector('input.rmflag');var removing=h.disabled;"
                + "h.disabled=!removing;c.classList.toggle('removing',removing);"
                + "btn.textContent=removing?'Undo':'Remove';}</script>";
    }

    /** The distinct server-row indices present in the posted form, in submission order. */
    private static List<Integer> serverRowIndices(Map<String, String> form) {
        List<Integer> order = new ArrayList<>();
        for (String k : form.keySet()) {
            if (!k.startsWith("srv.")) continue;
            int dot = k.indexOf('.', 4);
            if (dot < 0) continue;
            Integer i;
            try {
                i = Integer.valueOf(k.substring(4, dot));
            } catch (NumberFormatException e) {
                continue;
            }
            if (!order.contains(i)) order.add(i);
        }
        return order;
    }

    /** The current tools.mcp.servers object from config.json; empty when absent. Throws on a failed read. */
    private JSONObject readServers() throws ToolError {
        synchronized (configLock) {
            JSONObject cfg = parseConfig();
            JSONObject tools = cfg.optJSONObject("tools");
            JSONObject mcp = tools == null ? null : tools.optJSONObject("mcp");
            JSONObject servers = mcp == null ? null : mcp.optJSONObject("servers");
            return servers == null ? new JSONObject() : servers;
        }
    }

    /**
     * Applies the dynamic MCP-server rows to config.json under configLock. Reads the whole config, folds in the
     * posted rows (add / edit / rename / remove), and writes config.json only when something actually changed;
     * returns the changed server names. Each server's "type" and any unknown fields are preserved; a blank
     * Authorization submit keeps the stored header untouched (never blanks it). parseConfig/readFileRoot throw on
     * a failed read, so servers are never rewritten from a blank or failed read.
     */
    private List<String> applyServers(Map<String, String> form) throws ToolError {
        List<String> changed = new ArrayList<>();
        synchronized (configLock) {
            try {
                JSONObject cfg = parseConfig();
                JSONObject tools = cfg.optJSONObject("tools");
                if (tools == null) { tools = new JSONObject(); cfg.put("tools", tools); }
                JSONObject mcp = tools.optJSONObject("mcp");
                if (mcp == null) { mcp = new JSONObject(); tools.put("mcp", mcp); }
                JSONObject servers = mcp.optJSONObject("servers");
                if (servers == null) { servers = new JSONObject(); mcp.put("servers", servers); }

                for (int i : serverRowIndices(form)) {
                    String p = "srv." + i + ".";
                    String name = form.containsKey(p + "name") ? form.get(p + "name").trim() : "";
                    String orig = form.containsKey(p + "orig") ? form.get(p + "orig") : "";

                    if (form.containsKey(p + "remove")) {
                        if (!orig.isEmpty() && servers.has(orig)) {
                            servers.remove(orig);
                            if (!changed.contains(orig)) changed.add(orig);
                        }
                        continue;
                    }
                    if (name.isEmpty()) continue;

                    // The entry to edit: the row's original server, else an existing one of that name, else new.
                    String originalKey;
                    JSONObject entry;
                    if (!orig.isEmpty() && servers.optJSONObject(orig) != null) {
                        originalKey = orig;
                        entry = servers.optJSONObject(orig);
                    } else if (servers.optJSONObject(name) != null) {
                        originalKey = name;
                        entry = servers.optJSONObject(name);
                    } else {
                        originalKey = null;
                        entry = new JSONObject();
                    }
                    String before = originalKey == null ? null : entry.toString();

                    // Rename: drop the old key; the mutated entry is re-added under the new name below.
                    if (!orig.isEmpty() && !orig.equals(name)) servers.remove(orig);

                    entry.put("url", form.containsKey(p + "url") ? form.get(p + "url") : "");
                    entry.put("enabled", form.containsKey(p + "enabled"));
                    if (!entry.has("type")) entry.put("type", "http");

                    String auth = form.containsKey(p + "auth") ? form.get(p + "auth") : "";
                    if (!auth.isEmpty()) {
                        JSONObject h = entry.optJSONObject("headers");
                        if (h == null) { h = new JSONObject(); entry.put("headers", h); }
                        h.put("Authorization", auth);
                    }
                    // A blank auth leaves any existing headers untouched -- never blank a stored secret.

                    servers.put(name, entry);

                    boolean isChange = originalKey == null || !originalKey.equals(name)
                            || !entry.toString().equals(before);
                    if (isChange && !changed.contains(name)) changed.add(name);
                }

                if (!changed.isEmpty()) writeFileRoot(CONFIG_PATH, cfg.toString(2));
                return changed;
            } catch (JSONException e) {
                throw new ToolError("internal: " + e.getMessage());
            }
        }
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // --- root shell ----------------------------------------------------------------------------------------

    private RootResult runRoot(String command, long timeoutMs) {
        return runRootImpl(command, null, timeoutMs);
    }

    /** Writes secret-bearing content through stdin (never into the command string); keeps the file mode 600. */
    private void writeFileRoot(String path, String content) throws ToolError {
        RootResult r = runRootImpl("cat > " + path + " && chmod 600 " + path,
                content.getBytes(StandardCharsets.UTF_8), 8_000);
        if (r.timedOut || r.exit == null || r.exit != 0) {
            throw new ToolError("writing " + path + " failed: " + (r.output == null ? "" : r.output.trim()));
        }
    }

    private String readFileRoot(String path) throws ToolError {
        RootResult r = runRoot("cat " + path, 8_000);
        // Tell a genuine read failure (timeout, or cat returning non-zero: missing file, no root grant) apart from
        // an empty file. A silent "" on failure would let applyConfig rewrite .security.yml from blank values and
        // wipe the secrets; the project's make targets refuse to write on a failed read for the same reason.
        if (r.timedOut || r.exit == null || r.exit != 0) {
            throw new ToolError("reading " + path + " failed: "
                    + (r.timedOut ? "timed out" : (r.output == null ? "" : r.output.trim())));
        }
        return r.output == null ? "" : r.output;
    }

    /**
     * Runs one command as root through {@code su --mount-master -c <ENV_PREFIX + command>}. Combined stdout
     * is read on this thread; a watchdog destroys the process past the monotonic deadline (nanoTime, so an NTP
     * step cannot fire it early), which closes the pipe and unblocks the read. {@code stdin}, when given, is a
     * small payload written before the output is read -- it fits the pipe buffer, so this cannot deadlock.
     */
    private RootResult runRootImpl(String command, byte[] stdin, long timeoutMs) {
        String wrapped = ENV_PREFIX + command;
        Process proc = null;
        try {
            proc = new ProcessBuilder(Su.argv("--mount-master", "-c", wrapped)).redirectErrorStream(true).start();
            OutputStream os = proc.getOutputStream();
            if (stdin != null) os.write(stdin);
            os.close();
            final Process p = proc;
            final long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
            final AtomicBoolean killed = new AtomicBoolean(false);
            Thread wd = new Thread(() -> {
                while (Procs.alive(p)) {
                    if (System.nanoTime() - deadline > 0) {
                        killed.set(true);
                        p.destroy();
                        return;
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }, "pcx-ctl-wd");
            wd.setDaemon(true);
            wd.start();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            InputStream is = proc.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            int code = proc.waitFor();
            wd.interrupt();
            String output = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            return new RootResult(output, killed.get() ? null : Integer.valueOf(code), killed.get());
        } catch (IOException e) {
            if (proc != null) proc.destroy();
            return new RootResult("error: " + e.getMessage(), null, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (proc != null) proc.destroy();
            return new RootResult("interrupted", null, false);
        }
    }

    private static boolean gatewayHealthy() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(HEALTH_URL).openConnection();
            c.setConnectTimeout(2000);
            c.setReadTimeout(2000);
            return c.getResponseCode() == 200;
        } catch (IOException e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static final class RootResult {
        final String output;
        final Integer exit;      // null when the watchdog killed the process
        final boolean timedOut;

        RootResult(String output, Integer exit, boolean timedOut) {
            this.output = output;
            this.exit = exit;
            this.timedOut = timedOut;
        }
    }

    /** A tool-level failure: surfaced as an MCP result with isError=true, never a JSON-RPC error. */
    private static final class ToolError extends Exception {
        ToolError(String m) {
            super(m);
        }
    }

    // --- small helpers -------------------------------------------------------------------------------------

    private static JSONObject tool(String name, String description, JSONObject inputSchema) throws JSONException {
        return new JSONObject().put("name", name).put("description", description).put("inputSchema", inputSchema);
    }

    private static JSONObject prop(String type, String description) throws JSONException {
        return new JSONObject().put("type", type).put("description", description);
    }

    private static JSONObject schema(JSONObject properties, String... required) throws JSONException {
        JSONArray req = new JSONArray();
        for (String r : required) req.put(r);
        return new JSONObject().put("type", "object").put("properties", properties).put("required", req);
    }

    private static String join(JSONArray a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(a.optString(i));
        }
        return sb.toString();
    }

    private static String joinArray(String[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(a[i]);
        }
        return sb.toString();
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
        respond(out, status, contentType, body, "");
    }

    /** {@code extraHeaders}: whole header lines, each ending in CRLF (a Location, a Set-Cookie); "" for none. */
    private static void respond(OutputStream out, String status, String contentType, byte[] body,
            String extraHeaders) throws IOException {
        String header = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-cache, no-store\r\n"
                + extraHeaders
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static final String FORM_HEAD = ""
            + "<!doctype html><html lang=en><head><meta charset=utf-8>"
            + "<meta name=viewport content='width=device-width,initial-scale=1'>"
            + "<title>PopcornClaw control</title><style>"
            + ":root{color-scheme:dark}*{box-sizing:border-box}"
            + "html,body{margin:0;background:#0f1115;color:#d7dae0;"
            + "font:15px/1.45 system-ui,-apple-system,'Segoe UI',Roboto,sans-serif}"
            + ".wrap{max-width:680px;margin:0 auto;padding:20px 16px 48px}"
            + "header{display:flex;align-items:baseline;justify-content:space-between;gap:12px;"
            + "flex-wrap:wrap;margin-bottom:6px}"
            + "h1{font-size:19px;font-weight:600;margin:0}"
            + "header a{color:#7fb2ff;text-decoration:none;font-size:14px;white-space:nowrap}"
            + "header a:hover{text-decoration:underline}"
            + ".card{background:#171a21;border:1px solid #262b36;border-radius:12px;padding:14px 16px;margin:14px 0}"
            + ".card>h2{font-size:12px;letter-spacing:.08em;text-transform:uppercase;color:#8a93a6;"
            + "font-weight:600;margin:0 0 10px}"
            + "label{display:block;margin:12px 0 0}"
            + ".card>label:first-of-type{margin-top:0}"
            + ".lbl{display:block;color:#c3c9d4;font-size:13px;margin-bottom:4px}"
            + ".key{display:block;color:#5f6b80;font-size:11px;margin-bottom:5px;"
            + "font-family:ui-monospace,SFMono-Regular,Menlo,monospace}"
            + "input[type=text],input[type=password]{width:100%;background:#0c0e12;color:#e6e9ef;"
            + "border:1px solid #2d3340;border-radius:8px;padding:9px 11px;font:14px system-ui,sans-serif}"
            + "input.mono{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:13px}"
            + "input:focus{outline:none;border-color:#4b74b8;box-shadow:0 0 0 2px rgba(75,116,184,.25)}"
            + "input::placeholder{color:#5f6b80}"
            + ".srv{background:#12151b;border:1px solid #262b36;border-radius:10px;padding:12px;margin:10px 0}"
            + ".srvrow{display:flex;gap:18px;margin-top:12px;align-items:center}"
            + ".ck{display:inline-flex;align-items:center;gap:6px;margin:0;color:#c3c9d4;font-size:13px;cursor:pointer}"
            + ".ck input{width:auto;margin:0}"
            + ".opt{margin-top:16px}"
            + ".hint{color:#8a93a6;font-size:12px;margin:6px 0 0}"
            + ".warn{color:#ffd58a;background:#2a2416;border:1px solid #514426;border-radius:8px;"
            + "padding:10px 12px;margin:0 0 12px}"
            + "button{font:14px system-ui,sans-serif;border-radius:8px;padding:10px 16px;cursor:pointer;"
            + "border:1px solid transparent}"
            + "button.add{background:#1b2230;color:#9fc0ff;border-color:#2d3a52;margin-top:4px}"
            + "button.add:hover{background:#222b3c}"
            + "button.rm{background:#2a1618;color:#ff9a9a;border-color:#512a2c;margin-left:auto;padding:7px 13px;font-size:13px}"
            + "button.rm:hover{background:#3a1e20}"
            + ".srv.removing{opacity:.5}"
            + ".buttons{margin-top:22px;display:flex;gap:10px;flex-wrap:wrap}"
            + "button.primary{background:#3b6fd4;color:#fff}button.primary:hover{background:#4a7ee0}"
            + "button.secondary{background:#20252f;color:#d7dae0;border-color:#323846}"
            + "button.secondary:hover{background:#28303c}"
            + "a{color:#7fb2ff}"
            + ".err{color:#ff8a8a;background:#2a1618;border:1px solid #512a2c;border-radius:8px;padding:10px 12px}"
            + ".tabs{display:flex;gap:6px;flex-wrap:wrap;margin:14px 0 12px}"
            + "button.tab{background:#171a21;color:#9aa3b2;border-color:#262b36;padding:8px 14px;font-size:13px}"
            + "button.tab:hover{background:#1c212b}"
            + "button.tab.active{background:#243049;color:#cfe0ff;border-color:#36507d}"
            + ".pane{background:#171a21;border:1px solid #262b36;border-radius:12px;padding:14px 16px;margin:0 0 14px}"
            + ".pane.hidden{display:none}.pane>label:first-of-type{margin-top:0}"
            + ".secret{display:flex;gap:8px;align-items:stretch}.secret input{flex:1;min-width:0}"
            + "button.reveal{background:#20252f;color:#9aa3b2;border-color:#323846;padding:0 12px;font-size:12px}"
            + "button.reveal:hover{background:#28303c}"
            + ".actions{position:sticky;bottom:0;background:#0f1115;border-top:1px solid #262b36;"
            + "display:flex;gap:10px;flex-wrap:wrap;padding:12px 0;margin-top:4px}"
            + "button.danger{background:#3a1a1c;color:#ff9a9a;border-color:#5a2a2d}"
            + "button.danger:hover{background:#4a2124}"
            + "#pane-screen{position:relative;left:50%;transform:translateX(-50%);width:min(96vw,1120px);padding:8px}"
            + ".scrframe{width:100%;height:calc(min(96vw,1120px)*0.5625 + 92px);border:0;border-radius:8px;"
            + "background:#000;display:block}"
            + "@media(max-width:480px){.srvrow{flex-direction:column;gap:10px;align-items:stretch}.actions button{flex:1}}"
            + "</style></head><body><div class=wrap>"
            + "<header><h1>PopcornClaw control</h1></header>";
    private static final String FORM_TAIL = "</div></body></html>";
}
