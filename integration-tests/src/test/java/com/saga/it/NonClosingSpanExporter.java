package com.saga.it;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;

/**
 * O exporter em memoria e compartilhado por todos os servicos do ambiente de teste. Cada contexto Spring que fecha
 * chamaria {@code shutdown()} e o exporter passaria a recusar spans de TODOS os outros. Este wrapper ignora o shutdown.
 */
final class NonClosingSpanExporter implements SpanExporter {

    private final SpanExporter delegate;

    NonClosingSpanExporter(SpanExporter delegate) {
        this.delegate = delegate;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        return delegate.export(spans);
    }

    @Override
    public CompletableResultCode flush() {
        return delegate.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }
}
