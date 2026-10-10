package io.fprelay.panel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Iterator;
import java.util.Map;

/**
 * Polls the relay's admin stats, stores per-minute deltas and purges old rows.
 */
@Profile("panel")
@Component
@EnableScheduling
public class StatsPoller {
    private static final Logger log = LoggerFactory.getLogger(StatsPoller.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PanelProperties props;
    private final PanelDb db;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private volatile String lastError;
    private volatile Instant lastPollAt;
    private volatile boolean lastOk;

    public StatsPoller(PanelProperties props, PanelDb db) {
        this.props = props;
        this.db = db;
    }

    public Map<String, Object> pollHealth() {
        return Map.of(
                "last_poll_at", lastPollAt == null ? "never" : lastPollAt.toString(),
                "ok", lastOk,
                "last_error", lastError == null ? "" : lastError,
                "relay_url", props.getRelayUrl());
    }

    public JsonNode fetch() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(props.getRelayUrl() + "/_admin/stats"))
                .header("Authorization", "Bearer " + props.getRelayToken())
                .timeout(Duration.ofSeconds(5))
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("relay /_admin/stats returned " + resp.statusCode()
                    + (resp.statusCode() == 404 ? ": check that panel.relay-token equals the relay's FP_ADMIN_TOKEN" : ""));
        }
        return MAPPER.readTree(resp.body());
    }

    @Scheduled(fixedDelayString = "${panel.poll-seconds:10}000")
    public void poll() {
        try {
            JsonNode root = fetch();
            JsonNode tenants = root.path("tenants");
            Instant bucket = Instant.now().truncatedTo(ChronoUnit.MINUTES);

            Iterator<Map.Entry<String, JsonNode>> it = tenants.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                store(e.getKey(), e.getValue(), bucket);
            }
            lastOk = true;
            lastError = null;
        } catch (Exception ex) {
            lastOk = false;
            lastError = ex.getMessage();
            log.warn("poll failed: {}", ex.toString());
        } finally {
            lastPollAt = Instant.now();
        }
    }

    private void store(String tenant, JsonNode s, Instant bucket) {
        JsonNode kinds = s.path("received_by_kind");
        long loader = kinds.path("loader").asLong();
        long envelope = kinds.path("envelope").asLong();
        long nativ = kinds.path("native").asLong();
        long other = kinds.path("other").asLong();
        long delivered = s.path("delivered").asLong();
        long failed = s.path("failed").asLong();
        long rejected = s.path("rejected").asLong();
        String startedAt = s.path("started_at").asText("");

        Map<String, Object> prev = db.lastSample(tenant);
        boolean baseline = isBaseline(prev, startedAt, loader, envelope, nativ, delivered);

        if (!baseline) {
            db.addMinute(tenant, bucket,
                    loader    - num(prev, "loader"),
                    envelope  - num(prev, "envelope"),
                    nativ     - num(prev, "native"),
                    other     - num(prev, "other"),
                    delivered - num(prev, "delivered"),
                    failed    - num(prev, "failed"),
                    rejected  - num(prev, "rejected"),
                    intOrNull(s, "p50_latency_ms"),
                    intOrNull(s, "p95_latency_ms"));
        } else if (prev != null) {
            log.info("relay counters reset for {}: re-baselining", tenant);
        }

        db.saveSample(tenant, startedAt, loader, envelope, nativ, other, delivered, failed, rejected);
    }

    static boolean isBaseline(Map<String, Object> prev, String startedAt,
                              long loader, long envelope, long nativ, long delivered) {
        return prev == null
                || !startedAt.equals(String.valueOf(prev.get("started_at")))
                || loader    < num(prev, "loader")
                || envelope  < num(prev, "envelope")
                || nativ     < num(prev, "native")
                || delivered < num(prev, "delivered");
    }

    @Scheduled(cron = "0 17 3 * * *")
    public void purge() {
        try {
            int n = db.purgeOlderThan(props.getRetentionDays());
            if (n > 0) log.info("purged {} minute rows older than {} days", n, props.getRetentionDays());
        } catch (Exception e) {
            log.warn("purge failed: {}", e.toString());
        }
    }

    private static long num(Map<String, Object> row, String k) {
        Object v = row.get(k);
        return v instanceof Number n ? n.longValue() : 0L;
    }

    private static Integer intOrNull(JsonNode s, String field) {
        JsonNode n = s.path(field);
        return n.isNull() || n.isMissingNode() ? null : n.asInt();
    }
}
