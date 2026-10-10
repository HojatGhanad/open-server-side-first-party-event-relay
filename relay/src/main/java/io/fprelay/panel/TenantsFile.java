package io.fprelay.panel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Reads and atomically writes the tenants YAML file shared with the relay.
 */
@Profile("panel")
@Component
public class TenantsFile {
    private static final Logger log = LoggerFactory.getLogger(TenantsFile.class);

    private static final String HEADER = """
            # Tenant routing table, managed by the operations panel.
            # Manual edits are picked up within a few seconds, but comments and formatting
            # are not preserved when the panel saves. See tenants.example.yml for all keys.
            """;

    static final java.util.List<String> KEYS = java.util.List.of(
            "sgtm", "sgtm_preview", "preview_token", "collect_prefix",
            "serving_path", "loader_alias", "segment", "key", "aliases", "site_origins");

    private final PanelProperties props;

    public TenantsFile(PanelProperties props) {
        this.props = props;
    }

    @SuppressWarnings("unchecked")
    public synchronized Map<String, Object> readAll() {
        Path p = Path.of(props.getTenantsFile());
        if (!Files.isReadable(p)) return new LinkedHashMap<>();
        try (InputStream in = Files.newInputStream(p)) {
            Object root = new Yaml().load(in);
            if (!(root instanceof Map)) return new LinkedHashMap<>();
            Object tenants = ((Map<String, Object>) root).get("tenants");
            return tenants instanceof Map ? new LinkedHashMap<>((Map<String, Object>) tenants)
                                          : new LinkedHashMap<>();
        } catch (Exception e) {
            log.error("cannot read {}: {}", p, e.toString());
            throw new IllegalStateException("tenants file unreadable: " + e.getMessage());
        }
    }

    public synchronized void upsert(String host, Map<String, Object> values) {
        String h = normalise(host);
        if (h.isEmpty()) throw new IllegalArgumentException("host is required");

        Map<String, Object> all = readAll();
        Map<String, Object> row = new LinkedHashMap<>();
        Object existing = all.get(h);
        if (existing instanceof Map<?, ?> m) m.forEach((k, v) -> row.put(String.valueOf(k), v));

        for (String k : KEYS) {
            if (values.containsKey(k)) {
                Object v = values.get(k);
                if (v == null || (v instanceof String s && s.isBlank())) row.remove(k);
                else row.put(k, v);
            }
        }
        all.put(h, row);
        write(all);
    }

    public synchronized boolean delete(String host) {
        Map<String, Object> all = readAll();
        boolean removed = all.remove(normalise(host)) != null;
        if (removed) write(all);
        return removed;
    }

    private void write(Map<String, Object> tenants) {
        Path p = Path.of(props.getTenantsFile());
        Path dir = p.toAbsolutePath().getParent();
        try {
            DumperOptions opts = new DumperOptions();
            opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            opts.setIndent(2);
            opts.setPrettyFlow(true);

            Path tmp = Files.createTempFile(dir, ".tenants-", ".yml");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                w.write(HEADER);
                w.write("\n");
                new Yaml(opts).dump(Map.of("tenants", tenants), w);
            }

            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("tenants.yml written: {} host(s)", tenants.size());
        } catch (Exception e) {
            log.error("cannot write {}: {}", p, e.toString());
            throw new IllegalStateException("tenants file not writable: " + e.getMessage());
        }
    }

    static String normalise(String host) {
        if (host == null) return "";
        String h = host.trim().toLowerCase(Locale.ROOT);
        int colon = h.indexOf(':');
        if (colon > -1) h = h.substring(0, colon);
        return h;
    }
}
