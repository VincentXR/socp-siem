package com.socp.search.config.service;

import com.socp.search.config.persistence.store.ParseRuleStore;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解析预览单测：REGEX 命名分组 + KV + JSON 展平。
 */
class ParsePreviewServiceTest {

    private final ParsePreviewService service = new ParsePreviewService(new ParseRuleStore());

    @Test
    void regexNamedGroupsExtractFields() {
        Map<String, Object> r = service.preview("sshd-auth-failed", null, null,
                "Aug 07 01:00:00 web01 sshd[123]: Failed password for admin from 10.0.0.99 port 55006 ssh2");
        assertTrue((Boolean) r.get("matched"), "应命中");
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) r.get("fields");
        assertEquals("admin", fields.get("user"), "user 字段");
        assertEquals("10.0.0.99", fields.get("src_ip"), "src_ip 字段");
        assertEquals("failed", fields.get("auth_result"), "setFields 补充字段");
    }

    @Test
    void regexNoMatchReturnsEmpty() {
        Map<String, Object> r = service.preview(null, "REGEX",
                "(?<srcip>\\d+\\.\\d+\\.\\d+\\.\\d+)",
                "normal line without ip pattern here");
        assertEquals(false, r.get("matched"), "不匹配应 empty");
    }

    @Test
    void kvAndJsonFormats() {
        Map<String, Object> kv = service.preview(null, "KV", null,
                "user=admin action=login result=success");
        assertTrue((Boolean) kv.get("matched"));
        @SuppressWarnings("unchecked")
        Map<String, Object> kvf = (Map<String, Object>) kv.get("fields");
        assertEquals("admin", kvf.get("user"));

        Map<String, Object> json = service.preview(null, "JSON", null,
                "{\"user\":\"admin\",\"meta\":{\"ip\":\"1.2.3.4\"}}");
        assertTrue((Boolean) json.get("matched"));
        @SuppressWarnings("unchecked")
        Map<String, Object> jf = (Map<String, Object>) json.get("fields");
        assertEquals("admin", jf.get("user"));
        assertEquals("1.2.3.4", jf.get("meta.ip"), "JSON 嵌套展平");
    }
}
