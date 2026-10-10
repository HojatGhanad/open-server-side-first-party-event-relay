package io.fprelay.relay;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory per-tenant counters and latency percentiles.
 */
@Component
public class StatsRegistry {
    public enum Kind {
        LOADER,
        ENVELOPE,
        NATIVE,
        OTHER
    }

    public static final class TenantStats {
        final Instant startedAt = Instant.now();
        final Map<Kind, AtomicLong> received = new ConcurrentHashMap<>();
        final AtomicLong delivered = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
        final AtomicLong rejected = new AtomicLong();
        final AtomicLong totalLatencyMs = new AtomicLong();
        final Deque<Long> latencies = new ArrayDeque<>();
        volatile String lastDeliveryAt;
        volatile String lastErrorAt;
        volatile String lastError;

        TenantStats() {
            for (Kind k : Kind.values()) received.put(k, new AtomicLong());
        }
    }

    private final Map<String, TenantStats> byTenant = new ConcurrentHashMap<>();

    private TenantStats of(String tenant) {
        return byTenant.computeIfAbsent(tenant == null || tenant.isBlank() ? "unknown" : tenant,
                k -> new TenantStats());
    }

    public void received(String tenant, Kind kind) {
        of(tenant).received.get(kind).incrementAndGet();
    }

    public void outcome(String tenant, long durationMs, boolean ok, String error) {
        TenantStats s = of(tenant);
        s.totalLatencyMs.addAndGet(durationMs);
        synchronized (s.latencies) {
            s.latencies.addLast(durationMs);
            while (s.latencies.size() > 500) s.latencies.removeFirst();
        }
        if (ok) {
            s.delivered.incrementAndGet();
            s.lastDeliveryAt = Instant.now().toString();
        } else {
            s.failed.incrementAndGet();
            s.lastErrorAt = Instant.now().toString();
            s.lastError = error;
        }
    }

    public void rejected(String tenant) {
        of(tenant).rejected.incrementAndGet();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("now", Instant.now().toString());
        Map<String, Object> tenants = new LinkedHashMap<>();
        byTenant.forEach((host, s) -> tenants.put(host, render(s)));
        out.put("tenants", tenants);
        return out;
    }

    public Map<String, Object> forTenant(String tenant) {
        TenantStats s = byTenant.get(tenant == null ? "" : tenant);
        return s == null ? Map.of() : render(s);
    }

    private Map<String, Object> render(TenantStats s) {
        long d = s.delivered.get(), f = s.failed.get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("started_at", s.startedAt.toString());

        Map<String, Long> rec = new LinkedHashMap<>();
        long total = 0;
        for (Kind k : Kind.values()) {
            long v = s.received.get(k).get();
            rec.put(k.name().toLowerCase(), v);
            total += v;
        }
        m.put("received_by_kind", rec);
        m.put("received", total);

        m.put("delivered", d);
        m.put("failed", f);
        m.put("rejected", s.rejected.get());

        m.put("delivery_success_rate", (d + f) == 0 ? null : round((double) d / (d + f)));
        m.put("avg_latency_ms", (d + f) == 0 ? null : s.totalLatencyMs.get() / (d + f));
        m.put("p50_latency_ms", percentile(s, 50));
        m.put("p95_latency_ms", percentile(s, 95));
        m.put("last_delivery_at", s.lastDeliveryAt);
        m.put("last_error_at", s.lastErrorAt);
        m.put("last_error", s.lastError);
        return m;
    }

    private Long percentile(TenantStats s, int p) {
        List<Long> sorted;
        synchronized (s.latencies) {
            if (s.latencies.isEmpty()) return null;
            sorted = new ArrayList<>(s.latencies);
        }
        sorted.sort(Long::compareTo);
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
