package com.socp.gateway.security;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Streaming string subscriber that cancels an oversized response. */
final class BoundedStringBodyHandler implements HttpResponse.BodyHandler<String> {

    private static final Pattern CHARSET = Pattern.compile(
            ";\\s*charset\\s*=\\s*(\"?)([A-Za-z0-9._:-]+)\\1", Pattern.CASE_INSENSITIVE);

    private final int maxBytes;

    BoundedStringBodyHandler(int maxBytes) {
        this.maxBytes = maxBytes;
    }

    @Override
    public HttpResponse.BodySubscriber<String> apply(HttpResponse.ResponseInfo responseInfo) {
        Charset charset = responseInfo.headers().firstValue("Content-Type")
                .flatMap(BoundedStringBodyHandler::parseCharset)
                .orElse(StandardCharsets.UTF_8);
        return new Subscriber(charset);
    }

    private static Optional<Charset> parseCharset(String contentType) {
        Matcher matcher = CHARSET.matcher(contentType);
        if (!matcher.find()) return Optional.empty();
        try {
            return Optional.of(Charset.forName(matcher.group(2)));
        } catch (Exception invalidCharset) {
            return Optional.empty();
        }
    }

    private final class Subscriber implements HttpResponse.BodySubscriber<String> {

        private final CompletableFuture<String> result = new CompletableFuture<>();
        private final Charset charset;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        private Subscriber(Charset charset) {
            this.charset = charset;
        }

        @Override
        public CompletionStage<String> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            try {
                for (ByteBuffer item : items) {
                    if (buffer.size() + item.remaining() > maxBytes) {
                        subscription.cancel();
                        result.completeExceptionally(new IOException(
                                "OIDC response body exceeds the " + maxBytes + " byte limit"));
                        return;
                    }
                    byte[] chunk = new byte[item.remaining()];
                    item.get(chunk);
                    buffer.write(chunk);
                }
                subscription.request(1);
            } catch (Throwable failure) {
                subscription.cancel();
                result.completeExceptionally(failure);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(new String(buffer.toByteArray(), charset));
        }
    }
}
