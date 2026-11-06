package xyz.vvzvlad.popcornclaw;

import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
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
 * Its check loop first brings a fresh box to a working state ({@link Provisioning}), and the gateway is started
 * only once the model is configured.
 */
public final class AgentService extends Service {
    public static final String ACTION_RESTART = "xyz.vvzvlad.popcornclaw.RESTART";
    /** The panel's remote-voice switch changed: {@link #applyVoice} applies it. */
    public static final String ACTION_VOICE = "xyz.vvzvlad.popcornclaw.VOICE";

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
    private Provisioning provisioning;
    // All touched on the worker thread only.
    private int failures;
    // Set so the very first start is never held back by the one-minute gap.
    private long lastStart = -START_GAP_MS;
    private boolean provisioned;
    // The config gate (Provisioning.unconfigured): null when the model is configured, else the line written to
    // the gateway log once per change. Read at the service's first start attempt and on every ACTION_RESTART
    // only (the panel sends one after a save), so an unconfigured box gets no su a minute from the health check;
    // a failed read (no root grant yet) is not kept and is read again on the next attempt.
    private boolean gateRead;
    private String unready;

    /** Whether the launcher's "not the home screen" question may be asked now (Provisioning.homeAskable). */
    public static boolean homeAskable(Context c) {
        return Provisioning.homeAskable(c);
    }

    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            // Before the gateway's first start: on a fresh box it needs the files provisioning writes.
            if (!provisioned) provisioned = provisioning.run();
            if (healthy()) {
                failures = 0;
            } else if (++failures >= FAILURES_TO_START && SystemClock.elapsedRealtime() - lastStart >= START_GAP_MS) {
                start("health check failed " + failures + " times in a row", false);
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
        provisioning = new Provisioning(this);
        worker.post(check);
        // The service always runs, so it is the one that pops up each command the agent gets and its messages.
        popup = new CommandPopup(this);
        popup.start();
        // The owner-facing web page (box screen + mouse/keyboard over the LAN, "just to set things up").
        screen = new ScreenStreamServer(this);
        screen.start();
        // The owner-facing control MCP + settings form over the LAN.
        control = new ControlServer(this);
        control.start();
        // The ported tv_mcp (search/files/prepare/play/player/find_subtitles) on 127.0.0.1, for the gateway.
        tvmcp = new TvMcpServer();
        tvmcp.start();
        // The TV's half of the dialog with no screen open: the gateway has somewhere to send it.
        PicoLink.connect();
        // The box's own TorrServer (bundled in the app), so tv_mcp needs no networked one; ffprobe rides with it.
        worker.post(this::startTorrServer);
        // SPIKE: keep the voice helpers warm for the phone remote's ~15 s window (when the panel's switch is on).
        worker.post(() -> applyVoice(false));
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
     * Applies the panel's "Remote's voice button talks to the agent" switch (AppSettings), read at the service's
     * start and on every {@link #ACTION_VOICE} the panel sends when the switch changes. Off: our two voice
     * activities are disabled -- a disabled activity drops out of the resolver, so the system's WEB_SEARCH and
     * ASSIST go to Google (katniss) again -- and the helpers are killed. On: the activities are enabled and the
     * helpers started; on a change also AssistRole, since the preferred entries must point at the re-enabled
     * activities again (the owner may have picked "always Google" while ours were off). DONT_KILL_APP: this
     * service runs in the app's own process.
     */
    private void applyVoice(boolean changed) {
        boolean on = AppSettings.getVoiceRemote(this);
        int state = on ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        PackageManager pm = getPackageManager();
        pm.setComponentEnabledSetting(new ComponentName(this, VoiceSearchActivity.class), state, PackageManager.DONT_KILL_APP);
        pm.setComponentEnabledSetting(new ComponentName(this, VoiceAssistActivity.class), state, PackageManager.DONT_KILL_APP);
        if (!on) {
            // By comm, as in startVoiceHelpers: the su's own command text names the helpers too.
            Provisioning.Result r = Provisioning.root("pkill pcvoicerelay; pkill pcvoiceworker", null, 15_000);
            Log.i(TAG, "voice: remote voice button is off in the panel; activities disabled, helpers killed (su exited with "
                    + r.code + ")");
            return;
        }
        startVoiceHelpers();
        if (changed) {
            Provisioning.Result r = Provisioning.root("CLASSPATH=" + getPackageCodePath() + " app_process /system/bin "
                    + AssistRole.class.getName(), null, 60_000);
            Log.i(TAG, "voice: AssistRole exited with " + r.code + ": " + r.out.trim());
        }
    }

    /**
     * SPIKE: launches the two voice helpers kept warm for the phone remote (see {@link VoiceHelper}). The relay
     * must be root (only uid < AID_APP may ServiceManager.addService); the worker must be the assistant
     * package's uid so its transact(1) passes the service's uid gate, and it must open /dev/binder already as
     * that uid, so it is a fresh app_process under su, not a fork of root. Called by {@link #applyVoice} only
     * while the panel's switch is on.
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
        if (intent != null && ACTION_RESTART.equals(intent.getAction())) worker.post(() -> start("restart requested", true));
        if (intent != null && ACTION_VOICE.equals(intent.getAction())) worker.post(() -> applyVoice(true));
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
    private void start(String reason, boolean readConfig) {
        lastStart = SystemClock.elapsedRealtime();
        // A fresh box has no model until the owner sets one in the control panel: nothing to start yet.
        if (readConfig || !gateRead) {
            String u = Provisioning.unconfigured();
            if (u != null && !u.equals(unready)) appendLog(u);
            unready = u;
            gateRead = u == null || Provisioning.NOT_CONFIGURED.equals(u);
        }
        if (unready != null) return;
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

    /**
     * Starts the bundled TorrServer on :8090 (the port tv_mcp uses) the way {@link #start} starts the gateway:
     * a leftover is killed first (SIGTERM, then SIGKILL), then a fresh one is launched under su --mount-master
     * so it shares the box's mounts. TorrServer finds ffprobe (bundled too, as libffprobe.so) through PATH: a
     * symlink named "ffprobe" in its data dir points at the extracted library, and the data dir leads PATH. The
     * binary runs through the shell variable B so the pkill/pgrep pattern "[l]ibtorrserver.so --port 8090" does
     * not match this script's own command line, only the running server.
     */
    private void startTorrServer() {
        String bin = getApplicationInfo().nativeLibraryDir + "/libtorrserver.so";
        String ffprobe = getApplicationInfo().nativeLibraryDir + "/libffprobe.so";
        String data = DIR + "/torrserver";
        String script = "umask 000\n"
                + "B=" + bin + "\n"
                + "D=" + data + "\n"
                + "mkdir -p \"$D\"\n"
                + "ln -sf " + ffprobe + " \"$D/ffprobe\"\n"
                + "touch \"$D/torrserver.log\" && chmod 666 \"$D/torrserver.log\"\n"
                + "pkill -f '[l]ibtorrserver.so --port 8090'\n"
                + "for i in 1 2 3 4 5 6 7 8 9 10; do pgrep -f '[l]ibtorrserver.so --port 8090' >/dev/null || break; sleep 1; done\n"
                + "p=$(pgrep -f '[l]ibtorrserver.so --port 8090') && kill -9 $p\n"
                + "cd \"$D\" || exit 1\n"
                // Go binaries: CA store for HTTPS tracker lists, a temp dir Android otherwise lacks, and the data
                // dir on PATH so TorrServer's exec.LookPath finds the ffprobe symlink.
                + "export HOME=\"$D\" TMPDIR=/data/local/tmp SSL_CERT_DIR=/system/etc/security/cacerts\n"
                + "export PATH=\"$D:$PATH\"\n"
                + "setsid \"$B\" --port 8090 --path \"$D\" --logpath \"$D/torrserver.log\" >> \"$D/torrserver.log\" 2>&1 < /dev/null &\n";
        try {
            Process p = new ProcessBuilder("su", "--mount-master", "-c", script).redirectErrorStream(true).start();
            p.getOutputStream().close();
            try (BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                for (String line; (line = out.readLine()) != null; ) Log.w(TAG, "torrserver su: " + line);
            }
            Log.i(TAG, "torrserver: launched (su exited with " + p.waitFor() + ")");
        } catch (IOException e) {
            Log.e(TAG, "torrserver: launching failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
