package com.socp.platform.client.service;




import com.socp.platform.client.http.ServiceCall;
import com.socp.platform.client.http.SocpHttpClient;
import org.springframework.stereotype.Component;

/** 检测服务（detect-web）客户端：采集管线批量投递归一化事件。 */
@Component
public class DetectClient {

    /** 批量摄取超时放宽到 5s：一批 200 条，比单条调用更值得等。 */
    private static final int BULK_TIMEOUT_MS = 5000;

    private final SocpHttpClient http;

    public DetectClient(SocpHttpClient http) {
        this.http = http;
    }

    /**
     * NDJSON 批量投递事件（{@code POST /detect-web/api/v1/ingest/bulk}）。
     *
     * @param ndjson 每行一个 JSON 事件
     */
    public ServiceCall ingestBulk(String ndjson) {
        return http.post(SocpService.DETECT, "/api/v1/ingest/bulk", ndjson, SocpHttpClient.NDJSON, BULK_TIMEOUT_MS);
    }
}
