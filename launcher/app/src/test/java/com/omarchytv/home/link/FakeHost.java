package com.omarchytv.home.link;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** A JVM stand-in for the launcher (MainActivity + LauncherConfig) behind {@link LinkServer.Host}. */
class FakeHost implements LinkServer.Host {
    final File dir, config;
    volatile String code = "123456";
    volatile boolean pairingOpen = true;
    volatile boolean allowLoopback = true;
    volatile boolean back;   // accessibility service "enabled"
    String fgPackage = "com.omarchytv.home", fgLabel = "OmarchyTV";
    boolean homeVisible = true, screensaver;
    final List<String> events = new ArrayList<>();
    final List<String> keys = new ArrayList<>();
    LinkServer server;
    int codes;

    FakeHost(File dir) {
        this.dir = dir;
        dir.mkdirs();
        config = new File(dir, "omarchytv.json");
    }

    String[][] apps = {{"com.netflix.ninja", "Netflix"}, {"com.amazon.amazonvideo.livingroom", "Prime Video"},
            {"com.google.android.youtube.tv", "YouTube"}, {"com.disney.disneyplus", "Disney+"}};

    @Override public String tvName() { return "Harness BRAVIA"; }
    @Override public boolean pairingOpen() { return pairingOpen; }
    @Override public String pairingCode() { return code; }

    @Override public synchronized void newPairingCode() {
        codes++;
        code = String.format("%06d", new java.security.SecureRandom().nextInt(1_000_000));
    }

    @Override public boolean allowPeer(InetAddress peer) { return allowLoopback && peer.isLoopbackAddress(); }

    @Override public synchronized JSONObject status() {
        try {
            JSONArray caps = new JSONArray();
            for (String c : new String[]{"theme", "wallpaper", "widgets", "ai", "apps", "launch", "events", "key:home", "key:play_pause",
                    "key:play", "key:pause", "key:stop", "key:next", "key:previous", "key:rewind", "key:fast_forward",
                    "key:volume_up", "key:volume_down", "key:mute"}) caps.put(c);
            if (back) caps.put("key:back");
            JSONObject link = readConfig().optJSONObject("link");
            if (link != null && link.optJSONArray("aiProviders") != null && link.optJSONArray("aiProviders").length() > 0) caps.put("ai-ask");
            return new JSONObject().put("tvId", server.store().tvId()).put("name", tvName()).put("protocol", 1).put("launcherVersion", "harness")
                    .put("foreground", new JSONObject().put("package", fgPackage).put("label", fgLabel).put("source", "launcher"))
                    .put("homeVisible", homeVisible).put("screensaver", screensaver).put("screenOn", true)
                    .put("time", System.currentTimeMillis() / 1000).put("capabilities", caps);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override public JSONArray apps() {
        JSONArray a = new JSONArray();
        try {
            for (int i = 0; i < apps.length; i++) a.put(new JSONObject().put("package", apps[i][0]).put("label", apps[i][1]).put("dock", true));
        } catch (Exception ignored) {
        }
        return a;
    }

    @Override public boolean launch(String pkg) {
        for (String[] a : apps) {
            if (a[0].equals(pkg)) {
                synchronized (this) {
                    fgPackage = a[0];
                    fgLabel = a[1];
                    homeVisible = false;
                    screensaver = false;
                }
                server.broadcast("status", server.status());
                return true;
            }
        }
        return false;
    }

    @Override public int key(String key) {
        keys.add(key);
        if ("home".equals(key)) {
            synchronized (this) {
                fgPackage = "com.omarchytv.home";
                fgLabel = "OmarchyTV";
                homeVisible = true;
                screensaver = false;
            }
            server.broadcast("status", server.status());
            return 200;
        }
        if ("back".equals(key)) return back ? 200 : 409;
        if (key.matches("play_pause|play|pause|stop|next|previous|rewind|fast_forward|volume_up|volume_down|mute")) return 200;
        return 409;
    }

    synchronized JSONObject readConfig() {
        try {
            return config.exists() ? new JSONObject(new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8)) : new JSONObject();
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    @Override public synchronized void editConfig(LinkServer.ConfigEdit edit) throws Exception {
        JSONObject j = readConfig();
        if (!j.has("version")) j.put("version", 1);
        edit.edit(j);
        File tmp = new File(dir, "omarchytv.json.tmp");
        Files.write(tmp.toPath(), j.toString(2).getBytes(StandardCharsets.UTF_8));
        if (!tmp.renameTo(config)) throw new java.io.IOException("rename");
    }

    @Override public File wallpaperDir() { return new File(dir, "link"); }

    @Override public int[] jpegSize(byte[] jpeg) {
        // SOFn marker scan (the Android side uses BitmapFactory bounds decoding instead)
        int i = 2;
        while (i + 9 < jpeg.length) {
            if ((jpeg[i] & 0xFF) != 0xFF) return null;
            int m = jpeg[i + 1] & 0xFF;
            int len = ((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF);
            if (m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) {
                int h = ((jpeg[i + 5] & 0xFF) << 8) | (jpeg[i + 6] & 0xFF), w = ((jpeg[i + 7] & 0xFF) << 8) | (jpeg[i + 8] & 0xFF);
                return new int[]{w, h};
            }
            i += 2 + len;
        }
        return null;
    }

    @Override public void onPcsChanged(String pairedName) {
        try {
            JSONArray pcs = server.store().publicPcs();
            editConfig(j -> {
                JSONObject link = j.optJSONObject("link");
                if (link == null) j.put("link", link = new JSONObject());
                link.put("pcs", pcs);
            });
        } catch (Exception ignored) {
        }
        if (pairedName != null) log("paired " + pairedName);
    }

    @Override public synchronized void onEvent(String pcId, String type, JSONObject data) {
        events.add(type + " " + data);
        notifyAll();
        System.out.println("EVENT " + type + " " + data);
        System.out.flush();
    }

    @Override public void log(String message) {
        System.err.println("[link] " + message);
    }
}
