package com.socp.platform.client.http;

import java.io.IOException;

/**
 * 响应体超过配置上限时抛出。
 *
 * <p>属于永久性失败——同一个端点再请求一次大概率还是超限，
 * 因此 {@link SocpHttpClient} 会把它标记为不可重试，避免白烧重试预算。
 */
public class ResponseBodyTooLargeException extends IOException {

    public ResponseBodyTooLargeException(int maxBytes) {
        super("response body exceeds the " + maxBytes + " byte limit");
    }
}
