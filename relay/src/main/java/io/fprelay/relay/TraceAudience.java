package io.fprelay.relay;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides which client IPs get full per-event traces: a static allow-list plus
 * time-limited self-enrolment.
 */
@Component
public class TraceAudience {
    private final RelayProperties props;

    private final Map<String, Instant> enrolled = new ConcurrentHashMap<>();

    public TraceAudience(RelayProperties props) {
        this.props = props;
    }

    public boolean isTraced(String ip) {
        if (ip == null || ip.isBlank()) return false;
        if (props.getTraceClientIps().contains(ip)) return true;
        Instant until = enrolled.get(ip);
        if (until == null) return false;
        if (Instant.now().isAfter(until)) {
            enrolled.remove(ip);
            return false;
        }
        return true;
    }

    public Instant enroll(String ip) {
        Instant until = Instant.now().plus(Duration.ofHours(props.getTraceEnrollHours()));
        enrolled.put(ip, until);
        return until;
    }

    public int clear() {
        int n = enrolled.size();
        enrolled.clear();
        return n;
    }

    public Map<String, String> enrolledView() {
        Instant now = Instant.now();
        enrolled.entrySet().removeIf(e -> now.isAfter(e.getValue()));
        Map<String, String> m = new LinkedHashMap<>();
        enrolled.forEach((ip, until) -> m.put(ip, "until " + until));
        return m;
    }
}
