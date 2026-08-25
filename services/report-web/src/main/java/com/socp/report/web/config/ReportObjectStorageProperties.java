package com.socp.report.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed object-storage settings for report archives and downloads. */
@ConfigurationProperties(prefix = "socp.minio")
public class ReportObjectStorageProperties {

    private String url = "http://localhost:9000";
    private String accessKey = "socp";
    private String secretKey = "Socp@2026";
    private String bucket = "socp-reports";
    private boolean enabled = true;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
