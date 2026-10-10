package io.fprelay.relay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class EnvelopeTest {
    private static final String KEY = "TestKey0123456789abcdefghijklmno";

    private static Envelope.Decoded decode(String s) {
        return Envelope.decode(s.getBytes(StandardCharsets.UTF_8), KEY);
    }

    @Test
    @DisplayName("round-trips a normal GA4 collect request")
    void roundTrip() {
        String enc = Envelope.encode("POST", "/g/collect?v=2&tid=G-ABC&en=page_view", "en=scroll", KEY);
        Envelope.Decoded d = decode(enc);
        assertEquals("POST", d.method());
        assertEquals("/g/collect?v=2&tid=G-ABC&en=page_view", d.pathAndQuery());
        assertEquals("en=scroll", new String(d.body(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("survives non-ASCII, which a byte-oriented XOR could easily mangle")
    void unicode() {
        String title = "%D8%B3%D9%84%D8%A7%D9%85";
        String path = "/g/collect?v=2&dt=" + title + "&dl=https%3A%2F%2Fexample.com%2Fسلام";
        Envelope.Decoded d = decode(Envelope.encode("POST", path, "سلام", KEY));
        assertEquals(path, d.pathAndQuery());
        assertEquals("سلام", new String(d.body(), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("GET is preserved: gtag uses it for small payloads and sGTM cares")
    void keepsMethod() {
        assertEquals("GET", decode(Envelope.encode("GET", "/g/collect?v=2", "", KEY)).method());
    }

    @Test
    @DisplayName("a different tenant's key cannot decode this tenant's envelope")
    void wrongKeyRejected() {
        String enc = Envelope.encode("POST", "/g/collect?v=2", "", "SOME-OTHER-TENANTS-KEY-000000000");
        assertThrows(RuntimeException.class, () -> decode(enc));
    }

    @Test
    @DisplayName("path traversal is refused before it can be concatenated onto the sGTM URL")
    void pathTraversalRefused() {
        for (String bad : new String[]{"../../etc/passwd", "/../admin", "//evil.example.com/x", "no-leading-slash"}) {
            String enc = Envelope.encode("POST", bad, "", KEY);
            assertThrows(RuntimeException.class, () -> decode(enc), "should refuse: " + bad);
        }
    }

    @Test
    @DisplayName("only GET and POST are forwarded upstream")
    void methodAllowlist() {
        for (String m : new String[]{"DELETE", "PUT", "TRACE", "OPTIONS"}) {
            String enc = Envelope.encode(m, "/g/collect", "", KEY);
            assertThrows(RuntimeException.class, () -> decode(enc), "should refuse: " + m);
        }
    }

    @Test
    @DisplayName("junk is rejected rather than crashing the handler")
    void junkRejected() {
        assertThrows(RuntimeException.class, () -> decode(""));
        assertThrows(RuntimeException.class, () -> decode("not-base64!!!"));
        assertThrows(RuntimeException.class, () -> decode("aGVsbG8gd29ybGQ"));
    }

    @Test
    @DisplayName("oversized bodies are refused: the endpoint is public")
    void sizeCap() {
        byte[] huge = new byte[Envelope.MAX_BYTES + 1];
        java.util.Arrays.fill(huge, (byte) 'A');
        assertThrows(RuntimeException.class, () -> Envelope.decode(huge, KEY));
    }

    @Test
    @DisplayName("the encoded form contains none of the substrings filter lists match on")
    void noFilterableSubstrings() {
        String enc = Envelope.encode("POST", "/g/collect?v=2&tid=G-TEST123456", "", KEY);
        for (String sig : new String[]{"/g/collect", "/collect?v=", "/collect?tid=", "tid=", "gtm"}) {
            assertFalse(enc.contains(sig), "encoded envelope leaks '" + sig + "'");
        }
    }
}
