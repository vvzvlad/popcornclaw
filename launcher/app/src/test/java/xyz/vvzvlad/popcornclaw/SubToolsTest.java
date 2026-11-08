package xyz.vvzvlad.popcornclaw;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** transcribe's and sync_subtitles' pure parts, the SOCKS5 handshake against a fake proxy, player's delay text. */
public class SubToolsTest {
    @Test
    public void startsTakeClocksAndSeconds() throws ToolError {
        assertEquals(Arrays.asList(120, 600, 3723, 45), SubTools.parseStarts("2:00,10:00, 1:02:03 ,45"));
        assertEquals(Collections.singletonList(1880), SubTools.parseStarts("31:20"));
    }

    @Test
    public void badStartIsAnError() {
        for (String at : new String[]{"", "2:00,abc", "1.5", "-10"}) {
            try {
                SubTools.parseStarts(at);
                fail("no error for «" + at + "»");
            } catch (ToolError expected) {
                // the message names what to pass
            }
        }
    }

    @Test
    public void cueStartsAtItsFirstWordAndEndsAtItsLast() throws Exception {
        JSONObject answer = new JSONObject()
                .put("segments", new JSONArray()
                        .put(seg(0.0, 1.0, " ...cut off."))
                        .put(seg(1.0, 3.0, " Hello there."))
                        .put(seg(3.0, 6.0, " General Kenobi."))
                        .put(seg(6.0, 8.0, " Silence.")))
                .put("words", new JSONArray()
                        .put(word("cut", 0.2, 0.5)).put(word("off.", 0.5, 0.8))
                        .put(word("Hello", 1.6, 2.0)).put(word("there.", 2.0, 2.4))
                        .put(word("General", 3.5, 3.9)).put(word("Kenobi.", 3.9, 4.6)));
        // The phrase cut by the piece's start and the one without words are left out.
        List<Srt.Cue> cues = SubTools.cues(answer, 120);
        assertEquals(2, cues.size());
        assertEquals(121.6, cues.get(0).start, 1e-9);
        assertEquals(122.4, cues.get(0).end, 1e-9);
        assertEquals("Hello there.", cues.get(0).text);
        assertEquals(123.5, cues.get(1).start, 1e-9);
        assertEquals(124.6, cues.get(1).end, 1e-9);
    }

    @Test
    public void wordsGoToSegmentsInOrderWhenCountsAgree() throws Exception {
        // The segments' bound at 1.5 s is wrong: by time "there." would belong to the second phrase.
        JSONObject answer = new JSONObject()
                .put("segments", new JSONArray()
                        .put(seg(0.0, 1.5, " - Hi there."))
                        .put(seg(1.5, 4.0, " Bye now.")))
                .put("words", new JSONArray()
                        .put(word("-Hi", 1.0, 1.2)).put(word("there.", 1.4, 1.8))
                        .put(word("Bye", 2.5, 2.7)).put(word("now.", 2.7, 3.0)));
        List<Srt.Cue> cues = SubTools.cues(answer, 0);
        assertEquals(2, cues.size());
        assertEquals(1.0, cues.get(0).start, 1e-9);
        assertEquals(1.8, cues.get(0).end, 1e-9);
        assertEquals(2.5, cues.get(1).start, 1e-9);
        assertEquals(3.0, cues.get(1).end, 1e-9);
    }

    @Test
    public void mergeReplacesOnlyTheNewRanges() {
        List<Srt.Cue> old = Arrays.asList(new Srt.Cue(100, 101, "keep before"), new Srt.Cue(125, 126, "old in range"),
                new Srt.Cue(190, 191, "keep after"));
        List<Srt.Cue> fresh = Collections.singletonList(new Srt.Cue(130, 131, "new"));
        List<double[]> ranges = Collections.singletonList(new double[]{120, 180});
        List<String> texts = new ArrayList<>();
        for (Srt.Cue c : SubTools.merge(old, fresh, ranges)) texts.add(c.text);
        assertEquals(Arrays.asList("keep before", "keep after", "new"), texts);
    }

    @Test
    public void aPieceKeepsAnOlderWholePhraseItsEndCuts() {
        // An earlier call recognised X and Y whole; the piece [570, 630) holds only X's first 0.2 s and Y's last
        // part (Y starts within CUT_ONSET of the piece's start, the piece drops it).
        List<Srt.Cue> old = Arrays.asList(new Srt.Cue(629.8, 632.0, "X whole"), new Srt.Cue(570.2, 572.0, "Y whole"));
        List<Srt.Cue> piece = Arrays.asList(new Srt.Cue(575, 578, "early"), new Srt.Cue(620, 627.9, "middle"),
                new Srt.Cue(629.8, 629.99, "X cut"));
        List<Srt.Cue> whole = SubTools.whole(piece, 570, 60);
        List<String> texts = new ArrayList<>();
        for (Srt.Cue c : SubTools.merge(old, whole, Collections.singletonList(SubTools.covered(whole, 570)))) {
            texts.add(c.text);
        }
        assertEquals(Arrays.asList("X whole", "Y whole", "early", "middle"), texts);
    }

    @Test
    public void unmeasuredStretchesAreNamed() {
        // House 4x16 sampled at 2:00, 10:00, 25:00, 40:00: the first and the last piece were ambiguous.
        List<SubSync.Piece> pieces = Arrays.asList(
                new SubSync.Piece(120, 150, 6, 8, 4.24, false, "ambiguous"),
                new SubSync.Piece(601, 657, 16, 20, 4.2, true, null),
                new SubSync.Piece(1517, 1559, 10, 13, 1.2, true, null),
                new SubSync.Piece(2402, 2456, 4, 11, -0.29, false, "ambiguous"));
        List<Srt.Cue> shifted = Arrays.asList(new Srt.Cue(4.9, 6.1, "a"), new Srt.Cue(2585, 2590, "z"));
        List<double[]> gaps = SubTools.unmeasured(pieces, shifted);
        assertEquals(3, gaps.size());
        // A new piece goes into the longest part no piece, rejected ones included, has touched.
        assertArrayEquals(new double[]{4.9, 601, 0, 345.5}, gaps.get(0), 1e-9);
        assertArrayEquals(new double[]{657, 1517, 1, 1057}, gaps.get(1), 1e-9);
        assertArrayEquals(new double[]{1559, 2590, 0, 1950.5}, gaps.get(2), 1e-9);

        // The piece offered at 17:37 was rejected (it fell on the step): the next offer is elsewhere, and once
        // rejected pieces leave no room the stretch is named without a place.
        List<SubSync.Piece> retried = new ArrayList<>(pieces);
        retried.add(2, new SubSync.Piece(1058, 1116, 5, 12, 2.5, false, "ambiguous"));
        assertArrayEquals(new double[]{657, 1517, 1, 827.5}, SubTools.unmeasured(retried, shifted).get(1), 1e-9);
        List<SubSync.Piece> full = new ArrayList<>(pieces);
        for (double t = 700; t < 1517; t += 150) full.add(2, new SubSync.Piece(t, t + 60, 2, 9, 0, false, "too few"));
        full.sort((x, y) -> Double.compare(x.from, y.from));
        assertTrue(Double.isNaN(SubTools.unmeasured(full, shifted).get(1)[3]));

        // Pieces every 5 minutes: a 4-minute gap is enough for one shift, not for a step.
        List<SubSync.Piece> dense = Arrays.asList(
                new SubSync.Piece(120, 180, 9, 10, 4.2, true, null),
                new SubSync.Piece(420, 480, 9, 10, 4.2, true, null),
                new SubSync.Piece(720, 780, 9, 10, 2.5, true, null));
        List<double[]> denseGaps = SubTools.unmeasured(dense, Arrays.asList(new Srt.Cue(100, 101, "a"),
                new Srt.Cue(900, 901, "z")));
        assertEquals(1, denseGaps.size());
        assertArrayEquals(new double[]{480, 720, 1, 570}, denseGaps.get(0), 1e-9);
    }

    @Test
    public void aFailedProxyConnectionDoesNotNameTheProxy() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = s.getLocalPort();
        }
        File flac = File.createTempFile("piece", ".flac");
        try {
            SubTools.groq(SubTools.groqClient("http://127.0.0.1:" + port), "key", flac, null, true);
            fail("nothing listens on the proxy's port");
        } catch (ToolError e) {
            assertFalse(e.getMessage(), e.getMessage().contains("127.0.0.1"));
            assertFalse(e.getMessage(), e.getMessage().contains(String.valueOf(port)));
        } finally {
            assertTrue(flac.delete());
        }
    }

    @Test
    public void namesAndLanguages() {
        assertEquals("ru", SubTools.fileLang("/data/local/tmp/rus_subs/house416.ru.srt"));
        assertEquals("eng", SubTools.fileLang("Film.ENG.srt"));
        assertNull(SubTools.fileLang("/sdcard/film.srt"));
        assertEquals("house416", SubTools.srtBase("/data/local/tmp/rus_subs/house416.ru.srt"));
        assertEquals("film", SubTools.srtBase("/sdcard/film.srt"));
        assertEquals("House.M.D.S04E16.720p", SubTools.baseName("House.M.D.S04E16.720p.mkv"));
        assertEquals("Брат_2", SubTools.baseName("Брат 2 / .avi"));
        assertEquals("media", SubTools.baseName(""));
        assertEquals("en", SubTools.groqLanguage("eng"));
        assertEquals("ja", SubTools.groqLanguage("jpn"));
        assertNull(SubTools.groqLanguage("und"));
        assertNull(SubTools.groqLanguage(""));
    }

    @Test
    public void languagesOfRealSubtitleNames() {
        // subtitlecat names the language after a dash
        assertEquals("en", SubTools.fileLang("House, M.D. - S04E15-en.srt"));
        // release tags in the language's place are not languages
        assertNull(SubTools.fileLang("Show.S01E01.720p.WEB.srt"));
        assertNull(SubTools.fileLang("house.s04e16.hdtv.lol.srt"));
        assertEquals("Show.S01E01.720p.WEB", SubTools.srtBase("Show.S01E01.720p.WEB.srt"));
        // an unknown three-letter code is not cut to another language's two letters ("ka" is Georgian)
        assertNull(SubTools.groqLanguage("kaz"));
        assertEquals("ru", SubTools.groqLanguage("rus"));
        assertEquals("en", SubTools.groqLanguage("en"));
        // Matroska writes French as "fre"; a BCP 47 tag names its language first
        assertEquals("fr", SubTools.groqLanguage("fre"));
        assertEquals("fre", SubTools.fileLang("Film.fre.srt"));
        assertEquals("en", SubTools.groqLanguage("en-US"));
    }

    @Test
    public void clocks() {
        assertEquals("02:00", SubTools.clock(120));
        assertEquals("1:02:03", SubTools.clock(3723.9));
        assertEquals("02:03.4", SubTools.clockTenths(123.44));
        assertEquals("02:04.0", SubTools.clockTenths(123.96));
    }

    @Test
    public void audioTrackByNumberLanguageOrPlaying() throws Exception {
        JSONArray tracks = new JSONArray()
                .put(new JSONObject().put("id", 1).put("type", "audio").put("lang", "rus").put("selected", true)
                        .put("main-selection", 0).put("ff-index", 1))
                .put(new JSONObject().put("id", 2).put("type", "audio").put("lang", "eng").put("ff-index", 2));
        assertEquals(1, SubTools.audioTrack(tracks, null).optInt("id"));
        assertEquals(2, SubTools.audioTrack(tracks, "en").optInt("id"));
        assertEquals(2, SubTools.audioTrack(tracks, "2").optInt("id"));
        try {
            SubTools.audioTrack(tracks, "ja");
            fail("no error for a missing language");
        } catch (ToolError expected) {
            assertTrue(expected.getMessage().contains("#1 rus"));
        }
    }

    @Test
    public void proxySchemes() throws ToolError {
        SubTools.groqClient("");
        SubTools.groqClient("socks5://10.0.0.1:1080");
        SubTools.groqClient("socks5h://user:secret@10.0.0.1");
        SubTools.groqClient("http://user:secret@10.0.0.1:3128");
        try {
            SubTools.groqClient("ftp://user:secret@10.0.0.1");
            fail("no error for ftp://");
        } catch (ToolError expected) {
            assertFalse(expected.getMessage().contains("secret"));
            assertFalse(expected.getMessage().contains("10.0.0.1"));
        }
    }

    @Test
    public void socks5AsksForTheHostByNameWithLoginAndPassword() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            AtomicReference<byte[]> greeting = new AtomicReference<>();
            AtomicReference<byte[]> auth = new AtomicReference<>();
            AtomicReference<byte[]> connect = new AtomicReference<>();
            Thread proxy = new Thread(() -> {
                try (Socket s = server.accept()) {
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    OutputStream out = s.getOutputStream();
                    greeting.set(read(in, 4));
                    out.write(new byte[]{5, 2});
                    byte[] head = read(in, 2);
                    byte[] user = read(in, head[1]);
                    int passLength = in.readUnsignedByte();
                    byte[] pass = read(in, passLength);
                    ByteArrayOutputStream a = new ByteArrayOutputStream();
                    a.write(head);
                    a.write(user);
                    a.write(passLength);
                    a.write(pass);
                    auth.set(a.toByteArray());
                    out.write(new byte[]{1, 0});
                    byte[] req = read(in, 5);
                    byte[] rest = read(in, req[4] + 2);
                    ByteArrayOutputStream c = new ByteArrayOutputStream();
                    c.write(req);
                    c.write(rest);
                    connect.set(c.toByteArray());
                    out.write(new byte[]{5, 0, 0, 1, 0, 0, 0, 0, 0, 0});
                    out.write("hi".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            proxy.start();
            SubTools.Socks5 factory = new SubTools.Socks5("127.0.0.1", server.getLocalPort(), "u", "p:w");
            try (Socket s = factory.createSocket()) {
                s.connect(InetSocketAddress.createUnresolved("api.groq.com", 443), 5000);
                InputStream in = s.getInputStream();
                assertEquals('h', in.read());
                assertEquals('i', in.read());
            }
            proxy.join(5000);
            assertArrayEquals(new byte[]{5, 2, 0, 2}, greeting.get());
            assertArrayEquals(new byte[]{1, 1, 'u', 3, 'p', ':', 'w'}, auth.get());
            byte[] host = "api.groq.com".getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream want = new ByteArrayOutputStream();
            want.write(new byte[]{5, 1, 0, 3, (byte) host.length});
            want.write(host);
            want.write(new byte[]{1, (byte) 0xbb});
            assertArrayEquals(want.toByteArray(), connect.get());
        }
    }

    @Test
    public void delays() {
        assertEquals("+0.5 s", TvMcpServer.delayText(0.5));
        assertEquals("-1.25 s", TvMcpServer.delayText(-1.25));
        assertEquals("+3.0 s", TvMcpServer.delayText(3));
        assertEquals("+0.0 s", TvMcpServer.delayText(-0.001));
    }

    private static JSONObject seg(double start, double end, String text) throws Exception {
        return new JSONObject().put("start", start).put("end", end).put("text", text);
    }

    private static JSONObject word(String w, double start, double end) throws Exception {
        return new JSONObject().put("word", w).put("start", start).put("end", end);
    }

    private static byte[] read(DataInputStream in, int n) throws Exception {
        byte[] b = new byte[n];
        in.readFully(b);
        return b;
    }
}
