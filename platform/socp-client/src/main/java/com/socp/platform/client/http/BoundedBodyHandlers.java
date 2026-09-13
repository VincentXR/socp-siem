package com.socp.platform.client.http;

import java.net.http.HttpResponse;

/**
 * 公共有界响应体工厂。跨服务模块（TAXII 同步、LLM 客户端等自建
 * {@link java.net.http.HttpClient} 的调用方）与 {@link SocpHttpClient} 共用同一套
 * 字节上限语义：超过上限立即取消订阅并抛 {@link ResponseBodyTooLargeException}，
 * 不把超大响应读进堆内存。
 */
public final class BoundedBodyHandlers {

    private BoundedBodyHandlers() {
    }

    /** 带字节上限的字符串 BodyHandler；{@code maxBytes <= 0} 时退化为不限制的 ofString()。 */
    public static HttpResponse.BodyHandler<String> ofString(int maxBytes) {
        return maxBytes > 0 ? new BoundedStringBodyHandler(maxBytes) : HttpResponse.BodyHandlers.ofString();
    }
}
