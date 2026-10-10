package io.fprelay.relay;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * Per-tenant CORS for subdomain deployments. Same-origin deployments need none.
 */
@Profile("!panel")
@Configuration
public class CorsConfig {
    private static final Logger log = LoggerFactory.getLogger(CorsConfig.class);

    @Bean
    public CorsFilter corsFilter(TenantRegistry tenants, RelayProperties props) {
        log.info("CORS resolved per tenant from Host (fallback mode={}, origins={})",
                props.getDeploymentMode(), props.getSiteOrigins());
        return new CorsFilter(source(tenants));
    }

    private CorsConfigurationSource source(TenantRegistry tenants) {
        return (HttpServletRequest request) -> {
            String uri = request.getRequestURI();
            if (uri != null && uri.startsWith("/_t/")) return null;

            Tenant t = tenants.forHost(request.getHeader("Host"));
            List<String> origins = t.siteOrigins();
            if (origins == null || origins.isEmpty()) {
                return null;
            }

            CorsConfiguration cfg = new CorsConfiguration();

            cfg.setAllowedOrigins(origins);
            cfg.setAllowCredentials(true);
            cfg.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
            cfg.setAllowedHeaders(List.of("*"));
            cfg.setMaxAge(7200L);
            return cfg;
        };
    }
}
