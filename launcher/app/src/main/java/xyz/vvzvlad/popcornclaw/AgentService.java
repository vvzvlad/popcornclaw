package xyz.vvzvlad.popcornclaw;

import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
 * and the agent's messages as the Telegram chat gets them in a popup over everything ({@link CommandPopup}).
 * And it strips the keys that stop a bonded Bluetooth remote from reconnecting ({@link #fixRemoteKeys}).
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
    private ScreenStreamServer screen;
    private ControlServer control;
    private TvMcpServer tvmcp;
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
        // The service always runs, so it is the one that pops up each command the agent gets and its messages.
        popup = new CommandPopup(this);
        popup.start();
        // The owner-facing web page (box screen + mouse/keyboard over the LAN, "just to set things up").
        screen = new ScreenStreamServer();
        screen.start();
        // The owner-facing control MCP + settings form over the LAN.
        control = new ControlServer();
        control.start();
        // The ported tv_mcp (search/files/prepare/play/player/find_subtitles) over the LAN.
        tvmcp = new TvMcpServer();
        tvmcp.start();
        // The TV's half of the dialog with no screen open: the gateway has somewhere to send it.
        PicoLink.connect();
        // SPIKE: keep the voice helpers warm for the phone remote's ~15 s window.
        worker.post(this::startVoiceHelpers);
        new Thread(() -> fixRemoteKeys(1), "popcornclaw-bt").start();
        registerReceiver(bonded, new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED));
    }

    /** A new bond may bring the keys {@link #fixRemoteKeys} strips; its HID record is saved a few seconds later. */
    private final BroadcastReceiver bonded = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, 0) != BluetoothDevice.BOND_BONDED) return;
            new Thread(() -> fixRemoteKeys(30), "popcornclaw-bt").start();
        }
    };

    /**
     * Strips the identity keys (LE_KEY_PID, LE_KEY_LID) of bonded LE HID devices with a public address from the
     * Bluetooth config, then restarts Bluetooth so the stack rereads it. With those keys Android 9 puts the device
     * into the controller's resolving list and connects to it from a private address; the Xiaomi remote answers
     * only its host's public address, so every reconnect fails with 0x3E. The link keys (LE_KEY_PENC, LE_KEY_LENC)
     * stay. The stack writes the config from memory once more in its cleanup, ~3 s after Bluetooth reports OFF,
     * right before the Bluetooth process exits; an earlier edit is lost, so the file is edited only once that process
     * is gone, through cat so it keeps the bluetooth owner. Looks every 2 s, up to {@code tries} times.
     */
    // synchronized: the start-up run and a bond's run must not edit the file at the same time.
    private static synchronized void fixRemoteKeys(int tries) {
        String script = "f=/data/misc/bluedroid/bt_config.conf\n"
                + "need() {\n"
                + "  for a in $(sed -n 's/^\\[\\(..:..:..:..:..:..\\)\\]$/\\1/p' $f); do\n"
                + "    s=$(sed -n \"/^\\[$a\\]/,/^\\[/p\" $f)\n"
                + "    echo \"$s\" | grep -q '^HidDescriptor' && echo \"$s\" | grep -q '^AddrType = 0'"
                + " && echo \"$s\" | grep -qE '^LE_KEY_(PID|LID) ' && echo $a\n"
                + "  done\n"
                + "}\n"
                + "for i in $(seq 1 " + tries + "); do l=$(need); [ -n \"$l\" ] && break; sleep 2; done\n"
                + "[ -n \"$l\" ] || exit 0\n"
                + "p=$(pidof com.android.bluetooth)\n"
                + "svc bluetooth disable\n"
                + "for i in $(seq 1 30); do [ -n \"$p\" ] && [ -d /proc/$p ] || break; sleep 1; done\n"
                + "for g in $f /data/misc/bluedroid/bt_config.bak; do\n"
                + "  for a in $l; do\n"
                // Not .new: the stack saves the config through bt_config.conf.new itself.
                + "    sed \"/^\\[$a\\]/,/^\\[/{/^LE_KEY_PID /d;/^LE_KEY_LID /d;}\" $g > $g.strip && cat $g.strip > $g\n"
                + "  done\n"
                + "  rm -f $g.strip\n"
                + "done\n"
                + "svc bluetooth enable\n"
                + "echo stripped the identity keys of $l\n";
        try {
            Process p = new ProcessBuilder("su", "-c", script).redirectErrorStream(true).start();
            p.getOutputStream().close();
            try (BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                for (String line; (line = out.readLine()) != null; ) Log.i(TAG, "remote keys: " + line);
            }
            Log.i(TAG, "remote keys: su exited with " + p.waitFor());
        } catch (IOException e) {
            Log.e(TAG, "remote keys: su failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * SPIKE: launches the two voice helpers kept warm for the phone remote (see {@link VoiceHelper}). The relay
     * must be root (only uid < AID_APP may ServiceManager.addService); the worker must be the assistant
     * package's uid so its transact(1) passes the service's uid gate, and it must open /dev/binder already as
     * that uid, so it is a fresh app_process under su, not a fork of root.
     */
    private void startVoiceHelpers() {
        int katnissUid;
        try {
            katnissUid = getPackageManager().getPackageUid("com.google.android.katniss", 0);
        } catch (Exception e) {
            Log.w(TAG, "voice: no assistant package (com.google.android.katniss); helpers not started", e);
            return;
        }
        String cp = "CLASSPATH=" + getPackageCodePath();
        String cls = "xyz.vvzvlad.popcornclaw.VoiceHelper";
        String relay = "app_process /system/bin --nice-name=pcvoicerelay " + cls + " relay";
        String worker = "app_process /system/bin --nice-name=pcvoiceworker " + cls + " worker";
        String log = DIR + "/voice.log";
        // One root su: drop stale helpers, start the relay as root, the worker as the assistant uid. setsid so
        // both outlive this su; the env assignment goes before setsid (setsid would take VAR=val as its
        // program). pkill matches the process names (comm, which VoiceHelper.main sets with Thread.setName --
        // --nice-name only sets argv[0], leaving comm "main"), not the cmdline, so it does not kill this very su
        // whose command text also names the helper. Keep setName in VoiceHelper or this stops killing helpers.
        // The log is world-readable for pickup.
        String script = "touch " + log + " && chmod 666 " + log + "\n"
                + "pkill pcvoicerelay; pkill pcvoiceworker\n"
                + cp + " setsid " + relay + " >> " + log + " 2>&1 < /dev/null &\n"
                + "setsid su " + katnissUid + " -c \"" + cp + " " + worker + "\" >> " + log + " 2>&1 < /dev/null &\n";
        try {
            Process p = new ProcessBuilder("su", "--mount-master", "-c", script).redirectErrorStream(true).start();
            p.getOutputStream().close();
            try (BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                for (String line; (line = out.readLine()) != null; ) Log.w(TAG, "voice su: " + line);
            }
            Log.i(TAG, "voice: helpers launched (worker uid " + katnissUid + ", exit " + p.waitFor() + ")");
        } catch (IOException e) {
            Log.e(TAG, "voice: launching helpers failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_RESTART.equals(intent.getAction())) worker.post(() -> start("restart requested"));
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(bonded);
        popup.stop();
        if (screen != null) screen.stop();
        if (control != null) control.stop();
        if (tvmcp != null) tvmcp.stop();
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
                // The assistant screen's pico channel (PicoLink); it starts only with a token.
                + "export PICOCLAW_CHANNELS_PICO_TOKEN=" + PicoLink.TOKEN + "\n"
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
