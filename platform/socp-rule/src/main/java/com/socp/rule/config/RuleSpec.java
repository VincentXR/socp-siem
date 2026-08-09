package com.socp.rule.config;

import com.socp.rule.engine.Watchlists;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;
import com.socp.rule.rules.BaselineRule;
import com.socp.rule.rules.CorrelationRule;
import com.socp.rule.rules.CorrelationSetRule;
import com.socp.rule.rules.PatternRule;
import com.socp.rule.rules.RareValueRule;
import com.socp.rule.rules.Rule;
import com.socp.rule.rules.ThresholdRule;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 规则的可序列化描述（对应 JSON 配置里的一条规则）。
 * 支持六种类型：threshold（阈值）、pattern（模式）、correlation（有序关联）、
 * correlation-set（无序关联）、baseline（UEBA 基线离群）、rare（UEBA 首次出现）。
 * match / steps 由一组条件组成，条件间为 AND；每条条件 = 字段 + 操作符 + 取值。
 * 由 com.siem 迁移；新增 {@link #toMap()} 供 DETECT 规则 CRUD 回显。
 */
public final class RuleSpec {

    public final String id;
    public final String name;
    public final String type;
    public final Severity severity;
    public final String message;
    public final String mitre;        // 关联的 MITRE ATT&CK 技术 ID（如 T1110），可空
    public final String keyField;     // 阈值/关联的分组字段
    public final Integer threshold;   // 阈值规则的触发次数
    public final Duration window;     // 时间窗口
    public final boolean enabled;     // 是否启用（false 则加载时被跳过）
    public final List<Map<String, String>> match;             // pattern / threshold 的匹配条件
    public final List<List<Map<String, String>>> steps;       // correlation 的有序步骤

    // ---- UEBA 参数（baseline / rare 专用） ----
    public final String valueField;      // rare：被观察的字段（如 geo / process / dst_domain）
    public final Double sigma;           // baseline：触发倍数 k（默认 3.0）
    public final Integer baselineWindows;// baseline：参与基线计算的历史桶数（默认 12）
    public final Integer warmup;         // baseline/rare：学习期（桶数 / 观察次数）
    public final Integer minCount;       // baseline：绝对下限，过滤低频噪声（默认 5）

    @SuppressWarnings("unchecked")
    public RuleSpec(Map<String, Object> m) {
        this.id = str(m, "id");
        this.name = str(m, "name");
        this.type = str(m, "type").toLowerCase();
        this.severity = Severity.valueOf(str(m, "severity").toUpperCase());
        this.message = str(m, "message");
        this.mitre = str(m, "mitre");
        Object kf = m.get("keyField");
        this.keyField = kf == null ? null : String.valueOf(kf);
        Object th = m.get("threshold");
        this.threshold = th == null ? null : ((Number) th).intValue();
        Object w = m.get("window");
        this.window = w == null ? Duration.ofMinutes(1) : parseWindow(String.valueOf(w));
        Object en = m.get("enabled");
        this.enabled = en == null ? true : Boolean.parseBoolean(String.valueOf(en));
        this.match = parseConds((List<Object>) m.getOrDefault("match", List.of()));
        this.steps = parseSteps((List<Object>) m.getOrDefault("steps", List.of()));

        Object vf = m.get("valueField");
        this.valueField = vf == null ? null : String.valueOf(vf);
        Object sg = m.get("sigma");
        this.sigma = sg == null ? null : ((Number) sg).doubleValue();
        Object bw = m.get("baselineWindows");
        this.baselineWindows = bw == null ? null : ((Number) bw).intValue();
        Object wu = m.get("warmup");
        this.warmup = wu == null ? null : ((Number) wu).intValue();
        Object mc = m.get("minCount");
        this.minCount = mc == null ? null : ((Number) mc).intValue();
    }

    /** 把描述转换为可执行的 Rule 实例 */
    public Rule toRule() {
        return switch (type) {
            case "threshold" -> new ThresholdRule(
                    id, name, and(match), keyExtractor(),
                    threshold, window, severity, message);
            case "pattern" -> new PatternRule(id, name, and(match), severity, message);
            case "correlation" -> new CorrelationRule(
                    id, name, keyExtractor(),
                    steps.stream().map(this::and).toList(),
                    window, severity, message);
            case "correlation-set" -> new CorrelationSetRule(
                    id, name, keyExtractor(),
                    steps.stream().map(this::and).toList(),
                    window, severity, message);
            // UEBA：与实体自身历史水位比较的离群检测
            case "baseline" -> new BaselineRule(
                    id, name, and(match), keyExtractor(),
                    window,
                    baselineWindows == null ? 12 : baselineWindows,
                    warmup == null ? 4 : warmup,
                    sigma == null ? 3.0 : sigma,
                    minCount == null ? 5 : minCount,
                    severity, message);
            // UEBA：该实体从未出现过的取值
            case "rare" -> new RareValueRule(
                    id, name, and(match), keyExtractor(),
                    fieldExtractor(valueField), valueField,
                    warmup == null ? 20 : warmup,
                    severity, message);
            default -> throw new IllegalArgumentException("未知规则类型: " + type);
        };
    }

    /** 分组维度取值：host / source 走事件顶层字段，其余走 fields */
    private Function<SecurityEvent, String> keyExtractor() {
        return fieldExtractor(keyField);
    }

    /** 通用字段取值器，统一顶层字段与结构化字段的取法 */
    private static Function<SecurityEvent, String> fieldExtractor(String field) {
        if (field == null || field.isBlank()) return e -> null;
        return e -> fieldValue(e, field);
    }

    /** 序列化回 JSON Map（DETECT 规则 CRUD 回显 / 落库） */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("name", name);
        out.put("type", type);
        out.put("severity", severity.name());
        out.put("message", message);
        if (mitre != null && !mitre.isBlank()) out.put("mitre", mitre);
        if (keyField != null) out.put("keyField", keyField);
        if (threshold != null) out.put("threshold", threshold);
        out.put("window", window.toSeconds() + "s");
        out.put("enabled", enabled);
        if (valueField != null) out.put("valueField", valueField);
        if (sigma != null) out.put("sigma", sigma);
        if (baselineWindows != null) out.put("baselineWindows", baselineWindows);
        if (warmup != null) out.put("warmup", warmup);
        if (minCount != null) out.put("minCount", minCount);
        if (!match.isEmpty()) out.put("match", match);
        if (!steps.isEmpty()) out.put("steps", steps);
        return out;
    }

    private Predicate<SecurityEvent> and(List<Map<String, String>> conds) {
        Predicate<SecurityEvent> p = e -> true;
        for (var c : conds) p = p.and(toPredicate(c));
        return p;
    }

    private Predicate<SecurityEvent> toPredicate(Map<String, String> c) {
        String field = c.get("field");
        String op = (c.get("op") == null ? "eq" : c.get("op")).toLowerCase();
        String value = c.get("value") == null ? "" : c.get("value");
        // 正则提前编译一次（toPredicate 在加载期每条件仅调用一次），避免每条事件重编译
        final Pattern regex = "regex".equals(op) ? Pattern.compile(value, Pattern.CASE_INSENSITIVE) : null;
        return e -> {
            String actual = fieldValue(e, field);
            if (actual == null) actual = "";
            return switch (op) {
                case "eq" -> actual.equalsIgnoreCase(value);
                case "ne" -> !actual.equalsIgnoreCase(value);
                case "contains" -> actual.toLowerCase().contains(value.toLowerCase());
                case "startswith" -> actual.toLowerCase().startsWith(value.toLowerCase());
                case "endswith" -> actual.toLowerCase().endsWith(value.toLowerCase());
                // 严重级别比较（用级别数值，不受命名影响）
                case "ge" -> e.severity().level() >= Severity.valueOf(value.toUpperCase()).level();
                case "gtsev" -> e.severity().level() > Severity.valueOf(value.toUpperCase()).level();
                // 正则匹配（整段包含即命中）
                case "regex" -> regex != null && regex.matcher(actual).find();
                // 数值比较：双方都能解析为数字才比较，否则视为不命中
                case "gt", "gte", "lt", "lte" -> numericCompare(op, actual, value);
                // 观察名单：value 为名单名，运营侧动态维护，规则本身不用改
                case "inlist" -> Watchlists.contains(value, actual);
                case "notinlist" -> !Watchlists.contains(value, actual);
                default -> false;
            };
        };
    }

    /** 统一的字段取值：顶层字段（source/host/severity）与结构化字段一视同仁 */
    private static String fieldValue(SecurityEvent e, String field) {
        if (field == null) return null;
        return switch (field) {
            case "source" -> e.source();
            case "host" -> e.host();
            case "severity" -> e.severity().name();
            case "raw" -> e.raw();
            default -> e.fields().get(field);
        };
    }

    private static boolean numericCompare(String op, String actual, String expected) {
        double a, b;
        try {
            a = Double.parseDouble(actual.trim());
            b = Double.parseDouble(expected.trim());
        } catch (NumberFormatException ex) {
            return false;
        }
        return switch (op) {
            case "gt" -> a > b;
            case "gte" -> a >= b;
            case "lt" -> a < b;
            case "lte" -> a <= b;
            default -> false;
        };
    }

    private static Duration parseWindow(String s) {
        s = s.trim();
        if (s.endsWith("s")) return Duration.ofSeconds(Long.parseLong(s.substring(0, s.length() - 1)));
        if (s.endsWith("m")) return Duration.ofMinutes(Long.parseLong(s.substring(0, s.length() - 1)));
        if (s.endsWith("h")) return Duration.ofHours(Long.parseLong(s.substring(0, s.length() - 1)));
        return Duration.ofSeconds(Long.parseLong(s));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> parseConds(List<Object> list) {
        List<Map<String, String>> out = new ArrayList<>();
        for (var o : list) {
            Map<?, ?> mm = (Map<?, ?>) o;
            Map<String, String> cond = new LinkedHashMap<>();
            cond.put("field", String.valueOf(mm.get("field")));
            cond.put("op", mm.get("op") == null ? "eq" : String.valueOf(mm.get("op")));
            cond.put("value", mm.get("value") == null ? "" : String.valueOf(mm.get("value")));
            out.add(cond);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<List<Map<String, String>>> parseSteps(List<Object> list) {
        List<List<Map<String, String>>> out = new ArrayList<>();
        for (var o : list) out.add(parseConds((List<Object>) o));
        return out;
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : String.valueOf(v);
    }
}
