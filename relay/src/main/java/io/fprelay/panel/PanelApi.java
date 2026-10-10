package io.fprelay.panel;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API behind the operations panel UI.
 */
@Profile("panel")
@RestController
public class PanelApi {
    private static final SecureRandom RNG = new SecureRandom();
    private static final String ALPHANUM =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final PanelProperties props;
    private final PanelAuth auth;
    private final PanelDb db;
    private final StatsPoller poller;
    private final TenantsFile tenants;

    public PanelApi(PanelProperties props, PanelAuth auth, PanelDb db,
                    StatsPoller poller, TenantsFile tenants) {
        this.props = props;
        this.auth = auth;
        this.db = db;
        this.poller = poller;
        this.tenants = tenants;
    }

    @PostMapping(value = "/api/login", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body,
                                                     HttpServletRequest req,
                                                     HttpServletResponse res) {
        String ip = req.getRemoteAddr();
        String token = auth.login(body.get("user"), body.get("password"), ip);
        if (token == null) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid credentials"));
        }
        res.addCookie(auth.cookie(token, isHttps(req)));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping(value = "/api/logout", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest req, HttpServletResponse res) {
        auth.logout(PanelAuth.tokenOf(req));
        res.addCookie(auth.cookie(null, isHttps(req)));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping(value = "/api/live", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> live() {
        try {
            JsonNode n = poller.fetch();
            return ResponseEntity.ok(n);
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of(
                    "error", "relay unreachable",
                    "detail", String.valueOf(e.getMessage()),
                    "relay_url", props.getRelayUrl()));
        }
    }

    @GetMapping(value = "/api/series", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> series(@RequestParam(defaultValue = "") String tenant,
                                         @RequestParam(defaultValue = "24") int hours,
                                         @RequestParam(defaultValue = "minute") String grain) {
        int h = Math.max(1, Math.min(hours, 24 * 90));
        return ResponseEntity.ok(Map.of(
                "tenant", tenant,
                "hours", h,
                "grain", grain,
                "rows", db.series(tenant.isBlank() ? null : tenant, h, grain)));
    }

    @GetMapping(value = "/api/totals", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> totals(@RequestParam(defaultValue = "24") int hours) {
        return ResponseEntity.ok(Map.of("hours", hours, "tenants", db.totals(Math.max(1, hours))));
    }

    @GetMapping(value = "/api/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("panel", "up");
        m.put("poller", poller.pollHealth());
        try {
            m.put("db", db.health());
        } catch (Exception e) {
            m.put("db", Map.of("error", String.valueOf(e.getMessage())));
        }
        return ResponseEntity.ok(m);
    }

    @GetMapping(value = "/api/tenants", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> listTenants() {
        return ResponseEntity.ok(Map.of("tenants", tenants.readAll()));
    }

    @PostMapping(value = "/api/tenants", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> saveTenant(@RequestBody Map<String, Object> body) {
        String host = String.valueOf(body.getOrDefault("host", "")).trim();
        if (host.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "host is required"));

        Map<String, Object> values = new LinkedHashMap<>();
        for (String k : TenantsFile.KEYS) if (body.containsKey(k)) values.put(k, body.get(k));

        try {
            tenants.upsert(host, values);
            return ResponseEntity.ok(Map.of("ok", true, "host", TenantsFile.normalise(host),
                    "note", "the relay picks this up within ~5s; no restart needed"));
        } catch (RuntimeException e) {
            return ResponseEntity.status(500).body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @DeleteMapping(value = "/api/tenants/{host}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> deleteTenant(@PathVariable String host) {
        try {
            return ResponseEntity.ok(Map.of("ok", tenants.delete(host), "host", host));
        } catch (RuntimeException e) {
            return ResponseEntity.status(500).body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    @GetMapping(value = "/api/generate", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> generate() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("segment", random(10));
        m.put("key", random(32));
        m.put("loader_alias", "/assets/js/app." + hex(8) + ".js");
        return ResponseEntity.ok(m);
    }

    private static String random(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(ALPHANUM.charAt(RNG.nextInt(ALPHANUM.length())));
        return sb.toString();
    }

    private static String hex(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append("0123456789abcdef".charAt(RNG.nextInt(16)));
        return sb.toString();
    }

    private static boolean isHttps(HttpServletRequest req) {
        String proto = req.getHeader("X-Forwarded-Proto");
        return proto != null ? "https".equalsIgnoreCase(proto) : req.isSecure();
    }

    static List<String> writableKeys() {
        return TenantsFile.KEYS;
    }
}
