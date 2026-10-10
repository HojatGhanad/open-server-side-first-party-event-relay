package io.fprelay.relay;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Relay configuration, bound from {@code fp.*} properties or {@code FP_*} environment
 * variables. Also acts as the default tenant when no tenants file is configured.
 */
@ConfigurationProperties(prefix = "fp")
public class RelayProperties {
    private String sgtmUrl = "http://sgtm-tagging:8080";

    private String sgtmPreviewUrl = "http://sgtm-preview:8080";

    private String previewToken = "";

    private String collectPrefix = "/metrics";

    private String deploymentMode = "same_origin";

    private List<String> siteOrigins = List.of();

    private String protectedSegment = "";

    private String protectedKey = "";

    private String loaderPath = "";

    private String tenantsFile = "";

    private String adminToken = "";

    private String traceToken = "";

    private List<String> traceClientIps = List.of();

    private int traceEnrollHours = 8;

    private int traceBufferSize = 200;

    private int upstreamTimeoutMs = 10_000;

    public String getSgtmUrl() { return sgtmUrl; }
    public void setSgtmUrl(String v) { this.sgtmUrl = v; }

    public String getSgtmPreviewUrl() { return sgtmPreviewUrl; }
    public void setSgtmPreviewUrl(String v) { this.sgtmPreviewUrl = v; }

    public String getPreviewToken() { return previewToken; }
    public void setPreviewToken(String v) { this.previewToken = v; }

    public String getCollectPrefix() { return collectPrefix; }
    public void setCollectPrefix(String v) { this.collectPrefix = v; }

    public String getDeploymentMode() { return deploymentMode; }
    public void setDeploymentMode(String v) { this.deploymentMode = v; }

    public List<String> getSiteOrigins() { return siteOrigins; }
    public void setSiteOrigins(List<String> v) { this.siteOrigins = v; }

    public boolean isSubdomainMode() { return "subdomain".equalsIgnoreCase(deploymentMode); }

    public String getTenantsFile() { return tenantsFile; }
    public void setTenantsFile(String v) { this.tenantsFile = v; }

    public String getLoaderPath() { return loaderPath; }
    public void setLoaderPath(String v) { this.loaderPath = v; }

    public String getProtectedSegment() { return protectedSegment; }
    public void setProtectedSegment(String v) { this.protectedSegment = v; }

    public String getProtectedKey() { return protectedKey; }
    public void setProtectedKey(String v) { this.protectedKey = v; }

    public boolean isProtectedMode() {
        return protectedSegment != null && !protectedSegment.isBlank();
    }

    public String getAdminToken() { return adminToken; }
    public void setAdminToken(String v) { this.adminToken = v; }

    public String getTraceToken() { return traceToken; }
    public void setTraceToken(String v) { this.traceToken = v; }

    public List<String> getTraceClientIps() { return traceClientIps; }
    public void setTraceClientIps(List<String> v) { this.traceClientIps = v; }

    public int getTraceEnrollHours() { return traceEnrollHours; }
    public void setTraceEnrollHours(int v) { this.traceEnrollHours = v; }

    public int getTraceBufferSize() { return traceBufferSize; }
    public void setTraceBufferSize(int v) { this.traceBufferSize = v; }

    public int getUpstreamTimeoutMs() { return upstreamTimeoutMs; }
    public void setUpstreamTimeoutMs(int v) { this.upstreamTimeoutMs = v; }
}
