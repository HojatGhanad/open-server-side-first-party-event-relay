package io.fprelay.relay;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Catch-all request handler for collect traffic.
 *
 * <p>Resolves the tenant from the {@code Host} header and forwards the request to that
 * tenant's server-side GTM container. Three request types are handled:
 * <ul>
 *   <li><b>Loader</b>: the tag loader, served with the transport script prepended.</li>
 *   <li><b>Envelope</b>: a protected-transport request, unwrapped before forwarding.</li>
 *   <li><b>Native</b>: any other request, proxied unchanged.</li>
 * </ul>
 * The visitor IP, original host and scheme are forwarded upstream, and {@code Set-Cookie}
 * is passed back to the browser.
 */
@Profile("!panel")
@RestController
public class CollectController {
    private static final Logger log = LoggerFactory.getLogger(CollectController.class);

    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "content-length", "host");

    private final RelayProperties props;
    private final TraceStore store;
    private final TraceAudience audience;
    private final ShimInjector shim;
    private final TenantRegistry tenants;
    private final StatsRegistry stats;
    private final HttpClient http;

    public CollectController(RelayProperties props, TraceStore store, TraceAudience audience,
                             ShimInjector shim, TenantRegistry tenants, StatsRegistry stats) {
        this.props = props;
        this.store = store;
        this.audience = audience;
        this.shim = shim;
        this.tenants = tenants;
        this.stats = stats;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @PostConstruct
    void init() {
        store.configure(props.getTraceBufferSize());
        log.info("relay up: tenants={} {} · shim={} · fallback(sgtm={} prefix='{}' loader='{}' protected={})",
                tenants.size(), tenants.hosts(),
                shim.available() ? "loaded" : "MISSING",
                props.getSgtmUrl(), props.getCollectPrefix(),
                props.getLoaderPath(),
                props.isProtectedMode() ? props.getProtectedSegment() : "off");
    }

    @RequestMapping("/**")
    public ResponseEntity<byte[]> handle(HttpServletRequest req,
                                         @RequestBody(required = false) byte[] body) {
        long started = System.nanoTime();

        String host = req.getHeader("Host");
        Tenant t = tenants.forHost(host);

        String clientIp = clientIp(req);
        String raw = req.getRequestURI();
        String method = req.getMethod();
        byte[] payload = body == null ? new byte[0] : body;
        String path;
        String query = req.getQueryString();
        boolean injecting = false;
        StatsRegistry.Kind kind = StatsRegistry.Kind.OTHER;

        if (!t.loaderAlias().isBlank() && raw.equals(t.loaderAlias())) {
            path = t.servingPathWithSlash();
            query = null;
            injecting = true;
            kind = StatsRegistry.Kind.LOADER;
        } else {
            path = stripPrefix(raw, t.collectPrefix());

            if (!t.servingPathBare().isEmpty()
                    && (path.equals(t.servingPathBare()) || path.equals(t.servingPathWithSlash()))) {
                path = t.servingPathWithSlash();
                injecting = true;
                kind = StatsRegistry.Kind.LOADER;
            } else if (t.protectedMode() && path.equals("/" + t.protectedSegment())) {
                try {
                    Envelope.Decoded d = Envelope.decode(payload, t.protectedKey());
                    method = d.method();
                    int q = d.pathAndQuery().indexOf('?');
                    path = q < 0 ? d.pathAndQuery() : d.pathAndQuery().substring(0, q);
                    query = q < 0 ? null : d.pathAndQuery().substring(q + 1);
                    payload = d.body();
                    kind = StatsRegistry.Kind.ENVELOPE;
                } catch (RuntimeException e) {
                    stats.received(t.host(), StatsRegistry.Kind.ENVELOPE);
                    stats.rejected(t.host());
                    log.debug("envelope rejected from {} on {}: {}", clientIp, host, e.toString());
                    return ResponseEntity.badRequest().body(new byte[0]);
                }
            }
        }

        if (kind == StatsRegistry.Kind.OTHER && path.startsWith("/g/collect")) {
            kind = StatsRegistry.Kind.NATIVE;
        }
        stats.received(t.host(), kind);

        boolean preview = t.previewing();
        String target = t.upstream() + path + (query == null ? "" : "?" + query);

        HttpRequest.Builder out = HttpRequest.newBuilder()
                .uri(URI.create(target))
                .timeout(Duration.ofMillis(props.getUpstreamTimeoutMs()));

        var names = req.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) continue;
            var values = req.getHeaders(name);
            while (values.hasMoreElements()) {
                try {
                    out.header(name, values.nextElement());
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        if (injecting) {
            out.setHeader("Accept-Encoding", "identity");
        }

        out.header("X-Forwarded-For", clientIp);
        out.header("X-Forwarded-Host", host == null ? "" : host);
        out.header("X-Forwarded-Proto", forwardedProto(req));

        if (preview) {
            out.header("X-Gtm-Server-Preview", t.previewToken());
        }

        out.method(method, payload.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(payload));

        boolean traced = audience.isTraced(clientIp);
        int status = 0;
        byte[] respBody = new byte[0];
        Map<String, String> respHeaders = new LinkedHashMap<>();
        String error = null;
        HttpHeaders passthrough = new HttpHeaders();

        try {
            HttpResponse<byte[]> resp = http.send(out.build(), HttpResponse.BodyHandlers.ofByteArray());
            status = resp.statusCode();
            respBody = resp.body();

            if (injecting && status == 200) {
                respBody = shim.inject(respBody, forwardedProto(req), host, t);
            }

            resp.headers().map().forEach((k, v) -> {
                if (HOP_BY_HOP.contains(k.toLowerCase(Locale.ROOT))) return;

                v.forEach(val -> passthrough.add(k, val));
                respHeaders.put(k, String.join("; ", v));
            });
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            status = 502;
            log.warn("upstream failed for {}: {}", host, error);
        }

        long ms = (System.nanoTime() - started) / 1_000_000;
        boolean ok = error == null && status >= 200 && status < 300;
        stats.outcome(t.host(), ms, ok, error != null ? error : (ok ? null : "upstream status " + status));

        if (traced) {
            store.addTrace(new TraceStore.Trace(
                    UUID.randomUUID().toString(),
                    Instant.now().toString(),
                    t.host(),
                    kind.name().toLowerCase(),
                    clientIp,
                    method,
                    path,
                    query,
                    payload.length == 0 ? null : new String(payload),
                    status,
                    respBody.length == 0 ? null : new String(respBody),
                    respHeaders,
                    ms,
                    preview ? "preview" : "tagging",
                    error
            ));
        }

        return ResponseEntity.status(status).headers(passthrough).body(respBody);
    }

    private static String stripPrefix(String uri, String prefix) {
        if (prefix == null || prefix.isBlank() || prefix.equals("/")) return uri;
        return uri.startsWith(prefix) ? uri.substring(prefix.length()) : uri;
    }

    private String clientIp(HttpServletRequest req) {
        return ClientIp.of(req);
    }

    private String forwardedProto(HttpServletRequest req) {
        String p = req.getHeader("X-Forwarded-Proto");
        return (p == null || p.isBlank()) ? req.getScheme() : p;
    }

    static List<String> hopByHop() {
        return List.copyOf(HOP_BY_HOP);
    }
}
