package com.socp.search.config.render;

import com.socp.search.config.domain.LogSource;
import com.socp.search.config.domain.ParseFormat;
import com.socp.search.config.domain.SourceType;
import com.socp.search.config.domain.SinkTarget;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VectorConfigRenderer 单测——验证「迁移 com.siem 契约」的渲染产物正确。
 * 集群无关，直接跑。
 */
class VectorConfigRendererTest {

    @Test
    void builtInIngestSinkUsesConfiguredCollectorCredential() {
        LogSource src = LogSource.create("auth-log", SourceType.FILE, ParseFormat.AUTO,
                "/var/log/auth.log", null, null, "prod", true);
        SinkTarget builtInIngest = SinkTarget.create("SEARCH", "GLS_INGEST",
                "http://search:18081/search-config/api/v1/ingest", null, true);

        String toml = new VectorConfigRenderer(null, "collector-secret")
                .render(List.of(src), List.of(builtInIngest));

        assertTrue(toml.contains("request.headers.Authorization = \"Bearer collector-secret\""));
    }

    @Test
    void rendersFileSourceWithGlsIngestSink() {
        LogSource src = LogSource.create("auth-log", SourceType.FILE, ParseFormat.AUTO,
                "/var/log/auth.log", null, null, "prod", true);
        String toml = new VectorConfigRenderer("http://search:18081/search-config/api/v1/ingest")
                .render(List.of(src));

        assertTrue(toml.contains("[sources.src_"), "应包含 file source 块");
        assertTrue(toml.contains("type = \"file\""), "应为 file 源");
        assertTrue(toml.contains("include = [\"/var/log/auth.log\"]"), "路径应透传");
        assertTrue(toml.contains("read_from = \"beginning\""), "FILE 源应有读取模式");
        assertTrue(toml.contains("[transforms.t_"), "每源应有独立 transform");
        assertTrue(toml.contains(".parse_format = \"auto\""), "transform 应标注解析格式");
        assertTrue(toml.contains("[sinks.gls_ingest]"), "应含 SEARCH ingest sink");
        assertTrue(toml.contains("uri = \"http://search:18081/search-config/api/v1/ingest\""), "sink 指向 SEARCH");
        assertTrue(toml.contains("framing.method = \"newline_delimited\""), "必须为 NDJSON");
        assertTrue(toml.contains("healthcheck.enabled = false"), "必须关健康检查");
        assertTrue(toml.contains("encoding.codec = \"json\""), "必须 json codec");
    }

    @Test
    void rendersSocketAndKafkaSources() {
        LogSource sock = LogSource.create("syslog-tcp", SourceType.SYSLOG, ParseFormat.SYSLOG,
                null, "0.0.0.0:5514", null, "prod", true);
        LogSource kafka = LogSource.create("kafka-raw", SourceType.KAFKA, ParseFormat.JSON,
                null, null, "socp-raw", "prod", true);
        String toml = new VectorConfigRenderer(null).render(List.of(sock, kafka));

        assertTrue(toml.contains("type = \"syslog\""), "syslog 源");
        assertTrue(toml.contains("type = \"kafka\""), "kafka 源");
        assertTrue(toml.contains("topics = [\"socp-raw\"]"), "kafka topic 透传");
        // 每个 source 有独立 transform，且各自标注解析格式
        assertTrue(toml.contains(".parse_format = \"syslog\""), "syslog 源标注格式");
        assertTrue(toml.contains(".parse_format = \"json\""), "kafka 源标注格式");
        // sink 聚合所有 transform
        assertTrue(toml.contains("inputs = [\"t_"), "sink 应聚合所有 transform");
    }

    @Test
    void skipsDisabledSources() {
        LogSource off = LogSource.create("off-src", SourceType.FILE, ParseFormat.AUTO,
                "/tmp/x.log", null, null, "prod", false);
        String toml = new VectorConfigRenderer(null).render(List.of(off));
        assertTrue(toml.contains("未启用任何日志源"), "禁用源不应生成采集块");
    }

    @Test
    void syslogProtocolAndNewSourceTypes() {
        // SYSLOG 支持 udp/tcp 协议选择
        LogSource syslogUdp = LogSource.createFull("fw-syslog", SourceType.SYSLOG, ParseFormat.SYSLOG,
                null, "0.0.0.0:514", null, "prod", true,
                "beginning", null, null, List.of(), null,
                "udp", "utf-8", "event_time", "Asia/Shanghai", List.of(), 1, null, null);
        String toml = new VectorConfigRenderer(null).render(List.of(syslogUdp));
        assertTrue(toml.contains("mode = \"udp\""), "SYSLOG UDP 协议");
        assertTrue(toml.contains("address = \"0.0.0.0:514\""), "514 端口透传");

        // 非 Vector 原生类型输出对接说明
        LogSource win = LogSource.createFull("win-security", SourceType.WINDOWS_EVENT, ParseFormat.AUTO,
                null, null, null, "prod", true,
                "beginning", null, null, List.of(), null,
                null, null, null, null, List.of(), 1, null, null);
        String t2 = new VectorConfigRenderer(null).render(List.of(win));
        assertTrue(t2.contains("Winlogbeat"), "Windows 事件给出采集器说明");
    }
}
