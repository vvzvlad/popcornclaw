package xyz.vvzvlad.popcornclaw;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** How the LAN surfaces read the PIN off a request: the browser's cookie, an MCP client's bearer token. */
public class PinTest {
    @Test
    public void cookieAmongOthers() {
        assertEquals("123456", Pin.fromCookies("a=1; pcpin=123456; b=2"));
        assertEquals("123456", Pin.fromCookies("pcpin=123456"));
        assertEquals("123456", Pin.fromCookies("theme=dark;pcpin=123456"));
    }

    @Test
    public void cookieMissing() {
        assertNull(Pin.fromCookies("a=1; b=2"));
        // Another cookie whose name only ends in pcpin is not it.
        assertNull(Pin.fromCookies("xpcpin=123456"));
        assertNull(Pin.fromCookies(""));
        assertNull(Pin.fromCookies(null));
    }

    @Test
    public void bearerToken() {
        assertEquals("123456", Pin.fromBearer("Bearer 123456"));
        assertEquals("123456", Pin.fromBearer("bearer 123456"));
        assertEquals("123456", Pin.fromBearer("  Bearer   123456  "));
    }

    @Test
    public void otherSchemesAndEmpty() {
        assertNull(Pin.fromBearer("Basic dXNlcjpwYXNz"));
        assertNull(Pin.fromBearer("Token 123456"));
        assertNull(Pin.fromBearer("Bearer"));
        assertNull(Pin.fromBearer(""));
        assertNull(Pin.fromBearer(null));
    }

    @Test
    public void comparison() {
        assertTrue(Pin.same("123456", "123456"));
        assertTrue(Pin.same("123456", " 123456 "));
        assertFalse(Pin.same("123456", "654321"));
        assertFalse(Pin.same("123456", "12345"));
        assertFalse(Pin.same("123456", "1234567"));
        assertFalse(Pin.same("123456", ""));
    }
}
