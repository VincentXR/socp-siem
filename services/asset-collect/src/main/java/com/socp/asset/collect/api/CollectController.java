package com.socp.asset.collect.api;

import com.socp.asset.collect.collector.AssetScanner;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ASSET 采集入口——接收外部资产/情报上报（SCP/CMDB 同步）。
 * 生产环境消费 Kafka 或 CMDB API；当前为 HTTP 接收 + 内存暂存。
 */
@RestController
@RequestMapping("/api/v1")
public class CollectController {

    private final List<Map<String, Object>> collected = new CopyOnWriteArrayList<>();
    private final AssetScanner scanner;

    public CollectController(AssetScanner scanner) {
        this.scanner = scanner;
    }

    @PostMapping("/collect")
    public Map<String, Object> collect(@RequestBody Map<String, Object> asset) {
        Map<String, Object> record = new LinkedHashMap<>(asset);
        record.put("id", UUID.randomUUID().toString());
        record.put("collectedAt", Instant.now().toString());
        collected.add(record);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", true);
        result.put("total", collected.size());
        return result;
    }

    @GetMapping("/collected")
    public List<Map<String, Object>> list() {
        return List.copyOf(collected);
    }

    /** 定时扫描模拟器发现的资产（已上报 asset-web）。 */
    @GetMapping("/discovered")
    public List<Map<String, Object>> discovered() {
        return scanner.discovered();
    }
}

