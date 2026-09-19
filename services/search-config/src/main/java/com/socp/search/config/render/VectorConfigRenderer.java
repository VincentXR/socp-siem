package com.socp.search.config.render;

import com.socp.platform.error.exception.ApiException;
import com.socp.search.config.domain.LogSource;
import com.socp.search.config.domain.SinkTarget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 把 {@link LogSource} + {@link SinkTarget} 渲染成 Vector TOML 配置。
 *
 * <p>「迁移 com.siem 能力」的关键落点：直接复用 com.siem 已端到端验证过的 Vector 契约——
 * json codec + newline_delimited（NDJSON）、healthcheck 关闭（否则对只收 POST 的端点探活 405 致启动失败）、
 * disk buffer、retry 5 次（队列满时 SEARCH 回 503 + Retry-After，Vector 退避重投不丢数据）。
 *
 * <p>每个日志源生成独立的 transform（inputs=[sources.src_X]），在 VRL 里按源标注
 * parse_format（解析格式）与 parse_rule_ids（自定义解析规则），SEARCH ingest 侧据此选择解析方式；
 * Vector 本身做采集、轻量 envelope 元数据和传输，保持单一可信解析路径。
 *
 * <p>输出目标按 {@code LogSource.sinkTargetId} 经 {@link SinkResolver} 逐个解析，不再对租户目录
 * 做 findFirst 抢占；解析不到可用目标即返回 HTTP 409，让运维先配置，绝不兜底到进程内硬编码地址。
 * 凭据默认脱敏为 {@link #REDACTED_TOKEN}，只有显式授权（admin + includeSecret）的调用才回填明文。
 */
public class VectorConfigRenderer {

    /** 渲染产物里替代真实采集凭据的占位符；部署时由运维替换或用 SOCP_VECTOR_TOKEN 注入。 */
    public static final String REDACTED_TOKEN = "<SOCP_INGEST_TOKEN>";

    private final String platformAuthToken;

    /**
     * @param platformAuthToken credential of the platform SEARCH ingest target; it is only
     *                          emitted when the caller is explicitly allowed the secret
     */
    public VectorConfigRenderer(String platformAuthToken) {
        this.platformAuthToken = platformAuthToken;
    }

    public String render(List<LogSource> sources, SinkResolver sinks) {
        return render(sources, sinks, false);
    }

    public String render(List<LogSource> sources, SinkResolver sinks, boolean includeSecret) {
        StringBuilder sb = new StringBuilder();
        sb.append(header());

        List<LogSource> active = sources.stream().filter(LogSource::enabled).toList();
        if (active.isEmpty()) {
            sb.append("\n# 未启用任何日志源；在 SEARCH 控制台新增 LogSource 后重新渲染。\n");
            return sb.toString();
        }

        Map<String, SinkTarget> targets = new LinkedHashMap<>();
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (LogSource src : active) {
            String id = "src_" + src.id().replace('-', '_');
            String tName = "t_" + src.id().replace('-', '_');
            SinkTarget target = select(src, sinks);
            targets.put(target.id(), target);
            groups.computeIfAbsent(target.id(), ignored -> new ArrayList<>()).add(tName);
            sb.append(emitSource(src, id));
            sb.append(emitTransform(src, id, tName));
        }
        int index = 0;
        for (Map.Entry<String, List<String>> group : groups.entrySet()) {
            sb.append(sinkBlock(sinkBlockName(index++), targets.get(group.getKey()),
                    group.getValue(), includeSecret));
        }
        sb.append(footer());
        return sb.toString();
    }

    /** 单个源的 sink 名称保持稳定：第一组沿用历史契约名 gls_ingest，多目标时按序号区分。 */
    private static String sinkBlockName(int index) {
        return index == 0 ? "gls_ingest" : "gls_ingest_" + (index + 1);
    }

    private SinkTarget select(LogSource source, SinkResolver sinks) {
        SinkTarget target = sinks == null ? null : sinks.resolve(source.sinkTargetId());
        if (target == null) {
            throw ApiException.of(409, "日志源 " + displayName(source) + " 没有可用输出目标："
                    + "请在「输出目标」创建 sink 并绑定该源，或为平台设置 SOCP_VECTOR_URI 采集入口地址");
        }
        if (target.id() == null || target.id().isBlank()) {
            throw ApiException.of(409, "日志源 " + displayName(source)
                    + " 绑定的输出目标缺少稳定 id，无法生成唯一的 sink 名称");
        }
        if (!target.enabled() || target.uri() == null || target.uri().isBlank()) {
            throw ApiException.of(409, "日志源 " + displayName(source) + " 绑定的输出目标 "
                    + target.name() + " 已停用或未配置投递地址，请先修正后再渲染");
        }
        return target;
    }

    private static String displayName(LogSource source) {
        return source.name() == null || source.name().isBlank() ? source.id() : source.name();
    }

    private String emitSource(LogSource s, String id) {
        StringBuilder b = new StringBuilder();
        b.append("\n# ---- 日志源: ").append(s.name() == null ? s.id() : s.name())
                .append(" (").append(s.type()).append("/").append(s.format()).append(") ----\n");
        switch (s.type()) {
            case FILE -> {
                b.append("[sources.").append(id).append("]\n");
                b.append("type = \"file\"\n");
                b.append("include = [\"").append(s.path() == null ? "demo/sample.log" : s.path()).append("\"]\n");
                b.append("read_from = \"").append(s.readFrom() == null ? "beginning" : s.readFrom()).append("\"\n");
                b.append("data_dir = \".cache/vector\"\n");
                b.append("ignore_older_secs = ").append(s.frequency() == null ? 1 : Math.max(1, s.frequency())).append("\n");
                if (s.multiline() != null && !s.multiline().isBlank()) {
                    b.append("multiline = ").append(s.multiline()).append("\n");
                }
            }
            case SOCKET -> {
                b.append("[sources.").append(id).append("]\n");
                b.append("type = \"socket\"\n");
                b.append("mode = \"").append(protoOf(s, "tcp")).append("\"\n");
                b.append("address = \"").append(s.address() == null ? "0.0.0.0:5514" : s.address()).append("\"\n");
            }
            case SYSLOG -> {
                b.append("[sources.").append(id).append("]\n");
                b.append("type = \"syslog\"\n");
                b.append("address = \"").append(s.address() == null ? "0.0.0.0:5514" : s.address()).append("\"\n");
                b.append("mode = \"").append(protoOf(s, "tcp")).append("\"\n");
            }
            case KAFKA -> {
                b.append("[sources.").append(id).append("]\n");
                b.append("type = \"kafka\"\n");
                b.append("bootstrap_servers = \"kafka:9092\"\n");
                b.append("topics = [\"").append(s.topic() == null ? "socp-raw" : s.topic()).append("\"]\n");
                b.append("group_id = \"").append(s.groupId() == null ? "search-" + id : s.groupId()).append("\"\n");
            }
            case WINDOWS_EVENT -> b.append(agentNote(id, "Windows 事件日志",
                    "Winlogbeat / Windows 事件转发将日志推送到 SEARCH ingest（或先入 Kafka）"));
            case AGENT -> b.append(agentNote(id, "端点 Agent",
                    "HIPS/Falco Agent 通过托管采集入口推送运行时事件，再转发 SEARCH"));
            case HTTP_API -> b.append(agentNote(id, "HTTP/API 推送",
                    "第三方平台 Webhook / SIEM API 直接 POST 到 SEARCH ingest"));
            case DATABASE -> b.append(agentNote(id, "数据库日志",
                    "由 DB 采集器（CDC/日志表）读取后投递 SEARCH ingest"));
            case CLOUD -> b.append(agentNote(id, "云平台日志",
                    "云 SDK 拉取（CloudTrail/CLS/SLS）后投递 SEARCH ingest"));
        }
        return b.toString();
    }

    private String agentNote(String id, String label, String note) {
        return "\n# ---- 日志源: " + label + " ----\n"
                + "# 该类型由对应采集器负责（" + note + "），\n"
                + "# Vector 无需原生 source；采集器输出统一走 NDJSON → SEARCH ingest。\n";
    }

    private static String protoOf(LogSource s, String def) {
        String p = s.protocol() == null ? def : s.protocol().toLowerCase();
        return switch (p) {
            case "udp", "tcp", "tls" -> p;
            default -> def;
        };
    }

    private String emitTransform(LogSource s, String srcId, String tName) {
        String fmt = s.format() == null ? "auto" : s.format().name().toLowerCase();
        String rules = s.parseRuleIds() == null || s.parseRuleIds().isEmpty()
                ? "[]"
                : s.parseRuleIds().stream().map(r -> "\"" + r + "\"").collect(Collectors.joining(", ", "[", "]"));
        String tag = s.collectorTag();
        return """
                \n[transforms.%s]
                type = "remap"
                inputs = ["%s"]
                source = '''
                .message = string!(.message)

                # 采集主机改名 collector_host：避免与正文解析出的 host 冲突（同 com.siem 契约）
                if exists(.host) {
                  .collector_host = del(.host)
                }
                if !exists(.collector_host) {
                  .collector_host = "vector-local"
                }

                # 采集时刻：SEARCH 仅在正文解析不出时间戳时兜底
                .ingested_at = format_timestamp!(now(), format: "%%+")

                # SEARCH 解析标注（ingest 侧按此选择解析方式；Vector 不解析正文）
                .source_id = "%s"
                .collector_tag = "%s"
                .parse_format = "%s"
                .parse_rule_ids = %s

                if exists(.port) { del(.port) }
                '''
                """.formatted(tName, srcId, s.id(), tag, fmt, rules);
    }

    private String sinkBlock(String blockName, SinkTarget target, List<String> transformInputs,
                             boolean includeSecret) {
        String inputs = transformInputs.stream().map(t -> "\"" + t + "\"")
                .collect(Collectors.joining(", ", "[", "]"));
        // 输出目标可选带机机 token（Authorization 头）；dev-bypass=false 时缺失会导致 ingest 401。
        // authToken 语义允许已含 "Bearer " 前缀（SinkTarget 注释），此时不再重复加。
        String authToken = credential(target, includeSecret);
        String auth = authToken == null
                ? ""
                : "\nrequest.headers.Authorization = \""
                    + (authToken.startsWith("Bearer ") ? authToken : "Bearer " + authToken)
                    + "\"";
        String secretNote = authToken == null || !REDACTED_TOKEN.equals(authToken) ? ""
                : "\n# 凭据已脱敏：把 " + REDACTED_TOKEN
                    + " 替换为采集 token，或（管理员）请求 includeSecret=true 取回明文。";
        return """
                \n# ---------- 转发：NDJSON 批量 POST 给输出目标 %s ----------
                [sinks.%s]
                type = "http"
                inputs = %s
                uri = "%s"
                method = "post"

                # json + newline_delimited = NDJSON，与 SEARCH ingest 契约匹配
                encoding.codec = "json"
                framing.method = "newline_delimited"

                # 必须关闭健康检查：ingest 只收 POST，否则探活 405 致 Vector 启动失败
                healthcheck.enabled = false

                batch.max_events = 100
                batch.timeout_secs = 2

                # SEARCH 队列满回 503 + Retry-After，Vector 退避重投不丢数据
                request.retry_attempts = 5
                request.retry_backoff_secs = 2
                request.timeout_secs = 30
                %s%s

                buffer.type = "disk"
                buffer.max_size = 268435488
                buffer.when_full = "block"
                """.formatted(target.name(), blockName, inputs, target.uri(), auth, secretNote);
    }

    /** Effective credential of one target, redacted unless the caller may see the secret. */
    private String credential(SinkTarget target, boolean includeSecret) {
        String token = target.authToken();
        if ((token == null || token.isBlank()) && "GLS_INGEST".equalsIgnoreCase(target.type())) {
            token = platformAuthToken;
        }
        if (token == null || token.isBlank()) return null;
        return includeSecret ? token : REDACTED_TOKEN;
    }

    private String header() {
        return """
                # ============================================================================
                # SOCP / SEARCH 采集流水线 —— 由 search-config 渲染生成（勿手改，改配置后重新渲染）
                # 角色：Vector 采集 + 轻量 envelope 元数据 + 传输，解析/检索/告警归 SEARCH/OpenSearch。
                # 每个 LogSource 一个 transform（标注 parse_format/parse_rule_ids），
                # 输出目标按源的 sinkTargetId 选择；未绑定即使用平台采集入口。
                # 校验：vector validate --no-environment vector.generated.toml
                # 启动：vector --config vector.generated.toml
                # ============================================================================
                """;
    }

    private String footer() {
        return """
                \n# ============================================================================
                # 解析格式：AUTO(责任链自动探测) / SYSLOG / JSON / KV / CEF / LEEF / REGEX(自定义规则)
                # 多机采集：每台一个 Vector，collector_host 自动区分来源，无需改 SEARCH。
                # ============================================================================
                """;
    }
}
