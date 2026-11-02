package xyz.vvzvlad.popcornclaw;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Keeps the PicoClaw gateway running. Every 10 s it asks the gateway's /health; after three failures in a
 * row, and not within a minute of its own last start, it (re)starts the gateway as root through su.
 * {@link #ACTION_RESTART} kills and starts it at once. The gateway is a separate root process that outlives
 * this app: its output goes to a file, never to a pipe back here. It also shows every new command to the agent
 * in a popup over everything ({@link CommandPopup}).
 */
public final class AgentService extends Service {
    public static final String ACTION_RESTART = "xyz.vvzvlad.popcornclaw.RESTART";

    private static final String TAG = "PopcornClaw";
    private static final String HEALTH_URL = "http://127.0.0.1:18790/health";
    private static final String DIR = "/data/local/popcornclaw";
    private static final long CHECK_MS = 10_000;
    private static final int TIMEOUT_MS = 2_000;
    private static final int FAILURES_TO_START = 3;
    private static final long START_GAP_MS = 60_000;

    private HandlerThread thread;
    private Handler worker;
    private CommandPopup popup;
    // Both touched on the worker thread only.
    private int failures;
    // Set so the very first start is never held back by the one-minute gap.
    private long lastStart = -START_GAP_MS;

    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            if (healthy()) {
                failures = 0;
            } else if (++failures >= FAILURES_TO_START && SystemClock.elapsedRealtime() - lastStart >= START_GAP_MS) {
                start("health check failed " + failures + " times in a row");
            }
            worker.postDelayed(this, CHECK_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("popcornclaw-agent");
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(check);
        // The service always runs, so it is the one that pops up each command the agent gets.
        popup = new CommandPopup(this);
        popup.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_RESTART.equals(intent.getAction())) worker.post(() -> start("restart requested"));
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        popup.stop();
        thread.quit();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static void appendLog(String text) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", java.util.Locale.ROOT);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        String stamp = f.format(new java.util.Date());
        try (java.io.FileWriter w = new java.io.FileWriter(DIR + "/gateway.log", true)) {
            w.write(stamp + " popcornclaw app: " + text + "\n");
        } catch (IOException e) {
            Log.e(TAG, "cannot write the gateway log", e);
        }
    }

    private static boolean healthy() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(HEALTH_URL).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            return c.getResponseCode() == 200;
        } catch (IOException e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * Kills a leftover gateway and starts a new one the way the boot script in /data/media/0/init.d did.
     * Runs on the worker thread and waits for su: up to ~10 s while an old gateway ignores SIGTERM, plus up
     * to SuperSU's prompt timeout (10 s) when the app has no root grant yet.
     */
    private void start(String reason) {
        lastStart = SystemClock.elapsedRealtime();
        String lib = getApplicationInfo().nativeLibraryDir + "/libpicoclaw.so";
        // pkill/pgrep -f match this script's own command line too (the su client and the root shell), so the
        // text "picoclaw gateway" must not appear in it: "[p]icoclaw" in the patterns and "gateway" in quotes
        // below. The gateway can hang on SIGTERM, and a new one refuses to start while the old one lives:
        // wait, then SIGKILL. toybox pkill/pgrep -f with -9 matched their own shell, so SIGKILL goes through
        // kill with pgrep's list.
        // su sessions get umask 0077; the gateway ran with 0000 from the adb shell, and others read its
        // files: mpv (another uid) opens subtitles the agent downloads, this app reads the sessions and
        // appends to gateway.log. Keep the same open modes.
        String script = "umask 000\n"
                + "D=" + DIR + "\n"
                + "touch $D/gateway.log && chmod 666 $D/gateway.log\n"
                + "ln -sf " + lib + " $D/picoclaw\n"
                + "pkill -f '[p]icoclaw gateway'\n"
                + "for i in 1 2 3 4 5 6 7 8 9 10; do pgrep -f '[p]icoclaw gateway' >/dev/null || break; sleep 1; done\n"
                + "p=$(pgrep -f '[p]icoclaw gateway') && kill -9 $p\n"
                + "echo \"$(date -u '+%Y-%m-%d %H:%M:%S UTC') popcornclaw app: starting the gateway: " + reason + "\""
                + " >> $D/gateway.log\n"
                + "cd \"$D\" || exit 1\n"
                + "export HOME=\"$D\" PICOCLAW_HOME=\"$D\" PICOCLAW_CONFIG=\"$D/config.json\"\n"
                // A static Go binary does not know Android's CA store location.
                + "export SSL_CERT_DIR=/system/etc/security/cacerts\n"
                // Go's temp dir falls back to /tmp, which Android lacks: files from Telegram (documents, voice,
                // photos) are downloaded there and dropped when that fails. The adb shell had this value.
                + "export TMPDIR=/data/local/tmp\n"
                // Programs the agent installs through Termux run from its root shell; system tools stay first.
                // Root must not leave root-owned __pycache__ in Termux's prefix: apt runs as the Termux uid.
                + "export PATH=\"$PATH:/data/data/com.termux/files/usr/bin\" PYTHONDONTWRITEBYTECODE=1\n"
                // Append: the console log is kept across restarts for later bug analysis. A pipe to this app
                // instead of the file would kill the gateway with SIGPIPE once the app dies.
                + "setsid ./picoclaw \"gateway\" >> gateway.log 2>&1 < /dev/null &\n";
        try {
            // --mount-master: SuperSU gives every su session its own mount namespace; the gateway must see
            // the same mounts as the rest of the box (the init.d script ran it in the global one).
            Process p = new ProcessBuilder("su", "--mount-master", "-c", script).redirectErrorStream(true).start();
            p.getOutputStream().close();
            try (BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                for (String line; (line = out.readLine()) != null; ) Log.w(TAG, "su: " + line);
            }
            int code = p.waitFor();
            Log.i(TAG, "gateway start (" + reason + "): su exited with " + code);
            // A refused or timed-out SuperSU grant never runs the script, so the script's own log line is missing:
            // say it in the gateway log (the script keeps it mode 666) where the restart check prints from.
            if (code != 0) appendLog("su exited with " + code + " (" + reason + "); no root grant for the app?");
        } catch (IOException e) {
            Log.e(TAG, "gateway start (" + reason + "): su failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
