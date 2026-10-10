package io.fprelay.relay;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the visitor IP from {@code X-Forwarded-For}, {@code X-Real-IP} or the socket.
 */
final class ClientIp {
    private ClientIp() {}

    static String of(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        return req.getRemoteAddr();
    }
}
