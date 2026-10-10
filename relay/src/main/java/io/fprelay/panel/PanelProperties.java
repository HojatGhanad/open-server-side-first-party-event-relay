package io.fprelay.panel;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Panel configuration, bound from {@code panel.*} properties or {@code PANEL_*}
 * environment variables.
 */
@Profile("panel")
@Component
@ConfigurationProperties(prefix = "panel")
public class PanelProperties {
    private String relayUrl = "http://relay:8090";

    private String relayToken = "";

    private String tenantsFile = "/etc/fp/tenants.yml";

    private int pollSeconds = 10;

    private String adminUser = "admin";

    private String adminPasswordHash = "";

    private int sessionHours = 12;

    private int retentionDays = 90;

    public String getRelayUrl() { return relayUrl; }
    public void setRelayUrl(String v) { this.relayUrl = v; }

    public String getRelayToken() { return relayToken; }
    public void setRelayToken(String v) { this.relayToken = v; }

    public String getTenantsFile() { return tenantsFile; }
    public void setTenantsFile(String v) { this.tenantsFile = v; }

    public int getPollSeconds() { return pollSeconds; }
    public void setPollSeconds(int v) { this.pollSeconds = v; }

    public String getAdminUser() { return adminUser; }
    public void setAdminUser(String v) { this.adminUser = v; }

    public String getAdminPasswordHash() { return adminPasswordHash; }
    public void setAdminPasswordHash(String v) { this.adminPasswordHash = v; }

    public int getSessionHours() { return sessionHours; }
    public void setSessionHours(int v) { this.sessionHours = v; }

    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int v) { this.retentionDays = v; }
}
