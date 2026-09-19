package com.socp.detect.model.api.controller;

import com.socp.detect.model.api.request.AnalyzeRequest;
import com.socp.detect.model.engine.AlertWindowAggregator;
import com.socp.detect.model.service.AnalyzeService;
import com.socp.detect.web.config.DetectRuntimeRole;
import com.socp.platform.error.api.ApiResult;
import com.socp.platform.error.api.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;
import com.socp.platform.auth.security.RequireRole;
import com.socp.rule.model.Alert;

import java.util.List;
import java.util.Map;

/**
 * DETECT Model 窗口聚合 API——对告警做二次关联分析。
 *
 * <p>两条入口共用 {@link AnalyzeService} 同一分析路径：
 * <ul>
 *   <li>HTTP {@code POST /detect-model/api/v1/analyze}（网关兼容入口）；</li>
 *   <li>Kafka 消费 {@code socp-alarm-original}（生产主链，detect-web 转发后自动触发，
 *       由同一 Detection worker 内的 AlarmConsumer 处理）。</li>
 * </ul>
 * 两类读口径不同，必须分开理解：{@code /analyzed} 读耐久投影
 * {@code t_analyzed}，因此任一副本答案一致；{@code /stats} 的窗口聚合、
 * {@code /window} 与 {@code /window/trend} 读的是<b>应答副本自己的</b>进程内计数，
 * 风暴抑制判定同样是副本本地的。多副本 worker 分摊告警流时，连续两次查询可能
 * 命中不同副本而得到不同计数；{@code /stats} 与 {@code /window} 的响应体带
 * {@code scope=replica-local} 与 {@code instance}，{@code /analyze} 的响应带
 * {@code instance}。把这些视图改成集群视图需要按稳定 storm key 重新分区告警流，
 * 或把计数落到共享存储，见 docs/detection-state-semantics.md。
 */
@RestController
@DetectRuntimeRole(DetectRuntimeRole.Role.WORKER)
@RequestMapping("/model/api/v1")
public class ModelController {

    private final AnalyzeService analyzeService;
    private final AlertWindowAggregator windowAggregator;

    public ModelController(AnalyzeService analyzeService, AlertWindowAggregator windowAggregator) {
        this.analyzeService = analyzeService;
        this.windowAggregator = windowAggregator;
    }

    /** 接收原始告警做二次分析（HTTP 调试入口；生产主链走 Kafka，同一路径）。 */
    @RequireRole({"admin", "analyst"})
    @PostMapping("/analyze")
    public ApiResult<Map<String, Object>> analyze(@Valid @RequestBody AnalyzeRequest request) {
        return ApiResult.ok(analyzeService.analyze(request.asMap()));
    }

    /** 已分析告警分页查询。page 为 1-based 共享分页契约，service 内部按 0-based Spring PageRequest 取页。 */
    @GetMapping("/analyzed")
    public ApiResult<PageResponse<Alert>> analyzed(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        int safePage = Math.max(1, page);
        AnalyzeService.AnalyzedPage result = analyzeService.analyzed(safePage - 1, size);
        return ApiResult.ok(PageResponse.of(result.items(), result.total(), safePage, result.size(),
                result.totalPages()));
    }

    @GetMapping("/stats")
    public ApiResult<Map<String, Object>> stats() {
        return ApiResult.ok(analyzeService.stats());
    }

    /** 5 分钟滑动窗口聚合：按规则/实体/级别命中数 + 分钟级趋势。 */
    @GetMapping("/window")
    public ApiResult<Map<String, Object>> window() {
        return ApiResult.ok(windowAggregator.snapshot());
    }

    /** 分钟级趋势（最近 5 分钟命中数）；计数只覆盖应答副本，见类注释。 */
    @GetMapping("/window/trend")
    public ApiResult<List<Map<String, Object>>> windowTrend() {
        return ApiResult.ok(windowAggregator.trend());
    }
}
