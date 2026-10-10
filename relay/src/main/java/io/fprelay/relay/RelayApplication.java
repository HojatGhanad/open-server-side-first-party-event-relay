package io.fprelay.relay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point. Runs as the relay by default, or as the operations panel
 * when the {@code panel} Spring profile is active.
 */
@SpringBootApplication
@EnableConfigurationProperties(RelayProperties.class)
public class RelayApplication {
    public static void main(String[] args) {
        SpringApplication.run(RelayApplication.class, args);
    }
}
