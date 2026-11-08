package xyz.vvzvlad.popcornclaw;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One connection to mpv-android's JSON IPC over an Android {@link LocalSocket}, the Java port of the Python
 * tv_mcp {@code Ipc} class (src/tv.py). mpv-android's mpv.conf names {@code input-ipc-server=@mpv}, an abstract
 * (unnamed) unix socket; the Python side reached it through adb forward + a TCP connection, this one connects
 * to the abstract socket directly, since the app runs on the box itself.
 *
 * <p>A background reader thread reads newline-delimited JSON lines: each message with an {@code "event"} key is
 * queued in {@link #events} (drained by the follower in a later chunk), every other message is a reply routed
 * to the waiter blocked on its {@code request_id}. {@link #command} writes one command and blocks for the
 * matching reply up to {@link #IPC_TIMEOUT_MS}; {@link #get} and {@link #set} wrap get_property/set_property.
 * Any mpv error, a timeout or a closed connection surfaces as the checked {@link MpvError} (closed/timeout as
 * its {@link IpcClosed} subclass).
 */
public final class MpvIpc {
    private static final String TAG = "PopcornClaw-mpv";

    /** mpv-android's abstract IPC socket name (input-ipc-server=@mpv); the leading @ is the abstract namespace. */
    private static final String SOCKET_NAME = "mpv";
    /** Seconds one IPC reply may take (Python IPC_TIMEOUT). */
    private static final long IPC_TIMEOUT_MS = 10_000;
    /** The longest IPC line read: a season's playlist is one line (Python IPC_LINE_LIMIT = 2**22). Over it, the
     *  connection is treated as closed. */
    private static final int IPC_LINE_LIMIT = 1 << 22;

    /** Queued onto {@link #events} once the connection has closed, so a consumer blocked on the queue wakes;
     *  {@link #drain} skips it. */
    private static final JSONObject END = new JSONObject();

    private final LocalSocket socket;
    private final OutputStream out;
    private final Thread reader;
    private final Object writeLock = new Object();
    private final AtomicInteger ids = new AtomicInteger(0);
    private final Map<Integer, Waiter> waiters = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<JSONObject> events = new LinkedBlockingQueue<>();
    private volatile boolean closed;

    private MpvIpc(LocalSocket socket) throws IOException {
        this.socket = socket;
        this.out = socket.getOutputStream();
        final InputStream in = new BufferedInputStream(socket.getInputStream());
        this.reader = new Thread(() -> readLoop(in), "pcx-mpv-ipc");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    /**
     * Connects to mpv's abstract IPC socket and verifies mpv answers, or returns null when mpv is not running
     * (the Python {@code _open} returning None): a connect failure is not fatal, and if the mpv-version probe
     * fails or times out the connection is closed and null returned.
     */
    public static MpvIpc open() {
        LocalSocket sock = new LocalSocket();
        try {
            sock.connect(new LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT));
        } catch (IOException e) {
            // mpv is not running: nothing listens on @mpv.
            closeQuietly(sock);
            return null;
        }
        MpvIpc ipc;
        try {
            ipc = new MpvIpc(sock);
        } catch (IOException e) {
            closeQuietly(sock);
            return null;
        }
        try {
            ipc.command("get_property", "mpv-version");
        } catch (MpvError e) {
            // Connected, but mpv did not answer (a stale socket, or it closed at once): not running for us.
            ipc.close();
            return null;
        }
        return ipc;
    }

    /**
     * The data of mpv's reply to {@code args} (JSON null normalised to Java null); {@link MpvError} when mpv
     * answers with an error, {@link IpcClosed} on timeout or a closed connection.
     */
    public Object command(Object... args) throws MpvError {
        if (closed) throw new IpcClosed("mpv closed the connection");
        int id = ids.incrementAndGet();
        byte[] bytes = encode(args, id);
        Waiter waiter = new Waiter();
        waiters.put(id, waiter);
        try {
            synchronized (writeLock) {
                out.write(bytes);
                out.flush();
            }
            boolean got = waiter.latch.await(IPC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!got) throw new IpcClosed("mpv did not answer within " + (IPC_TIMEOUT_MS / 1000) + " s");
            // A delivered reply wins over a concurrent close: when mpv answers and then shuts the socket, the
            // reader may set waiter.closed on the same waiter before this thread wakes, but the reply is still
            // valid and must be returned (mirrors the Python reader's "if not reply.done()" guard).
            if (waiter.reply == null) throw new IpcClosed("mpv closed the connection");
            JSONObject reply = waiter.reply;
            String error = reply.optString("error", "");
            if (!"success".equals(error)) throw new MpvError(cmdName(args), error);
            Object data = reply.opt("data");
            return data == JSONObject.NULL ? null : data;
        } catch (IOException e) {
            throw new IpcClosed("mpv: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IpcClosed("mpv: interrupted");
        } finally {
            waiters.remove(id);
        }
    }

    /** A property's value, or null when mpv has none now (mirrors the Python {@code Ipc.get}: an item still
     *  opening has no time-pos, mpv answers error "property unavailable"). */
    public Object get(String name) throws MpvError {
        try {
            return command("get_property", name);
        } catch (IpcClosed e) {
            throw e;
        } catch (MpvError e) {
            if ("property unavailable".equals(e.error)) return null;
            throw e;
        }
    }

    public void set(String name, Object value) throws MpvError {
        command("set_property", name, value);
    }

    /** The events queued so far (never the end-of-events sentinel). */
    public List<JSONObject> drain() {
        List<JSONObject> out = new ArrayList<>();
        JSONObject event;
        while ((event = events.poll()) != null) {
            if (event != END) out.add(event);
        }
        return out;
    }

    /**
     * The next event mpv sent, blocking until one arrives; null once the connection has closed (the END
     * sentinel is dequeued), mirroring the Python follower's {@code await ipc.events.get()} returning None
     * on close. The follower blocks here between items; {@link InterruptedException} propagates so a cancelled
     * (interrupted) follower thread unwinds and exits. Like the Python queue, END is consumed once: a caller
     * that gets null must stop reading this connection (the follower closes it and reconnects).
     */
    public JSONObject takeEvent() throws InterruptedException {
        JSONObject event = events.take();
        return event == END ? null : event;
    }

    /**
     * Whether mpv closed the connection within {@code timeoutMs} (the Python {@code Ipc.wait_closed}): the reader
     * thread ends when mpv shuts the socket, so this waits for it to die. Used by {@code Tv._stop} after sending
     * {@code stop}, which makes mpv-android close its activity and the IPC socket with it.
     */
    public boolean waitClosed(long timeoutMs) {
        try {
            reader.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !reader.isAlive();
    }

    /** Stops the reader thread and closes the socket; waiters blocked on a reply wake with a closed error. */
    public void close() {
        closed = true;
        closeQuietly(socket);
        reader.interrupt();
    }

    // --- reader --------------------------------------------------------------------------------------------

    private void readLoop(InputStream in) {
        try {
            byte[] line;
            while ((line = readLine(in)) != null) {
                JSONObject message;
                try {
                    message = new JSONObject(new String(line, StandardCharsets.UTF_8));
                } catch (JSONException e) {
                    continue; // skip a line that is not JSON, as the Python reader does
                }
                if (message.has("event")) {
                    events.offer(message);
                } else {
                    int requestId = message.optInt("request_id", -1);
                    Waiter waiter = requestId >= 0 ? waiters.get(requestId) : null;
                    if (waiter != null) {
                        waiter.reply = message;
                        waiter.latch.countDown();
                    }
                }
            }
        } catch (IOException e) {
            // Stream closed, or a line over IPC_LINE_LIMIT: fall through and wake the waiters.
            Log.d(TAG, "ipc reader ended: " + e.getMessage());
        } finally {
            closed = true;
            for (Waiter waiter : waiters.values()) {
                waiter.closed = true;
                waiter.latch.countDown();
            }
            events.offer(END);
        }
    }

    /** One newline-delimited line without the newline, or null at end of stream; an {@link IOException} when a
     *  single line exceeds {@link #IPC_LINE_LIMIT}, which closes the connection. */
    private static byte[] readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return buf.toByteArray();
            buf.write(c);
            if (buf.size() > IPC_LINE_LIMIT) throw new IOException("IPC line over " + IPC_LINE_LIMIT + " bytes");
        }
        return buf.size() == 0 ? null : buf.toByteArray();
    }

    // --- helpers -------------------------------------------------------------------------------------------

    private static byte[] encode(Object[] args, int id) {
        try {
            JSONArray command = new JSONArray();
            for (Object arg : args) command.put(arg);
            JSONObject message = new JSONObject().put("command", command).put("request_id", id);
            return (message + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            // Building our own request cannot fail; a failure is a programming error.
            throw new IllegalStateException(e);
        }
    }

    /** The command name for an error message: the first two args joined, as the Python {@code MpvError} builds it. */
    private static String cmdName(Object[] args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length && i < 2; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.valueOf(args[i]));
        }
        return sb.toString();
    }

    private static void closeQuietly(LocalSocket sock) {
        try {
            sock.close();
        } catch (IOException ignored) {
        }
    }

    /** One pending command's slot: the reader fills {@link #reply} and counts the latch down. */
    private static final class Waiter {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile JSONObject reply;
        volatile boolean closed;
    }

    /** mpv answered a command with an error (Python {@code MpvError}). */
    public static class MpvError extends Exception {
        /** The raw error string mpv returned, or null for a closed/timed-out connection. */
        final String error;

        MpvError(String command, String error) {
            super("mpv " + command + ": " + error);
            this.error = error;
        }

        MpvError(String message) {
            super(message);
            this.error = null;
        }
    }

    /** mpv's IPC connection closed, or a reply did not come (Python {@code IpcClosed}). */
    public static final class IpcClosed extends MpvError {
        IpcClosed(String message) {
            super(message);
        }
    }
}
