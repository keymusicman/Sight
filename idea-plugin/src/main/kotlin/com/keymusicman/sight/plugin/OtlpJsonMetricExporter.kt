package com.keymusicman.sight.plugin

import com.intellij.openapi.diagnostic.Logger
import io.opentelemetry.api.common.AttributeType
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.metrics.InstrumentType
import io.opentelemetry.sdk.metrics.data.AggregationTemporality
import io.opentelemetry.sdk.metrics.data.HistogramPointData
import io.opentelemetry.sdk.metrics.data.LongPointData
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.metrics.data.MetricDataType
import io.opentelemetry.sdk.metrics.export.MetricExporter
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends metrics to an OTLP/HTTP endpoint using JSON encoding via java.net.http — no ServiceLoader,
 * no classloader conflicts with IntelliJ's platform-bundled OTel.
 */
internal class OtlpJsonMetricExporter(
    private val endpoint: String,
    private val authHeader: String,
) : MetricExporter {

    private val log = Logger.getInstance(OtlpJsonMetricExporter::class.java)

    private val isShutdown = AtomicBoolean(false)

    override fun export(metrics: Collection<MetricData>): CompletableResultCode {
        if (isShutdown.get()) return CompletableResultCode.ofFailure()
        if (metrics.isEmpty()) return CompletableResultCode.ofSuccess()
        return try {
            val json = buildOtlpJson(metrics)
            val conn = URI.create("$endpoint/v1/metrics").toURL().openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", authHeader)
            conn.connectTimeout = 5_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            val status = try {
                conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                conn.responseCode
            } finally {
                conn.disconnect()
            }
            if (status in 200..299) {
                CompletableResultCode.ofSuccess()
            } else {
                log.warn("OTLP export HTTP $status")
                CompletableResultCode.ofFailure()
            }
        } catch (e: Exception) {
            log.warn("OTLP export failed", e)
            CompletableResultCode.ofFailure()
        }
    }

    override fun getAggregationTemporality(instrumentType: InstrumentType): AggregationTemporality =
        AggregationTemporality.CUMULATIVE

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    override fun shutdown(): CompletableResultCode {
        isShutdown.set(true)
        return CompletableResultCode.ofSuccess()
    }

    // ---------- JSON building ----------

    private fun buildOtlpJson(metrics: Collection<MetricData>): String = buildString {
        append("{\"resourceMetrics\":[")
        val byResource = metrics.groupBy { it.resource }
        var firstResource = true
        for ((resource, rMetrics) in byResource) {
            if (!firstResource) append(",")
            firstResource = false
            append("{\"resource\":{\"attributes\":")
            appendAttrs(resource.attributes)
            append("},\"scopeMetrics\":[")
            val byScope = rMetrics.groupBy { it.instrumentationScopeInfo }
            var firstScope = true
            for ((scope, sMetrics) in byScope) {
                if (!firstScope) append(",")
                firstScope = false
                append("{\"scope\":{\"name\":")
                append(qs(scope.name))
                append("},\"metrics\":[")
                var firstMetric = true
                for (metric in sMetrics) {
                    if (!firstMetric) append(",")
                    firstMetric = false
                    appendMetric(metric)
                }
                append("]}")
            }
            append("]}")
        }
        append("]}")
    }

    private fun StringBuilder.appendMetric(m: MetricData) {
        append("{\"name\":")
        append(qs(m.name))
        if (m.description.isNotEmpty()) {
            append(",\"description\":"); append(qs(m.description))
        }
        if (m.unit.isNotEmpty()) {
            append(",\"unit\":"); append(qs(m.unit))
        }
        when (m.type) {
            MetricDataType.HISTOGRAM -> {
                val hd = m.histogramData
                append(",\"histogram\":{\"aggregationTemporality\":")
                append(qs(hd.aggregationTemporality.otlpName()))
                append(",\"dataPoints\":[")
                var first = true
                for (p in hd.points) {
                    if (!first) append(","); first = false
                    appendHistPoint(p)
                }
                append("]}")
            }
            MetricDataType.LONG_SUM -> {
                val sd = m.longSumData
                append(",\"sum\":{\"aggregationTemporality\":")
                append(qs(sd.aggregationTemporality.otlpName()))
                append(",\"isMonotonic\":"); append(sd.isMonotonic)
                append(",\"dataPoints\":[")
                var first = true
                for (p in sd.points) {
                    if (!first) append(","); first = false
                    appendLongPoint(p, includeStart = true)
                }
                append("]}")
            }
            MetricDataType.LONG_GAUGE -> {
                append(",\"gauge\":{\"dataPoints\":[")
                var first = true
                for (p in m.longGaugeData.points) {
                    if (!first) append(","); first = false
                    appendLongPoint(p, includeStart = false)
                }
                append("]}")
            }
            else -> Unit
        }
        append("}")
    }

    private fun StringBuilder.appendHistPoint(p: HistogramPointData) {
        append("{\"startTimeUnixNano\":\""); append(p.startEpochNanos); append("\"")
        append(",\"timeUnixNano\":\"");       append(p.epochNanos);      append("\"")
        append(",\"count\":\"");              append(p.count);           append("\"")
        append(",\"sum\":"); append(p.sum)
        append(",\"attributes\":"); appendAttrs(p.attributes)
        append(",\"bucketCounts\":[")
        var first = true
        for (c in p.counts) {
            if (!first) append(","); first = false
            append('"'); append(c); append('"')
        }
        append("],\"explicitBounds\":[")
        first = true
        for (b in p.boundaries) {
            if (!first) append(","); first = false
            append(b)
        }
        append("]}")
    }

    private fun StringBuilder.appendLongPoint(p: LongPointData, includeStart: Boolean) {
        append("{")
        if (includeStart) {
            append("\"startTimeUnixNano\":\""); append(p.startEpochNanos); append("\",")
        }
        append("\"timeUnixNano\":\""); append(p.epochNanos); append("\"")
        append(",\"asInt\":\"");       append(p.value);      append("\"")
        append(",\"attributes\":"); appendAttrs(p.attributes)
        append("}")
    }

    private fun StringBuilder.appendAttrs(attrs: Attributes) {
        append("[")
        var first = true
        for ((key, value) in attrs.asMap()) {
            if (!first) append(","); first = false
            append("{\"key\":"); append(qs(key.key))
            append(",\"value\":")
            when (key.type) {
                AttributeType.STRING  -> { append("{\"stringValue\":"); append(qs(value as String)); append("}") }
                AttributeType.LONG    -> { append("{\"intValue\":\""); append(value); append("\"}") }
                AttributeType.DOUBLE  -> { append("{\"doubleValue\":"); append(value); append("}") }
                AttributeType.BOOLEAN -> { append("{\"boolValue\":"); append(value); append("}") }
                else -> { append("{\"stringValue\":"); append(qs(value.toString())); append("}") }
            }
            append("}")
        }
        append("]")
    }

    private fun AggregationTemporality.otlpName() = when (this) {
        AggregationTemporality.CUMULATIVE -> "AGGREGATION_TEMPORALITY_CUMULATIVE"
        AggregationTemporality.DELTA      -> "AGGREGATION_TEMPORALITY_DELTA"
    }

    /** Returns a JSON-safe double-quoted string. */
    private fun qs(s: String) = buildString {
        append('"')
        for (c in s) when (c) {
            '"'  -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u${c.code.toString(16).padStart(4, '0')}") else append(c)
        }
        append('"')
    }
}
