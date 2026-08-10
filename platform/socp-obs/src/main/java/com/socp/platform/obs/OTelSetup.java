package com.socp.platform.obs;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenTelemetry SDK 初始化（2026-08-10）：
 * <ul>
 *   <li>W3C trace context propagator（与 TraceIdFilter/Kafka header 的 traceparent 完全兼容）</li>
 *   <li>OTLP gRPC exporter → Jaeger all-in-one（localhost:4317）</li>
 * </ul>
 * <p><b>默认关闭</b>：{@code socp.obs.tracing.enabled}（环境变量 {@code SOCP_TRACING_ENABLED}）默认
 * {@code false}。默认不连接 4317——否则 Jaeger 没起来的环境（核心 8 件套不含 Jaeger，它在 extra
 * profile）会被 BatchSpanProcessor 的导出失败刷满 ERROR 日志。开启后全链路 trace 进 Jaeger 可视化。
 *
 * <p>容错：Jaeger 不可达 / SDK 初始化异常时打 WARN 并回退（GlobalOpenTelemetry 保持 noop，
 * TraceIdFilter 走手写 traceparent 逻辑），**绝不阻塞业务启动**。
 */
public final class OTelSetup {

    private static final Logger log = LoggerFactory.getLogger(OTelSetup.class);
    private static volatile boolean initialized = false;
    /** 只决策一次，避免每个请求重复判断/打日志。 */
    private static volatile boolean decided = false;

    private OTelSetup() {
    }

    public static synchronized void initIfNeeded() {
        initIfNeeded("");
    }

    public static synchronized void initIfNeeded(String serviceName) {
        initIfNeeded(serviceName, isEnabledByEnv());
    }

    /**
     * @param enabled 是否启用 OTLP 上报（默认应走 false，见类注释）
     */
    public static synchronized void initIfNeeded(String serviceName, boolean enabled) {
        if (decided) return;
        decided = true;
        if (!enabled) {
            log.info("OpenTelemetry tracing 未启用（socp.obs.tracing.enabled=false），沿用手写 traceparent，不连接 4317");
            return;
        }
        try {
            String endpoint = System.getenv().getOrDefault("SOCP_OTLP_ENDPOINT", "http://localhost:4317");
            OtlpGrpcSpanExporter exporter = OtlpGrpcSpanExporter.builder()
                    .setEndpoint(endpoint)
                    .build();
            // service.name：优先显式传入（Spring Environment 的 spring.application.name），
            // 回退 system property / 环境变量 / 默认
            String svc = serviceName;
            if (svc.isBlank()) svc = System.getProperty("spring.application.name", "");
            if (svc.isBlank()) svc = System.getenv().getOrDefault("SOCP_SERVICE_NAME", "socp-unknown");
            Resource resource = Resource.getDefault().merge(Resource.create(Attributes.of(
                    AttributeKey.stringKey("service.name"), svc)));
            SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                    .setResource(resource)
                    .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                    .build();
            OpenTelemetry otel = OpenTelemetrySdk.builder()
                    .setTracerProvider(tracerProvider)
                    .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                    .build();
            GlobalOpenTelemetry.set(otel);
            initialized = true;
            log.info("OpenTelemetry SDK 已初始化，trace 上报到 {}", endpoint);
        } catch (Throwable t) {
            log.warn("OpenTelemetry SDK 初始化失败（回退手写 traceparent）: {}", t.getMessage());
        }
    }

    private static boolean isEnabledByEnv() {
        return Boolean.parseBoolean(System.getenv().getOrDefault("SOCP_TRACING_ENABLED", "false"));
    }

    public static boolean isInitialized() {
        return initialized;
    }
}
