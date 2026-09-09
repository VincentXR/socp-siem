package com.socp.soar.web.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runtime selection for the secret reference provider.  References are
 * resolved on demand by Activities so a Kubernetes volume rotation or a Vault
 * version change is observed without republishing a playbook.
 */
@ConfigurationProperties(prefix = "socp.soar.secrets")
public class SoarSecretProperties {
    private String backend = "env";
    private String kubernetesMountPath = "/var/run/secrets/socp";
    private String vaultEndpoint = "";
    private String vaultTokenRef = "";
    private boolean allowInsecure = false;
    private boolean allowEnvironmentFallback = true;
    private int connectTimeoutMs = 3_000;
    private int requestTimeoutMs = 5_000;

    public String getBackend() { return backend; }
    public void setBackend(String backend) { this.backend = backend; }
    public String getKubernetesMountPath() { return kubernetesMountPath; }
    public void setKubernetesMountPath(String kubernetesMountPath) { this.kubernetesMountPath = kubernetesMountPath; }
    public String getVaultEndpoint() { return vaultEndpoint; }
    public void setVaultEndpoint(String vaultEndpoint) { this.vaultEndpoint = vaultEndpoint; }
    public String getVaultTokenRef() { return vaultTokenRef; }
    public void setVaultTokenRef(String vaultTokenRef) { this.vaultTokenRef = vaultTokenRef; }
    public boolean isAllowInsecure() { return allowInsecure; }
    public void setAllowInsecure(boolean allowInsecure) { this.allowInsecure = allowInsecure; }
    public boolean isAllowEnvironmentFallback() { return allowEnvironmentFallback; }
    public void setAllowEnvironmentFallback(boolean allowEnvironmentFallback) {
        this.allowEnvironmentFallback = allowEnvironmentFallback;
    }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int getRequestTimeoutMs() { return requestTimeoutMs; }
    public void setRequestTimeoutMs(int requestTimeoutMs) { this.requestTimeoutMs = requestTimeoutMs; }
}
