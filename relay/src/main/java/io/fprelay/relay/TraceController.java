package io.fprelay.relay;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Token-protected debug endpoints: health, summary, recent traces and trace enrolment.
 */
@Profile("!panel")
@RestController
public class TraceController {
    private final RelayProperties props;
    private final TraceStore store;
    private final TraceAudience audience;
    private final StatsRegistry stats;
    private final TenantRegistry tenants;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public TraceController(RelayProperties props, TraceStore store, TraceAudience audience,
                           StatsRegistry stats, TenantRegistry tenants) {
        this.props = props;
        this.store = store;
        this.audience = audience;
        this.stats = stats;
        this.tenants = tenants;
    }

    @GetMapping(value = "/_t/{token}/trace-me", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> traceMe(@PathVariable String token,
                                                       HttpServletRequest req,
                                                       @RequestParam(required = false) String cb) {
        guard(token);
        String ip = ClientIp.of(req);
        Instant until = audience.enroll(ip);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enrolled", ip);
        m.put("until", until.toString());
        m.put("hours", props.getTraceEnrollHours());
        m.put("note", "Events from this IP are now captured in full. Re-visit to extend, "
                + "or after changing network.");
        m.put("next", "/_t/" + token + "/recent.json");
        return json(m);
    }

    @GetMapping(value = "/_t/{token}/trace-off", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> traceOff(@PathVariable String token,
                                                        @RequestParam(required = false) String cb) {
        guard(token);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cleared", audience.clear());
        m.put("static_allow_list", props.getTraceClientIps());
        return json(m);
    }

    @GetMapping(value = "/_t/{token}/health.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> health(@PathVariable String token,
                                                      @RequestParam(required = false) String cb) {
        guard(token);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("relay", "up");
        m.put("deployment_mode", props.getDeploymentMode());
        m.put("site_origins", props.getSiteOrigins());
        m.put("cors_ready", !props.isSubdomainMode() || !props.getSiteOrigins().isEmpty());
        m.put("collect_prefix", props.getCollectPrefix());
        m.put("sgtm_tagging", probe(props.getSgtmUrl() + "/healthz"));
        m.put("sgtm_preview", probe(props.getSgtmPreviewUrl() + "/healthz"));
        m.put("preview_session", props.getPreviewToken().isBlank() ? "none" : "active");
        m.put("trace_client_ips_static", props.getTraceClientIps());
        m.put("trace_client_ips_enrolled", audience.enrolledView());
        m.put("enroll_url", "/_t/" + token + "/trace-me");
        m.put("tenants", tenants.hosts());
        m.putAll(stats.snapshot());
        return json(m);
    }

    @GetMapping(value = "/_t/{token}/recent.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> recent(@PathVariable String token,
                                                      @RequestParam(required = false) String since,
                                                      @RequestParam(defaultValue = "20") int limit,
                                                      @RequestParam(required = false) String cb) {
        guard(token);
        List<TraceStore.Trace> traces = store.recent(Math.min(Math.max(limit, 1), 100), since);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", traces.size());
        m.put("note", traces.isEmpty()
                ? "No traces. Either no traffic yet, or the tester's IP is not in fp.trace-client-ips "
                  + "- visit /_t/{token}/trace-me from that browser to enrol it."
                : null);
        m.put("traces", traces);
        return json(m);
    }

    @GetMapping(value = "/_t/{token}/summary.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> summary(@PathVariable String token,
                                                       @RequestParam(required = false) String cb) {
        guard(token);

        return json(new LinkedHashMap<>(stats.snapshot()));
    }

    private void guard(String token) {
        String expected = props.getTraceToken();
        if (expected == null || expected.isBlank()
                || !java.security.MessageDigest.isEqual(
                        expected.getBytes(), token == null ? new byte[0] : token.getBytes())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private String probe(String url) {
        try {
            HttpResponse<Void> r = http.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(3))
                            .GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return "up (" + r.statusCode() + ")";
        } catch (Exception e) {
            return "unreachable: " + e.getClass().getSimpleName();
        }
    }

    private ResponseEntity<Map<String, Object>> json(Map<String, Object> body) {
        HttpHeaders h = new HttpHeaders();
        h.add(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate");
        h.add(HttpHeaders.PRAGMA, "no-cache");
        h.add("X-Robots-Tag", "noindex, nofollow");
        return ResponseEntity.ok().headers(h).body(body);
    }
}
