package com.socp.platform.client.http;


import com.socp.platform.client.service.SocpService;

/**
 * 一次服务间调用的完整结果。
 *
 * <p>过去 {@code Http.post()} 只返回一个 int，异常时返回 -1，调用方普遍写成
 * {@code Http.post(url, json, 3000);} 连返回值都不接——攻击命中但告警丢了也没人知道。
 * 现在强制把「成功与否 / 状态码 / 响应体 / 异常 / 耗时 / 是否可重试」一次性带回来，
 * 调用方可以忽略，但 {@link SocpHttpClient} 内部一定已经打了 WARN 日志并计了指标。
 *
 * @param target     目标服务（webhook 等外部地址为 null）
 * @param url        实际请求的完整 URL
 * @param ok         HTTP 2xx 才为 true
 * @param status     HTTP 状态码；连接层失败为 -1
 * @param body       响应体（失败时为错误响应体，可能为空串）
 * @param error      异常摘要（无异常为 null）
 * @param durationMs 总耗时（含重试）
 * @param retryable  是否属于「换个时间再试可能成功」的失败（连接超时 / 5xx / 429）
 * @param attempts   实际尝试次数
 */
public record ServiceCall(
        SocpService target,
        String url,
        boolean ok,
        int status,
        String body,
        String error,
        long durationMs,
        boolean retryable,
        int attempts) {

    /** 目标标签，用于日志与指标 tag。 */
    public String targetLabel() {
        return target == null ? "external" : target.serviceName();
    }

    /** 失败原因的一句话描述，便于塞进业务返回体。 */
    public String failureReason() {
        if (ok) return null;
        if (error != null) return error;
        return "HTTP " + status;
    }
}
