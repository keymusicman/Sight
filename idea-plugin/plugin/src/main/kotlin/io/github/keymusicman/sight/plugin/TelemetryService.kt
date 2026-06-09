package io.github.keymusicman.sight.plugin

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.metrics.ObservableLongGauge
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader
import io.opentelemetry.sdk.resources.Resource
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

class TelemetryService : Disposable {

    private val log = Logger.getInstance(TelemetryService::class.java)

    private var sdk: OpenTelemetrySdk? = null
    private var phaseHistogram: DoubleHistogram? = null
    private var renderCounter:  LongCounter?     = null
    private var heapUsedGauge:  ObservableLongGauge? = null
    private var heapDeltaGauge: ObservableLongGauge? = null
    private var gcCountCounter: LongCounter?     = null
    private var gcTimeCounter:  LongCounter?     = null

    private val lastHeapUsed  = AtomicLong(0L)
    private val lastHeapDelta = AtomicLong(0L)

    init {
        initializeSdk()
    }

    fun reinitialize() {
        dispose()
        initializeSdk()
    }

    private fun initializeSdk() {
        val settings = PluginSettingsService.getInstance().getState()
        if (!OtelConfig.OTEL_ENABLED || !settings.telemetryEnabled) {
            log.info("TelemetryService: disabled (OTEL_ENABLED=${OtelConfig.OTEL_ENABLED}, telemetryEnabled=${settings.telemetryEnabled})")
            return
        }

        try {
            val exporter = OtlpJsonMetricExporter(
                endpoint = OtelConfig.OTLP_ENDPOINT,
                authHeader = OtelConfig.OTLP_AUTH_HEADER,
            )

            val meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(
                    PeriodicMetricReader.builder(exporter)
                        .setInterval(Duration.ofSeconds(15))
                        .build()
                )
                .setResource(
                    Resource.create(
                        Attributes.of(AttributeKey.stringKey("service.name"), "appflower-plugin")
                    )
                )
                .build()

            val sdkInstance = OpenTelemetrySdk.builder()
                .setMeterProvider(meterProvider)
                .build()
            sdk = sdkInstance

            val meter = sdkInstance.getMeter("appflower")

            phaseHistogram = meter.histogramBuilder("appflower.render.phase.ms")
                .setDescription("Per-render phase duration in milliseconds")
                .setUnit("ms")
                .setExplicitBucketBoundariesAdvice(
                    listOf(5.0, 10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1000.0, 2500.0, 5000.0)
                )
                .build()

            renderCounter = meter.counterBuilder("appflower.render.count")
                .setDescription("Total render invocations by outcome")
                .build()

            heapUsedGauge = meter.gaugeBuilder("appflower.jvm.heap.used.bytes")
                .ofLongs()
                .setDescription("JVM heap used at last render")
                .setUnit("bytes")
                .buildWithCallback { obs -> obs.record(lastHeapUsed.get()) }

            heapDeltaGauge = meter.gaugeBuilder("appflower.jvm.heap.delta.bytes")
                .ofLongs()
                .setDescription("JVM heap delta (after minus before) at last render")
                .setUnit("bytes")
                .buildWithCallback { obs -> obs.record(lastHeapDelta.get()) }

            gcCountCounter = meter.counterBuilder("appflower.gc.collection.count")
                .setDescription("GC collections triggered during renders")
                .build()

            gcTimeCounter = meter.counterBuilder("appflower.gc.collection.ms")
                .setDescription("GC collection time during renders")
                .setUnit("ms")
                .build()

            log.info("TelemetryService: SDK initialized, pushing to ${OtelConfig.OTLP_ENDPOINT}")
        } catch (e: Throwable) {
            log.warn("TelemetryService: failed to initialize OTel SDK", e)
        }
    }

    fun record(sample: RenderSample) {
        val hist    = phaseHistogram  ?: return
        val counter = renderCounter   ?: return

        fun phaseAttrs(phase: String) = Attributes.of(
            AttributeKey.stringKey("phase"),  phase,
            AttributeKey.stringKey("format"), sample.format.name,
        )

        hist.record(sample.inflateMs.toDouble(),   phaseAttrs("inflate"))
        hist.record(sample.callbacksMs.toDouble(), phaseAttrs("callbacks"))
        hist.record(sample.renderMs.toDouble(),    phaseAttrs("render"))
        hist.record(sample.writeMs.toDouble(),     phaseAttrs("write"))
        hist.record(sample.totalMs.toDouble(),     phaseAttrs("total"))

        val outcomeAttrs = Attributes.of(
            AttributeKey.stringKey("outcome"), sample.outcome.name.lowercase(),
        )
        counter.add(1, outcomeAttrs)

        lastHeapUsed.set(sample.heapAfter)
        lastHeapDelta.set(sample.heapDelta)

        gcCountCounter?.add(sample.gcDelta.collectionCount)
        gcTimeCounter?.add(sample.gcDelta.collectionTimeMs)
    }

    fun recordSkip() {
        renderCounter?.add(1, Attributes.of(AttributeKey.stringKey("outcome"), "skip"))
    }

    override fun dispose() {
        try { heapUsedGauge?.close() } catch (_: Exception) {}
        try { heapDeltaGauge?.close() } catch (_: Exception) {}
        sdk?.close()
        sdk            = null
        phaseHistogram = null
        renderCounter  = null
        heapUsedGauge  = null
        heapDeltaGauge = null
        gcCountCounter = null
        gcTimeCounter  = null
        log.info("TelemetryService: SDK disposed")
    }

    companion object {
        fun getInstance(): TelemetryService =
            ApplicationManager.getApplication().getService(TelemetryService::class.java)
    }
}
