package dev.finguard.dashboard.service;

import dev.finguard.config.MetricsConfig;
import dev.finguard.domain.enums.*;
import dev.finguard.domain.model.MetricSnapshot;
import dev.finguard.domain.repository.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Aggregates statistics across the FinGuard system for the dashboard.
 *
 * <p>Provides overview metrics, detection analytics, explanation quality
 * summaries, and dataset breakdowns. Expensive aggregate queries are cached
 * using Caffeine with a short TTL to reduce database load on dashboard refreshes.</p>
 */
@Service
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    /**
     * A snapshot older than this is considered stale; the dashboard falls back
     * to live queries so a crashed/disabled refresher can't freeze the UI.
     * Chosen as 3× the refresh interval (60 s) — two misses before we fall back.
     */
    private static final Duration SNAPSHOT_MAX_AGE = Duration.ofSeconds(180);

    private final TransactionRepository transactionRepository;
    private final AlertRepository alertRepository;
    private final ExplanationRepository explanationRepository;
    private final ExperimentResultRepository experimentResultRepository;
    private final FraudPatternRepository fraudPatternRepository;
    private final MetricSnapshotRepository snapshotRepository;
    private final MeterRegistry meterRegistry;

    public DashboardService(TransactionRepository transactionRepository,
                             AlertRepository alertRepository,
                             ExplanationRepository explanationRepository,
                             ExperimentResultRepository experimentResultRepository,
                             FraudPatternRepository fraudPatternRepository,
                             MetricSnapshotRepository snapshotRepository,
                             MeterRegistry meterRegistry) {
        this.transactionRepository = transactionRepository;
        this.alertRepository = alertRepository;
        this.explanationRepository = explanationRepository;
        this.experimentResultRepository = experimentResultRepository;
        this.fraudPatternRepository = fraudPatternRepository;
        this.snapshotRepository = snapshotRepository;
        this.meterRegistry = meterRegistry;
    }

    /**
     * High-level system overview: counts of transactions, alerts, explanations,
     * fraud patterns, and experiments.
     *
     * <p>Audit D-3: prefers the most recent {@code metric_snapshots} row if it
     * was refreshed within the last {@link #SNAPSHOT_MAX_AGE}. Falls back to
     * live {@code COUNT(*)} queries only when no snapshot exists, or the
     * snapshot is older than the tolerance (e.g., the refresher crashed, or
     * the app just started and hasn't run it yet).</p>
     */
    @Cacheable(value = "dashboard-stats", key = "'overview'")
    public SystemOverview getSystemOverview() {
        Optional<MetricSnapshot> fresh = snapshotRepository.findLatest()
                .filter(s -> Duration.between(s.getSnapshotAt(), LocalDateTime.now())
                        .compareTo(SNAPSHOT_MAX_AGE) <= 0);

        if (fresh.isPresent()) {
            return fromSnapshot(fresh.get());
        }
        return computeLiveOverview();
    }

    /** Live path — used when no fresh snapshot is available. */
    private SystemOverview computeLiveOverview() {
        long totalTransactions = transactionRepository.count();
        long fraudTransactions = transactionRepository.countByIsFraudTrue();
        long totalAlerts = alertRepository.count();
        long totalExplanations = explanationRepository.count();
        long totalFraudPatterns = fraudPatternRepository.count();
        long totalExperiments = experimentResultRepository.count();

        double fraudRate = totalTransactions > 0
                ? round((double) fraudTransactions / totalTransactions * 100, 2)
                : 0.0;

        return new SystemOverview(
                totalTransactions, fraudTransactions, fraudRate,
                totalAlerts, totalExplanations,
                totalFraudPatterns, totalExperiments
        );
    }

    private SystemOverview fromSnapshot(MetricSnapshot s) {
        double fraudRate = s.getTotalTransactions() > 0
                ? round((double) s.getTotalFraudTx() / s.getTotalTransactions() * 100, 2)
                : 0.0;
        return new SystemOverview(
                s.getTotalTransactions(), s.getTotalFraudTx(), fraudRate,
                s.getTotalAlerts(), s.getTotalExplanations(),
                s.getTotalFraudPatterns(), s.getTotalExperiments()
        );
    }

    /**
     * Detection analytics: alert distribution by status, by detection config,
     * and average ML risk score.
     */
    @Cacheable(value = "dashboard-stats", key = "'detection'")
    public DetectionAnalytics getDetectionAnalytics() {
        Map<String, Long> alertsByStatus = groupedCountToMap(alertRepository.countByStatusGrouped());
        Map<String, Long> alertsByConfig = groupedCountToMap(alertRepository.countByDetectionConfigGrouped());

        // Audit B-5: one GROUP BY query instead of one COUNT per config.
        long anomalyCount = alertRepository.countAnomaliesByDetectionConfigGrouped().stream()
                .mapToLong(row -> ((Number) row[1]).longValue())
                .sum();

        Double avgRiskScore = alertRepository.avgMlRiskScore();

        return new DetectionAnalytics(
                alertsByStatus,
                alertsByConfig,
                anomalyCount,
                avgRiskScore != null ? round(avgRiskScore, 4) : null
        );
    }

    /**
     * Explanation quality: CAKR dimension averages, hallucination stats,
     * average latency and confidence, and breakdown by explanation type.
     */
    public ExplanationQuality getExplanationQuality() {
        Double avgCompleteness = explanationRepository.avgCakrCompleteness();
        Double avgCorrectness = explanationRepository.avgCakrCorrectness();
        Double avgActionability = explanationRepository.avgCakrActionability();
        Double avgRegulatory = explanationRepository.avgCakrRegulatory();

        CakrAverages cakr = new CakrAverages(
                roundOrNull(avgCompleteness, 2),
                roundOrNull(avgCorrectness, 2),
                roundOrNull(avgActionability, 2),
                roundOrNull(avgRegulatory, 2)
        );

        long hallucinationFree = explanationRepository.countByHallucinationFreeTrue();
        long hallucinationFlagged = explanationRepository.countByHallucinationFreeFalse();
        long totalExplanations = explanationRepository.count();
        double hallucinationRate = totalExplanations > 0
                ? round((double) hallucinationFlagged / totalExplanations * 100, 2)
                : 0.0;

        Double avgLatency = explanationRepository.avgLatency();
        Double avgConfidence = explanationRepository.avgConfidenceScore();

        Map<String, Long> byType = groupedCountToMap(explanationRepository.countByExplanationTypeGrouped());

        return new ExplanationQuality(
                cakr,
                hallucinationFree, hallucinationFlagged, hallucinationRate,
                roundOrNull(avgLatency, 0),
                roundOrNull(avgConfidence, 4),
                byType
        );
    }

    /**
     * Dataset breakdown: transaction and fraud counts per data source.
     */
    public List<DatasetBreakdown> getDatasetBreakdown() {
        return java.util.Arrays.stream(DatasetSource.values())
                .map(source -> {
                    long total = transactionRepository.countByDatasetSource(source);
                    long fraud = transactionRepository.countFraudByDatasetSource(source);
                    double fraudRate = total > 0
                            ? round((double) fraud / total * 100, 2)
                            : 0.0;
                    return new DatasetBreakdown(source.getDisplayName(), total, fraud, fraudRate);
                })
                .filter(ds -> ds.totalTransactions() > 0)
                .toList();
    }

    /**
     * Transaction type distribution.
     */
    public Map<String, Long> getTransactionTypeDistribution() {
        return groupedCountToMap(transactionRepository.countByTransactionType());
    }

    /**
     * Latest experiment results, optionally filtered by experiment name.
     */
    public List<ExperimentSummary> getExperimentSummaries(String experimentName) {
        List<dev.finguard.domain.model.ExperimentResult> results = experimentName != null
                ? experimentResultRepository.findByExperimentNameOrdered(experimentName)
                : experimentResultRepository.findAll();

        return results.stream()
                .map(r -> new ExperimentSummary(
                        r.getExperimentName(),
                        r.getConfig().name(),
                        r.getDataset() != null ? r.getDataset().name() : null,
                        roundOrNull(r.getPrecisionScore(), 4),
                        roundOrNull(r.getRecallScore(), 4),
                        roundOrNull(r.getF1Score(), 4),
                        roundOrNull(r.getFalsePositiveRate(), 4),
                        roundOrNull(r.getAvgCakrScore(), 2),
                        roundOrNull(r.getHallucinationRate(), 2),
                        roundOrNull(r.getAvgLatencyMs(), 0),
                        r.getTotalTransactions(),
                        r.getTotalAlerts()
                ))
                .toList();
    }

    // --- DTOs ---

    public record SystemOverview(
            long totalTransactions, long fraudTransactions, double fraudRatePercent,
            long totalAlerts, long totalExplanations,
            long totalFraudPatterns, long totalExperiments
    ) {}

    public record DetectionAnalytics(
            Map<String, Long> alertsByStatus,
            Map<String, Long> alertsByConfig,
            long totalAnomalies,
            Double avgMlRiskScore
    ) {}

    public record ExplanationQuality(
            CakrAverages cakrAverages,
            long hallucinationFree, long hallucinationFlagged, double hallucinationRatePercent,
            Double avgLatencyMs, Double avgConfidenceScore,
            Map<String, Long> explanationsByType
    ) {}

    public record CakrAverages(
            Double completeness, Double correctness,
            Double actionability, Double regulatory
    ) {}

    public record DatasetBreakdown(
            String datasetSource,
            long totalTransactions, long fraudTransactions, double fraudRatePercent
    ) {}

    public record ExperimentSummary(
            String experimentName, String config, String dataset,
            Double precision, Double recall, Double f1Score, Double falsePositiveRate,
            Double avgCakrScore, Double hallucinationRate, Double avgLatencyMs,
            Integer totalTransactions, Integer totalAlerts
    ) {}

    // ================================================================
    // Pipeline-stage timings (Option-1 dashboard panel)
    // ----------------------------------------------------------------
    // Reads Micrometer Timers populated at runtime by the various
    // pipeline services. Counts/sums reset on JVM restart — this is the
    // accepted MVP scope (see thesis discussion §X).
    // ================================================================

    /**
     * Per-stage timing record. Times are in milliseconds for compact JSON.
     * {@code count == 0} means the stage hasn't been run yet in this JVM.
     */
    public record StageTiming(String stage, long count, long totalMs, long avgMs) {}

    /**
     * Per-detection-config timing breakdown. {@code null} fields indicate the
     * stage doesn't apply to that config (e.g., RULES_ONLY has no LLM phase).
     */
    public record ConfigTiming(String config,
                                Long detectionMs, Long explanationMs, Long cakrMs,
                                Long totalMs) {}

    /**
     * Full timings response — matches the structure rendered by the dashboard
     * "Pipeline Stage Timings" panel.
     */
    public record TimingsSummary(
            List<StageTiming> ingestion,
            List<StageTiming> training,
            List<ConfigTiming> evaluation,
            long evaluationTotalMs
    ) {}

    /**
     * Aggregate per-stage timing data from the in-memory MeterRegistry into
     * the structured shape consumed by the dashboard.
     *
     * <p>Not cached — the registry itself is essentially an in-memory
     * aggregate, and the dashboard polls this every ~30 s during runs.
     * Cost is dominated by N small Map lookups (negligible).</p>
     */
    public TimingsSummary getTimings() {
        // ── Ingestion: import → features → split (canonical render order) ──
        List<StageTiming> ingestion = List.of(
                readStage("csv_import", "Importing"),
                readStage("feature_compute", "Compute Features"),
                readStage("train_test_split", "Train/Test Split")
        );

        // ── ML training: RF and XGBoost (run in parallel inside trainModels) ──
        List<StageTiming> training = List.of(
                readTraining("rf", "Random Forest"),
                readTraining("xgb", "XGBoost")
        );

        // ── Evaluation: per detection config, sum of detection + explanation + cakr ──
        List<ConfigTiming> evaluation = new ArrayList<>();
        long evalTotal = 0L;
        for (DetectionConfig cfg : DetectionConfig.values()) {
            Long det  = readNullableMs(MetricsConfig.DETECTION_CONFIG_TIMER,  "config", cfg.name());
            Long expl = readNullableMs(MetricsConfig.EXPLANATION_CONFIG_TIMER, "config", cfg.name());
            Long cakr = readNullableMs(MetricsConfig.CAKR_CONFIG_TIMER,        "config", cfg.name());
            long sum  = (det == null ? 0L : det) + (expl == null ? 0L : expl) + (cakr == null ? 0L : cakr);
            // Keep the row even if entirely empty so the dashboard table renders all 5 configs.
            evaluation.add(new ConfigTiming(cfg.name(), det, expl, cakr, sum > 0 ? sum : null));
            evalTotal += sum;
        }

        return new TimingsSummary(ingestion, training, evaluation, evalTotal);
    }

    /** Read a stage timer (canonical name from {@link MetricsConfig#STAGE_TIMER}). */
    private StageTiming readStage(String stageTag, String displayName) {
        return readTimer(MetricsConfig.STAGE_TIMER, "stage", stageTag, displayName);
    }

    /** Read a training timer (canonical name from {@link MetricsConfig#TRAINING_TIMER}). */
    private StageTiming readTraining(String modelTag, String displayName) {
        return readTimer(MetricsConfig.TRAINING_TIMER, "model", modelTag, displayName);
    }

    /**
     * Look up a tagged Timer and project it to a {@link StageTiming}.
     * Returns a zero-value record (not null) when the timer hasn't fired yet —
     * keeps the dashboard table rows stable across the first run.
     */
    private StageTiming readTimer(String name, String tagKey, String tagValue, String displayName) {
        Timer timer = meterRegistry.find(name).tag(tagKey, tagValue).timer();
        if (timer == null || timer.count() == 0) {
            return new StageTiming(displayName, 0L, 0L, 0L);
        }
        long count = timer.count();
        long totalMs = (long) timer.totalTime(TimeUnit.MILLISECONDS);
        long avgMs = count > 0 ? totalMs / count : 0L;
        return new StageTiming(displayName, count, totalMs, avgMs);
    }

    /**
     * Returns total-time-millis for a tagged Timer, or {@code null} when no
     * sample has been recorded. Used for the per-config evaluation table
     * where missing rows mean "this stage doesn't apply" (e.g., CAKR for
     * RULES_ONLY) and should render as "—" not "0 ms".
     */
    private Long readNullableMs(String name, String tagKey, String tagValue) {
        Timer timer = meterRegistry.find(name).tag(tagKey, tagValue).timer();
        if (timer == null || timer.count() == 0) return null;
        return (long) timer.totalTime(TimeUnit.MILLISECONDS);
    }

    // --- Helpers ---

    private Map<String, Long> groupedCountToMap(List<Object[]> rows) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String key = row[0] != null ? row[0].toString() : "UNKNOWN";
            Long count = (Long) row[1];
            result.put(key, count);
        }
        return result;
    }

    private double round(double value, int places) {
        return BigDecimal.valueOf(value)
                .setScale(places, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private Double roundOrNull(Double value, int places) {
        return value != null ? round(value, places) : null;
    }
}
