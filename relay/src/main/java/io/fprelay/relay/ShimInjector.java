package io.fprelay.relay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prepends the browser transport script ({@code fp-transport.js}), rendered with the
 * tenant's values, to the tag loader response.
 */
@Component
public class ShimInjector {
    private static final Logger log = LoggerFactory.getLogger(ShimInjector.class);

    private final String template;

    private final Map<String, byte[]> rendered = new ConcurrentHashMap<>();

    public ShimInjector() {
        this.template = load();
    }

    private String load() {
        try (InputStream in = getClass().getResourceAsStream("/fp-transport.js")) {
            if (in == null) {
                log.error("fp-transport.js missing from the jar: protected mode cannot inject");
                return "";
            }
            return strip(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("could not read fp-transport.js: {}", e.toString());
            return "";
        }
    }

    private static String strip(String src) {
        StringBuilder out = new StringBuilder(src.length());
        boolean inBlock = false;
        for (String line : src.split("\n", -1)) {
            String t = line.trim();
            if (inBlock) {
                if (t.endsWith("*/")) inBlock = false;
                continue;
            }
            if (t.startsWith("/*")) {
                if (!t.endsWith("*/")) inBlock = true;
                continue;
            }
            if (t.startsWith("//") || t.isEmpty()) continue;
            out.append(line).append('\n');
        }
        return out.toString();
    }

    public boolean available() {
        return !template.isEmpty();
    }

    public byte[] inject(byte[] loaderBody, String scheme, String host, Tenant t) {
        if (template.isEmpty() || loaderBody == null || loaderBody.length == 0) return loaderBody;
        if (host == null || host.isBlank() || !t.protectedMode()) return loaderBody;

        String origin = scheme + "://" + host;
        byte[] shim = rendered.computeIfAbsent(origin + "|" + t.protectedSegment(), k ->
                (template
                        .replace("__ORIGIN__", origin)

                        .replace("__PREFIX__", t.collectPrefix() == null ? "" : t.collectPrefix())
                        .replace("__SEGMENT__", t.protectedSegment())
                        .replace("__KEY__", t.protectedKey() == null ? "" : t.protectedKey())

                        .replace("__LOADER__", t.servingPathBare())
                        .replace("__ALIAS__", t.loaderAlias() == null ? "" : t.loaderAlias())
                        + "\n").getBytes(StandardCharsets.UTF_8));

        byte[] merged = new byte[shim.length + loaderBody.length];
        System.arraycopy(shim, 0, merged, 0, shim.length);
        System.arraycopy(loaderBody, 0, merged, shim.length, loaderBody.length);
        return merged;
    }
}
