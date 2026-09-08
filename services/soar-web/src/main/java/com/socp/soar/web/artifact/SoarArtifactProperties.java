package com.socp.soar.web.artifact;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for durable evidence objects. */
@ConfigurationProperties(prefix = "socp.soar.artifacts")
public class SoarArtifactProperties {
    private String backend = "inline";
    private String endpoint = "";
    private String region = "us-east-1";
    private String bucket = "";
    private String accessKeyRef = "";
    private String secretKeyRef = "";
    private boolean pathStyle = true;
    private boolean allowInsecure = false;
    private int connectTimeoutMs = 3_000;
    private int requestTimeoutMs = 15_000;

    public String getBackend() { return backend; }
    public void setBackend(String backend) { this.backend = backend; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public String getAccessKeyRef() { return accessKeyRef; }
    public void setAccessKeyRef(String accessKeyRef) { this.accessKeyRef = accessKeyRef; }
    public String getSecretKeyRef() { return secretKeyRef; }
    public void setSecretKeyRef(String secretKeyRef) { this.secretKeyRef = secretKeyRef; }
    public boolean isPathStyle() { return pathStyle; }
    public void setPathStyle(boolean pathStyle) { this.pathStyle = pathStyle; }
    public boolean isAllowInsecure() { return allowInsecure; }
    public void setAllowInsecure(boolean allowInsecure) { this.allowInsecure = allowInsecure; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int getRequestTimeoutMs() { return requestTimeoutMs; }
    public void setRequestTimeoutMs(int requestTimeoutMs) { this.requestTimeoutMs = requestTimeoutMs; }
}
