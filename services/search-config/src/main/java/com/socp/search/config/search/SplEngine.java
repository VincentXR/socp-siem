package com.socp.search.config.search;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * SPL 式检索引擎（集群无关，内存执行）——SIEM 查询语言的最小可用实现。
 *
 * <p>语法：
 * <pre>
 *   [&lt;条件表达式&gt;] [| top &lt;field&gt; [&lt;n&gt;]] [| count by &lt;field&gt;] [| head &lt;n&gt;] [| timechart]
 * </pre>
 * 条件表达式由 AND/OR 连接比较子句（无括号优先级，AND 优先于 OR）：
 * <ul>
 *   <li>field=value        —— 等于（value 可加引号）</li>
 *   <li>field!=value       —— 不等于</li>
 *   <li>field contains "x" —— 包含</li>
 *   <li>field&gt;=N / field&gt;N / field&lt;N / field&lt;=N —— 数值比较</li>
 *   <li>severity=HIGH      —— 级别过滤（同等于）</li>
 * </ul>
 * 支持字段：timestamp/source/host/severity/msg + 事件自定义字段（src_ip/user/action/http_method/url/bytes/dst_ip）。
 *
 * <p>示例：
 * <pre>
 *   source=auth severity=HIGH
 *   src_ip=10.0.0.9 OR user=admin
 *   msg contains "blocked" | top src_ip 5
 *   source=web | count by http_method
 *   severity>=HIGH | timechart
 * </pre>
 */
@Service
public class SplEngine {

    /** 管道正则：按 | 拆分，但保留 contains "..." 内的 |（此处简化：日志消息不含 |） */
    private static final Pattern COND = Pattern.compile(
            "(\\w+)\\s*(contains|=|!=|>=|<=|>|<)\\s*(?:\"([^\"]*)\"|(\\S+))");

    /**
     * Search result plus source provenance. The four original fields remain stable
     * for callers that only need events and aggregations.
     */
    public record QueryResult(int total, List<SearchEvent> events, Stat stat,
                              String source, boolean degraded, Instant freshness,
                              String degradationReason) {
        public QueryResult(int total, List<SearchEvent> events, Stat stat) {
            this(total, events, stat, "unspecified", false, null, null);
        }

        public QueryResult withSource(String source, boolean degraded, Instant freshness,
                                      String degradationReason) {
            return new QueryResult(total, events, stat, source, degraded, freshness, degradationReason);
        }

        public QueryResult limitEvents(int limit) {
            if (events.size() <= limit) return this;
            return new QueryResult(total, events.stream().limit(limit).toList(), stat,
                    source, degraded, freshness, degradationReason);
        }

        public record Stat(String type, List<Map<String, Object>> rows) {
        }
    }

    public QueryResult execute(String query, List<SearchEvent> corpus) {
        String q = query == null ? "" : query.trim();
        String[] parts = q.split("\\|");
        String expr = parts[0].trim();

        List<SearchEvent> matched = corpus.stream()
                .filter(buildPredicate(expr))
                .sorted(Comparator.comparing(SearchEvent::timestamp).reversed())
                .toList();

        // 解析管道
        String statType = null;
        String statField = null;
        Integer statLimit = null;
        Integer headLimit = null;
        for (int i = 1; i < parts.length; i++) {
            String p = parts[i].trim().toLowerCase(Locale.ROOT);
            if (p.startsWith("top ")) {
                String[] t = p.substring(4).trim().split("\\s+");
                statType = "top";
                statField = t[0];
                statLimit = t.length > 1 ? Integer.parseInt(t[1]) : 10;
            } else if (p.startsWith("count by ")) {
                statType = "count";
                statField = p.substring(9).trim();
            } else if (p.startsWith("head ")) {
                headLimit = Integer.parseInt(p.substring(5).trim());
            } else if (p.equals("timechart")) {
                statType = "timechart";
            }
        }

        List<SearchEvent> shown = headLimit == null ? matched : matched.stream().limit(headLimit).toList();

        QueryResult.Stat stat = null;
        if ("top".equals(statType) || "count".equals(statType)) {
            final String f = statField;
            final long limit = statLimit == null ? Long.MAX_VALUE : statLimit;
            Map<String, Long> counts = matched.stream()
                    .collect(Collectors.groupingBy(e -> String.valueOf(e.get(f)), Collectors.counting()));
            List<Map<String, Object>> rows = counts.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(limit)
                    .map(e -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("key", e.getKey());
                        m.put("count", e.getValue());
                        return m;
                    })
                    .toList();
            stat = new QueryResult.Stat(statType, rows);
        } else if ("timechart".equals(statType)) {
            Map<String, Long> byDay = matched.stream()
                    .collect(Collectors.groupingBy(
                            e -> LocalDate.ofInstant(e.timestamp(), ZoneId.systemDefault()).toString(),
                            Collectors.counting()));
            List<Map<String, Object>> rows = byDay.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(e -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("key", e.getKey());
                        m.put("count", e.getValue());
                        return m;
                    })
                    .toList();
            stat = new QueryResult.Stat("timechart", rows);
        }

        return new QueryResult(matched.size(), shown, stat);
    }

    private static Predicate<SearchEvent> buildPredicate(String expr) {
        if (expr == null || expr.isBlank()) return e -> true;
        // 拆分 OR 组（无括号优先级：AND 优先）
        List<Predicate<SearchEvent>> orGroups = new ArrayList<>();
        for (String orPart : expr.split("(?i)\\s+OR\\s+")) {
            List<Predicate<SearchEvent>> andConds = new ArrayList<>();
            // 用全局匹配收集所有比较子句；子句之间残留文本视为 msg 包含词
            Matcher cm = COND.matcher(orPart);
            int last = 0;
            while (cm.find()) {
                String between = orPart.substring(last, cm.start()).trim();
                if (!between.isEmpty()) andConds.add(msgContains(between));
                andConds.add(parseCond(cm.group()));
                last = cm.end();
            }
            String tail = orPart.substring(last).trim();
            if (!tail.isEmpty()) andConds.add(msgContains(tail));
            if (andConds.isEmpty() && !orPart.isBlank()) andConds.add(msgContains(orPart));
            orGroups.add(andConds.stream().reduce(e -> true, Predicate::and));
        }
        return orGroups.stream().reduce(e -> false, Predicate::or);
    }

    private static Predicate<SearchEvent> msgContains(String kw) {
        String k = kw.replaceAll("^\"|\"$", "").trim();
        return e -> e.msg() != null && e.msg().contains(k);
    }

    private static final Map<String, Integer> SEV_LEVEL = Map.of(
            "INFO", 1, "LOW", 2, "MEDIUM", 3, "HIGH", 4, "CRITICAL", 5);

    private static Predicate<SearchEvent> parseCond(String cond) {
        Matcher m = COND.matcher(cond);
        if (!m.find()) {
            return msgContains(cond);
        }
        String field = m.group(1);
        String op = m.group(2).toLowerCase(Locale.ROOT);
        String value = m.group(3) != null ? m.group(3) : m.group(4);

        // severity 的数值比较走级别数值（severity>=HIGH）
        if ("severity".equals(field) && List.of(">=", ">", "<=", "<").contains(op)) {
            Integer lv = SEV_LEVEL.get(value.toUpperCase(Locale.ROOT));
            if (lv != null) {
                int b = lv;
                return e -> {
                    Integer a = SEV_LEVEL.get(e.severity().toUpperCase(Locale.ROOT));
                    if (a == null) return false;
                    return switch (op) {
                        case ">=" -> a >= b;
                        case "<=" -> a <= b;
                        case ">" -> a > b;
                        default -> a < b;
                    };
                };
            }
        }

        return switch (op) {
            case "=" -> e -> value.equalsIgnoreCase(String.valueOf(e.get(field)));
            case "!=" -> e -> !value.equalsIgnoreCase(String.valueOf(e.get(field)));
            case "contains" -> e -> String.valueOf(e.get(field)).toLowerCase(Locale.ROOT)
                    .contains(value.toLowerCase(Locale.ROOT));
            default -> { // 数值比较 >= <= > <
                double b;
                try {
                    b = Double.parseDouble(value);
                } catch (NumberFormatException ex) {
                    yield e -> false;
                }
                yield e -> {
                    try {
                        double a = Double.parseDouble(String.valueOf(e.get(field)));
                        return switch (op) {
                            case ">=" -> a >= b;
                            case "<=" -> a <= b;
                            case ">" -> a > b;
                            case "<" -> a < b;
                            default -> false;
                        };
                    } catch (NumberFormatException ex) {
                        return false;
                    }
                };
            }
        };
    }
}
