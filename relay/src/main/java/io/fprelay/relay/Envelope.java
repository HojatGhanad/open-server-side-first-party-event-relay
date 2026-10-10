package io.fprelay.relay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Protected-transport envelope codec.
 *
 * <p>An envelope is a JSON object {@code {m, u, b}} (method, path and query, body),
 * XOR-obfuscated with the tenant key and base64url encoded. This is obfuscation, not
 * encryption: confidentiality on the wire comes from TLS.
 */
public final class Envelope {
    static final int MAX_BYTES = 256 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Decoded(String method, String pathAndQuery, byte[] body) {}

    private Envelope() {}

    public static Decoded decode(byte[] raw, String key) {
        if (raw == null || raw.length == 0) throw new IllegalArgumentException("empty envelope");
        if (raw.length > MAX_BYTES) throw new IllegalArgumentException("envelope too large");

        byte[] xored = Base64.getUrlDecoder().decode(new String(raw, StandardCharsets.UTF_8).trim());
        byte[] plain = xor(xored, key.getBytes(StandardCharsets.UTF_8));

        JsonNode node;
        try {
            node = MAPPER.readTree(new String(plain, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalArgumentException("envelope did not decode to JSON", e);
        }

        String method = node.path("m").asText("POST").toUpperCase(java.util.Locale.ROOT);
        String u = node.path("u").asText("");
        String b = node.path("b").asText("");

        if (!u.startsWith("/") || u.startsWith("//") || u.contains("..")) {
            throw new IllegalArgumentException("envelope path rejected: " + u);
        }
        if (!method.equals("GET") && !method.equals("POST")) {
            throw new IllegalArgumentException("envelope method rejected: " + method);
        }

        return new Decoded(method, u, b.getBytes(StandardCharsets.UTF_8));
    }

    public static String encode(String method, String pathAndQuery, String body, String key) {
        try {
            String json = MAPPER.writeValueAsString(
                    java.util.Map.of("m", method, "u", pathAndQuery, "b", body == null ? "" : body));
            byte[] out = xor(json.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] xor(byte[] data, byte[] key) {
        if (key.length == 0) return data;
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) out[i] = (byte) (data[i] ^ key[i % key.length]);
        return out;
    }
}
