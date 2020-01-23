package io.opentelemetry.auto.tooling;

import com.microsoft.applicationinsights.agentot.Exporter;
import com.microsoft.applicationinsights.agentot.internal.Global;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.export.SimpleSpansProcessor;

public class TracerInstaller {

    public static void installAgentTracer() {
        OpenTelemetrySdk.getTracerFactory()
                .addSpanProcessor(SimpleSpansProcessor.newBuilder(new Exporter(Global.getTelemetryClient())).build());
    }

    public static void logVersionInfo() {
    }
}
