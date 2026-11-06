package xyz.vvzvlad.popcornclaw;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Which su gets -g/-G, and where they go in the command line. */
public class SuTest {
    @Test
    public void magiskWithGroupFlags() {
        assertTrue(Su.groups("26.4:MAGISKSU\n", "26400\n"));
        assertTrue(Su.groups("26.2:MAGISKSU\n", "26200\n"));
    }

    @Test
    public void magiskBeforeGroupFlags() {
        assertFalse(Su.groups("26.1:MAGISKSU\n", "26100\n"));
        assertFalse(Su.groups("25.2:MAGISKSU\n", "25200\n"));
    }

    @Test
    public void superSu() {
        assertFalse(Su.groups("2.82:SUPERSU\n", ""));
    }

    @Test
    public void unreadableVersionCode() {
        assertFalse(Su.groups("26.4:MAGISKSU\n", ""));
    }

    @Test
    public void flagsAfterMountMaster() {
        assertArrayEquals(new String[] {"su", "--mount-master", "-g", "0", "-G", "3003", "-c", "id"},
                Su.argv(true, "--mount-master", "-c", "id"));
        assertArrayEquals(new String[] {"su", "-g", "0", "-G", "3003", "-c", "id"}, Su.argv(true, "-c", "id"));
        assertArrayEquals(new String[] {"su", "-g", "0", "-G", "3003"}, Su.argv(true));
    }

    @Test
    public void noFlags() {
        assertArrayEquals(new String[] {"su", "--mount-master", "-c", "id"},
                Su.argv(false, "--mount-master", "-c", "id"));
        assertArrayEquals(new String[] {"su"}, Su.argv(false));
    }
}
