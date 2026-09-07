package com.socp.platform.client.service;


import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.http.SocpHttpClient;
import org.springframework.stereotype.Component;

/** 编排服务（soar-web）客户端：告警触发剧本评估。 */
@Component
public class SoarClient {

    private final SocpHttpClient http;

    public SoarClient(SocpHttpClient http) {
        this.http = http;
    }

    /**
     * 评估告警并触发已发布的 V2 自动化规则。
     *
     * <p>Alert Web 是 SOAR 的主事件生产者，不能继续依赖 V1 的同步执行
     * 入口：生产环境会关闭 legacy execution，V2 入口负责 durable admission
     * 和去重。保留方法名只是为了兼容现有调用方。</p>
     */
    public ServiceCall evaluate(String alarmJson) {
        return http.postJson(SocpService.SOAR, "/api/v2/events/evaluate", alarmJson);
    }
}
