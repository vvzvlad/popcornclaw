package xyz.vvzvlad.popcornclaw;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The app's own tiny web interface, served on the LAN so the owner can see the box's screen in a PC browser
 * and drive it with the mouse and keyboard "just to set things up". Every route needs the box's {@link Pin}:
 * the control panel's cookie (cookies are per host, so the one :8081 sets reaches this port too) or a
 * {@code ?pin=} query, which also sets that cookie; the panel's Screen tab loads this page with it. Anything
 * else gets a 401 page pointing at the control panel.
 *
 * <p>Routes:
 * <ul>
 *   <li>{@code GET /} (or {@code /screen}) — a standalone page: the live screen as an MJPEG {@code <img>},
 *       mouse clicks/drags/wheel and the keyboard captured and posted back.</li>
 *   <li>{@code GET /screen.mjpeg} — {@code multipart/x-mixed-replace} of JPEG frames, a few per second.</li>
 *   <li>{@code POST /input} — a small JSON event injected on the box as root.</li>
 * </ul>
 *
 * <p>Capture is the device's own {@code screencap} run through {@code su} to a single temp PNG, re-encoded to
 * JPEG to cut bandwidth; one shared capture loop feeds every MJPEG client and stops (and removes the temp file)
 * when the last one leaves. Input is injected with {@code input tap/swipe/text/keyevent} through {@code su};
 * browser coordinates arrive as fractions of the image and are scaled to the device's real {@code wm size}.
 * The server shells out as root exactly the way {@link AgentService} does, so it lives there.
 */
public final class ScreenStreamServer {
    private static final String TAG = "PopcornClaw-web";
    /** The gateway already owns 18790; this is the owner-facing web page. Bound on all interfaces. */
    public static final int PORT = 8080;

    private static final String BOUNDARY = "pcxframe";
    // The capture itself (screencap) paces the loop; this is only a small floor so an error path cannot spin hot.
    private static final long FRAME_DELAY_MS = 15;
    private static final int JPEG_QUALITY = 60;
    // screencap's raw header on API 28: uint32 width, height, format, colorspace (little-endian), then RGBA pixels.
    private static final int SCREENCAP_HEADER = 16;
    // screencap's pixel format field (header bytes 8-11): 1 = RGBA_8888, the only layout we unpack (4 bytes/px).
    private static final int SCREENCAP_FORMAT_RGBA_8888 = 1;
    // Marks the end of a text command's output on the persistent root shell.
    private static final String MARK = "__PCXDONE__";
    // Longer pause after a failed frame so a dead/denied shell is not re-spawned dozens of times a second.
    private static final long ERROR_BACKOFF_MS = 1000;
    // A single root-shell read may not block longer than this; a watchdog then kills the shell to unblock it.
    private static final long OP_TIMEOUT_MS = 4000;

    private volatile boolean running;
    // Assigned on the accept thread, read by stop() on the caller's thread: volatile for the handoff.
    private volatile ServerSocket serverSocket;
    private Thread acceptThread;

    // Shared capture loop. captureThread and clients are touched only under clientsLock.
    private final Object clientsLock = new Object();
    private int clients;
    private Thread captureThread;

    // The latest JPEG frame and a sequence number a waiting client blocks on; touched under frameLock.
    private final Object frameLock = new Object();
    private byte[] latestFrame;
    private long frameSeq;

    // The device's real screen size, taken from the captured frame (screencap is the full screen, 1:1 with the
    // display, so the frame's pixels are the device's) and cached; `wm size` is only a fallback used if an input
    // event arrives before the first frame was captured.
    private volatile int[] deviceSize;

    // One persistent root shell instead of a `su` per frame: SuperSU grants once (a single toast, not one per
    // frame) and the per-call su spawn is gone. Capture and input are serialized through it.
    private final RootShell root = new RootShell();
    // For the PIN: the service's application context.
    private final Context ctx;
    // Reused across frames (the capture loop is single-threaded): the raw RGBA buffer and the Bitmap it fills.
    private byte[] frameBuf;
    private Bitmap frameBmp;

    public ScreenStreamServer(Context c) {
        ctx = c.getApplicationContext();
    }

    public void start() {
        if (running) return;
        running = true;
        acceptThread = new Thread(this::accept, "pcx-web-accept");
        acceptThread.start();
        Log.i(TAG, "screen stream server starting on port " + PORT);
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        root.close();
        synchronized (frameLock) {
            frameLock.notifyAll();
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
            new Thread(() -> handle(s), "pcx-web-conn").start();
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
            String host = null;
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
                } else if (name.equalsIgnoreCase("Host")) {
                    host = value;
                }
            }

            String route = path;
            String query = "";
            int q = route.indexOf('?');
            if (q >= 0) {
                query = route.substring(q + 1);
                route = route.substring(0, q);
            }

            // The PIN before anything else, the request body included: an unknown client gets nothing but the
            // pointer to the control panel. A right ?pin= also stores the cookie for the page's own requests.
            boolean byQuery = Pin.matches(ctx, queryParam(query, "pin"));
            if (!byQuery && !Pin.matches(ctx, Pin.fromCookies(cookies))) {
                serveUnauthorized(out, socket, host);
                return;
            }
            String setCookie = byQuery ? Pin.setCookieHeader(ctx) : "";

            if ("GET".equals(method) && ("/".equals(route) || "/screen".equals(route))) {
                serveHtml(out, setCookie);
            } else if ("GET".equals(method) && "/screen.mjpeg".equals(route)) {
                serveMjpeg(out, setCookie);
            } else if ("POST".equals(method) && "/input".equals(route)) {
                serveInput(out, readBody(in, contentLength), setCookie);
            } else {
                respond(out, "404 Not Found", "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            // A client that walks away mid-stream is normal; not worth a stack trace.
            Log.d(TAG, "connection ended: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    // --- screen page ---------------------------------------------------------------------------------------

    private void serveHtml(OutputStream out, String setCookie) throws IOException {
        // The page's own requests carry the PIN too, so it works even where the browser keeps no cookie.
        String page = PAGE.replace("__PIN__", Pin.get(ctx));
        respond(out, "200 OK", "text/html; charset=utf-8", page.getBytes(StandardCharsets.UTF_8), setCookie);
    }

    /** 401 with a link to the control panel on the same host, where the PIN is entered. */
    private static void serveUnauthorized(OutputStream out, Socket socket, String hostHeader) throws IOException {
        String host = hostOnly(hostHeader);
        if (host == null) host = socket.getLocalAddress().getHostAddress();
        String panel = escapeHtml("http://" + host + ":" + ControlServer.PORT + "/");
        String body = "<!doctype html><html lang=en><head><meta charset=utf-8>"
                + "<meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>PopcornClaw screen</title></head>"
                + "<body style='margin:0;padding:24px 16px;background:#111;color:#ccc;font:15px system-ui,sans-serif'>"
                + "<p>This page needs the PIN shown on the TV.</p>"
                + "<p>Open the control panel: <a style='color:#7fb2ff' href=\"" + panel + "\">" + panel + "</a></p>"
                + "</body></html>";
        respond(out, "401 Unauthorized", "text/html; charset=utf-8", body.getBytes(StandardCharsets.UTF_8), "");
    }

    /** The host part of a Host header ("10.0.0.5:8080" -> "10.0.0.5", "[fe80::1]:8080" -> "[fe80::1]"). */
    private static String hostOnly(String hostHeader) {
        if (hostHeader == null || hostHeader.isEmpty()) return null;
        if (hostHeader.startsWith("[")) {
            int end = hostHeader.indexOf(']');
            return end > 0 ? hostHeader.substring(0, end + 1) : null;
        }
        int colon = hostHeader.indexOf(':');
        String h = colon >= 0 ? hostHeader.substring(0, colon) : hostHeader;
        return h.isEmpty() ? null : h;
    }

    /** One query-string parameter, URL-decoded; null when absent or malformed. */
    private static String queryParam(String query, String name) {
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0 || !name.equals(pair.substring(0, eq))) continue;
            try {
                return URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
            } catch (UnsupportedEncodingException | IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    // --- MJPEG stream --------------------------------------------------------------------------------------

    private void serveMjpeg(OutputStream out, String setCookie) throws IOException {
        String header = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: multipart/x-mixed-replace; boundary=" + BOUNDARY + "\r\n"
                + "Cache-Control: no-cache, no-store\r\n"
                + "Pragma: no-cache\r\n"
                + setCookie
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.flush();

        joinCapture();
        try {
            long seen = -1;
            while (running) {
                byte[] frame;
                synchronized (frameLock) {
                    while (running && frameSeq == seen) frameLock.wait(1000);
                    if (!running) break;
                    frame = latestFrame;
                    seen = frameSeq;
                }
                if (frame == null) continue;
                String part = "--" + BOUNDARY + "\r\n"
                        + "Content-Type: image/jpeg\r\n"
                        + "Content-Length: " + frame.length + "\r\n\r\n";
                out.write(part.getBytes(StandardCharsets.UTF_8));
                out.write(frame);
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            leaveCapture();
        }
    }

    private void joinCapture() {
        synchronized (clientsLock) {
            clients++;
            if (captureThread == null) {
                captureThread = new Thread(this::captureLoop, "pcx-capture");
                captureThread.start();
            }
        }
    }

    private void leaveCapture() {
        synchronized (clientsLock) {
            if (clients > 0) clients--;
        }
    }

    private void captureLoop() {
        while (running) {
            synchronized (clientsLock) {
                if (clients == 0) {
                    // Clear the thread handle and free the reused buffers in ONE critical section: a client
                    // reconnecting (joinCapture) then either sees us still running or starts a fresh thread that
                    // re-creates them -- it can never reach a half-freed/recycled Bitmap.
                    releaseCapture();
                    return;
                }
            }
            byte[] jpeg = captureJpeg();
            long delay = FRAME_DELAY_MS;
            if (jpeg != null) {
                synchronized (frameLock) {
                    latestFrame = jpeg;
                    frameSeq++;
                    frameLock.notifyAll();
                }
            } else {
                // A failed frame usually means the root shell was just killed; it is re-spawned on the next call.
                // Back off so we do not re-spawn su (and flash SuperSU toasts) dozens of times a second.
                delay = ERROR_BACKOFF_MS;
            }
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // Shutting down (running == false) or interrupted: release under the lock, so an exit on any path leaves
        // captureThread clear and never strands it pointing at a dead thread.
        synchronized (clientsLock) {
            releaseCapture();
        }
    }

    /** Clears the capture-thread handle and frees the reused frame buffers. Caller holds {@link #clientsLock}. */
    private void releaseCapture() {
        if (captureThread == Thread.currentThread()) captureThread = null;
        if (frameBmp != null) { frameBmp.recycle(); frameBmp = null; }
        frameBuf = null;
    }

    /**
     * Grabs one frame: raw {@code screencap} straight from the persistent root shell's stdout (no temp file, no
     * PNG encode), copied into a reused ARGB_8888 Bitmap and re-encoded to JPEG. The captured surface is the full
     * screen, 1:1 with the display, so its pixels are the device's and its size maps input coordinates.
     */
    private byte[] captureJpeg() {
        try {
            int[] dim = root.screencap();
            if (dim == null) return null;
            int w = dim[0], h = dim[1];
            if (deviceSize == null) deviceSize = new int[]{w, h};
            if (frameBmp == null || frameBmp.getWidth() != w || frameBmp.getHeight() != h) {
                if (frameBmp != null) frameBmp.recycle();
                frameBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            }
            frameBmp.copyPixelsFromBuffer(ByteBuffer.wrap(frameBuf, 0, w * h * 4));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            frameBmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos);
            return bos.toByteArray();
        } catch (RuntimeException | OutOfMemoryError e) {
            // Never let a capture error kill the capture thread and freeze the stream -- including an
            // OutOfMemoryError on the big frame byte[]/Bitmap, which is an Error, not a RuntimeException.
            Log.w(TAG, "capture failed", e);
            return null;
        }
    }

    // --- input injection -----------------------------------------------------------------------------------

    private void serveInput(OutputStream out, byte[] body, String setCookie) throws IOException {
        int[] size = deviceSize();
        if (size == null) {
            respond(out, "503 Service Unavailable", "text/plain",
                    "no screen size".getBytes(StandardCharsets.UTF_8), setCookie);
            return;
        }
        int w = size[0], h = size[1];
        try {
            JSONObject j = new JSONObject(new String(body, StandardCharsets.UTF_8));
            String type = j.getString("type");
            switch (type) {
                case "tap":
                    runSu("input tap " + px(j.getDouble("fx"), w) + " " + px(j.getDouble("fy"), h));
                    break;
                case "swipe": {
                    int ms = Math.max(20, Math.min(3000, j.optInt("ms", 120)));
                    runSu("input swipe " + px(j.getDouble("fx"), w) + " " + px(j.getDouble("fy"), h)
                            + " " + px(j.getDouble("tx"), w) + " " + px(j.getDouble("ty"), h) + " " + ms);
                    break;
                }
                case "key":
                    runSu("input keyevent " + j.getInt("code"));
                    break;
                case "text":
                    injectText(j.getString("text"));
                    break;
                default:
                    respond(out, "400 Bad Request", "text/plain",
                            "unknown type".getBytes(StandardCharsets.UTF_8), setCookie);
                    return;
            }
        } catch (JSONException e) {
            respond(out, "400 Bad Request", "text/plain", "bad json".getBytes(StandardCharsets.UTF_8), setCookie);
            return;
        }
        respond(out, "200 OK", "text/plain", "ok".getBytes(StandardCharsets.UTF_8), setCookie);
    }

    /** Types a string char by char. Space is its own keyevent; every other char goes single-quoted. */
    private void injectText(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ') {
                runSu("input keyevent 62"); // KEYCODE_SPACE
            } else {
                // Inside single quotes only the quote itself needs escaping; $, `, \, " stay literal.
                String esc = String.valueOf(c).replace("'", "'\\''");
                runSu("input text '" + esc + "'");
            }
        }
    }

    /** A fraction of the image (0..1) to a device pixel, clamped inside the screen. */
    private static int px(double frac, int span) {
        int v = (int) Math.round(frac * span);
        if (v < 0) v = 0;
        if (v > span - 1) v = span - 1;
        return v;
    }

    private int[] deviceSize() {
        int[] s = deviceSize;
        if (s != null) return s;
        String out = runSuOut("wm size");
        // "Physical size: 1920x1080" (an "Override size" line may follow; the physical one is what input wants).
        Matcher m = Pattern.compile("Physical size:\\s*(\\d+)x(\\d+)").matcher(out);
        if (!m.find()) m = Pattern.compile("(\\d+)x(\\d+)").matcher(out);
        if (m.find()) {
            s = new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
            deviceSize = s;
            Log.i(TAG, "device screen size " + s[0] + "x" + s[1]);
        }
        return s;
    }

    // --- root shell ----------------------------------------------------------------------------------------

    private boolean runSu(String cmd) {
        return root.exec(cmd) != null;
    }

    private String runSuOut(String cmd) {
        String out = root.exec(cmd);
        return out == null ? "" : out;
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xff) | (b[off + 1] & 0xff) << 8 | (b[off + 2] & 0xff) << 16 | (b[off + 3] & 0xff) << 24;
    }

    private static boolean readFull(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) return false;
            off += n;
        }
        return true;
    }

    /**
     * A single long-lived {@code su} shell. Starting it is the one SuperSU grant (one toast); every capture and
     * input command then runs through its stdin/stdout, so there is no per-frame su spawn and no per-frame toast.
     * All access is serialized -- the capture loop and the input handlers run one command at a time.
     */
    private final class RootShell {
        // proc is read without the monitor by the watchdog and by close(), so it is volatile.
        private volatile Process proc;
        private OutputStream in;
        private InputStream out;
        // The read in progress and its monotonic deadline (System.nanoTime); the watchdog kills the shell past it
        // so a stuck read (e.g. a screencap that never finishes) cannot hang a thread forever. nanoTime, not the
        // wall clock, so an NTP step cannot fire the watchdog early or delay it.
        private volatile boolean opActive;
        private volatile long opDeadlineNanos;

        private boolean ensure() {
            if (proc != null && Procs.alive(proc)) return true;
            try {
                // stdin is a pipe, not a tty, so the shell runs non-interactively and prints no prompt.
                final Process started = new ProcessBuilder("su").redirectErrorStream(false).start();
                proc = started;
                in = started.getOutputStream();
                out = started.getInputStream();
                final InputStream err = started.getErrorStream();
                Thread t = new Thread(() -> {
                    byte[] b = new byte[256];
                    try { while (err.read(b) != -1) { /* drain stderr */ } } catch (IOException ignored) { }
                }, "pcx-root-err");
                t.setDaemon(true);
                t.start();
                // Watchdog: if the current read outlives opDeadline, kill the shell so the blocked read unblocks.
                Thread wd = new Thread(() -> {
                    while (Procs.alive(started)) {
                        try { Thread.sleep(500); } catch (InterruptedException e) { return; }
                        if (opActive && System.nanoTime() - opDeadlineNanos > 0) {
                            Log.w(TAG, "root shell read timed out; killing the shell");
                            started.destroy();
                            return;
                        }
                    }
                }, "pcx-root-wd");
                wd.setDaemon(true);
                wd.start();
                // Swallow any shell/su startup banner so the stream starts at a clean command boundary.
                in.write(("echo " + MARK + "0\n").getBytes(StandardCharsets.UTF_8));
                in.flush();
                for (String line; (line = readLine(out)) != null; ) {
                    if (line.startsWith(MARK)) return true;
                }
                kill();
                return false;
            } catch (IOException e) {
                Log.e(TAG, "cannot start root shell", e);
                kill();
                return false;
            }
        }

        /** Runs a text command and returns its stdout up to the marker; null if the shell could not run it. */
        synchronized String exec(String cmd) {
            opDeadlineNanos = System.nanoTime() + OP_TIMEOUT_MS * 1_000_000L;
            opActive = true;
            try {
                if (!ensure()) return null;
                in.write((cmd + "\necho " + MARK + "$?\n").getBytes(StandardCharsets.UTF_8));
                in.flush();
                StringBuilder body = new StringBuilder();
                for (String line; (line = readLine(out)) != null; ) {
                    if (line.startsWith(MARK)) return body.toString();
                    body.append(line).append('\n');
                }
                kill();
                return null;
            } catch (IOException e) {
                Log.w(TAG, "root exec failed: " + cmd, e);
                kill();
                return null;
            } finally {
                opActive = false;
            }
        }

        /** One raw screencap frame straight from stdout into {@link #frameBuf}; returns {width,height} or null. */
        synchronized int[] screencap() {
            opDeadlineNanos = System.nanoTime() + OP_TIMEOUT_MS * 1_000_000L;
            opActive = true;
            try {
                if (!ensure()) return null;
                in.write("screencap\n".getBytes(StandardCharsets.UTF_8));
                in.flush();
                byte[] hdr = new byte[SCREENCAP_HEADER];
                if (!readFull(out, hdr, SCREENCAP_HEADER)) { kill(); return null; }
                int w = le32(hdr, 0), h = le32(hdr, 4), fmt = le32(hdr, 8);
                if (w <= 0 || h <= 0 || w > 8192 || h > 8192 || fmt != SCREENCAP_FORMAT_RGBA_8888) {
                    // A bad header (or a non-RGBA_8888 surface, e.g. HDR FP16) would misread the pixel count and
                    // desync the stream; drop the shell instead and let the next frame start clean.
                    Log.w(TAG, "unexpected screencap header " + w + "x" + h + " fmt " + fmt);
                    kill();
                    return null;
                }
                int px = w * h * 4;
                if (frameBuf == null || frameBuf.length != px) frameBuf = new byte[px];
                if (!readFull(out, frameBuf, px)) { kill(); return null; }
                return new int[]{w, h};
            } catch (IOException e) {
                Log.w(TAG, "root screencap failed", e);
                kill();
                return null;
            } finally {
                opActive = false;
            }
        }

        /**
         * Not synchronized: a reader may hold the monitor blocked on the pipe. destroy() closes the pipe so that
         * read unblocks (its catch then runs kill()); taking the monitor here would deadlock against that reader.
         */
        void close() {
            Process p = proc;
            proc = null;
            if (p != null) p.destroy();
        }

        private void kill() {
            Process p = proc;
            proc = null;
            in = null;
            out = null;
            if (p != null) p.destroy();
        }
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

    private static void respond(OutputStream out, String status, String contentType, byte[] body)
            throws IOException {
        respond(out, status, contentType, body, "");
    }

    /** {@code extraHeaders}: whole header lines, each ending in CRLF (e.g. a Set-Cookie); "" for none. */
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

    // The standalone control page. Normalized image fractions keep the mapping independent of the JPEG size.
    private static final String PAGE = ""
            + "<!doctype html><html lang=ru><head><meta charset=utf-8>"
            + "<meta name=viewport content='width=device-width,initial-scale=1'>"
            + "<title>PopcornClaw screen</title><style>"
            + "html,body{margin:0;height:100%;background:#111;color:#ccc;font:14px system-ui,sans-serif}"
            + "#wrap{display:flex;flex-direction:column;height:100%}"
            + "#bar{display:flex;flex-wrap:wrap;gap:6px;padding:8px;background:#1a1a1a}"
            + "#bar button{background:#2a2a2a;color:#ddd;border:1px solid #444;border-radius:5px;padding:7px 11px;cursor:pointer}"
            + "#bar button:active{background:#3a3a3a}"
            + "#stage{flex:1;display:flex;align-items:center;justify-content:center;overflow:hidden}"
            + "#screen{max-width:100%;max-height:100%;cursor:crosshair;-webkit-user-select:none;user-select:none}"
            + "#hint{padding:4px 8px;color:#777;font-size:12px}"
            + "</style></head><body><div id=wrap>"
            + "<div id=bar>"
            + "<button data-key=4>Back</button>"
            + "<button data-key=3>Home</button>"
            + "<button data-key=187>Recents</button>"
            + "<button data-key=19>Up</button>"
            + "<button data-key=20>Down</button>"
            + "<button data-key=21>Left</button>"
            + "<button data-key=22>Right</button>"
            + "<button data-key=23>OK</button>"
            + "<button data-key=66>Enter</button>"
            + "<button data-key=67>Del</button>"
            + "</div>"
            + "<div id=stage><img id=screen src='/screen.mjpeg?pin=__PIN__' draggable=false></div>"
            + "<div id=hint>Click to tap, drag to swipe, wheel to scroll. Type on the keyboard; buttons above for keys. LAN only.</div>"
            + "</div><script>"
            + "var img=document.getElementById('screen');"
            + "function send(o){fetch('/input?pin=__PIN__',{method:'POST',body:JSON.stringify(o)});}"
            + "function frac(cx,cy){var r=img.getBoundingClientRect();"
            + "var fx=(cx-r.left)/r.width,fy=(cy-r.top)/r.height;"
            + "fx=Math.max(0,Math.min(1,fx));fy=Math.max(0,Math.min(1,fy));return{fx:fx,fy:fy};}"
            + "var down=null;"
            + "img.addEventListener('mousedown',function(e){down={x:e.clientX,y:e.clientY,t:Date.now()};e.preventDefault();});"
            + "img.addEventListener('mouseup',function(e){if(!down)return;"
            + "var a=frac(down.x,down.y),b=frac(e.clientX,e.clientY);"
            + "var dist=Math.hypot(e.clientX-down.x,e.clientY-down.y);"
            + "if(dist<8){send({type:'tap',fx:a.fx,fy:a.fy});}"
            + "else{send({type:'swipe',fx:a.fx,fy:a.fy,tx:b.fx,ty:b.fy,ms:Math.max(60,Math.min(2000,Date.now()-down.t))});}"
            + "down=null;});"
            + "img.addEventListener('wheel',function(e){e.preventDefault();var a=frac(e.clientX,e.clientY);"
            + "var dy=e.deltaY>0?-0.2:0.2;var ty=Math.max(0,Math.min(1,a.fy+dy));"
            + "send({type:'swipe',fx:a.fx,fy:a.fy,tx:a.fx,ty:ty,ms:150});},{passive:false});"
            + "img.addEventListener('contextmenu',function(e){e.preventDefault();});"
            + "var KEYS={'Backspace':67,'Enter':66,'Tab':61,'Escape':4,'ArrowUp':19,'ArrowDown':20,"
            + "'ArrowLeft':21,'ArrowRight':22,'Home':3,'Delete':112,'PageUp':92,'PageDown':93};"
            + "document.addEventListener('keydown',function(e){"
            + "if(e.key in KEYS){e.preventDefault();send({type:'key',code:KEYS[e.key]});return;}"
            + "if(e.key.length===1&&!e.ctrlKey&&!e.metaKey&&!e.altKey){e.preventDefault();send({type:'text',text:e.key});}});"
            + "Array.prototype.forEach.call(document.querySelectorAll('#bar button'),function(b){"
            + "b.addEventListener('click',function(){send({type:'key',code:parseInt(b.dataset.key,10)});img.focus();});});"
            + "</script></body></html>";
}
