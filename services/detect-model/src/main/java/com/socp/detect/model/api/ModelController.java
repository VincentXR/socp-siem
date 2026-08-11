package com.socp.detect.model.api;

import com.socp.detect.model.engine.AlertWindowAggregator;
import com.socp.detect.model.service.AnalyzeService;
import com.socp.rule.model.Alert;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * DETECT Model 窗口聚合 API——对告警做二次关联分析。
 *
 * <p>两条入口共用 {@link AnalyzeService} 同一分析路径：
 * <ul>
 *   <li>HTTP {@code POST /api/v1/analyze}（调试/直连）；</li>
 *   <li>Kafka 消费 {@code socp-alarm-original}（生产主链，detect-web 转发后自动触发，
 *       见 detect-model 的 AlarmConsumer）。</li>
 * </ul>
 * 分析结果（命中/窗口聚合）对 /analyzed /stats /window 统一可查。
 */
@RestController
@RequestMapping("/api/v1")
public class ModelController {

    private final AnalyzeService analyzeService;
    private final AlertWindowAggregator windowAggregator;

    public ModelController(AnalyzeService analyzeService, AlertWindowAggregator windowAggregator) {
        this.analyzeService = analyzeService;
        this.windowAggregator = windowAggregator;
    }

    /** 接收原始告警做二次分析（HTTP 调试入口；生产主链走 Kafka，同一路径）。 */
    @PostMapping("/analyze")
    public Map<String, Object> analyze(@RequestBody Map<String, Object> alarm) {
        return analyzeService.analyze(alarm);
    }

    @GetMapping("/analyzed")
    public List<Alert> analyzed() {
        return analyzeService.analyzed();
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return analyzeService.stats();
    }

    /** 5 分钟滑动窗口聚合：按规则/实体/级别命中数 + 分钟级趋势。 */
    @GetMapping("/window")
    public Map<String, Object> window() {
        return windowAggregator.snapshot();
    }

    /** 分钟级趋势（最近 5 分钟命中数）。 */
    @GetMapping("/window/trend")
    public List<Map<String, Object>> windowTrend() {
        return windowAggregator.trend();
    }
}
