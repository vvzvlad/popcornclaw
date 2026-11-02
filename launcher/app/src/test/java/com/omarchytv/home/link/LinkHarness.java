package com.omarchytv.home.link;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.InetAddress;

/**
 * Runs the real {@link LinkServer} on the JVM (loopback, fake launcher) so the Omarchy Link PC side can be
 * pointed at it: tools/link-e2e/pc_vs_tv.py. Not part of the APK.
 *
 *   java -cp … com.omarchytv.home.link.LinkHarness FILESDIR PORT CODE [SECRETS_FILE]   (OTV_LIMITS=0: no rate limits)
 * stdin: "code" | "pairing on|off" | "launch PKG" | "screensaver on|off" | "back on|off" | "forget" | "ask [PROVIDER] TEXT"
 */
public final class LinkHarness {
    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        FakeHost host = new FakeHost(dir);
        host.code = args.length > 2 ? args[2] : "123456";
        File secrets = args.length > 3 ? new File(args[3]) : new File(dir, "link.json");
        secrets.getParentFile().mkdirs();
        host.castDir = new File(secrets.getParentFile(), "cast");
        LinkServer server = new LinkServer(host, new LinkStore(secrets));
        host.server = server;
        server.limits = !"0".equals(System.getenv("OTV_LIMITS"));
        File reqLog = new File(dir, "requests.log");
        server.requestLog = l -> {
            synchronized (reqLog) {
                try (java.io.FileWriter w = new java.io.FileWriter(reqLog, true)) {
                    w.write(l + "\n");
                } catch (java.io.IOException ignored) {
                }
            }
        };
        int port = server.start(InetAddress.getLoopbackAddress(), Integer.parseInt(args[1]));
        System.out.println("READY " + port + " " + host.code + " " + server.store().tvId());
        System.out.flush();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        String line;
        int n = 0;
        while ((line = in.readLine()) != null) {
            String[] p = line.trim().split(" ", 3);
            String rest = line.trim().indexOf(' ') > 0 ? line.trim().substring(line.trim().indexOf(' ') + 1) : "";
            String reply = "OK";
            switch (p[0]) {
                case "code": reply = host.code; break;
                case "pairing": host.pairingOpen = "on".equals(p[1]); break;
                case "launch": reply = String.valueOf(host.launch(p[1])); break;
                case "back": host.back = "on".equals(p[1]); break;
                case "screensaver":
                    synchronized (host) {
                        host.screensaver = "on".equals(p[1]);
                        host.homeVisible = false;
                    }
                    server.broadcast("status", server.status());
                    break;
                case "forget":
                    for (LinkStore.Pc pc : server.store().list()) server.forget(pc.id);
                    break;
                case "clients": reply = String.valueOf(server.connectedClients()); break;
                case "shortcut": server.shortcut(rest); break;
                case "rawframe": server.debugRawFrame(rest); break;
                case "idle": reply = String.valueOf(server.idle()); break;
                case "status": reply = server.status().toString(); break;
                case "cast": host.castOn = "on".equals(p[1]); break;
                case "castmax": server.castUploadMax = Long.parseLong(p[1]); break;
                case "castplay": host.castPlay = "on".equals(p[1]); break;
                case "castlive": host.castLiveBytes = Integer.parseInt(p[1]); break;
                case "castkinds": host.castKinds = new java.util.ArrayList<>(java.util.Arrays.asList(p[1].split(","))); break;
                case "ask": {   // ask PROVIDER|- SESSION|- TEXT
                    String[] q = rest.split(" ", 3);
                    JSONObject d = new JSONObject().put("id", "h" + (++n)).put("text", q.length > 2 ? q[2] : rest).put("lang", "it-IT");
                    if (q.length > 2 && !"-".equals(q[0])) d.put("provider", q[0]);
                    if (q.length > 2 && !"-".equals(q[1])) d.put("session", q[1]);
                    String e = server.ask(d);
                    reply = e == null ? "SENT h" + n : "ASKERR " + e;
                    break;
                }
                case "aisources": {   // aisources id1,id2 (or "-" for none)
                    JSONArray en = new JSONArray();
                    if (!"-".equals(rest) && !rest.isEmpty()) for (String id : rest.split(",")) en.put(id);
                    JSONObject d = new JSONObject().put("id", "s" + (++n));
                    d.put("enabled", rest.startsWith("raw:") ? (Object) rest.substring(4) : en);
                    String e = server.aiSources(d);
                    reply = e == null ? "SENT s" + n : "SRCERR " + e;
                    break;
                }
                case "agentask": {   // agentask ID AGENTID [TEXT]
                    String[] q = rest.split(" ", 3);
                    String e = server.agentAsk(q[0], q.length > 1 ? q[1] : "", q.length > 2 ? q[2] : "");
                    reply = e == null ? "SENT " + q[0] : "AGENTERR " + e;
                    break;
                }
                case "agentsnap": server.agentSnapshot(); break;
                case "agents": reply = server.agents().toString(); break;
                default: reply = "?";
            }
            System.out.println(reply);
            System.out.flush();
        }
        server.shutdown();
    }
}
