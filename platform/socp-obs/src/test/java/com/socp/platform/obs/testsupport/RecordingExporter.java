package com.socp.platform.obs.testsupport;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Minimal in-memory span exporter so observability tests can assert on exported
 * span data — which is what a collector actually receives — without adding a
 * test-only dependency.
 */
public final class RecordingExporter implements SpanExporter {

    private final List<SpanData> spans = new ArrayList<>();

    @Override
    public CompletableResultCode export(Collection<SpanData> exported) {
        spans.addAll(exported);
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }

    public List<SpanData> spans() {
        return spans;
    }

    public SpanData only(String name) {
        List<SpanData> matches = new ArrayList<>();
        for (SpanData span : spans) {
            if (name.equals(span.getName())) {
                matches.add(span);
            }
        }
        if (matches.size() != 1) {
            throw new AssertionError("expected exactly one span named " + name + ", found " + matches.size());
        }
        return matches.get(0);
    }
}
