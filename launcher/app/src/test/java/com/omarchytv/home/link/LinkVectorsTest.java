package com.omarchytv.home.link;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Byte-exact checks against omarchy-link/docs/vectors.json (copied into test resources). */
public class LinkVectorsTest {

    static JSONObject vectors() throws Exception {
        try (InputStream in = LinkVectorsTest.class.getClassLoader().getResourceAsStream("omarchy-link-vectors.json")) {
            assertNotNull("vectors resource", in);
            return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    public void groupConstants() throws Exception {
        JSONObject g = vectors().getJSONObject("group");
        assertEquals(new BigInteger(g.getString("p"), 16), LinkCrypto.P);
        assertEquals(g.getInt("g"), LinkCrypto.G.intValue());
        assertEquals(new BigInteger(g.getString("M"), 16), LinkCrypto.M);
        assertEquals(new BigInteger(g.getString("N"), 16), LinkCrypto.N);
        assertEquals(BigInteger.ONE, LinkCrypto.M.modPow(LinkCrypto.Q, LinkCrypto.P));
    }

    @Test
    public void pairing() throws Exception {
        JSONObject v = vectors().getJSONObject("pairing");
        assertEquals(new BigInteger(v.getString("w"), 16), LinkCrypto.codeScalar(v.getString("code")));
        LinkCrypto.PcPairing pc = new LinkCrypto.PcPairing(v.getString("code"), v.getString("pcId"), new BigInteger(v.getString("x"), 16));
        assertEquals(v.getString("T"), LinkCrypto.b64u(pc.message()));
        LinkCrypto.TvPairing tv = new LinkCrypto.TvPairing(v.getString("code"), v.getString("tvId"), v.getString("pcId"),
                LinkCrypto.unb64u(v.getString("T")), new BigInteger(v.getString("y"), 16));
        assertEquals(v.getString("S"), LinkCrypto.b64u(LinkCrypto.elementBytes(tv.s)));
        assertEquals(v.getString("ttHash"), LinkCrypto.hex(tv.keys.ttHash));
        assertEquals(v.getString("confirmPc"), LinkCrypto.b64u(tv.keys.confirmPc));
        assertEquals(v.getString("confirmTv"), LinkCrypto.b64u(tv.keys.confirmTv));
        assertEquals(v.getString("token"), LinkCrypto.b64u(tv.keys.token));
        LinkCrypto.Keys k = pc.finish(v.getString("tvId"), LinkCrypto.unb64u(v.getString("S")));
        assertArrayEquals(tv.keys.token, k.token);
        assertArrayEquals(tv.keys.confirmTv, k.confirmTv);
        // a wrong code gives different keys
        LinkCrypto.TvPairing wrong = new LinkCrypto.TvPairing("654321", v.getString("tvId"), v.getString("pcId"),
                LinkCrypto.unb64u(v.getString("T")), new BigInteger(v.getString("y"), 16));
        if (LinkCrypto.equal(wrong.keys.confirmPc, tv.keys.confirmPc)) fail("wrong code must not confirm");
    }

    @Test
    public void elementChecks() {
        BigInteger p = LinkCrypto.P;
        for (BigInteger bad : new BigInteger[]{BigInteger.ZERO, BigInteger.ONE, p.subtract(BigInteger.ONE), p.subtract(BigInteger.valueOf(2))}) {
            try {
                LinkCrypto.parseElement(LinkCrypto.elementBytes(bad));
                fail("accepted " + bad.toString(16));
            } catch (IllegalArgumentException expected) {
            }
        }
        try {
            LinkCrypto.parseElement(new byte[255]);
            fail("accepted 255 bytes");
        } catch (IllegalArgumentException expected) {
        }
        for (String code : new String[]{"12345", "1234567", "12a456", " 123456"}) {
            try {
                LinkCrypto.codeScalar(code);
                fail("accepted code " + code);
            } catch (IllegalArgumentException expected) {
            }
        }
    }

    @Test
    public void requestAndResponse() throws Exception {
        JSONObject all = vectors();
        JSONObject r = all.getJSONObject("request");
        byte[] token = LinkCrypto.unb64u(all.getJSONObject("pairing").getString("token"));
        Map<String, String> f = LinkServer.parseAuthorization(r.getString("authorization"));
        assertNotNull(f);
        assertEquals(all.getJSONObject("pairing").getString("pcId"), f.get("pc"));
        assertEquals(String.valueOf(r.getLong("ts")), f.get("ts"));
        assertEquals(r.getString("nonce"), f.get("nonce"));
        byte[] body = r.getString("body").getBytes(StandardCharsets.UTF_8);
        assertEquals(f.get("sig"), LinkCrypto.signRequest(token, r.getString("method"), r.getString("target"),
                f.get("ts"), f.get("nonce"), body));
        assertEquals(r.getString("xOtvSig"), LinkCrypto.signResponse(token, r.getString("nonce"), r.getInt("responseStatus"),
                r.getString("responseBody").getBytes(StandardCharsets.UTF_8)));
        assertNull(LinkServer.parseAuthorization("Bearer x"));
        assertNull(LinkServer.parseAuthorization("OTV1 pc=a, ts=1, nonce=n"));
    }

    @Test
    public void websocket() throws Exception {
        JSONObject all = vectors();
        JSONObject w = all.getJSONObject("websocket");
        byte[] token = LinkCrypto.unb64u(all.getJSONObject("pairing").getString("token"));
        byte[] ks = LinkCrypto.wsSessionKey(token, w.getString("clientNonce"), w.getString("serverNonce"));
        assertEquals(w.getString("sessionKey"), LinkCrypto.hex(ks));
        String frame = w.getString("frame");
        String payload = frame.substring(frame.indexOf(' ') + 1);
        assertEquals(frame, LinkCrypto.seal(ks, payload));
        assertEquals(payload, LinkCrypto.open(ks, frame));
        assertNull(LinkCrypto.open(ks, frame.replace("hello", "hellO")));
        assertNull(LinkCrypto.open(new byte[32], frame));
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", LinkServer.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="));   // RFC 6455 §1.3
    }

    @Test
    public void hkdfRfc5869Case1() {
        byte[] ikm = new byte[22];
        java.util.Arrays.fill(ikm, (byte) 0x0b);
        byte[] salt = new byte[13], info = new byte[10];
        for (int i = 0; i < 13; i++) salt[i] = (byte) i;
        for (int i = 0; i < 10; i++) info[i] = (byte) (0xf0 + i);
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
                LinkCrypto.hex(LinkCrypto.hkdf(ikm, salt, info, 42)));
    }

    @Test
    public void widgetActionsAreFiltered() throws Exception {
        assertNotNull(LinkServer.cleanAction(new JSONObject("{\"type\":\"view\",\"uri\":\"https://youtu.be/x\",\"package\":\"com.google.android.youtube.tv\"}")));
        assertNotNull(LinkServer.cleanAction(new JSONObject("{\"type\":\"pc\",\"command\":\"lock\"}")));
        assertNull(LinkServer.cleanAction(new JSONObject("{\"type\":\"view\",\"uri\":\"intent://evil#Intent;end\"}")));
        assertNull(LinkServer.cleanAction(new JSONObject("{\"type\":\"view\",\"uri\":\"file:///sdcard/x\"}")));
        assertNull(LinkServer.cleanAction(new JSONObject("{\"type\":\"view\",\"uri\":\"content://x\"}")));
        assertNull(LinkServer.cleanAction(new JSONObject("{\"type\":\"pc\",\"command\":\"rm -rf /\"}")));
        assertNull(LinkServer.cleanAction(new JSONObject("{\"type\":\"shell\",\"command\":\"ls\"}")));
        assertEquals("ab", LinkServer.printable("a\u0007b\n", 10));
    }
}
