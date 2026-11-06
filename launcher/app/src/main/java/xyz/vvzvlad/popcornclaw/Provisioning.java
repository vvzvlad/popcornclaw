package xyz.vvzvlad.popcornclaw;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Brings a fresh box to the state {@code make install} leaves it in, so the APK alone is the install: the
 * agent's files from neutral defaults inside the APK, the home screen, permissions, HDMI-CEC, resolv.conf,
 * RuTor search in the bundled TorrServer, mpv-android's IPC socket, and Termux with its bootstrap and socat.
 * {@link AgentService} calls {@link #run} on its worker thread from its check loop until it returns true; the
 * Termux step, minutes long on a fresh box, runs on a thread of its own. Every step is idempotent. The agent's
 * files are written only while absent, so the owner's boxes keep the files make pushed there; mpv-android's
 * mpv.conf gets one input-ipc-server=@mpv line appended when it names no IPC socket.
 */
final class Provisioning {
    private static final String TAG = "PopcornClaw";
    private static final String DIR = "/data/local/popcornclaw";
    /** The agent's files, relative to DIR; each gets its default from {@link #defaultContent} while absent. */
    private static final String[] FILES = {"config.json", ".security.yml", "tv_mcp.json", "workspace/AGENT.md"};
    /** Empty keys in the layout ControlServer.buildSecurityYml writes. */
    private static final String SECURITY_YML = "model_list:\n"
            + "  main:\n"
            + "    api_keys:\n"
            + "      - \"\"\n"
            + "  groq-asr:\n"
            + "    api_keys:\n"
            + "      - \"\"\n"
            + "channel_list:\n"
            + "  telegram:\n"
            + "    settings:\n"
            + "      token: \"\"\n"
            + "web:\n"
            + "  brave:\n"
            + "    api_keys:\n"
            + "      - \"\"\n";
    private static final String TV_MCP_JSON = "{\n"
            + "  \"jackett_url\": \"\",\n"
            + "  \"torrserver_url\": \"http://127.0.0.1:8090\",\n"
            + "  \"jackett_api_key\": \"\",\n"
            + "  \"opensubtitles_api_key\": \"\",\n"
            + "  \"subdl_api_key\": \"\"\n"
            + "}\n";
    private static final String LAUNCHER_CONFIG = "{\"version\":1,\"screensaver\":{\"idleSec\":0}}\n";

    private static final String TERMUX = "com.termux";
    private static final String TERMUX_PREFIX = "/data/data/com.termux/files/usr";
    // Termux of the app's own ABI: a 64-bit app process runs the APK's arm64-v8a binaries, a 32-bit one its
    // armeabi-v7a ones. The 32-bit pins are the Makefile's TERMUX_* (make termux): keep the two in step. The
    // GitHub build is debuggable, which is what lets run-as com.termux run apt (and the bootstrap) as the app's
    // uid from the root shell.
    private static final boolean ARM64 = android.os.Process.is64Bit();
    private static final String TERMUX_APK_URL = ARM64
            ? "https://github.com/termux/termux-app/releases/download/v0.118.3/termux-app_v0.118.3+github-debug_arm64-v8a.apk"
            : "https://github.com/termux/termux-app/releases/download/v0.118.3/termux-app_v0.118.3+github-debug_armeabi-v7a.apk";
    private static final String TERMUX_APK_SHA256 = ARM64
            ? "72fdb596045116bf5ba1b5bdf5b26fddb9acc0bd074ad9f2da9eb0ae85e83a4e"
            : "89416397b70f9ff67a0044e8abe6ef82487cd48fcf543e2d23e02688cc541cf0";
    private static final String TERMUX_BOOTSTRAP_ZIP = ARM64 ? "bootstrap-aarch64.zip" : "bootstrap-arm.zip";
    private static final String TERMUX_BOOTSTRAP_URL = "https://github.com/termux/termux-packages/releases/download/bootstrap-2026.11.01-r1%2Bapt.android-7/"
            + TERMUX_BOOTSTRAP_ZIP;
    private static final String TERMUX_BOOTSTRAP_SHA256 = ARM64
            ? "5a1429b6c68ee3bdc17b5fd6e47ba42933ec924cd7283fe74992574e1d5badae"
            : "99660c5a95733765c9967309a0c91c67335e9804b9e2027cfba71926fa4a3140";
    /** Termux and resolv.conf need the network, which may still be down at boot: tried at most this often. */
    private static final long NET_GAP_MS = 3 * 60_000;

    private static final String MPV = "is.xyz.mpv";
    /** mpv-android may be installed any time after the app: its mpv.conf is looked at this often until it is. */
    private static final long MPV_GAP_MS = 60_000;

    private static final String TORRSERVER_SETTINGS = "http://127.0.0.1:8090/settings";
    private static final MediaType JSON_MEDIA = MediaType.parse("application/json");
    // ControlServer's pattern for the main model's key in .security.yml.
    private static final Pattern YML_MAIN = Pattern.compile("main:\\s*api_keys:\\s*-\\s*\"([^\"]*)\"");
    /** {@link #unconfigured}'s answer when the files were read and the model is missing (not a read failure). */
    static final String NOT_CONFIGURED = "not configured: set the model in the control panel";

    private final Context ctx;
    /**
     * This install's PackageInfo.lastUpdateTime. Once the one-time steps have run it is kept in the app's own
     * SharedPreferences ({@link #SETUP_PREFS}), so they run again after every install, update or reinstall: an
     * update (pm install -r) clears the app's preferred activities, and an uninstall also drops its permissions,
     * appops and home-screen choice (and these prefs with them). The launcher reads it without root.
     */
    private final long installTime;
    private static final String SETUP_PREFS = "provisioning";
    private static final String SETUP_KEY = "setup_done_for";
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
    private final OkHttpClient local = http.newBuilder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    /** The last survey's su failed: no root grant, so provisioning cannot make the app the home screen. */
    private static volatile boolean noRoot;

    // Worker thread only.
    private boolean surveyed, filesDone, setupDone, rutorDone, resolvDone, mpvDone;
    private final List<String> missing = new ArrayList<>();
    private long lastResolvTry = -NET_GAP_MS;
    private long lastMpvTry = -MPV_GAP_MS;
    // The Termux step runs on its own thread (a download and an unpack take minutes and must not hold the
    // service's worker): the worker starts it and reads these; the thread writes them, and termuxPrefix and
    // termuxSocat after the survey are the thread's alone.
    private volatile boolean termuxDone, termuxRunning;
    private volatile long termuxTriedAt = -NET_GAP_MS;
    private volatile boolean termuxPrefix, termuxSocat;

    Provisioning(Context ctx) {
        this.ctx = ctx;
        try {
            installTime = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException("own package not found", e);
        }
    }

    /**
     * Whether the launcher may ask to be made the home screen: the one-time setup (which makes it the home screen)
     * has run for this install, or root is refused for now, so provisioning cannot set it. On a fresh install the question
     * would otherwise pop up a moment before provisioning sets the home itself.
     */
    static boolean homeAskable(Context c) {
        if (noRoot) return true;
        try {
            long installed = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).lastUpdateTime;
            return c.getSharedPreferences(SETUP_PREFS, Context.MODE_PRIVATE).getLong(SETUP_KEY, 0) == installed;
        } catch (PackageManager.NameNotFoundException e) {
            return true;
        }
    }

    /** One pass over the steps not done yet; true once nothing is left. */
    boolean run() {
        // No root grant yet (SuperSU's prompt unanswered or refused): nothing else could run either.
        if (!surveyed) {
            if (!survey()) return false;
            surveyed = true;
        }
        if (!filesDone) filesDone = writeDefaults();
        if (!setupDone) setupDone = setup();
        if (!rutorDone) rutorDone = enableRutor();
        long now = SystemClock.elapsedRealtime();
        if (!resolvDone && now - lastResolvTry >= NET_GAP_MS) {
            lastResolvTry = now;
            resolvDone = resolvConf();
        }
        if (!mpvDone && now - lastMpvTry >= MPV_GAP_MS) {
            lastMpvTry = now;
            mpvDone = mpvIpc();
        }
        // One Termux thread at a time; the gap counts from the end of the last try.
        if (!termuxDone && !termuxRunning && now - termuxTriedAt >= NET_GAP_MS) {
            termuxRunning = true;
            new Thread(() -> {
                try {
                    termuxDone = termux();
                } finally {
                    termuxTriedAt = SystemClock.elapsedRealtime();
                    termuxRunning = false;
                }
            }, "popcornclaw-termux").start();
        }
        return filesDone && setupDone && rutorDone && resolvDone && mpvDone && termuxDone;
    }

    /** What is there already, in one su: the files, RuTor's marker, Termux's prefix and socat, resolv.conf. */
    private boolean survey() {
        String script = "D=" + DIR + "\n"
                + "for f in " + TextUtils.join(" ", FILES) + "; do [ -e \"$D/$f\" ] || echo \"missing $f\"; done\n"
                + "[ -e $D/.rutor ] && echo rutor\n"
                + "[ -d " + TERMUX_PREFIX + " ] && echo termux\n"
                + "[ -x " + TERMUX_PREFIX + "/bin/socat ] && echo socat\n"
                + "[ -f /system/etc/resolv.conf ] && echo resolv\n"
                + "exit 0\n";
        Result r = root(script, null, 15_000);
        noRoot = r.code != 0;
        if (noRoot) {
            Log.w(TAG, "provisioning: su exited with " + r.code + "; no root grant for the app? " + r.out.trim());
            return false;
        }
        for (String line : r.out.split("\n")) {
            line = line.trim();
            if (line.startsWith("missing ")) {
                missing.add(line.substring("missing ".length()));
            } else if (line.equals("rutor")) {
                rutorDone = true;
            } else if (line.equals("termux")) {
                termuxPrefix = true;
            } else if (line.equals("socat")) {
                termuxSocat = true;
            } else if (line.equals("resolv")) {
                resolvDone = true;
            }
        }
        filesDone = missing.isEmpty();
        setupDone = ctx.getSharedPreferences(SETUP_PREFS, Context.MODE_PRIVATE).getLong(SETUP_KEY, 0) == installTime;
        termuxDone = termuxPrefix && termuxSocat && termuxInstalled();
        return true;
    }

    /** Writes the default of every file the survey found missing; true once all are there. */
    private boolean writeDefaults() {
        for (Iterator<String> it = missing.iterator(); it.hasNext(); ) {
            String name = it.next();
            byte[] content;
            try {
                content = defaultContent(name);
            } catch (IOException e) {
                Log.e(TAG, "provisioning: cannot read the default " + name, e);
                return false;
            }
            String path = DIR + "/" + name;
            // The directories get umask 000, as make's adb shell made them: the app reads workspace/sessions and
            // appends to gateway.log as its own uid. The file is root's, 600 (the keys go there), and comes
            // through stdin, which is drained either way so the write into the pipe never meets a closed end.
            // tmp + mv: a write cut off midway must not pass for a present file on the next pass.
            String script = "umask 000 && mkdir -p " + DIR + "/workspace " + DIR + "/backups || exit 1\n"
                    + "umask 077\n"
                    + "if [ -e " + path + " ]; then cat > /dev/null; exit 0; fi\n"
                    + "cat > " + path + ".tmp && chmod 600 " + path + ".tmp && mv " + path + ".tmp " + path + "\n";
            Result r = root(script, content, 15_000);
            if (r.code != 0) {
                Log.w(TAG, "provisioning: writing " + path + ": su exited with " + r.code + ": " + r.out.trim());
                return false;
            }
            Log.i(TAG, "provisioning: " + path + " in place");
            it.remove();
        }
        return true;
    }

    private byte[] defaultContent(String name) throws IOException {
        switch (name) {
            case "config.json":
                return asset("defaults/config.json");
            case "workspace/AGENT.md":
                return asset("defaults/AGENT.md");
            case ".security.yml":
                return SECURITY_YML.getBytes(StandardCharsets.UTF_8);
            case "tv_mcp.json":
                return TV_MCP_JSON.getBytes(StandardCharsets.UTF_8);
            default:
                throw new IOException("no default for " + name);
        }
    }

    /**
     * The steps make app and make files did on every install, done once per install ({@link #installTime}).
     * Each command is best-effort, as in make; the marker is written after su, so a pass su never ran is repeated.
     */
    private boolean setup() {
        // The launcher's own screensaver (150 s idle on the home screen by default) off; a config already there
        // is kept. Same place LauncherConfig.file reads it from.
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) dir = ctx.getFilesDir();
        File launcherConfig = new File(dir, "omarchytv.json");
        if (!launcherConfig.exists()) {
            try (OutputStream out = new FileOutputStream(launcherConfig)) {
                out.write(LAUNCHER_CONFIG.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                Log.w(TAG, "provisioning: cannot write " + launcherConfig, e);
                return false;
            }
        }
        String pkg = ctx.getPackageName();
        String script = "cmd package set-home-activity --user 0 " + pkg + "/com.omarchytv.home.MainActivity\n"
                // Off the whitelist Android stops this service a minute after the launcher leaves the screen and
                // refuses to start it while the app is in the background.
                + "dumpsys deviceidle whitelist +" + pkg + "\n"
                // The recognition service checks the caller's RECORD_AUDIO: VoiceSearchActivity's SpeechRecognizer.
                + "pm grant " + pkg + " android.permission.RECORD_AUDIO\n"
                // CommandPopup shows each command to the agent over every app, video included.
                + "appops set " + pkg + " SYSTEM_ALERT_WINDOW allow\n"
                // ACTION_ASSIST and WEB_SEARCH preferred at our voice activities; uid 0 passes the permission check.
                // Skipped while the panel's remote-voice switch is off (AppSettings); turning it on later runs it
                // from AgentService.applyVoice.
                + (AppSettings.getVoiceRemote(ctx)
                        ? "CLASSPATH=" + ctx.getPackageCodePath() + " app_process /system/bin " + AssistRole.class.getName() + "\n"
                        : "")
                // HDMI-CEC on so the agent can wake the TV and take its input; the box must not sleep with the TV.
                + "settings put global hdmi_control_enabled 1\n"
                + "settings put global hdmi_control_auto_device_off_enabled 0\n"
                + "setprop persist.sys.hdmi.keep_awake true\n";
        Result r = root(script, null, 60_000);
        Log.i(TAG, "provisioning: one-time setup, su exited with " + r.code + ": " + r.out.trim());
        if (r.code != 0) return false;
        ctx.getSharedPreferences(SETUP_PREFS, Context.MODE_PRIVATE).edit().putLong(SETUP_KEY, installTime).apply();
        return true;
    }

    /**
     * Turns on RuTor search in the bundled TorrServer once (marker DIR/.rutor), so tv_mcp finds releases with no
     * Jackett. TorrServer is started after the first pass and takes a moment to listen: until it answers, every
     * check tries again. The settings go back whole, with only EnableRutorSearch changed.
     */
    private boolean enableRutor() {
        try {
            JSONObject sets = new JSONObject(postSettings("{\"action\":\"get\"}"));
            sets.put("EnableRutorSearch", true);
            postSettings(new JSONObject().put("action", "set").put("sets", sets).toString());
        } catch (IOException | JSONException e) {
            return false;
        }
        Result r = root("echo 1 > " + DIR + "/.rutor", null, 15_000);
        Log.i(TAG, "provisioning: RuTor search enabled in TorrServer; marker: su exited with " + r.code);
        return r.code == 0;
    }

    /**
     * Names mpv-android's IPC socket in its mpv.conf: input-ipc-server=@mpv, the abstract socket tv_mcp
     * ({@link MpvIpc}) and the agent's socat talk to. An mpv.conf that already names one is left as it is; the line
     * is appended to the owner's other settings. False while mpv-android is not installed: tried again later.
     */
    private boolean mpvIpc() {
        // Asked without root: each su flashes the root app's toast on the TV, and this waits for an install.
        try {
            ctx.getPackageManager().getPackageInfo(MPV, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        String d = "/data/data/" + MPV;
        String script = "f=" + d + "/files/mpv.conf\n"
                + "grep -q '^input-ipc-server=' $f 2>/dev/null && exit 0\n"
                // The file and a files/ made here belong to mpv's uid, as mpv-android's own settings screen writes it.
                + "u=$(stat -c %u " + d + ")\n"
                + "mkdir -p " + d + "/files && chown $u:$u " + d + "/files || exit 1\n"
                + "[ -s $f ] && [ -n \"$(tail -c 1 $f)\" ] && echo >> $f\n"
                + "echo 'input-ipc-server=@mpv' >> $f && chown $u:$u $f && chmod 600 $f || exit 1\n"
                + "restorecon -R " + d + "/files 2>/dev/null\n"
                + "exit 0\n";
        Result r = root(script, null, 15_000);
        Log.i(TAG, "provisioning: mpv.conf IPC socket: su exited with " + r.code + ": " + r.out.trim());
        return r.code == 0;
    }

    private String postSettings(String body) throws IOException {
        Request req = new Request.Builder().url(TORRSERVER_SETTINGS).post(RequestBody.create(body, JSON_MEDIA)).build();
        try (Response resp = local.newCall(req).execute()) {
            ResponseBody b = resp.body();
            if (!resp.isSuccessful() || b == null) throw new IOException("TorrServer /settings: HTTP " + resp.code());
            return b.string();
        }
    }

    /**
     * Android has no /etc/resolv.conf, and a static Go binary (the gateway, TorrServer) falls back to 127.0.0.1:53
     * without it. The nameserver is the default route's gateway, else net.dns1, else 1.1.1.1. No route means the
     * network is not up yet: the next try waits. Once it is up there is one write attempt; a system that cannot be
     * remounted is logged, not retried.
     */
    private boolean resolvConf() {
        String f = "/system/etc/resolv.conf";
        String script = "[ -f " + f + " ] && exit 0\n"
                + "route=$(ip route get 1.1.1.1 2>&1) || { echo \"no route yet: $route\"; exit 1; }\n"
                + "ns=$(echo \"$route\" | sed -n 's/.* via \\([0-9.]*\\).*/\\1/p' | head -n 1)\n"
                + "[ -n \"$ns\" ] || ns=$(getprop net.dns1)\n"
                + "[ -n \"$ns\" ] || ns=1.1.1.1\n"
                + "mount -o remount,rw / && echo \"nameserver $ns\" > " + f + " && chmod 644 " + f
                + " && echo \"wrote " + f + ": nameserver $ns\"\n"
                + "mount -o remount,ro /\n"
                + "exit 0\n";
        Result r = root(script, null, 30_000);
        Log.i(TAG, "provisioning: resolv.conf, su exited with " + r.code + ": " + r.out.trim());
        return r.code == 0;
    }

    /**
     * Termux, the agent's package manager: installed from the pinned GitHub build, then its bootstrap unpacked,
     * then socat, which the control panel's player tool and the agent talk to mpv's IPC socket through.
     * Runs on the "popcornclaw-termux" thread {@link #run} starts, never on the service's worker.
     */
    private boolean termux() {
        File cache = ctx.getCacheDir();
        if (!termuxInstalled()) {
            File apk = new File(cache, "termux.apk");
            if (!fetch(TERMUX_APK_URL, TERMUX_APK_SHA256, apk)) return false;
            // pm takes the APK from /data/local/tmp, as in make termux. A never-opened app sits in app standby,
            // and Android rejects all its network traffic: Termux goes on the idle whitelist.
            String tmp = "/data/local/tmp/popcornclaw-termux.apk";
            Result r = root("cp " + apk + " " + tmp + " && chmod 644 " + tmp + " && pm install -r " + tmp
                    + " && dumpsys deviceidle whitelist +" + TERMUX + "\n"
                    + "r=$?; rm -f " + tmp + "; exit $r\n", null, 5 * 60_000);
            Log.i(TAG, "provisioning: Termux install, su exited with " + r.code + ": " + r.out.trim());
            if (r.code != 0) return false;
            if (!apk.delete()) Log.w(TAG, "provisioning: cannot delete " + apk);
        }
        // Both steps below go through run-as, which works only for a debuggable app: a Termux from F-Droid or
        // Play would fail them on every try, each try an su toast on the TV.
        if (!termuxDebuggable()) {
            Log.w(TAG, "provisioning: Termux is not the GitHub build (not debuggable), bootstrap and socat skipped");
            return true;
        }
        if (!termuxPrefix) {
            if (!bootstrap(cache)) return false;
            termuxPrefix = true;
        }
        if (!termuxSocat) {
            if (!socat()) return false;
            termuxSocat = true;
        }
        return true;
    }

    /** Installs socat with Termux's apt, run as the Termux uid: apt refuses root, and root's files break it. */
    private boolean socat() {
        String t = TERMUX_PREFIX;
        Result r = root("run-as " + TERMUX + " /system/bin/env -i HOME=/data/data/" + TERMUX + "/files/home PREFIX=" + t
                + " TMPDIR=" + t + "/tmp PATH=" + t + "/bin:/system/bin LANG=en_US.UTF-8 " + t
                + "/bin/bash -c 'apt-get update && apt-get -y install socat' && [ -x " + t + "/bin/socat ]\n",
                null, 10 * 60_000);
        String out = r.out.trim();
        Log.i(TAG, "provisioning: socat, su exited with " + r.code + ": "
                + (out.length() > 2000 ? "…" + out.substring(out.length() - 2000) : out));
        return r.code == 0;
    }

    /**
     * Unpacks Termux's bootstrap with assets/termux/bootstrap.sh, run as the Termux uid. The boxes have no unzip
     * of their own, so the zip is extracted here; the app's data dir is closed to other uids, so root copies the
     * tree out to a world-readable dir under /data/local/tmp, where run-as reads it (as in make termux).
     */
    private boolean bootstrap(File cache) {
        File zip = new File(cache, TERMUX_BOOTSTRAP_ZIP);
        if (!fetch(TERMUX_BOOTSTRAP_URL, TERMUX_BOOTSTRAP_SHA256, zip)) return false;
        File tree = new File(cache, "termux-bootstrap");
        File script = new File(cache, "termux-bootstrap.sh");
        try {
            deleteTree(tree);
            unzip(zip, tree);
            try (InputStream in = ctx.getAssets().open("termux/bootstrap.sh");
                 OutputStream out = new FileOutputStream(script)) {
                copy(in, out, null);
            }
        } catch (IOException e) {
            Log.w(TAG, "provisioning: cannot extract the Termux bootstrap", e);
            deleteTree(tree);
            return false;
        }
        String s = "/data/local/tmp/popcornclaw-termux-bootstrap";
        Result r = root("rm -rf " + s + " " + s + ".sh\n"
                + "cp -R " + tree + " " + s + " && cp " + script + " " + s + ".sh && chmod -R 755 " + s + " " + s + ".sh"
                + " && run-as " + TERMUX + " sh " + s + ".sh " + s + "\n"
                + "r=$?; rm -rf " + s + " " + s + ".sh; exit $r\n", null, 15 * 60_000);
        deleteTree(tree);
        if (!script.delete()) Log.w(TAG, "provisioning: cannot delete " + script);
        // The second stage configures every package: the end of its output is where a failure shows.
        String out = r.out.trim();
        Log.i(TAG, "provisioning: Termux bootstrap, su exited with " + r.code + ": "
                + (out.length() > 2000 ? "…" + out.substring(out.length() - 2000) : out));
        if (r.code != 0) return false;
        if (!zip.delete()) Log.w(TAG, "provisioning: cannot delete " + zip);
        return true;
    }

    private boolean termuxInstalled() {
        try {
            ctx.getPackageManager().getPackageInfo(TERMUX, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private boolean termuxDebuggable() {
        try {
            return (ctx.getPackageManager().getApplicationInfo(TERMUX, 0).flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Downloads url to dst unless dst already holds it; true once dst matches sha256. */
    private boolean fetch(String url, String sha256, File dst) {
        try {
            if (dst.isFile()) {
                try (InputStream in = new FileInputStream(dst)) {
                    if (sha256.equals(copy(in, null, MessageDigest.getInstance("SHA-256")))) return true;
                }
            }
            File part = new File(dst.getPath() + ".part");
            try (Response resp = http.newCall(new Request.Builder().url(url).build()).execute()) {
                ResponseBody body = resp.body();
                if (!resp.isSuccessful() || body == null) {
                    Log.w(TAG, "provisioning: " + url + ": HTTP " + resp.code());
                    return false;
                }
                String got;
                try (InputStream in = body.byteStream(); OutputStream out = new FileOutputStream(part)) {
                    got = copy(in, out, MessageDigest.getInstance("SHA-256"));
                }
                String bad = !sha256.equals(got) ? "sha256 " + got + ", expected " + sha256
                        : !part.renameTo(dst) ? "cannot rename " + part : null;
                if (bad != null) {
                    Log.w(TAG, "provisioning: " + url + ": " + bad);
                    if (!part.delete()) Log.w(TAG, "provisioning: cannot delete " + part);
                    return false;
                }
            }
            Log.i(TAG, "provisioning: downloaded " + url);
            return true;
        } catch (IOException | NoSuchAlgorithmException e) {
            Log.w(TAG, "provisioning: downloading " + url + " failed", e);
            return false;
        }
    }

    /** Copies in to out (when not null), hashing with md (when not null); returns the hex digest or null. */
    private static String copy(InputStream in, OutputStream out, MessageDigest md) throws IOException {
        byte[] buf = new byte[64 * 1024];
        for (int n; (n = in.read(buf)) != -1; ) {
            if (md != null) md.update(buf, 0, n);
            if (out != null) out.write(buf, 0, n);
        }
        if (md == null) return null;
        StringBuilder hex = new StringBuilder();
        for (byte b : md.digest()) hex.append(String.format(Locale.ROOT, "%02x", b));
        return hex.toString();
    }

    private static void unzip(File zip, File dir) throws IOException {
        try (ZipFile z = new ZipFile(zip)) {
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                File f = new File(dir, entry.getName());
                File d = entry.isDirectory() ? f : f.getParentFile();
                if (!d.isDirectory() && !d.mkdirs()) throw new IOException("cannot create " + d);
                if (entry.isDirectory()) continue;
                try (InputStream in = z.getInputStream(entry); OutputStream out = new FileOutputStream(f)) {
                    copy(in, out, null);
                }
            }
        }
    }

    /** The tree holds plain files and directories only (the zip carries no symlinks). */
    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        if (f.exists() && !f.delete()) Log.w(TAG, "provisioning: cannot delete " + f);
    }

    private byte[] asset(String name) throws IOException {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(in, out, null);
            return out.toByteArray();
        }
    }

    /**
     * Null when the gateway has what it needs to start: the main model's api_base and model in config.json and
     * its key in .security.yml. Otherwise the line for the gateway log. Both files are root's, mode 600.
     */
    static String unconfigured() {
        String sep = "@@popcornclaw-security-yml@@";
        Result r = root("cat " + DIR + "/config.json 2>/dev/null; echo; echo '" + sep + "'; cat " + DIR
                + "/.security.yml 2>/dev/null; exit 0", null, 15_000);
        if (r.code != 0) return "cannot read the config: su exited with " + r.code + "; no root grant for the app?";
        int i = r.out.indexOf(sep);
        String cfg = i < 0 ? r.out : r.out.substring(0, i);
        String yml = i < 0 ? "" : r.out.substring(i + sep.length());
        return unconfigured(cfg, yml);
    }

    /** {@link #unconfigured()} over the text of config.json and .security.yml: null or {@link #NOT_CONFIGURED}. */
    static String unconfigured(String cfg, String yml) {
        boolean model = false;
        try {
            JSONArray list = new JSONObject(cfg).optJSONArray("model_list");
            for (int k = 0; list != null && k < list.length(); k++) {
                JSONObject e = list.optJSONObject(k);
                if (e != null && "main".equals(e.optString("model_name"))) {
                    model = !e.optString("api_base").isEmpty() && !e.optString("model").isEmpty();
                }
            }
        } catch (JSONException e) {
            // A missing or unparsable config.json is not a configured one.
        }
        Matcher m = YML_MAIN.matcher(yml);
        return model && m.find() && !m.group(1).isEmpty() ? null : NOT_CONFIGURED;
    }

    static final class Result {
        final int code;
        final String out;

        Result(int code, String out) {
            this.code = code;
            this.out = out;
        }
    }

    /**
     * Runs script as root through su --mount-master (the box's global mount namespace, as the gateway runs) and
     * returns its exit code and combined output; code -1 when su could not run. stdin, when given, is written
     * first. A watchdog kills su past timeoutMs, which ends the read.
     */
    static Result root(String script, byte[] stdin, long timeoutMs) {
        Process p;
        try {
            p = new ProcessBuilder("su", "--mount-master", "-c", script).redirectErrorStream(true).start();
        } catch (IOException e) {
            return new Result(-1, "su failed: " + e.getMessage());
        }
        final Process proc = p;
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(timeoutMs);
            } catch (InterruptedException e) {
                return;
            }
            proc.destroy();
        }, "popcornclaw-provision-wd");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            try (OutputStream in = p.getOutputStream()) {
                if (stdin != null) in.write(stdin);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(p.getInputStream(), out, null);
            int code = p.waitFor();
            return new Result(code, new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            p.destroy();
            return new Result(-1, "su failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroy();
            return new Result(-1, "interrupted");
        } finally {
            watchdog.interrupt();
        }
    }
}
