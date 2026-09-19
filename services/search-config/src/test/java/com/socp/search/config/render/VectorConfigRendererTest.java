package com.socp.search.config.render;

import com.socp.platform.error.exception.ApiException;
import com.socp.search.config.domain.LogSource;
import com.socp.search.config.domain.ParseFormat;
import com.socp.search.config.domain.SinkTarget;
import com.socp.search.config.domain.SourceType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VectorConfigRenderer 单测——验证「迁移 com.siem 契约」的渲染产物正确，
 * 以及输出目标按源绑定解析、凭据默认脱敏、无可用目标即 409。
 * 集群无关，直接跑。
 */
class VectorConfigRendererTest {

    private static final String INGEST_URI = "http://search:18081/search-config/api/v1/ingest";

    @Test
    void builtInIngestSinkUsesConfiguredCollectorCredentialOnlyForSecretCallers() {
        LogSource src = fileSource("auth-log", null);
        SinkTarget builtInIngest = platformIngest("enabled-without-token");

        String redacted = new VectorConfigRenderer("collector-secret")
                .render(List.of(src), sinkTargetId -> builtInIngest);
        String revealed = new VectorConfigRenderer("collector-secret")
                .render(List.of(src), sinkTargetId -> builtInIngest, true);

        assertTrue(redacted.contains("request.headers.Authorization = \"Bearer "
                + VectorConfigRenderer.REDACTED_TOKEN + "\""), "默认输出必须脱敏");
        assertFalse(redacted.contains("collector-secret"), "脱敏渲染不得含明文 token");
        assertTrue(revealed.contains("request.headers.Authorization = \"Bearer collector-secret\""));
    }

    @Test
    void tenantSinkCredentialIsRedactedUnlessTheCallerMayRenderSecrets() {
        LogSource src = fileSource("auth-log", null);
        SinkTarget tenantSink = new SinkTarget("tenant-sink", "外部 SIEM", "HTTP",
                "https://siem.example/ingest", "Bearer tenant-secret", true, Instant.now());

        String toml = new VectorConfigRenderer("collector-secret")
                .render(List.of(src), sinkTargetId -> tenantSink);

        assertFalse(toml.contains("tenant-secret"));
        assertTrue(toml.contains("uri = \"https://siem.example/ingest\""));
    }

    @Test
    void rendersFileSourceWithBoundSinkAndSiemContract() {
        LogSource src = fileSource("auth-log", "tenant-sink");
        SinkTarget tenantSink = new SinkTarget("tenant-sink", "外部 SIEM", "HTTP",
                "https://siem.example/ingest", null, true, Instant.now());

        String toml = new VectorConfigRenderer(null)
                .render(List.of(src), sinkTargetId -> tenantSink);

        assertTrue(toml.contains("[sources.src_"), "应包含 file source 块");
        assertTrue(toml.contains("type = \"file\""), "应为 file 源");
        assertTrue(toml.contains(".source_id = \"" + src.id() + "\""), "应透传稳定 source_id");
        assertTrue(toml.contains("include = [\"/var/log/auth.log\"]"), "路径应透传");
        assertTrue(toml.contains("read_from = \"beginning\""), "FILE 源应有读取模式");
        assertTrue(toml.contains("[transforms.t_"), "每源应有独立 transform");
        assertTrue(toml.contains(".parse_format = \"auto\""), "transform 应标注解析格式");
        assertTrue(toml.contains("[sinks.gls_ingest]"), "首个输出块沿用历史 sink 名");
        assertTrue(toml.contains("uri = \"https://siem.example/ingest\""), "sink 指向绑定目标");
        assertTrue(toml.contains("framing.method = \"newline_delimited\""), "必须为 NDJSON");
        assertTrue(toml.contains("healthcheck.enabled = false"), "必须关健康检查");
        assertTrue(toml.contains("encoding.codec = \"json\""), "必须 json codec");
    }

    @Test
    void selectsTargetPerSourceBindingInsteadOfFirstEnabledSink() {
        LogSource bound = fileSource("bound", "sink-b");
        LogSource unbound = fileSource("unbound", null);
        SinkTarget sinkA = new SinkTarget("sink-a", "A", "HTTP", "https://a.example", null, true, Instant.now());
        SinkTarget sinkB = new SinkTarget("sink-b", "B", "HTTP", "https://b.example", null, true, Instant.now());
        List<SinkTarget> catalogue = List.of(sinkA, sinkB);

        String toml = new VectorConfigRenderer(null).render(List.of(bound, unbound),
                sinkTargetId -> sinkTargetId == null || sinkTargetId.isBlank()
                        ? sinkA
                        : catalogue.stream().filter(item -> item.id().equals(sinkTargetId)).findFirst().orElse(null));

        assertTrue(toml.contains("[sinks.gls_ingest]"), "第一组沿用历史 sink 名");
        assertTrue(toml.contains("[sinks.gls_ingest_2]"), "第二组必须有独立 sink 名");
        assertTrue(toml.contains("uri = \"https://b.example\""), "绑定 sink 的目标必须生效");
        assertTrue(toml.contains("uri = \"https://a.example\""), "未绑定源使用调用方给出的平台目标");
    }

    @Test
    void missingOrUnusableTargetFailsWithConflictInsteadOfLoopbackFallback() {
        LogSource src = fileSource("auth-log", null);

        ApiException unresolved = assertThrows(ApiException.class,
                () -> new VectorConfigRenderer("collector-secret").render(List.of(src), SinkResolver.NONE));
        assertEquals(409, unresolved.getCode());
        assertFalse(unresolved.getMessage().contains("localhost"), "失败提示不得引导到 loopback 兜底");

        SinkTarget disabled = new SinkTarget("off", "停用目标", "HTTP",
                "https://off.example", null, false, Instant.now());
        ApiException stopped = assertThrows(ApiException.class,
                () -> new VectorConfigRenderer(null).render(List.of(src), sinkTargetId -> disabled));
        assertEquals(409, stopped.getCode());

        SinkTarget blankUri = new SinkTarget("blank", "未配置地址", "GLS_INGEST",
                "  ", null, true, Instant.now());
        assertThrows(ApiException.class,
                () -> new VectorConfigRenderer(null).render(List.of(src), sinkTargetId -> blankUri));
    }

    @Test
    void rendersSocketAndKafkaSources() {
        LogSource sock = LogSource.create("syslog-tcp", SourceType.SYSLOG, ParseFormat.SYSLOG,
                null, "0.0.0.0:5514", null, "prod", true);
        LogSource kafka = LogSource.create("kafka-raw", SourceType.KAFKA, ParseFormat.JSON,
                null, null, "socp-raw", "prod", true);
        String toml = new VectorConfigRenderer(null).render(List.of(sock, kafka), id -> platformIngest("p"));

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
        String toml = new VectorConfigRenderer(null).render(List.of(off), SinkResolver.NONE);
        assertTrue(toml.contains("未启用任何日志源"), "禁用源不应生成采集块");
    }

    @Test
    void syslogProtocolAndNewSourceTypes() {
        // SYSLOG 支持 udp/tcp 协议选择
        LogSource syslogUdp = LogSource.createFull("fw-syslog", SourceType.SYSLOG, ParseFormat.SYSLOG,
                null, "0.0.0.0:514", null, "prod", true,
                "beginning", null, null, List.of(), null,
                "udp", "utf-8", "event_time", "Asia/Shanghai", List.of(), 1, null, null);
        String toml = new VectorConfigRenderer(null).render(List.of(syslogUdp), id -> platformIngest("p"));
        assertTrue(toml.contains("mode = \"udp\""), "SYSLOG UDP 协议");
        assertTrue(toml.contains("address = \"0.0.0.0:514\""), "514 端口透传");

        // 非 Vector 原生类型输出对接说明
        LogSource win = LogSource.createFull("win-security", SourceType.WINDOWS_EVENT, ParseFormat.AUTO,
                null, null, null, "prod", true,
                "beginning", null, null, List.of(), null,
                null, null, null, null, List.of(), 1, null, null);
        String t2 = new VectorConfigRenderer(null).render(List.of(win), id -> platformIngest("p"));
        assertTrue(t2.contains("Winlogbeat"), "Windows 事件给出采集器说明");
    }

    private static LogSource fileSource(String name, String sinkTargetId) {
        return LogSource.createFull(name, SourceType.FILE, ParseFormat.AUTO,
                "/var/log/auth.log", null, null, "prod", true,
                "beginning", null, sinkTargetId, List.of(), null,
                null, "utf-8", "event_time", null, List.of(), 1, null, null);
    }

    private static SinkTarget platformIngest(String id) {
        return new SinkTarget(id, "平台 SEARCH ingest", "GLS_INGEST", INGEST_URI, null, true, Instant.now());
    }
}
