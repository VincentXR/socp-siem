package com.socp.search.config.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Centralized OpenSearch connection settings shared by reader and writer. */
@ConfigurationProperties(prefix = "socp.opensearch")
public class OpenSearchProperties {

    private String url = "https://localhost:9200";
    private String username = "admin";
    private String password = "Socp!Sec2026xK";
    private boolean enabled = true;
    private String searchIndex = "socp-events-*";
    private int indexShards = 1;
    private int indexReplicas = 0;
    private Tls tls = new Tls();

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getSearchIndex() { return searchIndex; }
    public void setSearchIndex(String searchIndex) { this.searchIndex = searchIndex; }
    public int getIndexShards() { return indexShards; }
    public void setIndexShards(int indexShards) {
        if (indexShards < 1 || indexShards > 1024) {
            throw new IllegalArgumentException("socp.opensearch.index-shards must be between 1 and 1024");
        }
        this.indexShards = indexShards;
    }
    public int getIndexReplicas() { return indexReplicas; }
    public void setIndexReplicas(int indexReplicas) {
        if (indexReplicas < 0 || indexReplicas > 32) {
            throw new IllegalArgumentException("socp.opensearch.index-replicas must be between 0 and 32");
        }
        this.indexReplicas = indexReplicas;
    }

    public Tls getTls() { return tls; }
    public void setTls(Tls tls) { this.tls = tls == null ? new Tls() : tls; }

    public static class Tls {
        /** Explicit local-only escape hatch; production validation rejects it. */
        private boolean insecureSkipVerify;
        private String trustStore;
        private String trustStorePassword;

        public boolean isInsecureSkipVerify() { return insecureSkipVerify; }
        public void setInsecureSkipVerify(boolean insecureSkipVerify) {
            this.insecureSkipVerify = insecureSkipVerify;
        }
        public String getTrustStore() { return trustStore; }
        public void setTrustStore(String trustStore) { this.trustStore = trustStore; }
        public String getTrustStorePassword() { return trustStorePassword; }
        public void setTrustStorePassword(String trustStorePassword) {
            this.trustStorePassword = trustStorePassword;
        }
    }
}
