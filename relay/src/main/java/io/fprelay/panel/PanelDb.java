package io.fprelay.panel;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Postgres storage for per-minute traffic history.
 */
@Profile("panel")
@Component
public class PanelDb {
    private final JdbcTemplate jdbc;

    public PanelDb(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void schema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS stat_minute (
              tenant      text        NOT NULL,
              bucket      timestamptz NOT NULL,
              loader      bigint      NOT NULL DEFAULT 0,
              envelope    bigint      NOT NULL DEFAULT 0,
              native      bigint      NOT NULL DEFAULT 0,
              other       bigint      NOT NULL DEFAULT 0,
              delivered   bigint      NOT NULL DEFAULT 0,
              failed      bigint      NOT NULL DEFAULT 0,
              rejected    bigint      NOT NULL DEFAULT 0,
              p50_ms      integer,
              p95_ms      integer,
              PRIMARY KEY (tenant, bucket)
            )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS stat_minute_bucket ON stat_minute (bucket DESC)");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS poll_state (
              tenant      text PRIMARY KEY,
              started_at  text,
              loader      bigint NOT NULL DEFAULT 0,
              envelope    bigint NOT NULL DEFAULT 0,
              native      bigint NOT NULL DEFAULT 0,
              other       bigint NOT NULL DEFAULT 0,
              delivered   bigint NOT NULL DEFAULT 0,
              failed      bigint NOT NULL DEFAULT 0,
              rejected    bigint NOT NULL DEFAULT 0,
              updated_at  timestamptz NOT NULL DEFAULT now()
            )""");
    }

    public Map<String, Object> lastSample(String tenant) {
        List<Map<String, Object>> rows =
                jdbc.queryForList("SELECT * FROM poll_state WHERE tenant = ?", tenant);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void saveSample(String tenant, String startedAt, long loader, long envelope,
                           long nativ, long other, long delivered, long failed, long rejected) {
        jdbc.update("""
            INSERT INTO poll_state
              (tenant, started_at, loader, envelope, native, other, delivered, failed, rejected, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?, now())
            ON CONFLICT (tenant) DO UPDATE SET
              started_at = EXCLUDED.started_at, loader = EXCLUDED.loader,
              envelope = EXCLUDED.envelope, native = EXCLUDED.native, other = EXCLUDED.other,
              delivered = EXCLUDED.delivered, failed = EXCLUDED.failed,
              rejected = EXCLUDED.rejected, updated_at = now()
            """, tenant, startedAt, loader, envelope, nativ, other, delivered, failed, rejected);
    }

    public void addMinute(String tenant, Instant bucket, long loader, long envelope,
                          long nativ, long other, long delivered, long failed, long rejected,
                          Integer p50, Integer p95) {
        jdbc.update("""
            INSERT INTO stat_minute
              (tenant, bucket, loader, envelope, native, other, delivered, failed, rejected, p50_ms, p95_ms)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (tenant, bucket) DO UPDATE SET
              loader    = stat_minute.loader    + EXCLUDED.loader,
              envelope  = stat_minute.envelope  + EXCLUDED.envelope,
              native    = stat_minute.native    + EXCLUDED.native,
              other     = stat_minute.other     + EXCLUDED.other,
              delivered = stat_minute.delivered + EXCLUDED.delivered,
              failed    = stat_minute.failed    + EXCLUDED.failed,
              rejected  = stat_minute.rejected  + EXCLUDED.rejected,
              p50_ms    = COALESCE(EXCLUDED.p50_ms, stat_minute.p50_ms),
              p95_ms    = COALESCE(EXCLUDED.p95_ms, stat_minute.p95_ms)
            """, tenant, java.sql.Timestamp.from(bucket), loader, envelope, nativ, other,
                delivered, failed, rejected, p50, p95);
    }

    public List<Map<String, Object>> series(String tenant, int hours, String grain) {
        String trunc = switch (grain == null ? "" : grain) {
            case "hour" -> "hour";
            case "day"  -> "day";
            default     -> "minute";
        };
        String sql = """
            SELECT date_trunc(?, bucket) AS t,
                   SUM(loader)    AS loader,
                   SUM(envelope)  AS envelope,
                   SUM(native)    AS native,
                   SUM(other)     AS other,
                   SUM(delivered) AS delivered,
                   SUM(failed)    AS failed,
                   SUM(rejected)  AS rejected,
                   MAX(p50_ms)    AS p50_ms,
                   MAX(p95_ms)    AS p95_ms
              FROM stat_minute
             WHERE bucket > now() - make_interval(hours => ?)
               AND (? = '' OR tenant = ?)
             GROUP BY 1
             ORDER BY 1
            """;
        String t = tenant == null ? "" : tenant;
        return jdbc.queryForList(sql, trunc, hours, t, t);
    }

    public List<Map<String, Object>> totals(int hours) {
        return jdbc.queryForList("""
            SELECT tenant,
                   SUM(loader) AS loader, SUM(envelope) AS envelope,
                   SUM(native) AS native, SUM(other) AS other,
                   SUM(delivered) AS delivered, SUM(failed) AS failed,
                   SUM(rejected) AS rejected,
                   MAX(p95_ms) AS p95_ms
              FROM stat_minute
             WHERE bucket > now() - make_interval(hours => ?)
             GROUP BY tenant
             ORDER BY tenant
            """, hours);
    }

    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("minute_rows", jdbc.queryForObject("SELECT count(*) FROM stat_minute", Long.class));
        m.put("oldest", jdbc.queryForObject("SELECT min(bucket)::text FROM stat_minute", String.class));
        m.put("newest", jdbc.queryForObject("SELECT max(bucket)::text FROM stat_minute", String.class));
        return m;
    }

    public int purgeOlderThan(int days) {
        return jdbc.update("DELETE FROM stat_minute WHERE bucket < now() - make_interval(days => ?)", days);
    }
}
