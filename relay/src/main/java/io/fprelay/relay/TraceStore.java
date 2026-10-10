package io.fprelay.relay;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded in-memory buffer of recent traces.
 */
@Component
public class TraceStore {
    public record Trace(
            String id,
            String at,
            String tenant,
            String kind,
            String clientIp,
            String method,
            String path,
            String query,
            String requestBody,
            int status,
            String responseBody,
            Map<String, String> responseHeaders,
            long durationMs,
            String pool,
            String error
    ) {}

    private final Deque<Trace> traces = new ArrayDeque<>();
    private int capacity = 200;

    private final AtomicLong received = new AtomicLong();
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong totalLatencyMs = new AtomicLong();
    private volatile String lastDeliveryAt = null;
    private volatile String lastErrorAt = null;
    private volatile String lastError = null;
    private final Instant startedAt = Instant.now();

    private final Deque<Long> latencies = new ArrayDeque<>();

    void configure(int capacity) {
        this.capacity = Math.max(10, capacity);
    }

    public void recordReceived() {
        received.incrementAndGet();
    }

    public synchronized void recordOutcome(long durationMs, boolean ok, String error) {
        totalLatencyMs.addAndGet(durationMs);
        latencies.addLast(durationMs);
        while (latencies.size() > 500) latencies.removeFirst();

        if (ok) {
            delivered.incrementAndGet();
            lastDeliveryAt = Instant.now().toString();
        } else {
            failed.incrementAndGet();
            lastErrorAt = Instant.now().toString();
            lastError = error;
        }
    }

    public synchronized void addTrace(Trace t) {
        traces.addLast(t);
        while (traces.size() > capacity) traces.removeFirst();
    }

    public synchronized List<Trace> recent(int limit, String since) {
        List<Trace> out = new ArrayList<>();
        for (Trace t : traces) {
            if (since != null && !since.isBlank() && t.at().compareTo(since) <= 0) continue;
            out.add(t);
        }
        int from = Math.max(0, out.size() - limit);
        return out.subList(from, out.size());
    }

    public synchronized Map<String, Object> summary() {
        long r = received.get(), d = delivered.get(), f = failed.get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("started_at", startedAt.toString());
        m.put("now", Instant.now().toString());
        m.put("received", r);
        m.put("delivered", d);
        m.put("failed", f);

        m.put("delivery_success_rate", (d + f) == 0 ? null : round((double) d / (d + f)));
        m.put("avg_latency_ms", (d + f) == 0 ? null : totalLatencyMs.get() / (d + f));
        m.put("p50_latency_ms", percentile(50));
        m.put("p95_latency_ms", percentile(95));
        m.put("last_delivery_at", lastDeliveryAt);
        m.put("last_error_at", lastErrorAt);
        m.put("last_error", lastError);
        m.put("traces_held", traces.size());
        return m;
    }

    private Long percentile(int p) {
        if (latencies.isEmpty()) return null;
        List<Long> sorted = new ArrayList<>(latencies);
        sorted.sort(Long::compareTo);
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
