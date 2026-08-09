package com.socp.detect.web.api;

import com.socp.detect.web.store.WatchlistStore;
import com.socp.detect.web.ueba.EntityRiskStore;
import com.socp.rule.score.RiskScorer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * UEBA / 威胁评分 / 观察名单 API。
 * 提供"最该看哪个实体"的排序视图，以及评分模型的可解释试算入口。
 */
@RestController
@RequestMapping("/api/v1")
public class UebaController {

    private final EntityRiskStore riskStore;
    private final WatchlistStore watchlists;

    public UebaController(EntityRiskStore riskStore, WatchlistStore watchlists) {
        this.riskStore = riskStore;
        this.watchlists = watchlists;
    }

    // ---------- 实体风险画像 ----------

    /** 风险 Top N 实体（默认 20），按时间衰减后的累积风险倒序 */
    @GetMapping("/ueba/entities")
    public List<Map<String, Object>> entities(@RequestParam(defaultValue = "20") int limit) {
        return riskStore.top(limit);
    }

    /** 单实体下钻：风险分 / 告警数 / ATT&CK 分布 / Top 规则 */
    @GetMapping("/ueba/entities/{entity}")
    public ResponseEntity<?> entity(@PathVariable String entity) {
        Map<String, Object> m = riskStore.get(entity);
        if (m == null) return ResponseEntity.status(404).body(Map.of("error", "entity_not_found", "entity", entity));
        return ResponseEntity.ok(m);
    }

    /** 全局风险摘要：实体总数 + 风险档位分布 + 最高风险 */
    @GetMapping("/ueba/summary")
    public Map<String, Object> summary() {
        return riskStore.summary();
    }

    /** 评分模型试算：给定条件返回总分与分项拆解，用于向分析师解释评分口径 */
    @GetMapping("/ueba/score")
    public Map<String, Object> score(
            @RequestParam(defaultValue = "HIGH") String severity,
            @RequestParam(required = false) String mitre,
            @RequestParam(defaultValue = "0") int tiHits,
            @RequestParam(defaultValue = "0") int recentAlerts,
            @RequestParam(defaultValue = "0") int assetCriticality) {
        com.socp.rule.model.Severity sev;
        try {
            sev = com.socp.rule.model.Severity.valueOf(severity.toUpperCase());
        } catch (IllegalArgumentException e) {
            sev = com.socp.rule.model.Severity.INFO;
        }
        RiskScorer.Score s = RiskScorer.score(sev, mitre, tiHits, recentAlerts, assetCriticality);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("score", s.score());
        out.put("level", s.level());
        out.put("breakdown", s.breakdown());
        return out;
    }

    // ---------- 观察名单 ----------

    @GetMapping("/watchlists")
    public List<Map<String, Object>> listWatchlists() {
        return watchlists.list();
    }

    @GetMapping("/watchlists/{name}")
    public Map<String, Object> getWatchlist(@PathVariable String name) {
        return watchlists.describe(name);
    }

    /** 全量替换一个名单（规则条件 op=inlist 立即生效，无需重载规则） */
    @PutMapping("/watchlists/{name}")
    public Map<String, Object> putWatchlist(@PathVariable String name, @RequestBody List<String> values) {
        return watchlists.put(name, values);
    }

    /** 追加若干值到名单 */
    @PostMapping("/watchlists/{name}")
    public Map<String, Object> appendWatchlist(@PathVariable String name, @RequestBody List<String> values) {
        return watchlists.append(name, values);
    }

    @DeleteMapping("/watchlists/{name}")
    public Map<String, Object> deleteWatchlist(@PathVariable String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("removed", watchlists.delete(name));
        return body;
    }
}
