package com.socp.platform.client.http;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedStringBodyHandlerTest {

    @Test
    void decodesDeclaredCharsetAndRequestsOneChunkAtATime() {
        HttpResponse.BodySubscriber<String> subscriber = new BoundedStringBodyHandler(32)
                .apply(responseInfo("text/plain; charset=\"UTF-16\""));
        RecordingSubscription subscription = new RecordingSubscription();

        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap("你好".getBytes(StandardCharsets.UTF_16))));
        subscriber.onComplete();

        assertThat(subscriber.getBody().toCompletableFuture().join()).isEqualTo("你好");
        assertThat(subscription.requested.get()).isEqualTo(2);
        assertThat(subscription.cancelled).isFalse();
    }

    @Test
    void fallsBackToUtf8WhenCharsetIsMissingOrInvalid() {
        HttpResponse.BodySubscriber<String> missing = new BoundedStringBodyHandler(8)
                .apply(responseInfo(null));
        missing.onSubscribe(new RecordingSubscription());
        missing.onNext(List.of(ByteBuffer.wrap("ok".getBytes(StandardCharsets.UTF_8))));
        missing.onComplete();
        assertThat(missing.getBody().toCompletableFuture().join()).isEqualTo("ok");

        HttpResponse.BodySubscriber<String> invalid = new BoundedStringBodyHandler(8)
                .apply(responseInfo("text/plain; charset=does-not-exist"));
        invalid.onSubscribe(new RecordingSubscription());
        invalid.onNext(List.of(ByteBuffer.wrap("好".getBytes(StandardCharsets.UTF_8))));
        invalid.onComplete();
        assertThat(invalid.getBody().toCompletableFuture().join()).isEqualTo("好");
    }

    @Test
    void cancelsWhenBodyExceedsTheLimitAndPropagatesUpstreamErrors() {
        HttpResponse.BodySubscriber<String> oversized = new BoundedStringBodyHandler(2)
                .apply(responseInfo(null));
        RecordingSubscription subscription = new RecordingSubscription();
        oversized.onSubscribe(subscription);
        oversized.onNext(List.of(ByteBuffer.wrap("too large".getBytes(StandardCharsets.UTF_8))));

        assertThat(subscription.cancelled).isTrue();
        assertThatThrownBy(() -> oversized.getBody().toCompletableFuture().join())
                .hasCauseInstanceOf(ResponseBodyTooLargeException.class);

        HttpResponse.BodySubscriber<String> failed = new BoundedStringBodyHandler(8)
                .apply(responseInfo(null));
        RuntimeException upstream = new RuntimeException("upstream");
        failed.onError(upstream);
        assertThatThrownBy(() -> failed.getBody().toCompletableFuture().join())
                .hasCause(upstream);
    }

    @Test
    void cancelsWhenAChunkCannotBeConsumed() {
        HttpResponse.BodySubscriber<String> subscriber = new BoundedStringBodyHandler(8)
                .apply(responseInfo(null));
        RecordingSubscription subscription = new RecordingSubscription();
        subscriber.onSubscribe(subscription);
        subscriber.onNext(Collections.singletonList(null));

        assertThat(subscription.cancelled).isTrue();
        assertThatThrownBy(() -> subscriber.getBody().toCompletableFuture().join())
                .hasCauseInstanceOf(NullPointerException.class);
    }

    private static HttpResponse.ResponseInfo responseInfo(String contentType) {
        Map<String, List<String>> values = contentType == null
                ? Map.of() : Map.of("Content-Type", List.of(contentType));
        return new HttpResponse.ResponseInfo() {
            @Override
            public int statusCode() {
                return 200;
            }

            @Override
            public HttpHeaders headers() {
                return HttpHeaders.of(values, (name, value) -> true);
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }

    private static final class RecordingSubscription implements Flow.Subscription {
        private final AtomicLong requested = new AtomicLong();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public void request(long count) {
            requested.addAndGet(count);
        }

        @Override
        public void cancel() {
            cancelled.set(true);
        }
    }
}
