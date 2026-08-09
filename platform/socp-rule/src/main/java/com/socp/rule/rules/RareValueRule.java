package com.socp.rule.rules;

import com.socp.rule.model.Alert;
import com.socp.rule.model.SecurityEvent;
import com.socp.rule.model.Severity;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * UEBA 稀有值 / 首次出现规则（first-seen detection）。
 *
 * <p>为每个实体（用户 / 主机 / IP）学习某个观察字段的历史取值集合。学习期结束后，
 * 一旦出现从未见过的取值即判定为异常。典型场景：
 * <ul>
 *   <li>某账号首次从新的国家 / 新设备登录（valueField=geo / device_id）；</li>
 *   <li>某服务器首次执行某个进程（valueField=process）；</li>
 *   <li>某主机首次外联某个域名（valueField=dst_domain）。</li>
 * </ul>
 *
 * <p>这是签名规则完全覆盖不了的检测面——没有任何"恶意特征"，异常只体现在"与该实体的
 * 历史行为不符"。学习期（warmup）保证冷启动时不会把正常行为全部报成首次出现。
 */
public final class RareValueRule extends AbstractRule {

    /** 每个实体记忆的取值上限，超出后淘汰最早的（LRU 近似） */
    private static final int MAX_SEEN = 512;

    private final Predicate<SecurityEvent> matcher;
    private final Function<SecurityEvent, String> keyOf;
    private final Function<SecurityEvent, String> valueOf;
    private final String valueField;
    private final int warmup;   // 学习期观察次数，达到后才开始告警
    private final Severity severity;
    private final String messageTemplate;

    private final Map<String, State> states = new ConcurrentHashMap<>();

    private static final class State {
        final Set<String> seen = new LinkedHashSet<>();
        long observed;
    }

    public RareValueRule(String id, String name,
                         Predicate<SecurityEvent> matcher,
                         Function<SecurityEvent, String> keyOf,
                         Function<SecurityEvent, String> valueOf,
                         String valueField, int warmup,
                         Severity severity, String messageTemplate) {
        super(id, name);
        this.matcher = matcher;
        this.keyOf = keyOf;
        this.valueOf = valueOf;
        this.valueField = valueField;
        this.warmup = Math.max(1, warmup);
        this.severity = severity;
        this.messageTemplate = messageTemplate;
    }

    @Override
    public void accept(SecurityEvent event) {
        if (!matcher.test(event)) return;
        String key = keyOf.apply(event);
        if (key == null || key.isBlank()) return;
        String value = valueOf.apply(event);
        if (value == null || value.isBlank()) return;

        State st = states.computeIfAbsent(key, k -> new State());
        synchronized (st) {
            st.observed++;
            if (st.seen.contains(value)) return;

            st.seen.add(value);
            if (st.seen.size() > MAX_SEEN) {
                var it = st.seen.iterator();
                it.next();
                it.remove();
            }
            // 学习期内只记忆不告警
            if (st.observed <= warmup) return;

            String msg = messageTemplate
                    .replace("{key}", key)
                    .replace("{value}", value)
                    .replace("{field}", valueField == null ? "?" : valueField)
                    .replace("{known}", String.valueOf(st.seen.size() - 1))
                    .replace("{observed}", String.valueOf(st.observed));
            emit(new Alert(id, name, severity, msg, key, List.of(event)));
        }
    }

    /** 暴露已学习画像，供 UEBA 看板展示"这个实体平时都用哪些值" */
    public List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        states.forEach((k, st) -> {
            synchronized (st) {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("entity", k);
                m.put("field", valueField);
                m.put("known", st.seen.size());
                m.put("observed", st.observed);
                m.put("values", st.seen.stream().limit(20).toList());
                out.add(m);
            }
        });
        return out;
    }
}
