package com.omarchytv.home.link;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** The server end to end over loopback, with a tiny Java PC client. */
public class LinkServerTest {

    File dir;
    FakeHost host;
    LinkServer server;
    int port;
    String pcId = "abcdef0123456789";
    byte[] token;
    String tvId;

    @Before
    public void setUp() throws Exception {
        dir = Files.createTempDirectory("otv-link-test").toFile();
        host = new FakeHost(dir);
        server = new LinkServer(host, new LinkStore(new File(dir, "link.json")));
        host.server = server;
        port = server.start(InetAddress.getLoopbackAddress(), 0);
    }

    @After
    public void tearDown() {
        server.shutdown();
    }

    static final class Resp {
        int status;
        Map<String, String> headers = new HashMap<>();
        byte[] body;

        JSONObject json() throws Exception {
            return new JSONObject(new String(body, StandardCharsets.UTF_8));
        }
    }

    Resp http(String method, String target, byte[] body, Map<String, String> headers) throws Exception {
        try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
            s.setSoTimeout(5000);
            StringBuilder h = new StringBuilder(method + " " + target + " HTTP/1.1\r\nHost: tv\r\nConnection: close\r\n");
            if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) h.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            h.append("Content-Length: ").append(body.length).append("\r\n\r\n");
            OutputStream out = s.getOutputStream();
            out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) all.write(buf, 0, n);
            byte[] raw = all.toByteArray();
            String text = new String(raw, StandardCharsets.ISO_8859_1);
            int end = text.indexOf("\r\n\r\n");
            Resp r = new Resp();
            String[] lines = text.substring(0, end).split("\r\n");
            r.status = Integer.parseInt(lines[0].split(" ")[1]);
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                r.headers.put(lines[i].substring(0, c).toLowerCase(Locale.ROOT), lines[i].substring(c + 1).trim());
            }
            r.body = java.util.Arrays.copyOfRange(raw, end + 4, raw.length);
            return r;
        }
    }

    Resp post(String target, String json) throws Exception {
        return http("POST", target, json.getBytes(StandardCharsets.UTF_8), null);
    }

    void pair(String code) throws Exception {
        LinkCrypto.PcPairing a = new LinkCrypto.PcPairing(code, pcId, null);
        Resp start = post("/v1/pair/start", new JSONObject().put("protocol", 1).put("pcId", pcId).put("pcName", "omarchy-box")
                .put("t", LinkCrypto.b64u(a.message())).toString());
        assertEquals(200, start.status);
        JSONObject s = start.json();
        tvId = s.getString("tvId");
        LinkCrypto.Keys k = a.finish(tvId, LinkCrypto.unb64u(s.getString("s")));
        // pair/start and pair/confirm have separate 1 req / 2 s buckets so confirm succeeds immediately
        Resp done = post("/v1/pair/confirm", new JSONObject().put("pairingId", s.getString("pairingId"))
                .put("confirm", LinkCrypto.b64u(k.confirmPc)).toString());
        if (done.status == 200) {
            assertTrue(LinkCrypto.equal(LinkCrypto.unb64u(done.json().getString("confirm")), k.confirmTv));
            token = k.token;
        } else {
            throw new AssertionError("confirm " + done.status + " " + new String(done.body, StandardCharsets.UTF_8));
        }
    }

    Resp signed(String method, String target, String body, String nonce, long ts) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String sig = LinkCrypto.signRequest(token, method, target, String.valueOf(ts), nonce, b);
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "OTV1 pc=" + pcId + ", ts=" + ts + ", nonce=" + nonce + ", sig=" + sig);
        return http(method, target, b, h);
    }

    Resp signed(String method, String target, String body) throws Exception {
        return signed(method, target, body, LinkCrypto.b64u(LinkCrypto.randomBytes(16)), System.currentTimeMillis() / 1000);
    }

    void assertSigned(Resp r, String nonce) {
        assertEquals(r.headers.get("x-otv-sig"), LinkCrypto.signResponse(token, nonce, r.status, r.body));
    }

    @Test
    public void infoAndNoUnauthenticatedEndpoints() throws Exception {
        Resp info = http("GET", "/v1/info", new byte[0], null);
        assertEquals(200, info.status);
        assertEquals(5, info.json().length());
        assertEquals("no-store", info.headers.get("cache-control"));
        assertNotNull(info.headers.get("x-otv-time"));
        for (String[] e : new String[][]{{"GET", "/v1/status"}, {"POST", "/v1/remote/key"}, {"PUT", "/v1/ai"}, {"GET", "/v1/nope"}}) {
            assertEquals(e[1], 401, http(e[0], e[1], "{}".getBytes(), null).status);
        }
    }

    @Test
    public void pairingSignedRequestsReplaySkew() throws Exception {
        pair("123456");
        assertEquals(1, host.codes);   // a code pairs one PC
        JSONObject cfg = host.readConfig();
        assertEquals("omarchy-box", cfg.getJSONObject("link").getJSONArray("pcs").getJSONObject(0).getString("name"));
        String text = cfg.toString();
        assertFalse(text.contains(LinkCrypto.b64u(token)));
        assertFalse(text.contains(java.util.Base64.getEncoder().encodeToString(token)));

        String nonce = LinkCrypto.b64u(LinkCrypto.randomBytes(16));
        long ts = System.currentTimeMillis() / 1000;
        Resp r = signed("POST", "/v1/remote/key", "{\"key\":\"home\"}", nonce, ts);
        assertEquals(200, r.status);
        assertSigned(r, nonce);
        assertEquals("replay", signed("POST", "/v1/remote/key", "{\"key\":\"home\"}", nonce, ts).json().getString("error"));
        assertEquals("clock_skew", signed("GET", "/v1/status", "", "n1", ts - 600).json().getString("error"));
        // tampered body: signature over another body
        String sig = LinkCrypto.signRequest(token, "POST", "/v1/remote/key", String.valueOf(ts), "n2", "{\"key\":\"home\"}".getBytes());
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "OTV1 pc=" + pcId + ", ts=" + ts + ", nonce=n2, sig=" + sig);
        assertEquals(401, http("POST", "/v1/remote/key", "{\"key\":\"mute\"}".getBytes(), h).status);

        Resp bad = signed("POST", "/v1/remote/key", "{\"key\":\"reboot\"}");
        assertEquals(400, bad.status);
        assertNotNull(bad.headers.get("x-otv-sig"));
        assertEquals(409, signed("POST", "/v1/remote/key", "{\"key\":\"back\"}").status);
        assertEquals(404, signed("GET", "/v1/nope", "").status);
        assertEquals(200, signed("GET", "/v1/status", "").status);
    }

    @Test
    public void wrongCodeRotatesAfterFive() throws Exception {
        for (int i = 0; i < 5; i++) {
            LinkCrypto.PcPairing a = new LinkCrypto.PcPairing("000000", pcId, null);
            Resp start = post("/v1/pair/start", new JSONObject().put("pcId", pcId).put("t", LinkCrypto.b64u(a.message())).toString());
            assertEquals(200, start.status);
            Thread.sleep(2050);
            JSONObject s = start.json();
            LinkCrypto.Keys k = a.finish(s.getString("tvId"), LinkCrypto.unb64u(s.getString("s")));
            Resp done = post("/v1/pair/confirm", new JSONObject().put("pairingId", s.getString("pairingId"))
                    .put("confirm", LinkCrypto.b64u(k.confirmPc)).toString());
            assertEquals(401, done.status);
            assertEquals(4 - i, done.json().getInt("attemptsLeft"));
            Thread.sleep(2050);
        }
        assertEquals(1, host.codes);
        host.pairingOpen = false;
        assertEquals("pairing_closed", post("/v1/pair/start", "{}").json().getString("error"));
    }

    @Test
    public void widgetPaneOptionalAt() throws Exception {
        pair("123456");
        long now = System.currentTimeMillis() / 1000;
        String body = "{\"panes\":[{\"id\":\"cpu\",\"title\":\"cpu\",\"at\":" + (now - 30) + ",\"lines\":[{\"label\":\"load\",\"value\":\"1\"}]},"
                + "{\"id\":\"old\",\"lines\":[]},{\"id\":\"future\",\"at\":" + (now + 86_400) + "}],\"shortcuts\":[]}";
        assertEquals(200, signed("PUT", "/v1/widgets", body).status);
        org.json.JSONArray p = host.readConfig().getJSONObject("link").getJSONArray("panes");
        assertEquals(now - 30, p.getJSONObject(0).getLong("at"));
        assertFalse(p.getJSONObject(1).has("at"));   // backward compatible: no "at" = widgetsAt
        assertFalse(p.getJSONObject(2).has("at"));   // a clock far in the future is ignored
    }

    @Test
    public void cleanPanesKeepsKnownTonesOnly() throws Exception {
        org.json.JSONArray in = new org.json.JSONArray("[{\"id\":\"rates\",\"lines\":[{\"label\":\"US 10Y\",\"value\":\"4.99%\",\"tone\":\"up\"},"
                + "{\"label\":\"WTI\",\"value\":\"$100\",\"tone\":\"down\"},{\"label\":\"x\",\"value\":\"y\",\"tone\":\"<b>\"}]}]");
        String[] why = new String[2];
        org.json.JSONArray l = LinkServer.cleanPanes(in, System.currentTimeMillis() / 1000, why).getJSONObject(0).getJSONArray("lines");
        assertEquals("up", l.getJSONObject(0).getString("tone"));
        assertEquals("down", l.getJSONObject(1).getString("tone"));
        assertFalse(l.getJSONObject(2).has("tone"));
        assertEquals(null, LinkServer.cleanPanes(new org.json.JSONArray("[{\"id\":\"Bad Id\"}]"), 0, why));
        assertEquals("bad_widgets", why[0]);
    }

    @Test
    public void customBanners() throws Exception {
        pair("123456");
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0};
        assertEquals(200, signedBytes("PUT", "/v1/banners/com.netflix.ninja", png, "image/png").status);
        java.io.File f = new java.io.File(host.wallpaperDir().getParentFile(), "banners/com.netflix.ninja.png");
        assertTrue(f.isFile());
        assertEquals("com.netflix.ninja", signed("GET", "/v1/banners", "").json().getJSONArray("banners").getString(0));
        assertEquals(415, signedBytes("PUT", "/v1/banners/com.netflix.ninja", "nope".getBytes(), "image/png").status);
        assertEquals(404, signed("PUT", "/v1/banners/..evil", "").status);   // not a package name: no route
        assertEquals(200, signed("DELETE", "/v1/banners/com.netflix.ninja", "").status);
        assertFalse(f.exists());
        assertEquals(404, signed("DELETE", "/v1/banners/com.netflix.ninja", "").status);
        assertTrue(host.events.stream().anyMatch(e -> e.startsWith("banners ")));
    }

    @Test
    public void notLanIsRefused() throws Exception {
        host.allowLoopback = false;
        Resp r = http("GET", "/v1/info", new byte[0], null);
        assertEquals(403, r.status);
        assertEquals("not_lan", r.json().getString("error"));
    }

    @Test
    public void aiNullsAndSources() throws Exception {
        pair("123456");
        server.limits = false;   // three PUT /v1/ai in a row (1 per 5 s otherwise)
        String body = "{\"ai\":[{\"name\":\"Claude 5h\",\"used\":null,\"limit\":null,\"unit\":\"%\"}],"
                + "\"aiSources\":[{\"id\":\"omarchy-agents\",\"name\":\"Omarchy Agents panel\",\"enabled\":true,\"available\":true},"
                + "{\"id\":\"omacash\",\"name\":\"Omacash\",\"enabled\":false}]}";
        assertEquals(200, signed("PUT", "/v1/ai", body).status);
        JSONObject cfg = host.readConfig();
        assertTrue(cfg.getJSONArray("ai").getJSONObject(0).isNull("used"));   // verbatim: the TV shows "?"
        assertEquals("omacash", cfg.getJSONArray("aiSources").getJSONObject(1).getString("id"));
        assertFalse(cfg.getJSONArray("aiSources").getJSONObject(1).getBoolean("enabled"));
        // a bad source id is refused; without aiSources the list (and the TV switches) go away
        assertEquals(400, signed("PUT", "/v1/ai", "{\"ai\":[],\"aiSources\":[{\"id\":\"rm -rf\"}]}").status);
        assertEquals(200, signed("PUT", "/v1/ai", "{\"ai\":[]}").status);
        assertFalse(host.readConfig().has("aiSources"));
        assertEquals("not_connected", server.aiSources(new JSONObject().put("id", "x").put("enabled", new org.json.JSONArray())));
    }

    static int threads(String name) {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) if (t.isAlive() && t.getName().equals(name)) n++;
        return n;
    }

    /**
     * PC off / never there: the server is passive. Binding is quick, nothing is scheduled (no ping timer, no
     * pool thread), and plain HTTP traffic does not start a timer either: only an open event socket does.
     */
    @Test
    public void idleWithoutPcIsPassive() throws Exception {
        long t0 = System.nanoTime();
        LinkServer s2 = new LinkServer(host, new LinkStore(new File(dir, "idle.json")));
        s2.start(java.net.InetAddress.getLoopbackAddress(), 0);
        long startMs = (System.nanoTime() - t0) / 1_000_000;
        try {
            System.out.println("BENCH LinkServer construct+bind (no PC): " + startMs + " ms");
            assertTrue("bind took " + startMs + " ms", startMs < 250);
            Thread.sleep(300);
            assertTrue(s2.idle());
            assertEquals(0, threads("otv-link-ping"));
            assertEquals("not_connected", s2.ask(new JSONObject().put("id", "q").put("text", "hi")));   // instant
            pair("123456");                       // HTTP only on the other server
            assertTrue(server.idle());
            assertEquals(0, threads("otv-link-ping"));
        } finally {
            s2.shutdown();
        }
    }

    @Test
    public void bodyLimit() throws Exception {
        assertEquals(413, http("PUT", "/v1/ai", new byte[64 * 1024 + 1], null).status);
    }

    // ---- signed binary uploads (banners, themes)

    Resp signedBytes(String method, String target, byte[] b, String contentType) throws Exception {
        String nonce = LinkCrypto.b64u(LinkCrypto.randomBytes(16));
        long ts = System.currentTimeMillis() / 1000;
        String sig = LinkCrypto.signRequest(token, method, target, String.valueOf(ts), nonce, b);
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "OTV1 pc=" + pcId + ", ts=" + ts + ", nonce=" + nonce + ", sig=" + sig);
        h.put("Content-Type", contentType);
        return http(method, target, b, h);
    }

    @Test
    public void themePushSetsSanitisedPrompt() throws Exception {
        pair("123456");
        JSONObject t = new JSONObject().put("name", "tokyo-night").put("accent", "#7aa2f7").put("accent2", "#bb9af7")
                .put("prompt", new JSONObject().put("user", "Jane Doe!").put("host", "omarchy-box.local.lan.example"));
        assertEquals(200, signed("PUT", "/v1/theme", t.toString()).status);
        JSONObject p = host.readConfig().getJSONObject("prompt");
        assertEquals("janedoe", p.getString("user"));
        assertEquals("omarchy-box.loca", p.getString("host"));   // 16 characters at most
    }

    @Test
    public void agentsPutIsBoundedPlainTextAndServed() throws Exception {
        pair("123456");
        assertTrue(server.status().getJSONArray("capabilities").toString().contains("\"agents\""));
        org.json.JSONArray many = new org.json.JSONArray();
        for (int i = 0; i < 9; i++) many.put(new JSONObject().put("id", "a" + i));
        assertEquals(400, signed("PUT", "/v1/agents", new JSONObject().put("agents", many).toString()).status);
        StringBuilder longStep = new StringBuilder();
        for (int i = 0; i < 100; i++) longStep.append('x');
        org.json.JSONArray steps = new org.json.JSONArray();
        for (int i = 0; i < 10; i++) steps.put(new JSONObject().put("text", "s" + i).put("status", i == 9 ? "weird" : "ok"));
        JSONObject a = new JSONObject().put("id", "claude-code:7d0c6a2e-111").put("agent", "claude-code").put("agentName", "Claude\u0007")
                .put("project", "shop").put("status", "nonsense").put("step", longStep.toString()).put("question", "<b>ok?</b>")
                .put("since", "2026-10-19T08:11:58Z").put("elapsed", 12).put("needsInput", true).put("canAsk", true)
                .put("cwd", "/home/someone").put("steps", steps);
        JSONObject body = new JSONObject().put("at", "2026-10-19T08:12:00Z").put("enabled", true)
                .put("agents", new org.json.JSONArray().put(a).put(new JSONObject().put("agentName", "no id")));
        assertEquals(200, signed("PUT", "/v1/agents", body.toString()).status);
        JSONObject stored = host.readConfig().getJSONObject("link").getJSONObject("agents");
        assertTrue(host.readConfig().getJSONObject("link").getLong("agentsAt") > 0);
        assertEquals(1, stored.getJSONArray("agents").length());   // the entry without an id is dropped
        JSONObject g = stored.getJSONArray("agents").getJSONObject(0);
        assertEquals("Claude", g.getString("agentName"));
        assertEquals("idle", g.getString("status"));
        assertEquals(72, g.getString("step").length());
        assertEquals("<b>ok?</b>", g.getString("question"));   // kept as plain text, never rendered as HTML
        assertFalse(g.has("cwd"));
        assertEquals(8, g.getJSONArray("steps").length());
        assertEquals("s2", g.getJSONArray("steps").getJSONObject(0).getString("text"));
        assertEquals("ok", g.getJSONArray("steps").getJSONObject(7).getString("status"));
        assertEquals("claude-code:7d0c6a2e-111", new JSONObject(new String(signed("GET", "/v1/agents", "").body, StandardCharsets.UTF_8))
                .getJSONArray("agents").getJSONObject(0).getString("id"));
        assertTrue(host.events.stream().anyMatch(e -> e.startsWith("agent_activity ")));
    }

    @Test
    public void agentAskRefusesLocally() throws Exception {
        assertEquals("bad_request", server.agentAsk("r1", "claude-code:1", "   "));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 2001; i++) big.append('a');
        assertEquals("bad_request", server.agentAsk("r1", "claude-code:1", big.toString()));
        assertEquals("not_connected", server.agentAsk("r1", "claude-code:1", "hi"));
    }

    @Test
    public void agentTimersAndTimes() {
        assertEquals(1760861518000L, Agents.isoMs("2025-10-19T08:11:58Z"));
        assertEquals(1760861518250L, Agents.isoMs("2025-10-19T08:11:58.25Z"));
        assertEquals(-1, Agents.isoMs("yesterday"));
        assertEquals("42s", Agents.span(42));
        assertEquals("2m", Agents.span(150));
        assertEquals("3h", Agents.span(3 * 3600 + 5));
        Agents a = new Agents();
        a.rx = 1_000_000;
        Agents.Agent run = new Agents.Agent();
        run.status = "running";
        run.elapsed = 10;
        Agents.Agent done = new Agents.Agent();
        done.status = "done";
        done.elapsed = 10;
        assertEquals(15, a.elapsedNow(run, 1_005_000));
        assertEquals(10, a.elapsedNow(done, 1_005_000));
    }

    @Test
    public void mcpInitializeListAndCall() throws Exception {
        pair("123456");
        server.limits = false;
        assertTrue(server.status().getJSONArray("capabilities").toString().contains("\"mcp\""));

        // 1. Unauthenticated / wrong Bearer token -> 401
        Map<String, String> badH = new HashMap<>();
        badH.put("Authorization", "Bearer wrongtoken");
        assertEquals(401, http("POST", "/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}".getBytes(StandardCharsets.UTF_8), badH).status);

        // 2. Bearer token authentication on /mcp
        Map<String, String> bearerH = new HashMap<>();
        bearerH.put("Authorization", "Bearer " + LinkCrypto.b64u(token));
        Resp init = http("POST", "/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}".getBytes(StandardCharsets.UTF_8), bearerH);
        assertEquals(200, init.status);
        assertEquals("omarchytv-mcp", init.json().getJSONObject("result").getJSONObject("serverInfo").getString("name"));

        // 3. tools/list via Bearer
        Resp list = http("POST", "/mcp", "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}".getBytes(StandardCharsets.UTF_8), bearerH);
        assertEquals(200, list.status);
        assertTrue(list.json().getJSONObject("result").getJSONArray("tools").length() >= 8);

        // 4. tools/call tv_set_theme via OTV1 signed request on /v1/mcp
        JSONObject callReq = new JSONObject()
                .put("jsonrpc", "2.0").put("id", 3).put("method", "tools/call")
                .put("params", new JSONObject().put("name", "tv_set_theme").put("arguments", new JSONObject().put("name", "jade-horse")));
        Resp call = signed("POST", "/v1/mcp", callReq.toString());
        assertEquals(200, call.status);
        assertFalse(call.json().getJSONObject("result").getBoolean("isError"));
        assertEquals("jade-horse", host.readConfig().getJSONObject("theme").getString("name"));
    }
}
