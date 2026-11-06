package xyz.vvzvlad.popcornclaw;

import static org.junit.Assert.assertEquals;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** What Tv.rutor keeps of TorrServer's RuTor answer: sizes in bytes and the best-seeded releases. */
public class RutorTest {
    @Test
    public void binarySizes() {
        assertEquals(19810536652L, Tv.rutorSize("18.45 GB"));
        assertEquals(664849612L, Tv.rutorSize("634.05 MB"));
        assertEquals(2340757176L, Tv.rutorSize("2.18 GB"));
        assertEquals(1536L, Tv.rutorSize("1.5 KB"));
        assertEquals(1649267441664L, Tv.rutorSize("1.5 TB"));
        assertEquals(1610612736L, Tv.rutorSize(" 1.5 gb "));
    }

    @Test
    public void bytesWithoutAPrefix() {
        assertEquals(512L, Tv.rutorSize("512 B"));
        assertEquals(512L, Tv.rutorSize("512B"));
    }

    @Test
    public void garbageIsZero() {
        assertEquals(0L, Tv.rutorSize(""));
        assertEquals(0L, Tv.rutorSize("n/a"));
        assertEquals(0L, Tv.rutorSize("18.45 XB"));
        assertEquals(0L, Tv.rutorSize("GB"));
        // No unit at all: not RuTor's shape.
        assertEquals(0L, Tv.rutorSize("512"));
    }

    @Test
    public void mostSeededFirstTiesInArrivalOrder() {
        List<JSONObject> best = new ArrayList<>();
        feed(best, 10, "a:5", "b:3", "c:5", "d:7", "e:3", "f:5");
        assertEquals("d a c f b e", titles(best));
    }

    @Test
    public void releasesWithoutSeedersAreDropped() {
        List<JSONObject> best = new ArrayList<>();
        feed(best, 10, "a:0", "b:2", "c:0", "d:1");
        assertEquals("b d", titles(best));
        best.clear();
        feed(best, 10, "a:0", "b:0");
        assertEquals("", titles(best));
    }

    @Test
    public void limitKeepsTheBest() {
        List<JSONObject> best = new ArrayList<>();
        feed(best, 3, "a:1", "b:9", "c:2", "d:8", "e:3", "f:7");
        assertEquals("b d f", titles(best));
    }

    @Test
    public void tieAtTheLimitKeepsTheEarlier() {
        List<JSONObject> best = new ArrayList<>();
        feed(best, 3, "a:5", "b:5", "c:5", "d:5", "e:6");
        assertEquals("e a b", titles(best));
    }

    @Test
    public void searchLimitOfTen() {
        List<JSONObject> best = new ArrayList<>();
        for (int i = 1; i <= 25; i++) Tv.keepBest(best, release("r" + i, i), TvFormat.SEARCH_LIMIT);
        assertEquals(10, best.size());
        assertEquals("r25 r24 r23 r22 r21 r20 r19 r18 r17 r16", titles(best));
    }

    /** Each spec is "title:seeders". */
    private static void feed(List<JSONObject> best, int limit, String... specs) {
        for (String s : specs) {
            String[] p = s.split(":");
            Tv.keepBest(best, release(p[0], Integer.parseInt(p[1])), limit);
        }
    }

    private static JSONObject release(String title, int seeders) {
        try {
            return new JSONObject().put("Title", title).put("Seeders", seeders);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String titles(List<JSONObject> best) {
        StringBuilder sb = new StringBuilder();
        for (JSONObject o : best) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(o.optString("Title"));
        }
        return sb.toString();
    }
}
