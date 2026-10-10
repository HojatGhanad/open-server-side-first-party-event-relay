package io.fprelay.relay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoutingTest {
    private static Tenant tenant(String prefix, String serving, String alias, String seg) {
        return new Tenant("edge.example.com", "http://sgtm:8080", "http://preview:8080", "",
                prefix, serving, alias, seg, "key", List.of());
    }

    @Test
    @DisplayName("the serving path always gains its trailing slash")
    void trailingSlash() {
        assertEquals("/AbC123xyZ/", tenant("", "/AbC123xyZ", "", "S").servingPathWithSlash());
        assertEquals("/AbC123xyZ/", tenant("", "/AbC123xyZ/", "", "S").servingPathWithSlash());
        assertEquals("/AbC123xyZ/", tenant("", "AbC123xyZ", "", "S").servingPathWithSlash());
    }

    @Test
    @DisplayName("the bare form drops it again, for the shim's prefix comparison")
    void bareForm() {
        assertEquals("/AbC123xyZ", tenant("", "/AbC123xyZ/", "", "S").servingPathBare());
        assertEquals("", tenant("", "", "", "S").servingPathBare());
    }

    @Test
    @DisplayName("protected mode is off when no segment is configured")
    void protectedModeOff() {
        assertFalse(tenant("", "/x", "", "").protectedMode());
        assertFalse(tenant("", "/x", "", "   ").protectedMode());
        assertTrue(tenant("", "/x", "", "Seg0Example").protectedMode());
    }

    @Test
    @DisplayName("preview routes to the preview container only when a token is present")
    void previewRouting() {
        Tenant plain = tenant("", "/x", "", "S");
        assertEquals("http://sgtm:8080", plain.upstream());

        Tenant previewing = new Tenant("h", "http://sgtm:8080", "http://preview:8080", "tok",
                "", "/x", "", "S", "k", List.of());
        assertTrue(previewing.previewing());
        assertEquals("http://preview:8080", previewing.upstream());
    }

    @Test
    @DisplayName("hosts are matched case-insensitively and without the port")
    void hostNormalisation() {
        assertEquals("edge.example.com", TenantRegistry.normalise("EDGE.Example.COM"));
        assertEquals("edge.example.com", TenantRegistry.normalise("edge.example.com:443"));
        assertEquals("edge.example.com", TenantRegistry.normalise("  edge.example.com  "));
        assertEquals("", TenantRegistry.normalise(null));
    }
}
