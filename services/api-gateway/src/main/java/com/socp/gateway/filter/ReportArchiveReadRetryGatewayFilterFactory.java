package com.socp.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.PrematureCloseException;

/** Recovers one pre-response disconnect while listing report archives. */
@Component
public class ReportArchiveReadRetryGatewayFilterFactory
        extends AbstractGatewayFilterFactory<ReportArchiveReadRetryGatewayFilterFactory.Config> {

    private static final Logger log = LoggerFactory.getLogger(ReportArchiveReadRetryGatewayFilterFactory.class);
    private static final String ARCHIVE_PATH = "/report-web/api/v1/reports/archive";

    public ReportArchiveReadRetryGatewayFilterFactory() {
        super(Config.class);
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> Mono.defer(() -> chain.filter(exchange))
                .onErrorResume(PrematureCloseException.class, failure -> {
                    if (!canRetry(exchange)) {
                        return Mono.error(failure);
                    }
                    // Use Gateway's reset so the second attempt really routes
                    // upstream again and discards the failed response connection.
                    ServerWebExchangeUtils.reset(exchange);
                    log.warn("Retrying report archive list once after upstream closed before response");
                    // Errors from this replacement publisher propagate unchanged;
                    // onErrorResume does not retry this second attempt recursively.
                    return Mono.defer(() -> chain.filter(exchange));
                });
    }

    private boolean canRetry(ServerWebExchange exchange) {
        return HttpMethod.GET.equals(exchange.getRequest().getMethod())
                && ARCHIVE_PATH.equals(exchange.getRequest().getPath().value())
                && exchange.getRequest().getHeaders().getContentLength() <= 0
                && !exchange.getRequest().getHeaders().containsHeader(HttpHeaders.TRANSFER_ENCODING)
                && !exchange.getResponse().isCommitted()
                // A disconnect during a response body must not splice two
                // responses together, even if the browser headers aren't sent yet.
                && exchange.getAttribute(ServerWebExchangeUtils.CLIENT_RESPONSE_ATTR) == null;
    }

    public static class Config {
    }
}
