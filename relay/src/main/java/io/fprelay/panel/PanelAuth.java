package io.fprelay.panel;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-operator login with BCrypt and in-memory sessions. Guards every panel route.
 */
@Profile("panel")
@Configuration
public class PanelAuth {
    private static final Logger log = LoggerFactory.getLogger(PanelAuth.class);
    private static final SecureRandom RNG = new SecureRandom();
    public static final String COOKIE = "fp_panel";

    private final PanelProperties props;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private final Map<String, Instant> sessions = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAttempt = new ConcurrentHashMap<>();

    public PanelAuth(PanelProperties props) {
        this.props = props;
        if (props.getAdminPasswordHash() == null || props.getAdminPasswordHash().isBlank()) {
            log.error("PANEL_ADMIN_PASSWORD_HASH is empty: nobody can log in. "
                    + "See docs/panel.md for how to generate one.");
        }
    }

    public String login(String user, String password, String ip) {
        long now = System.currentTimeMillis();
        Long prev = lastAttempt.get(ip);
        if (prev != null && now - prev < 1000) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        lastAttempt.put(ip, now);

        String hash = props.getAdminPasswordHash();
        if (hash == null || hash.isBlank()) return null;
        if (user == null || !user.equals(props.getAdminUser())) {
            bcrypt.matches(password == null ? "" : password, hash);
            return null;
        }
        if (!bcrypt.matches(password == null ? "" : password, hash)) return null;

        byte[] raw = new byte[32];
        RNG.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        sessions.put(token, Instant.now().plusSeconds(props.getSessionHours() * 3600L));
        log.info("panel login from {}", ip);
        return token;
    }

    public void logout(String token) {
        if (token != null) sessions.remove(token);
    }

    public boolean valid(String token) {
        if (token == null || token.isBlank()) return false;
        Instant until = sessions.get(token);
        if (until == null) return false;
        if (Instant.now().isAfter(until)) {
            sessions.remove(token);
            return false;
        }
        return true;
    }

    public Cookie cookie(String token, boolean secure) {
        Cookie c = new Cookie(COOKIE, token == null ? "" : token);
        c.setHttpOnly(true);
        c.setSecure(secure);
        c.setPath("/");
        c.setMaxAge(token == null ? 0 : props.getSessionHours() * 3600);
        c.setAttribute("SameSite", "Strict");
        return c;
    }

    public static String tokenOf(HttpServletRequest req) {
        if (req.getCookies() == null) return null;
        for (Cookie c : req.getCookies()) if (COOKIE.equals(c.getName())) return c.getValue();
        return null;
    }

    @Bean
    public FilterRegistrationBean<Filter> panelGuard() {
        Filter f = new Filter() {
            @Override
            public void doFilter(ServletRequest rq, ServletResponse rs, FilterChain chain)
                    throws IOException, ServletException {
                HttpServletRequest req = (HttpServletRequest) rq;
                HttpServletResponse res = (HttpServletResponse) rs;
                String path = req.getRequestURI();

                boolean open = path.equals("/login") || path.equals("/api/login")
                        || path.equals("/favicon.ico") || path.startsWith("/assets/");
                if (open || valid(tokenOf(req))) {
                    chain.doFilter(rq, rs);
                    return;
                }
                if (path.startsWith("/api/")) {
                    res.setStatus(401);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"error\":\"not authenticated\"}");
                } else {
                    res.sendRedirect("/login");
                }
            }
        };
        FilterRegistrationBean<Filter> reg = new FilterRegistrationBean<>(f);
        reg.addUrlPatterns("/*");
        reg.setOrder(1);
        return reg;
    }
}
