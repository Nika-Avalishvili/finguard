package dev.finguard.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers custom Micrometer metrics for the FinGuard application.
 *
 * <p>These metrics complement the default Spring Boot Actuator metrics
 * (HTTP request latency, JVM stats, DB pool) with domain-specific
 * counters and timers for:</p>
 * <ul>
 *   <li><b>Detection pipeline</b> — alerts created per config, pipeline duration</li>
 *   <li><b>LLM calls</b> — invocation count, latency, failure count</li>
 *   <li><b>RAG retrieval</b> — search count, latency, cache hit ratio</li>
 *   <li><b>Ingestion</b> — transactions imported, batches processed</li>
 * </ul>
 *
 * <p>Metrics are exported to Prometheus at {@code /actuator/prometheus}
 * for collection by monitoring infrastructure.</p>
 */
@Configuration
public class MetricsConfig {

    // ================================================================
    // Stage-timer name registry (Option-1 dashboard panel)
    // ----------------------------------------------------------------
    // These timers are NOT registered as eager beans because they all
    // share a name and are differentiated by tags. Lazily creating them
    // via {@code registry.timer(name, "tag", value)} at the call site is
    // the idiomatic Micrometer pattern and avoids a combinatorial bean
    // explosion across stages × configs.
    //
    // Recorded values are exposed at /actuator/prometheus AND aggregated
    // by DashboardService.getTimings() for the dashboard panel.
    // ================================================================

    /** Ingestion-pipeline stages. Tag: {@code stage} ∈ {csv_import, feature_compute, train_test_split} */
    public static final String STAGE_TIMER = "finguard.stage.duration";

    /** ML training. Tag: {@code model} ∈ {rf, xgb} */
    public static final String TRAINING_TIMER = "finguard.training.duration";

    /** Detection pipeline per detection config. Tag: {@code config} ∈ DetectionConfig.name() */
    public static final String DETECTION_CONFIG_TIMER = "finguard.detection.config.duration";

    /** LLM explanation batch per detection config. Tag: {@code config} ∈ DetectionConfig.name() */
    public static final String EXPLANATION_CONFIG_TIMER = "finguard.explanation.config.duration";

    /** CAKR scoring per detection config. Tag: {@code config} ∈ DetectionConfig.name() */
    public static final String CAKR_CONFIG_TIMER = "finguard.cakr.config.duration";

    // ================================================================
    // Detection pipeline metrics
    // ================================================================

    @Bean
    public Counter alertsCreatedCounter(MeterRegistry registry) {
        return Counter.builder("finguard.detection.alerts.created")
                .description("Total number of alerts created by the detection pipeline")
                .register(registry);
    }

    @Bean
    public Timer detectionPipelineTimer(MeterRegistry registry) {
        return Timer.builder("finguard.detection.pipeline.duration")
                .description("Time taken by the detection pipeline per batch run")
                .publishPercentileHistogram()
                .register(registry);
    }

    // ================================================================
    // LLM metrics
    // ================================================================

    @Bean
    public Counter llmCallCounter(MeterRegistry registry) {
        return Counter.builder("finguard.llm.calls.total")
                .description("Logical LLM calls initiated (one per explanation; retries not counted here — see llm.attempts.total)")
                .register(registry);
    }

    @Bean
    public Counter llmFailureCounter(MeterRegistry registry) {
        return Counter.builder("finguard.llm.calls.failures")
                .description("Number of logical LLM calls that failed after all retries")
                .register(registry);
    }

    /**
     * Audit B-8: incremented inside the {@code @Retryable} body so every HTTP
     * attempt (initial + retries) is counted. Essential for cost analysis
     * (the thesis's evaluation of LLM call volume under failure).
     */
    @Bean
    public Counter llmAttemptCounter(MeterRegistry registry) {
        return Counter.builder("finguard.llm.attempts.total")
                .description("Total LLM API attempts including retries")
                .register(registry);
    }

    @Bean
    public Timer llmCallTimer(MeterRegistry registry) {
        return Timer.builder("finguard.llm.call.duration")
                .description("LLM call latency (including retries)")
                .publishPercentileHistogram()
                .register(registry);
    }

    // ================================================================
    // RAG metrics
    // ================================================================

    @Bean
    public Counter ragSearchCounter(MeterRegistry registry) {
        return Counter.builder("finguard.rag.searches.total")
                .description("Total number of RAG vector store searches")
                .register(registry);
    }

    @Bean
    public Timer ragSearchTimer(MeterRegistry registry) {
        return Timer.builder("finguard.rag.search.duration")
                .description("RAG vector store search latency")
                .publishPercentileHistogram()
                .register(registry);
    }

    // ================================================================
    // Ingestion metrics
    // ================================================================

    @Bean
    public Counter transactionsImportedCounter(MeterRegistry registry) {
        return Counter.builder("finguard.ingestion.transactions.imported")
                .description("Total number of transactions imported from CSV")
                .register(registry);
    }
}
