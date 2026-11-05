package xyz.vvzvlad.popcornclaw;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The app's own tiny web interface, served on the LAN so the owner can see the box's screen in a PC browser
 * and drive it with the mouse and keyboard "just to set things up". No auth: the box is on the home LAN only.
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

    private static final String FRAME_PATH = "/data/local/tmp/pcx_frame.png";
    private static final String BOUNDARY = "pcxframe";
    // ~5 fps target: capture itself takes a chunk of this, so the real rate is a little lower. Setup only.
    private static final long FRAME_DELAY_MS = 150;
    private static final int JPEG_QUALITY = 60;

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

            if ("GET".equals(method) && ("/".equals(route) || "/screen".equals(route))) {
                serveHtml(out);
            } else if ("GET".equals(method) && "/screen.mjpeg".equals(route)) {
                serveMjpeg(out);
            } else if ("POST".equals(method) && "/input".equals(route)) {
                serveInput(out, readBody(in, contentLength));
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

    private void serveHtml(OutputStream out) throws IOException {
        respond(out, "200 OK", "text/html; charset=utf-8", PAGE.getBytes(StandardCharsets.UTF_8));
    }

    // --- MJPEG stream --------------------------------------------------------------------------------------

    private void serveMjpeg(OutputStream out) throws IOException {
        String header = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: multipart/x-mixed-replace; boundary=" + BOUNDARY + "\r\n"
                + "Cache-Control: no-cache, no-store\r\n"
                + "Pragma: no-cache\r\n"
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
                    captureThread = null;
                    break;
                }
            }
            byte[] jpeg = captureJpeg();
            if (jpeg != null) {
                synchronized (frameLock) {
                    latestFrame = jpeg;
                    frameSeq++;
                    frameLock.notifyAll();
                }
            }
            try {
                Thread.sleep(FRAME_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // Last client gone (or shutting down): drop the temp frame.
        runSu("rm -f " + FRAME_PATH);
    }

    /**
     * Grabs the screen as root into one temp PNG (made readable by this app's uid), decodes it and re-encodes
     * to JPEG. The file is written through {@code su} and read directly to avoid the CRLF mangling a piped
     * screencap stdout would do to a PNG.
     */
    private byte[] captureJpeg() {
        if (!runSu("screencap " + FRAME_PATH + " && chmod 666 " + FRAME_PATH)) return null;
        Bitmap bmp = BitmapFactory.decodeFile(FRAME_PATH);
        if (bmp == null) {
            Log.w(TAG, "screencap produced no decodable frame");
            return null;
        }
        // The captured frame is the full screen; its size is the device size used to map input coordinates.
        if (deviceSize == null) deviceSize = new int[]{bmp.getWidth(), bmp.getHeight()};
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, bos);
            return bos.toByteArray();
        } finally {
            bmp.recycle();
        }
    }

    // --- input injection -----------------------------------------------------------------------------------

    private void serveInput(OutputStream out, byte[] body) throws IOException {
        int[] size = deviceSize();
        if (size == null) {
            respond(out, "503 Service Unavailable", "text/plain",
                    "no screen size".getBytes(StandardCharsets.UTF_8));
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
                            "unknown type".getBytes(StandardCharsets.UTF_8));
                    return;
            }
        } catch (JSONException e) {
            respond(out, "400 Bad Request", "text/plain", "bad json".getBytes(StandardCharsets.UTF_8));
            return;
        }
        respond(out, "200 OK", "text/plain", "ok".getBytes(StandardCharsets.UTF_8));
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

    // --- su helpers (same shell-out as AgentService) -------------------------------------------------------

    private boolean runSu(String cmd) {
        try {
            Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            try (InputStream is = p.getInputStream()) {
                byte[] buf = new byte[256];
                while (is.read(buf) != -1) { /* drain so su never blocks on a full pipe */ }
            }
            return p.waitFor() == 0;
        } catch (IOException e) {
            Log.w(TAG, "su failed: " + cmd, e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String runSuOut(String cmd) {
        try {
            Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream is = p.getInputStream()) {
                byte[] buf = new byte[1024];
                for (int n; (n = is.read(buf)) != -1; ) bos.write(buf, 0, n);
            }
            p.waitFor();
            return bos.toString("UTF-8");
        } catch (IOException e) {
            Log.w(TAG, "su (reading output) failed: " + cmd, e);
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
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
        String header = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-cache, no-store\r\n"
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
            + "<div id=stage><img id=screen src='/screen.mjpeg' draggable=false></div>"
            + "<div id=hint>Click to tap, drag to swipe, wheel to scroll. Type on the keyboard; buttons above for keys. LAN only.</div>"
            + "</div><script>"
            + "var img=document.getElementById('screen');"
            + "function send(o){fetch('/input',{method:'POST',body:JSON.stringify(o)});}"
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
