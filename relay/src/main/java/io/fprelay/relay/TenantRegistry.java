package io.fprelay.relay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads tenants from the YAML routing table and reloads it automatically when the file
 * changes. Falls back to the flat {@code FP_*} configuration when no file is present, and
 * keeps the last good table if a new version cannot be parsed.
 */
@Component
public class TenantRegistry {
    private static final Logger log = LoggerFactory.getLogger(TenantRegistry.class);
    private static final long RECHECK_MS = 5_000;

    private final RelayProperties props;

    private volatile Map<String, Tenant> byHost = Map.of();
    private volatile long loadedMtime = -1;
    private volatile long lastCheck = 0;

    public TenantRegistry(RelayProperties props) {
        this.props = props;
        reloadIfChanged(true);
    }

    public Tenant forHost(String hostHeader) {
        reloadIfChanged(false);
        Map<String, Tenant> map = byHost;
        if (!map.isEmpty()) {
            Tenant t = map.get(normalise(hostHeader));
            if (t != null) return t;
        }
        return fallback();
    }

    public int size() {
        return byHost.size();
    }

    public List<String> hosts() {
        return new ArrayList<>(byHost.keySet());
    }

    static String normalise(String host) {
        if (host == null) return "";
        String h = host.trim().toLowerCase(Locale.ROOT);
        int colon = h.indexOf(':');
        if (colon > -1) h = h.substring(0, colon);
        return h;
    }

    private Tenant fallback() {
        return new Tenant(
                "*",
                props.getSgtmUrl(),
                props.getSgtmPreviewUrl(),
                props.getPreviewToken(),
                props.getCollectPrefix(),
                props.getLoaderPath(),
                "",
                props.getProtectedSegment(),
                props.getProtectedKey(),
                props.getSiteOrigins());
    }

    private synchronized void reloadIfChanged(boolean initial) {
        long now = System.currentTimeMillis();
        if (!initial && now - lastCheck < RECHECK_MS) return;
        lastCheck = now;

        String file = props.getTenantsFile();
        if (file == null || file.isBlank()) return;

        Path p = Path.of(file);
        try {
            if (!Files.isReadable(p)) {
                if (initial) log.info("no tenants file at {}: using the flat FP_* configuration", file);
                return;
            }
            long mtime = Files.getLastModifiedTime(p).toMillis();
            if (mtime == loadedMtime) return;

            Map<String, Tenant> parsed = parse(p);
            byHost = parsed;
            loadedMtime = mtime;
            log.info("tenants loaded from {}: {} host(s) {}", file, parsed.size(), parsed.keySet());
        } catch (Exception e) {
            log.error("tenants file {} could not be loaded ({}): keeping {} previously loaded host(s)",
                    file, e.toString(), byHost.size());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Tenant> parse(Path p) throws Exception {
        Map<String, Tenant> out = new HashMap<>();
        try (InputStream in = Files.newInputStream(p)) {
            Object root = new Yaml().load(in);
            if (!(root instanceof Map)) return out;
            Object tenants = ((Map<String, Object>) root).get("tenants");
            if (!(tenants instanceof Map)) return out;

            ((Map<String, Object>) tenants).forEach((host, raw) -> {
                if (!(raw instanceof Map)) return;
                Map<String, Object> m = (Map<String, Object>) raw;
                Tenant t = new Tenant(
                        normalise(host),
                        str(m, "sgtm", props.getSgtmUrl()),
                        str(m, "sgtm_preview", props.getSgtmPreviewUrl()),
                        str(m, "preview_token", ""),
                        str(m, "collect_prefix", ""),
                        str(m, "serving_path", ""),
                        str(m, "loader_alias", ""),
                        str(m, "segment", ""),
                        str(m, "key", ""),
                        list(m, "site_origins"));
                out.put(t.host(), t);

                for (String alias : list(m, "aliases")) {
                    out.put(normalise(alias), new Tenant(normalise(alias), t.sgtmUrl(), t.sgtmPreviewUrl(),
                            t.previewToken(), t.collectPrefix(), t.servingPath(), t.loaderAlias(),
                            t.protectedSegment(), t.protectedKey(), t.siteOrigins()));
                }
            });
        }
        return out;
    }

    private static String str(Map<String, Object> m, String k, String dflt) {
        Object v = m.get(k);
        return v == null ? dflt : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static List<String> list(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v instanceof List) {
            List<String> out = new ArrayList<>();
            ((List<Object>) v).forEach(o -> out.add(String.valueOf(o)));
            return out;
        }
        if (v instanceof String s && !s.isBlank()) {
            return List.of(s.split("\\s*,\\s*"));
        }
        return List.of();
    }
}
