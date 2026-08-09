package com.socp.notify.web.domain;

/**
 * 通知渠道（大厂 SIEM 的集成/响应连接器）：
 * 告警经规则命中后可推送到 IM/工单/邮件等外部系统。
 *
 * @param type   WEBHOOK（自定义 HTTP 回调）/ SLACK（Slack Incoming Webhook）/ EMAIL（邮件）
 * @param target WEBHOOK/SLACK 为 URL；EMAIL 为收件地址
 */
public record Channel(String id, String name, String type, String target, boolean enabled, String description) {

    public static Channel of(String name, String type, String target, boolean enabled, String description) {
        String id = "CH-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return new Channel(id, name, type.toUpperCase(), target, enabled, description);
    }
}
