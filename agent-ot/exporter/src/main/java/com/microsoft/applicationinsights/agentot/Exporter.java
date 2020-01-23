/*
 * ApplicationInsights-Java
 * Copyright (c) Microsoft Corporation
 * All rights reserved.
 *
 * MIT License
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the ""Software""), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify, merge,
 * publish, distribute, sublicense, and/or sell copies of the Software, and to permit
 * persons to whom the Software is furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED *AS IS*, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
 * PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE
 * FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */
package com.microsoft.applicationinsights.agentot;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.common.base.Strings;
import com.microsoft.applicationinsights.TelemetryClient;
import com.microsoft.applicationinsights.internal.logger.InternalLogger;
import com.microsoft.applicationinsights.telemetry.Duration;
import com.microsoft.applicationinsights.telemetry.ExceptionTelemetry;
import com.microsoft.applicationinsights.telemetry.RemoteDependencyTelemetry;
import com.microsoft.applicationinsights.telemetry.RequestTelemetry;
import com.microsoft.applicationinsights.telemetry.SeverityLevel;
import com.microsoft.applicationinsights.telemetry.Telemetry;
import com.microsoft.applicationinsights.telemetry.TraceTelemetry;
import io.opentelemetry.sdk.trace.SpanData;
import io.opentelemetry.sdk.trace.SpanData.TimedEvent;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.trace.AttributeValue;
import io.opentelemetry.trace.AttributeValue.Type;
import io.opentelemetry.trace.Span.Kind;
import io.opentelemetry.trace.SpanId;
import io.opentelemetry.trace.TraceId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

public class Exporter implements SpanExporter {

    private static final Logger logger = LoggerFactory.getLogger(Exporter.class);

    private final TelemetryClient telemetryClient;

    public Exporter(TelemetryClient telemetryClient) {
        this.telemetryClient = telemetryClient;
    }

    @Override
    public ResultCode export(List<SpanData> spans) {
        try {
            for (SpanData span : spans) {
                System.out.println("SPAN: " + span);
                export(span);
            }
            return ResultCode.SUCCESS;
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
            return ResultCode.FAILED_NOT_RETRYABLE;
        }
    }

    private void export(SpanData span) {
        Kind kind = span.getKind();
        if (kind == Kind.INTERNAL) {
            if (span.getName().equals("log.message")) {
                exportLogSpan(span);
            } else {
                exportRemoteDependency(span, true);
            }
        } else if (kind == Kind.CLIENT) {
            exportRemoteDependency(span, false);
        } else if (kind == Kind.SERVER) {
            exportRequest(span);
        } else {
            throw new UnsupportedOperationException(kind.name());
        }
    }

    private void exportRequest(SpanData span) {

        RequestTelemetry telemetry = new RequestTelemetry();

        AttributeValue httpStatusCode = span.getAttributes().get("http.status_code");
        if (isNonNullLong(httpStatusCode)) {
            telemetry.setResponseCode(Long.toString(httpStatusCode.getLongValue()));
        }

        String httpUrl = getString(span, "http.url");
        if (httpUrl != null) {
            telemetry.setUrl(httpUrl);
        }

        telemetry.setName(span.getName());

        String id = setContext(span, telemetry);
        telemetry.setId(id);

        telemetry.setTimestamp(new Date(NANOSECONDS.toMillis(span.getStartEpochNanos())));
        telemetry.setDuration(new Duration(NANOSECONDS.toMillis(span.getEndEpochNanos() - span.getStartEpochNanos())));

        telemetry.setSuccess(span.getStatus().isOk());
        String description = span.getStatus().getDescription();
        if (description != null) {
            telemetry.getProperties().put("statusDescription", description);
        }

        telemetryClient.track(telemetry);

        trackExceptionIfNeeded(span, telemetry, telemetry.getId());

        exportEvents(span);
    }

    private void exportRemoteDependency(SpanData span, boolean inProc) {

        RemoteDependencyTelemetry telemetry = new RemoteDependencyTelemetry();
        if (inProc) {
            telemetry.setType("InProc");
            telemetry.setName(span.getName());
        } else {
            // FIXME better to use component = "http" once that is set correctly in auto-instrumentation
            // http.method is required for http requests, see
            // https://github.com/open-telemetry/opentelemetry-specification/blob/master/specification/data-http.md
            if (span.getAttributes().containsKey("http.method")) {
                applyHttpRequestSpan(span, telemetry);
            } else if (span.getName().equals("database.query") || span.getName().equals("redis.query")) {
                applyDatabaseQuerySpan(span, telemetry);
            } else {
                telemetry.setName(span.getName());
            }
        }

        String id = setContext(span, telemetry);
        telemetry.setId(id);

        telemetry.setTimestamp(new Date(NANOSECONDS.toMillis(span.getStartEpochNanos())));
        telemetry.setDuration(new Duration(NANOSECONDS.toMillis(span.getEndEpochNanos() - span.getStartEpochNanos())));

        // TODO what is command name, why doesn't dot net exporter use it?
        // https://raw.githubusercontent.com/open-telemetry/opentelemetry-dotnet/master/src/OpenTelemetry.Exporter
        // .ApplicationInsights/ApplicationInsightsTraceExporter.cs
        // telemetry.setCommandName(uri);

        telemetry.setSuccess(span.getStatus().isOk());
        String description = span.getStatus().getDescription();
        if (description != null) {
            telemetry.getProperties().put("statusDescription", description);
        }

        telemetryClient.track(telemetry);

        trackExceptionIfNeeded(span, telemetry, telemetry.getId());

        exportEvents(span);
    }

    private void exportLogSpan(SpanData span) {
        String message = getString(span, "message");
        String level = getString(span, "level");
        String loggerName = getString(span, "loggerName");
        String errorStack = getString(span, "error.stack");
        if (errorStack == null) {
            trackTrace(message, span.getStartEpochNanos(), level, loggerName, span.getTraceId(),
                    span.getParentSpanId());
        } else {
            trackException(message, span.getStartEpochNanos(), level, loggerName, errorStack, span.getTraceId(),
                    span.getParentSpanId());
        }
    }

    // TODO handle other types of events besides logs
    private void exportEvents(SpanData span) {
        for (TimedEvent event : span.getTimedEvents()) {
            String message = event.getName();
            long timeEpochNanos = event.getEpochNanos();
            String level = getString(event, "level");
            String loggerName = getString(event, "loggerName");
            String errorStack = getString(event, "error.stack");
            if (errorStack == null) {
                trackTrace(message, timeEpochNanos, level, loggerName, span.getTraceId(), span.getSpanId());
            } else {
                trackException(message, timeEpochNanos, level, loggerName, errorStack, span.getTraceId(),
                        span.getSpanId());
            }
        }
    }

    private void trackTrace(String message, long timeEpochNanos, String level, String loggerName, TraceId traceId,
                            SpanId parentSpanId) {
        TraceTelemetry telemetry = new TraceTelemetry(message, toSeverityLevel(level));

        String traceIdStr = traceId.toLowerBase16();
        telemetry.getContext().getOperation().setId(traceIdStr);
        if (parentSpanId.isValid()) {
            telemetry.getContext().getOperation().setParentId(combine(traceIdStr, parentSpanId));
        }

        setProperties(telemetry.getProperties(), timeEpochNanos, level, loggerName);
        telemetryClient.trackTrace(telemetry);
    }

    private void trackException(String message, long timeEpochNanos, String level, String loggerName, String errorStack,
                                TraceId traceId, SpanId parentSpanId) {
        ExceptionTelemetry telemetry = new ExceptionTelemetry();

        String traceIdStr = traceId.toLowerBase16();
        telemetry.getContext().getOperation().setId(traceIdStr);
        if (parentSpanId.isValid()) {
            telemetry.getContext().getOperation().setParentId(combine(traceIdStr, parentSpanId));
        }

        telemetry.getData().setExceptions(Exceptions.minimalParse(errorStack));
        telemetry.setSeverityLevel(toSeverityLevel(level));
        telemetry.getProperties().put("Logger Message", message);
        setProperties(telemetry.getProperties(), timeEpochNanos, level, loggerName);
        telemetryClient.trackException(telemetry);
    }

    private void setProperties(Map<String, String> properties, long timeEpochNanos, String level, String loggerName) {

        properties.put("SourceType", "Logger");
        properties.put("TimeStamp", getFormattedDate(NANOSECONDS.toMillis(timeEpochNanos)));
        if (level != null) {
            properties.put("LoggingLevel", level);
        }
        if (loggerName != null) {
            properties.put("LoggerName", loggerName);
        }
    }

    private void applyHttpRequestSpan(SpanData span, RemoteDependencyTelemetry telemetry) {

        telemetry.setType("Http (tracked component)");

        String method = getString(span, "http.method");
        String url = getString(span, "http.url");

        String httpMethod = getString(span, "http.method");
        if (httpMethod != null) {
            String httpUrl = getString(span, "http.url");
            // TODO handle if no http.url
            if (httpUrl != null) {
                // TODO is this right, overwriting name?
                telemetry.setName(httpMethod + " " + httpUrl);
            }
        }

        AttributeValue httpStatusCode = span.getAttributes().get("http.status_code");
        if (httpStatusCode != null && httpStatusCode.getType() == Type.LONG) {
            long statusCode = httpStatusCode.getLongValue();
            telemetry.setResultCode(Long.toString(statusCode));
            // success is handled more generally now
            // telemetry.setSuccess(statusCode < 400);
        }

        if (method != null) {
            // FIXME can drop this now?
            // for backward compatibility (same comment from CoreAgentNotificationsHandler)
            telemetry.getProperties().put("Method", method);
        }
        if (url != null) {
            try {
                URI uriObject = new URI(url);
                String target = createTarget(uriObject);
                // TODO can drop this now?
//                if (requestContext != null) {
//                    String incomingTarget = TraceContextCorrelationCore.generateChildDependencyTarget(requestContext);
//                    if (incomingTarget != null && !incomingTarget.isEmpty()) {
//                        target += " | " + incomingTarget;
//                    }
//                }
                telemetry.setTarget(target);
                String path = uriObject.getPath();
                if (Strings.isNullOrEmpty(path)) {
                    telemetry.setName(method + " /");
                } else {
                    telemetry.setName(method + " " + path);
                }
            } catch (URISyntaxException e) {
                logger.error(e.getMessage());
                logger.debug(e.getMessage(), e);
            }
            telemetry.setCommandName(url);
            // FIXME can drop this now?
            // for backward compatibility (same comment from CoreAgentNotificationsHandler)
            telemetry.getProperties().put("URI", url);
        }
    }

    private void applyDatabaseQuerySpan(SpanData span, RemoteDependencyTelemetry telemetry) {
        String dbType = getString(span, "db.type"); // e.g. "hsqldb"
        String resourceName = getString(span, "resource.name"); // same as db.statement
        String spanType = getString(span, "span.type"); // "sql"

        if (dbType != null) {
            // ???
            telemetry.setName(dbType);
        }

        if (resourceName != null) { // same as dbStatement (but more generally applicable?)
            telemetry.setCommandName(resourceName);
        }
        if (spanType != null) {
            if (spanType.equals("sql")) {
                telemetry.setType("SQL");
            } else if (spanType.equals("redis")) {
                telemetry.setType("Redis");
            } else {
                telemetry.setType(spanType);
            }
        }
    }

    private static String getString(SpanData span, String attributeName) {
        AttributeValue attributeValue = span.getAttributes().get(attributeName);
        if (attributeValue == null) {
            return null;
        } else if (attributeValue.getType() == AttributeValue.Type.STRING) {
            return attributeValue.getStringValue();
        } else {
            // TODO log debug warning
            return null;
        }
    }

    private static String getString(TimedEvent event, String attributeName) {
        AttributeValue attributeValue = event.getAttributes().get(attributeName);
        if (attributeValue == null) {
            return null;
        } else if (attributeValue.getType() == AttributeValue.Type.STRING) {
            return attributeValue.getStringValue();
        } else {
            // TODO log debug warning
            return null;
        }
    }

    private void trackExceptionIfNeeded(SpanData span, Telemetry telemetry, String id) {
        String errorStack = getString(span, "error.stack");
        if (errorStack != null) {
            ExceptionTelemetry exceptionTelemetry = new ExceptionTelemetry();
            exceptionTelemetry.getData().setExceptions(Exceptions.minimalParse(errorStack));
            exceptionTelemetry.getContext().getOperation().setId(telemetry.getContext().getOperation().getId());
            exceptionTelemetry.getContext().getOperation().setParentId(id);
            exceptionTelemetry.setTimestamp(new Date(NANOSECONDS.toMillis(span.getEndEpochNanos())));
            telemetryClient.track(exceptionTelemetry);
        }
    }

    @Override
    public void shutdown() {
    }

    private static String setContext(SpanData span, Telemetry telemetry) {
        String traceId = span.getTraceId().toLowerBase16();
        String id = combine(traceId, span.getSpanId());
        telemetry.getContext().getOperation().setId(traceId);
        SpanId parentSpanId = span.getParentSpanId();
        if (parentSpanId.isValid()) {
            telemetry.getContext().getOperation().setParentId(combine(traceId, parentSpanId));
        }
        return id;
    }

    private static String combine(String traceId, SpanId spanId) {
        // TODO optimize with fixed length StringBuilder
        return "|" + traceId + "." + spanId.toLowerBase16() + ".";
    }

    private static boolean isNonNullLong(AttributeValue attributeValue) {
        return attributeValue != null && attributeValue.getType() == AttributeValue.Type.LONG;
    }

    private static String createTarget(URI uriObject) {
        String target = uriObject.getHost();
        if (uriObject.getPort() != 80 && uriObject.getPort() != 443 && uriObject.getPort() != -1) {
            target += ":" + uriObject.getPort();
        }
        return target;
    }

    private static String getFormattedDate(long dateInMilliseconds) {
        return new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).format(new Date(dateInMilliseconds));
    }

    private static SeverityLevel toSeverityLevel(String level) {
        switch (level) {
            case "FATAL":
                return SeverityLevel.Critical;
            case "ERROR":
                return SeverityLevel.Error;
            case "WARN":
                return SeverityLevel.Warning;
            case "INFO":
                return SeverityLevel.Information;
            case "DEBUG":
            case "TRACE":
            case "ALL":
                return SeverityLevel.Verbose;
            default:
                InternalLogger.INSTANCE.error("Unexpected level '%s', using TRACE level as default", level);
                return SeverityLevel.Verbose;
        }
    }
}
