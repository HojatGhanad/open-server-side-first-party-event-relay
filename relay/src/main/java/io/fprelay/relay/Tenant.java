package io.fprelay.relay;

import java.util.List;

/**
 * One routing entry: a collect hostname and where its traffic is sent.
 */
public record Tenant(
        String host,
        String sgtmUrl,
        String sgtmPreviewUrl,
        String previewToken,
        String collectPrefix,
        String servingPath,
        String loaderAlias,
        String protectedSegment,
        String protectedKey,
        List<String> siteOrigins
) {
    public boolean protectedMode() {
        return protectedSegment != null && !protectedSegment.isBlank();
    }

    public boolean previewing() {
        return previewToken != null && !previewToken.isBlank();
    }

    public String upstream() {
        return previewing() ? sgtmPreviewUrl : sgtmUrl;
    }

    public String servingPathWithSlash() {
        if (servingPath == null || servingPath.isBlank()) return "/";
        String p = servingPath.startsWith("/") ? servingPath : "/" + servingPath;
        return p.endsWith("/") ? p : p + "/";
    }

    public String servingPathBare() {
        String p = servingPathWithSlash();
        return p.length() > 1 ? p.substring(0, p.length() - 1) : "";
    }
}
