package com.socp.search.config.config;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** A production writer must not silently create an unreplicated event index. */
@Component
@Profile("prod")
public class ProductionOpenSearchIndexGuard {

    public ProductionOpenSearchIndexGuard(OpenSearchProperties properties) {
        if (properties.isEnabled() && properties.getIndexReplicas() < 1) {
            throw new IllegalStateException(
                    "socp.opensearch.index-replicas must be at least 1 in production; "
                            + "provision enough OpenSearch data nodes for every replica");
        }
    }
}
