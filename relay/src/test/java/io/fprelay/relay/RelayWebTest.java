package io.fprelay.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "fp.trace-token=test-trace-token",
        "fp.admin-token=test-admin-token-0123456789",
        "fp.collect-prefix=/metrics"
})
class RelayWebTest {
    static HttpServer sgtm;
    static final AtomicInteger sgtmHits = new AtomicInteger();
    static Path tenantsFile;

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;

    static final String KEY = "TestKey0123456789abcdefghijklmno";
    static final ObjectMapper M = new ObjectMapper();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        startFakeSgtm();
        registry.add("fp.tenants-file", () -> tenantsFile.toString());
    }

    static void startFakeSgtm() throws Exception {
        if (sgtm != null) return;
        sgtm = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        sgtm.createContext("/", ex -> {
            sgtmHits.incrementAndGet();
            ex.getRequestBody().readAllBytes();
            byte[] body;
            int code;
            if (ex.getRequestURI().getPath().startsWith("/Serve1234/")) {
                body = "// Copyright 2012 Google Inc.\nvar gtm=1;\n".getBytes(StandardCharsets.UTF_8);
                code = 200;
                ex.getResponseHeaders().add("Content-Type", "application/javascript");
            } else {
                body = ("SAW " + ex.getRequestURI()).getBytes(StandardCharsets.UTF_8);
                code = 200;
                ex.getResponseHeaders().add("Set-Cookie", "FPID=abc; Path=/; HttpOnly");
            }
            ex.sendResponseHeaders(code, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });
        sgtm.start();

        tenantsFile = Files.createTempFile("tenants-", ".yml");
        Files.writeString(tenantsFile, """
            tenants:
              localhost:
                sgtm:           http://127.0.0.1:%d
                collect_prefix: /metrics
                serving_path:   /Serve1234
                segment:        SEGMENT123
                key:            %s
                aliases:        [edge.example.com]
                site_origins:   [https://example.com]
            """.formatted(sgtm.getAddress().getPort(), KEY));
    }

    @AfterAll
    static void stop() throws IOException {
        if (sgtm != null) sgtm.stop(0);
        if (tenantsFile != null) Files.deleteIfExists(tenantsFile);
    }

    private HttpHeaders host() {
        return new HttpHeaders();
    }

    private <T> ResponseEntity<T> get(String path, Class<T> type) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(host()), type);
    }

    @Test
    @DisplayName("trace endpoints survive the /** catch-all")
    void traceEndpointsStillRouted() {
        ResponseEntity<String> ok = get("/_t/test-trace-token/summary.json", String.class);
        assertEquals(HttpStatus.OK, ok.getStatusCode());
        assertTrue(ok.getBody().contains("tenants"), "summary should be per tenant now");
    }

    @Test
    @DisplayName("a wrong trace token 404s rather than being proxied upstream")
    void wrongTraceToken() {
        assertEquals(HttpStatus.NOT_FOUND, get("/_t/nope/summary.json", String.class).getStatusCode());
    }

    @Test
    @DisplayName("/_admin needs the bearer token, and 404s without it")
    void adminNeedsToken() {
        assertEquals(HttpStatus.NOT_FOUND, get("/_admin/stats", String.class).getStatusCode());

        HttpHeaders h = host();
        h.setBearerAuth("wrong-token-entirely-000000");
        assertEquals(HttpStatus.NOT_FOUND,
                rest.exchange("/_admin/stats", HttpMethod.GET, new HttpEntity<>(h), String.class).getStatusCode());

        h = host();
        h.setBearerAuth("test-admin-token-0123456789");
        assertEquals(HttpStatus.OK,
                rest.exchange("/_admin/stats", HttpMethod.GET, new HttpEntity<>(h), String.class).getStatusCode());
    }

    @Test
    @DisplayName("loader, envelope and native traffic are counted apart, and junk never counts as failure")
    void classification() throws Exception {
        JsonNode before = tenantStats();
        long l0 = kind(before, "loader"), e0 = kind(before, "envelope"), n0 = kind(before, "native");
        long rej0 = before.path("rejected").asLong(), fail0 = before.path("failed").asLong();

        get("/metrics/Serve1234/", String.class);
        postEnvelope("/g/collect?v=2&tid=G-X&en=a");
        postEnvelope("/g/collect?v=2&tid=G-X&en=b");
        get("/metrics/g/collect?v=2&en=c", String.class);
        postRaw("total-junk-not-an-envelope");

        JsonNode after = tenantStats();
        assertEquals(1, kind(after, "loader") - l0, "loader");
        assertEquals(3, kind(after, "envelope") - e0, "envelope: 2 good + 1 malformed");
        assertEquals(1, kind(after, "native") - n0, "native");
        assertEquals(1, after.path("rejected").asLong() - rej0, "malformed envelope");
        assertEquals(0, after.path("failed").asLong() - fail0,
                "a malformed envelope must NOT count as a delivery failure");
    }

    private JsonNode tenantStats() throws Exception {
        return stats().path("tenants").path("localhost");
    }

    private static long kind(JsonNode tenant, String k) {
        return tenant.path("received_by_kind").path(k).asLong();
    }

    @Test
    @DisplayName("the shim is prepended to the loader, above Google's runtime")
    void shimInjected() {
        ResponseEntity<String> r = get("/metrics/Serve1234/", String.class);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        String body = r.getBody();
        assertNotNull(body);
        assertTrue(body.indexOf("SEGMENT123") < body.indexOf("Copyright 2012 Google"),
                "the shim must run before Google's runtime, not after");
        assertFalse(body.contains("__SEGMENT__"), "placeholders must be substituted");
        assertFalse(body.contains("__ORIGIN__"), "placeholders must be substituted");
    }

    @Test
    @DisplayName("an envelope is unwrapped into the native request sGTM expects")
    void envelopeUnwrapped() {
        ResponseEntity<String> r = postEnvelope("/g/collect?v=2&tid=G-UNWRAP&en=z");
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertTrue(r.getBody().contains("/g/collect?v=2&tid=G-UNWRAP&en=z"),
                "sGTM should see the original path and query, got: " + r.getBody());
    }

    @Test
    @DisplayName("Set-Cookie survives the hop, so the FPID cookie reaches the site's domain")
    void setCookiePassesThrough() {
        ResponseEntity<String> r = get("/metrics/g/collect?v=2", String.class);
        assertTrue(String.valueOf(r.getHeaders().get(HttpHeaders.SET_COOKIE)).contains("FPID"));
    }

    private JsonNode stats() throws Exception {
        HttpHeaders h = host();
        h.setBearerAuth("test-admin-token-0123456789");
        return M.readTree(rest.exchange("/_admin/stats", HttpMethod.GET,
                new HttpEntity<>(h), String.class).getBody());
    }

    private ResponseEntity<String> postEnvelope(String pathAndQuery) {
        return postRaw(Envelope.encode("POST", pathAndQuery, "", KEY));
    }

    private ResponseEntity<String> postRaw(String body) {
        HttpHeaders h = host();
        h.setContentType(MediaType.TEXT_PLAIN);
        return rest.exchange("/metrics/SEGMENT123", HttpMethod.POST,
                new HttpEntity<>(body, h), String.class);
    }

    static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
