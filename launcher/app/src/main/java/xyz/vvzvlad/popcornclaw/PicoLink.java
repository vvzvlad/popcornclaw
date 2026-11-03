package xyz.vvzvlad.popcornclaw;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BiConsumer;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * The assistant screen's line to the agent: one pico WebSocket to the local gateway for the whole app
 * process (session_id=tv). The TV and the owner's Telegram DM are one dialog (picoclaw/shared-session.patch):
 * the gateway sends here everything the DM gets, the owner's Telegram messages and the end of each turn,
 * so the screen also shows turns begun in Telegram; {@link PicoTurn} turns those frames into the screen's
 * events. AgentService opens the connection when it starts, and a dropped one is opened again
 * {@link #RECONNECT_MS} later, so the frames arrive with no screen open; a frame the gateway sends while the
 * connection is down is lost.
 */
public final class PicoLink {
    /** The gateway's pico token; AgentService exports it. The gateway listens on localhost only. */
    public static final String TOKEN = "popcornclaw-tv";
    /** The app carries the agent: the assistant screen asks it instead of a paired PC. */
    public static final boolean ENABLED = true;
    /** The owner speaks to the agent in Russian. */
    public static final String SPEECH_LANGUAGE = "ru-RU";

    private static final String URL = "ws://127.0.0.1:18790/pico/ws?session_id=tv";
    // Below the screen's 100 s answer timeout; sent only between a turn's start and its end.
    private static final long KEEPALIVE_MS = 30_000;
    // The gateway is on this box: retrying often costs nothing, and a restart takes a few seconds.
    private static final long RECONNECT_MS = 5_000;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final OkHttpClient client = new OkHttpClient();
    private static final Random ids = new Random();
    // Main thread only, all of them.
    private static final Map<Object, BiConsumer<String, JSONObject>> listeners = new LinkedHashMap<>();
    private static final PicoTurn turn = new PicoTurn(() -> Long.toHexString(ids.nextLong()));
    private static WebSocket socket;

    private static final Runnable keepalive = new Runnable() {
        @Override
        public void run() {
            PicoTurn.Event e = turn.keepalive();
            if (e == null) return;
            emit(e);
            main.postDelayed(this, KEEPALIVE_MS);
        }
    };

    private static final Runnable reconnect = PicoLink::connect;

    private PicoLink() {
    }

    /**
     * Subscribes {@code owner} to the events until {@link #unlisten} and connects; a screen that opens in
     * the middle of a turn joins it. Main thread.
     */
    public static void listen(Object owner, BiConsumer<String, JSONObject> events) {
        listeners.put(owner, events);
        connect();
        for (PicoTurn.Event e : turn.join()) events.accept(e.type, e.data);
    }

    public static void unlisten(Object owner) {
        listeners.remove(owner);
    }

    /**
     * Sends the question {@code d} ({"id", "text"}) to the agent; the gateway steers it into a turn that
     * is running. Main thread.
     */
    public static void ask(JSONObject d) {
        connect();
        String id = d.optString("id");
        turn.ask(id);
        String frame;
        try {
            JSONObject payload = new JSONObject().put("content", d.optString("text"));
            frame = new JSONObject().put("type", "message.send").put("id", id).put("payload", payload).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        // OkHttp queues it until the connection is open; false means the connection is already going away.
        if (!socket.send(frame)) {
            show(turn.failed("вопрос не ушёл агенту: соединение закрывается"));
            return;
        }
        keepAlive();
    }

    /** Opens the connection unless it is open or opening. Main thread. */
    static void connect() {
        main.removeCallbacks(reconnect);
        if (socket != null) return;
        Request r = new Request.Builder().url(URL).header("Authorization", "Bearer " + TOKEN).build();
        socket = client.newWebSocket(r, new Frames());
    }

    private static final class Frames extends WebSocketListener {
        @Override
        public void onMessage(WebSocket ws, String text) {
            main.post(() -> {
                if (ws == socket) show(turn.frame(text));
            });
        }

        @Override
        public void onClosing(WebSocket ws, int code, String reason) {
            ws.close(1000, null);
            main.post(() -> lost(ws, "closed " + code + " " + reason));
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
            // A gateway that dies sends no close frame: OkHttp reports a bare EOF with no message.
            main.post(() -> lost(ws, String.valueOf(t.getMessage() != null ? t.getMessage() : t)));
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            main.post(() -> lost(ws, "closed " + code + " " + reason));
        }
    }

    private static void lost(WebSocket ws, String why) {
        if (ws != socket) return;
        socket = null;
        show(turn.lost(why));
        main.postDelayed(reconnect, RECONNECT_MS);
    }

    private static void show(List<PicoTurn.Event> events) {
        if (events.isEmpty()) return;
        for (PicoTurn.Event e : events) emit(e);
        keepAlive();
    }

    // Restarts the keepalive answers while a turn is open and stops them when none is.
    private static void keepAlive() {
        main.removeCallbacks(keepalive);
        if (turn.id() != null) main.postDelayed(keepalive, KEEPALIVE_MS);
    }

    private static void emit(PicoTurn.Event e) {
        for (BiConsumer<String, JSONObject> l : new ArrayList<>(listeners.values())) l.accept(e.type, e.data);
    }
}
