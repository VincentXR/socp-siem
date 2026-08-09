package com.socp.rule.engine;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 观察名单 / 参考数据集全局注册表（watchlist、reference set）。
 *
 * <p>大厂 SIEM 的规则很少把 IP、账号硬编码在规则里，而是引用一个"名单"：
 * 离职人员名单、特权账号名单、暴露面资产名单、恶意 IP 名单。名单由运营侧动态维护，
 * 规则本身不用改——这是规则可运营性的关键。
 *
 * <p>规则条件通过 {@code op=inlist / notinlist、value=<名单名>} 引用本注册表。
 * 名单值统一小写归一，匹配大小写不敏感。进程内实现（集群无关），
 * 生产化后由 SEARCH ReferenceSet 服务定期下发刷新，接口契约不变。
 */
public final class Watchlists {

    private static final Map<String, Set<String>> LISTS = new ConcurrentHashMap<>();

    private Watchlists() {
    }

    /** 全量替换一个名单 */
    public static void put(String name, Collection<String> values) {
        if (name == null || name.isBlank()) return;
        Set<String> set = ConcurrentHashMap.newKeySet();
        if (values != null) {
            for (String v : values) {
                if (v != null && !v.isBlank()) set.add(v.trim().toLowerCase());
            }
        }
        LISTS.put(name.trim().toLowerCase(), set);
    }

    /** 向名单追加若干值（名单不存在则创建） */
    public static void add(String name, Collection<String> values) {
        if (name == null || name.isBlank() || values == null) return;
        Set<String> set = LISTS.computeIfAbsent(name.trim().toLowerCase(), k -> ConcurrentHashMap.newKeySet());
        for (String v : values) {
            if (v != null && !v.isBlank()) set.add(v.trim().toLowerCase());
        }
    }

    public static boolean delete(String name) {
        return name != null && LISTS.remove(name.trim().toLowerCase()) != null;
    }

    /** 规则条件求值入口：value 是否命中名单 name */
    public static boolean contains(String name, String value) {
        if (name == null || value == null) return false;
        Set<String> set = LISTS.get(name.trim().toLowerCase());
        return set != null && set.contains(value.trim().toLowerCase());
    }

    public static Set<String> names() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(LISTS.keySet()));
    }

    public static Set<String> values(String name) {
        if (name == null) return Set.of();
        Set<String> set = LISTS.get(name.trim().toLowerCase());
        return set == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(set));
    }

    public static int size(String name) {
        return values(name).size();
    }

    /** 仅供测试用：清空全部名单 */
    public static void clear() {
        LISTS.clear();
    }
}
