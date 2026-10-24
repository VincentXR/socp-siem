package com.socp.search.config.infrastructure.opensearch;

import java.nio.charset.StandardCharsets;

/** Single production mapping contract shared by the writer and real OpenSearch tests. */
public final class OpenSearchIndexTemplate {
    public static final String NAME = "socp-events-template";
    public static final String PATH = "/_index_template/" + NAME;

    private static final String PAYLOAD_FORMAT = """
            {
              "index_patterns": ["socp-events-*"],
              "priority": 200,
              "_meta": { "fieldRegistryVersion": "1.0" },
              "template": {
                "settings": {
                  "number_of_shards": %d,
                  "number_of_replicas": %d,
                  "analysis": {
                    "normalizer": {
                      "socp_lowercase": {
                        "type": "custom",
                        "char_filter": [],
                        "filter": ["lowercase"]
                      }
                    }
                  }
                },
                "mappings": {
                  "dynamic_templates": [
                    {
                      "fields_strings": {
                        "path_match": "fields.*",
                        "match_mapping_type": "string",
                        "mapping": {
                          "type": "text",
                          "fields": { "keyword": { "type": "keyword", "ignore_above": 8191 } }
                        }
                      }
                    },
                    {
                      "ecs_strings": {
                        "path_match": "ecs.*",
                        "match_mapping_type": "string",
                        "mapping": {
                          "type": "text",
                          "fields": { "keyword": { "type": "keyword", "ignore_above": 8191 } }
                        }
                      }
                    }
                  ],
                  "properties": {
                    "schemaVersion": { "type": "keyword" },
                    "eventId": { "type": "keyword" },
                    "tenantId": { "type": "keyword" },
                    "timestamp": { "type": "date" },
                    "@timestamp": { "type": "date" },
                    "source": {
                      "type": "keyword",
                      "fields": { "ci": { "type": "keyword", "normalizer": "socp_lowercase" } }
                    },
                    "host": {
                      "type": "keyword",
                      "fields": { "ci": { "type": "keyword", "normalizer": "socp_lowercase" } }
                    },
                    "severity": { "type": "keyword" },
                    "msg": {
                      "type": "text",
                      "fields": {
                        "exact": {
                          "type": "keyword",
                          "normalizer": "socp_lowercase",
                          "ignore_above": 8191
                        }
                      }
                    },
                    "fields": {
                      "type": "object",
                      "dynamic": true,
                      "properties": {
                        "tenant_id": { "type": "keyword" },
                        "src_ip": { "type": "ip", "ignore_malformed": true },
                        "dst_ip": { "type": "ip", "ignore_malformed": true },
                        "http_status": { "type": "integer", "ignore_malformed": true },
                        "count": { "type": "long", "ignore_malformed": true },
                        "bytes": { "type": "long", "ignore_malformed": true },
                        "category": {
                          "type": "keyword",
                          "fields": { "ci": { "type": "keyword", "normalizer": "socp_lowercase" } }
                        }
                      }
                    },
                    "ecs": { "type": "object", "dynamic": true },
                    "tags": { "type": "object", "dynamic": true }
                  }
                }
              }
            }
            """;

    private OpenSearchIndexTemplate() {
    }

    public static String payload() {
        return payload(1, 0);
    }

    public static String payload(int shards, int replicas) {
        if (shards < 1 || shards > 1024 || replicas < 0 || replicas > 32) {
            throw new IllegalArgumentException("invalid OpenSearch index shard/replica counts");
        }
        return PAYLOAD_FORMAT.formatted(shards, replicas);
    }

    public static byte[] payloadBytes() {
        return payload().getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] payloadBytes(int shards, int replicas) {
        return payload(shards, replicas).getBytes(StandardCharsets.UTF_8);
    }
}
