package com.socp.asset.collect.collector;

import com.socp.platform.client.AssetClient;
import com.socp.platform.client.ServiceCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 资产采集模拟器：定时"发现"资产并上报 asset-web 落库。
 *
 * <p>生产环境由 Agent/CMDB/网络扫描器（Nmap、Tenable 等）上报；此处模拟
 * 周期性扫描发现主机/网络设备，通过 asset-web 的采集端点入库，打通
 * "采集 → 资产库" 链路。
 */
@Component
@EnableScheduling
public class AssetScanner {

    private static final Logger log = LoggerFactory.getLogger(AssetScanner.class);

    private final List<Map<String, Object>> discovered = new CopyOnWriteArrayList<>();
    private int round = 0;

    private final AssetClient assetClient;

    public AssetScanner(AssetClient assetClient) {
        this.assetClient = assetClient;
    }

    /** 每 60 秒模拟一轮扫描：发现 1-2 台新资产并上报。 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    public void scan() {
        round++;
        String[] hosts = {"app-node-" + round, "cache-node-" + round, "lb-" + round};
        for (String name : hosts) {
            Map<String, Object> asset = new LinkedHashMap<>();
            asset.put("name", name);
            asset.put("type", pick("SERVER", "SERVER", "LOADBALANCER", "DATABASE"));
            asset.put("ip", "10.0.10." + (10 + round));
            asset.put("os", "Ubuntu 22.04");
            asset.put("owner", "platform");
            asset.put("criticality", round % 3 == 0 ? "CRITICAL" : "HIGH");
            asset.put("scannedAt", Instant.now().toString());
            discovered.add(asset);

            // 上报 asset-web 落库（best-effort，但失败必须可观测）
            ServiceCall call = assetClient.collect(toJson(asset));
            if (!call.ok()) {
                log.warn("资产上报失败 name={} 原因={}", name, call.failureReason());
            }
        }
        log.info("扫描轮次 #{} 完成，累计发现 {} 台", round, discovered.size());
    }

    public List<Map<String, Object>> discovered() {
        return List.copyOf(discovered);
    }

    private static String pick(String... xs) {
        return xs[(int) (Math.random() * xs.length)];
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
