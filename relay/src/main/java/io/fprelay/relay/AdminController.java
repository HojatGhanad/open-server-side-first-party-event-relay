package io.fprelay.relay;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bearer-token protected JSON endpoints polled by the operations panel.
 * Returns 404 when no admin token is configured.
 */
@Profile("!panel")
@RestController
public class AdminController {
    private final RelayProperties props;
    private final StatsRegistry stats;
    private final TenantRegistry tenants;
    private final ShimInjector shim;

    public AdminController(RelayProperties props, StatsRegistry stats,
                           TenantRegistry tenants, ShimInjector shim) {
        this.props = props;
        this.stats = stats;
        this.tenants = tenants;
        this.shim = shim;
    }

    @GetMapping(value = "/_admin/stats", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> statsJson(HttpServletRequest req) {
        guard(req);
        Map<String, Object> m = new LinkedHashMap<>(stats.snapshot());
        m.put("relay_tenants", tenants.hosts());
        m.put("shim_loaded", shim.available());
        return ResponseEntity.ok(m);
    }

    @GetMapping(value = "/_admin/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> health(HttpServletRequest req) {
        guard(req);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("relay", "up");
        m.put("tenants", tenants.size());
        m.put("hosts", tenants.hosts());
        m.put("shim_loaded", shim.available());
        m.put("tenants_file", props.getTenantsFile());
        return ResponseEntity.ok(m);
    }

    private void guard(HttpServletRequest req) {
        String expected = props.getAdminToken();
        if (expected == null || expected.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String got = req.getHeader("Authorization");
        if (got != null && got.startsWith("Bearer ")) got = got.substring(7);
        if (got == null || !constantTimeEquals(expected, got)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }
}
