package com.socp.search.config.infrastructure.opensearch;

import java.nio.charset.StandardCharsets;

/** Single production mapping contract shared by the writer and real OpenSearch tests. */
public final class OpenSearchIndexTemplate {
    public static final String NAME = "socp-events-template";
    public static final String PATH = "/_index_template/" + NAME;

    private static final String PAYLOAD = """
            {
              "index_patterns": ["socp-events-*"],
              "priority": 200,
              "_meta": { "fieldRegistryVersion": "1.0" },
              "template": {
                "settings": {
                  "number_of_shards": 1,
                  "number_of_replicas": 0,
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
                        "src_ip": { "type": "ip" },
                        "dst_ip": { "type": "ip" },
                        "http_status": { "type": "integer" },
                        "count": { "type": "long" },
                        "bytes": { "type": "long" },
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
        return PAYLOAD;
    }

    public static byte[] payloadBytes() {
        return PAYLOAD.getBytes(StandardCharsets.UTF_8);
    }
}
