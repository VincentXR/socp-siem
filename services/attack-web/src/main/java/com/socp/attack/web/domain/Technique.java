package com.socp.attack.web.domain;

/**
 * MITRE ATT&CK 技术条目。
 * 大厂 SIEM 用 ATT&CK 作为检测/content 映射的通用语言：规则、告警、狩猎均挂技术 ID，
 * 便于横向对标（如 Splunk ES 的 MITRE ATT&CK 映射、Microsoft Sentinel 的 ATT&CK 看板）。
 */
public record Technique(String id, String name, String tactic, String url, String description) {
}
