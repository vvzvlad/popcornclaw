package xyz.vvzvlad.popcornclaw;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONArray;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The panel's Telegram ID list and the TV dialog's link it moves. The group case: the TV linked to a group chat,
 * owner telegram:-1009876543, with allow_from 3333333333, 11111111.
 */
public class ControlServerTest {
    @Test
    public void idsSplitOnCommasAndSpaces() {
        assertEquals(Arrays.asList("11111111", "2222222222", "42"),
                ControlServer.parseIds("11111111, 2222222222 42"));
        assertEquals(Arrays.asList("1", "2", "3"), ControlServer.parseIds("1,2,,3"));
    }

    @Test
    public void idsDropRepeatsAndKeepOrder() {
        assertEquals(Arrays.asList("3", "1", "2"), ControlServer.parseIds("3, 1, 3, 2, 1"));
    }

    @Test
    public void idsDropEmpties() {
        assertEquals(Collections.singletonList("5"), ControlServer.parseIds(" , 5 ,"));
        assertEquals(Collections.emptyList(), ControlServer.parseIds(""));
        assertEquals(Collections.emptyList(), ControlServer.parseIds(" ,, "));
    }

    @Test
    public void freshBoxGetsTheFirstIdLinked() {
        // The APK's defaults/config.json: owner ["pico:tv"], allow_from [].
        JSONArray owner = ControlServer.ownerLink(new JSONArray().put("pico:tv"), null, ids("11111111", "42"));
        assertEquals("[\"telegram:11111111\",\"pico:tv\"]", owner.toString());
    }

    @Test
    public void noOwnerAtAllGetsTheFirstIdLinked() {
        JSONArray owner = ControlServer.ownerLink(null, null, ids("11111111"));
        assertEquals("[\"telegram:11111111\",\"pico:tv\"]", owner.toString());
    }

    @Test
    public void dmLinkFollowsTheFirstId() {
        JSONArray current = new JSONArray().put("telegram:11111111").put("pico:tv");
        JSONArray owner = ControlServer.ownerLink(current, "11111111", ids("2222222222", "11111111"));
        assertEquals("[\"telegram:2222222222\",\"pico:tv\"]", owner.toString());
    }

    @Test
    public void groupLinkIsKept() {
        JSONArray current = new JSONArray().put("telegram:-1009876543").put("pico:tv");
        assertNull(ControlServer.ownerLink(current, "3333333333", ids("11111111", "3333333333")));
        assertNull(ControlServer.ownerLink(current, "3333333333", ids("3333333333", "11111111", "42")));
    }

    @Test
    public void groupLinkIsKeptWhenTheIdsWereEmpty() {
        JSONArray current = new JSONArray().put("telegram:-1009876543").put("pico:tv");
        assertNull(ControlServer.ownerLink(current, null, ids("11111111")));
    }

    @Test
    public void emptiedIdsLeaveTheTvAlone() {
        JSONArray current = new JSONArray().put("telegram:11111111").put("pico:tv");
        JSONArray owner = ControlServer.ownerLink(current, "11111111", ids());
        assertEquals("[\"pico:tv\"]", owner.toString());
    }

    @Test
    public void emptiedIdsKeepAGroupLink() {
        JSONArray current = new JSONArray().put("telegram:-1009876543").put("pico:tv");
        assertNull(ControlServer.ownerLink(current, "3333333333", ids()));
    }

    private static List<String> ids(String... ids) {
        return Arrays.asList(ids);
    }
}
