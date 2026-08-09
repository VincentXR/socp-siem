package com.socp.rule.score;

import com.socp.rule.model.Severity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 威胁评分器：把一条告警折算成 0~100 的风险分，并给出可解释的分项拆解。
 *
 * <p>只看 severity 排序告警是不够的——同为 HIGH，"命中情报的凭据转储"和"扫描探测"
 * 的处置优先级天差地别。评分把多个维度合成一个可排序的数：
 * <ul>
 *   <li><b>基础分</b>：告警严重级别（0~85）；</li>
 *   <li><b>战术权重</b>：MITRE ATT&amp;CK 技术对应的杀伤链阶段权重，越靠后越致命（0~20）；</li>
 *   <li><b>情报加权</b>：命中威胁情报 IOC 的条数（0~24）；</li>
 *   <li><b>行为频次</b>：同实体近期告警数，反映攻击持续性（0~15）；</li>
 *   <li><b>资产重要性</b>：核心资产权重 0~3 级（0~15）。</li>
 * </ul>
 * 总分截断到 100。分项拆解一并返回，保证评分对分析师是"可解释"的，而不是黑盒。
 */
public final class RiskScorer {

    /** 评分结果：总分 + 等级 + 分项拆解 */
    public record Score(int score, String level, Map<String, Integer> breakdown) {
    }

    /** ATT&CK 技术权重：越接近"造成实际损害"权重越高 */
    private static final Map<String, Integer> TECHNIQUE_WEIGHT = Map.ofEntries(
            Map.entry("T1486", 20),  // 数据加密勒索 —— 影响
            Map.entry("T1490", 18),  // 抑制系统恢复
            Map.entry("T1003", 18),  // 凭据转储 —— 凭据访问
            Map.entry("T1041", 17),  // C2 通道外传 —— 数据渗出
            Map.entry("T1567", 17),  // Web 服务外传
            Map.entry("T1071", 15),  // 应用层 C2 —— 命令与控制
            Map.entry("T1548", 15),  // 提权（滥用提升控制机制）
            Map.entry("T1068", 15),  // 提权（漏洞利用）
            Map.entry("T1078", 14),  // 有效账户 —— 已得手
            Map.entry("T1021", 13),  // 远程服务 —— 横向移动
            Map.entry("T1070", 13),  // 清除痕迹 —— 防御规避
            Map.entry("T1190", 12),  // 面向公众应用利用 —— 初始访问
            Map.entry("T1059", 11),  // 命令与脚本解释器 —— 执行
            Map.entry("T1053", 10),  // 计划任务 —— 持久化
            Map.entry("T1110", 8),   // 暴力破解 —— 凭据访问（噪声大）
            Map.entry("T1566", 8),   // 钓鱼 —— 初始访问
            Map.entry("T1498", 8),   // 网络拒绝服务
            Map.entry("T1046", 5)    // 网络服务扫描 —— 侦察（噪声最大）
    );

    /** 未识别但填写了 ATT&CK 技术号时的兜底权重 */
    private static final int DEFAULT_TECHNIQUE_WEIGHT = 6;

    private RiskScorer() {
    }

    /**
     * @param severity        告警严重级别
     * @param mitre           ATT&CK 技术 ID（如 T1110），可空
     * @param tiHits          命中情报的 IOC 条数
     * @param recentAlerts    同实体近期（默认 1 小时）告警条数
     * @param assetCriticality 资产重要性 0~3（0=普通，3=核心）
     */
    public static Score score(Severity severity, String mitre, int tiHits, int recentAlerts, int assetCriticality) {
        int base = switch (severity == null ? Severity.INFO : severity) {
            case CRITICAL -> 60;
            case HIGH -> 45;
            case MEDIUM -> 28;
            case LOW -> 14;
            case INFO -> 5;
        };
        int tactic = 0;
        if (mitre != null && !mitre.isBlank() && !"null".equals(mitre)) {
            tactic = TECHNIQUE_WEIGHT.getOrDefault(mitre.trim().toUpperCase(), DEFAULT_TECHNIQUE_WEIGHT);
        }
        int intel = Math.min(3, Math.max(0, tiHits)) * 8;
        int frequency = (int) Math.round(Math.min(10, Math.max(0, recentAlerts)) * 1.5);
        int asset = Math.min(3, Math.max(0, assetCriticality)) * 5;

        int total = Math.min(100, base + tactic + intel + frequency + asset);

        Map<String, Integer> breakdown = new LinkedHashMap<>();
        breakdown.put("severity", base);
        breakdown.put("tactic", tactic);
        breakdown.put("intel", intel);
        breakdown.put("frequency", frequency);
        breakdown.put("asset", asset);
        return new Score(total, level(total), breakdown);
    }

    /** 风险等级分档，与告警级别语义对齐，便于前端着色 */
    public static String level(int score) {
        if (score >= 85) return "CRITICAL";
        if (score >= 65) return "HIGH";
        if (score >= 40) return "MEDIUM";
        if (score >= 20) return "LOW";
        return "INFO";
    }

    public static int techniqueWeight(String mitre) {
        if (mitre == null || mitre.isBlank()) return 0;
        return TECHNIQUE_WEIGHT.getOrDefault(mitre.trim().toUpperCase(), DEFAULT_TECHNIQUE_WEIGHT);
    }
}
