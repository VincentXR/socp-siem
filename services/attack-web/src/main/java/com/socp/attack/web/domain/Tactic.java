package com.socp.attack.web.domain;

/** ATT&CK 战术（Kill Chain 阶段）。order 用于看板排序。 */
public record Tactic(String id, String name, int order) {
}
