package dev.finguard.dashboard.service;

import dev.finguard.dashboard.service.DashboardService.*;
import dev.finguard.domain.enums.*;
import dev.finguard.domain.model.ExperimentResult;
import dev.finguard.domain.repository.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DashboardService")
class DashboardServiceTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private AlertRepository alertRepository;
    @Mock private ExplanationRepository explanationRepository;
    @Mock private ExperimentResultRepository experimentResultRepository;
    @Mock private FraudPatternRepository fraudPatternRepository;
    @Mock private MetricSnapshotRepository snapshotRepository;

    /** SimpleMeterRegistry — lets us record real Timer values without bringing in Prometheus. */
    private MeterRegistry meterRegistry;
    private DashboardService dashboardService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        dashboardService = new DashboardService(
                transactionRepository, alertRepository,
                explanationRepository, experimentResultRepository,
                fraudPatternRepository, snapshotRepository,
                meterRegistry
        );
    }

    @Nested
    @DisplayName("System Overview")
    class SystemOverviewTests {

        @Test
        @DisplayName("computes correct counts and fraud rate from live repos when snapshot missing")
        void computesOverview() {
            when(snapshotRepository.findLatest()).thenReturn(java.util.Optional.empty());
            when(transactionRepository.count()).thenReturn(1000L);
            when(transactionRepository.countByIsFraudTrue()).thenReturn(50L);
            when(alertRepository.count()).thenReturn(75L);
            when(explanationRepository.count()).thenReturn(30L);
            when(fraudPatternRepository.count()).thenReturn(10L);
            when(experimentResultRepository.count()).thenReturn(5L);

            SystemOverview overview = dashboardService.getSystemOverview();

            assertThat(overview.totalTransactions()).isEqualTo(1000);
            assertThat(overview.fraudTransactions()).isEqualTo(50);
            assertThat(overview.fraudRatePercent()).isEqualTo(5.0);
            assertThat(overview.totalAlerts()).isEqualTo(75);
            assertThat(overview.totalExplanations()).isEqualTo(30);
            assertThat(overview.totalFraudPatterns()).isEqualTo(10);
            assertThat(overview.totalExperiments()).isEqualTo(5);
        }

        @Test
        @DisplayName("D-3: reads fresh snapshot instead of live repos")
        void readsFreshSnapshot() {
            dev.finguard.domain.model.MetricSnapshot snap = new dev.finguard.domain.model.MetricSnapshot();
            snap.setSnapshotAt(java.time.LocalDateTime.now().minusSeconds(30)); // fresh
            snap.setTotalTransactions(2000);
            snap.setTotalFraudTx(100);
            snap.setTotalAlerts(150);
            snap.setTotalExplanations(60);
            snap.setTotalFraudPatterns(20);
            snap.setTotalExperiments(8);
            when(snapshotRepository.findLatest()).thenReturn(java.util.Optional.of(snap));

            SystemOverview overview = dashboardService.getSystemOverview();

            assertThat(overview.totalTransactions()).isEqualTo(2000);
            assertThat(overview.fraudRatePercent()).isEqualTo(5.0);
            assertThat(overview.totalAlerts()).isEqualTo(150);
            // Live repos must NOT have been touched — snapshot was fresh.
            org.mockito.Mockito.verifyNoInteractions(transactionRepository, alertRepository,
                    explanationRepository, fraudPatternRepository, experimentResultRepository);
        }

        @Test
        @DisplayName("D-3: stale snapshot is ignored; falls back to live repos")
        void staleSnapshotFallsBack() {
            dev.finguard.domain.model.MetricSnapshot stale = new dev.finguard.domain.model.MetricSnapshot();
            stale.setSnapshotAt(java.time.LocalDateTime.now().minusMinutes(10)); // WAY over 180s
            stale.setTotalTransactions(999_999_999L); // poisoned value — must be ignored
            when(snapshotRepository.findLatest()).thenReturn(java.util.Optional.of(stale));

            when(transactionRepository.count()).thenReturn(500L);
            when(transactionRepository.countByIsFraudTrue()).thenReturn(25L);
            when(alertRepository.count()).thenReturn(40L);
            when(explanationRepository.count()).thenReturn(10L);
            when(fraudPatternRepository.count()).thenReturn(5L);
            when(experimentResultRepository.count()).thenReturn(3L);

            SystemOverview overview = dashboardService.getSystemOverview();

            assertThat(overview.totalTransactions()).isEqualTo(500);   // live, not stale
            assertThat(overview.fraudTransactions()).isEqualTo(25);
        }

        @Test
        @DisplayName("handles zero transactions gracefully")
        void handlesZeroTransactions() {
            when(transactionRepository.count()).thenReturn(0L);
            when(transactionRepository.countByIsFraudTrue()).thenReturn(0L);
            when(alertRepository.count()).thenReturn(0L);
            when(explanationRepository.count()).thenReturn(0L);
            when(fraudPatternRepository.count()).thenReturn(0L);
            when(experimentResultRepository.count()).thenReturn(0L);

            SystemOverview overview = dashboardService.getSystemOverview();

            assertThat(overview.fraudRatePercent()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("Detection Analytics")
    class DetectionAnalyticsTests {

        @Test
        @DisplayName("aggregates alert distributions and risk score")
        void aggregatesDetectionStats() {
            when(alertRepository.countByStatusGrouped()).thenReturn(List.of(
                    new Object[]{AlertStatus.NEW, 10L},
                    new Object[]{AlertStatus.CONFIRMED_FRAUD, 5L}
            ));
            when(alertRepository.countByDetectionConfigGrouped()).thenReturn(List.of(
                    new Object[]{DetectionConfig.RULES_ONLY, 8L},
                    new Object[]{DetectionConfig.FULL_SYSTEM, 7L}
            ));
            // B-5: DashboardService now uses a single GROUP BY query instead of
            // one COUNT per config. Stub the grouped query with per-config rows.
            when(alertRepository.countAnomaliesByDetectionConfigGrouped()).thenReturn(List.of(
                    new Object[]{DetectionConfig.RULES_ONLY, 3L},
                    new Object[]{DetectionConfig.ML_ONLY, 3L},
                    new Object[]{DetectionConfig.ML_LLM_DIRECT, 3L},
                    new Object[]{DetectionConfig.ML_LLM_RAG, 3L},
                    new Object[]{DetectionConfig.FULL_SYSTEM, 3L}
            ));
            when(alertRepository.avgMlRiskScore()).thenReturn(0.7523);

            DetectionAnalytics analytics = dashboardService.getDetectionAnalytics();

            assertThat(analytics.alertsByStatus()).containsEntry("NEW", 10L);
            assertThat(analytics.alertsByStatus()).containsEntry("CONFIRMED_FRAUD", 5L);
            assertThat(analytics.alertsByConfig()).containsEntry("RULES_ONLY", 8L);
            assertThat(analytics.totalAnomalies()).isEqualTo(15L); // 3 × 5 configs
            assertThat(analytics.avgMlRiskScore()).isEqualTo(0.7523);
        }

        @Test
        @DisplayName("handles null average risk score")
        void handlesNullAvgRiskScore() {
            when(alertRepository.countByStatusGrouped()).thenReturn(List.of());
            when(alertRepository.countByDetectionConfigGrouped()).thenReturn(List.of());
            when(alertRepository.countAnomaliesByDetectionConfigGrouped()).thenReturn(List.of());
            when(alertRepository.avgMlRiskScore()).thenReturn(null);

            DetectionAnalytics analytics = dashboardService.getDetectionAnalytics();

            assertThat(analytics.avgMlRiskScore()).isNull();
        }
    }

    @Nested
    @DisplayName("Explanation Quality")
    class ExplanationQualityTests {

        @Test
        @DisplayName("computes CAKR averages and hallucination rate")
        void computesQualityMetrics() {
            when(explanationRepository.avgCakrCompleteness()).thenReturn(4.2);
            when(explanationRepository.avgCakrCorrectness()).thenReturn(3.8);
            when(explanationRepository.avgCakrActionability()).thenReturn(4.0);
            when(explanationRepository.avgCakrRegulatory()).thenReturn(3.5);
            when(explanationRepository.countByHallucinationFreeTrue()).thenReturn(18L);
            when(explanationRepository.countByHallucinationFreeFalse()).thenReturn(2L);
            when(explanationRepository.count()).thenReturn(20L);
            when(explanationRepository.avgLatency()).thenReturn(1250.0);
            when(explanationRepository.avgConfidenceScore()).thenReturn(0.85);
            when(explanationRepository.countByExplanationTypeGrouped()).thenReturn(List.of(
                    new Object[]{ExplanationType.LLM_DIRECT, 10L},
                    new Object[]{ExplanationType.LLM_RAG, 10L}
            ));

            ExplanationQuality quality = dashboardService.getExplanationQuality();

            assertThat(quality.cakrAverages().completeness()).isEqualTo(4.2);
            assertThat(quality.cakrAverages().correctness()).isEqualTo(3.8);
            assertThat(quality.cakrAverages().actionability()).isEqualTo(4.0);
            assertThat(quality.cakrAverages().regulatory()).isEqualTo(3.5);
            assertThat(quality.hallucinationFree()).isEqualTo(18);
            assertThat(quality.hallucinationFlagged()).isEqualTo(2);
            assertThat(quality.hallucinationRatePercent()).isEqualTo(10.0);
            assertThat(quality.avgLatencyMs()).isEqualTo(1250.0);
            assertThat(quality.avgConfidenceScore()).isEqualTo(0.85);
            assertThat(quality.explanationsByType()).containsEntry("LLM_DIRECT", 10L);
        }

        @Test
        @DisplayName("handles all-null CAKR scores")
        void handlesNullCakr() {
            when(explanationRepository.avgCakrCompleteness()).thenReturn(null);
            when(explanationRepository.avgCakrCorrectness()).thenReturn(null);
            when(explanationRepository.avgCakrActionability()).thenReturn(null);
            when(explanationRepository.avgCakrRegulatory()).thenReturn(null);
            when(explanationRepository.countByHallucinationFreeTrue()).thenReturn(0L);
            when(explanationRepository.countByHallucinationFreeFalse()).thenReturn(0L);
            when(explanationRepository.count()).thenReturn(0L);
            when(explanationRepository.avgLatency()).thenReturn(null);
            when(explanationRepository.avgConfidenceScore()).thenReturn(null);
            when(explanationRepository.countByExplanationTypeGrouped()).thenReturn(List.of());

            ExplanationQuality quality = dashboardService.getExplanationQuality();

            assertThat(quality.cakrAverages().completeness()).isNull();
            assertThat(quality.hallucinationRatePercent()).isEqualTo(0.0);
            assertThat(quality.avgLatencyMs()).isNull();
        }
    }

    @Nested
    @DisplayName("Dataset Breakdown")
    class DatasetBreakdownTests {

        @Test
        @DisplayName("returns only datasets with transactions")
        void filtersEmptyDatasets() {
            when(transactionRepository.countByDatasetSource(DatasetSource.PAYSIM)).thenReturn(500L);
            when(transactionRepository.countFraudByDatasetSource(DatasetSource.PAYSIM)).thenReturn(25L);
            when(transactionRepository.countByDatasetSource(DatasetSource.IBM_AML)).thenReturn(0L);
            when(transactionRepository.countFraudByDatasetSource(DatasetSource.IBM_AML)).thenReturn(0L);
            when(transactionRepository.countByDatasetSource(DatasetSource.CUSTOM)).thenReturn(0L);
            when(transactionRepository.countFraudByDatasetSource(DatasetSource.CUSTOM)).thenReturn(0L);

            List<DatasetBreakdown> breakdowns = dashboardService.getDatasetBreakdown();

            assertThat(breakdowns).hasSize(1);
            assertThat(breakdowns.get(0).datasetSource()).isEqualTo("PaySim");
            assertThat(breakdowns.get(0).totalTransactions()).isEqualTo(500);
            assertThat(breakdowns.get(0).fraudTransactions()).isEqualTo(25);
            assertThat(breakdowns.get(0).fraudRatePercent()).isEqualTo(5.0);
        }
    }

    @Nested
    @DisplayName("Experiment Summaries")
    class ExperimentSummaryTests {

        @Test
        @DisplayName("maps experiment results to summaries")
        void mapsExperimentResults() {
            ExperimentResult result = new ExperimentResult();
            result.setExperimentName("baseline-v1");
            result.setConfig(DetectionConfig.FULL_SYSTEM);
            result.setDataset(DatasetSource.PAYSIM);
            result.setPrecisionScore(0.92);
            result.setRecallScore(0.88);
            result.setF1Score(0.90);
            result.setFalsePositiveRate(0.05);
            result.setAvgCakrScore(4.1);
            result.setHallucinationRate(8.5);
            result.setAvgLatencyMs(1100.0);
            result.setTotalTransactions(1000);
            result.setTotalAlerts(50);

            when(experimentResultRepository.findByExperimentNameOrdered("baseline-v1"))
                    .thenReturn(List.of(result));

            List<ExperimentSummary> summaries = dashboardService.getExperimentSummaries("baseline-v1");

            assertThat(summaries).hasSize(1);
            ExperimentSummary s = summaries.get(0);
            assertThat(s.experimentName()).isEqualTo("baseline-v1");
            assertThat(s.config()).isEqualTo("FULL_SYSTEM");
            assertThat(s.precision()).isEqualTo(0.92);
            assertThat(s.recall()).isEqualTo(0.88);
            assertThat(s.f1Score()).isEqualTo(0.9);
        }

        @Test
        @DisplayName("returns all experiments when name is null")
        void returnsAllWhenNameNull() {
            when(experimentResultRepository.findAll()).thenReturn(List.of());

            List<ExperimentSummary> summaries = dashboardService.getExperimentSummaries(null);

            assertThat(summaries).isEmpty();
        }
    }

    @Nested
    @DisplayName("Pipeline Stage Timings")
    class TimingsTests {

        @Test
        @DisplayName("returns zero-valued rows for every stage when no timer fired yet")
        void returnsZerosWhenEmpty() {
            TimingsSummary timings = dashboardService.getTimings();

            // Ingestion: 3 stages always present (csv_import, feature_compute, train_test_split)
            assertThat(timings.ingestion()).hasSize(3);
            assertThat(timings.ingestion()).allSatisfy(s -> {
                assertThat(s.count()).isZero();
                assertThat(s.totalMs()).isZero();
                assertThat(s.avgMs()).isZero();
            });

            // Training: rf + xgb
            assertThat(timings.training()).hasSize(2);
            assertThat(timings.training()).allSatisfy(s -> assertThat(s.count()).isZero());

            // Evaluation: 5 configs always present, each with all-null fields when empty
            assertThat(timings.evaluation()).hasSize(DetectionConfig.values().length);
            assertThat(timings.evaluation()).allSatisfy(c -> {
                assertThat(c.detectionMs()).isNull();
                assertThat(c.explanationMs()).isNull();
                assertThat(c.cakrMs()).isNull();
                assertThat(c.totalMs()).isNull();
            });

            assertThat(timings.evaluationTotalMs()).isZero();
        }

        @Test
        @DisplayName("aggregates ingestion timer values into the matching stage row")
        void aggregatesIngestionTimers() {
            // Two import runs: 1500 ms + 2500 ms = 4000 total, 2000 avg
            meterRegistry.timer("finguard.stage.duration", "stage", "csv_import")
                    .record(1500, TimeUnit.MILLISECONDS);
            meterRegistry.timer("finguard.stage.duration", "stage", "csv_import")
                    .record(2500, TimeUnit.MILLISECONDS);

            // One feature compute: 60 000 ms
            meterRegistry.timer("finguard.stage.duration", "stage", "feature_compute")
                    .record(60_000, TimeUnit.MILLISECONDS);

            TimingsSummary timings = dashboardService.getTimings();

            StageTiming importing = timings.ingestion().get(0);
            assertThat(importing.stage()).isEqualTo("Importing");
            assertThat(importing.count()).isEqualTo(2);
            assertThat(importing.totalMs()).isEqualTo(4000);
            assertThat(importing.avgMs()).isEqualTo(2000);

            StageTiming features = timings.ingestion().get(1);
            assertThat(features.stage()).isEqualTo("Compute Features");
            assertThat(features.count()).isEqualTo(1);
            assertThat(features.totalMs()).isEqualTo(60_000);

            // train_test_split untouched → still zero
            assertThat(timings.ingestion().get(2).count()).isZero();
        }

        @Test
        @DisplayName("aggregates per-config evaluation timers and computes totals correctly")
        void aggregatesEvaluationTimers() {
            // FULL_SYSTEM: detection 30 s + explanation 600 s + cakr 90 s = 720 000 ms total
            meterRegistry.timer("finguard.detection.config.duration", "config", "FULL_SYSTEM")
                    .record(30_000, TimeUnit.MILLISECONDS);
            meterRegistry.timer("finguard.explanation.config.duration", "config", "FULL_SYSTEM")
                    .record(600_000, TimeUnit.MILLISECONDS);
            meterRegistry.timer("finguard.cakr.config.duration", "config", "FULL_SYSTEM")
                    .record(90_000, TimeUnit.MILLISECONDS);

            // RULES_ONLY: only detection (no LLM phase) — assert null fields stay null
            meterRegistry.timer("finguard.detection.config.duration", "config", "RULES_ONLY")
                    .record(15_000, TimeUnit.MILLISECONDS);

            TimingsSummary timings = dashboardService.getTimings();

            ConfigTiming fullSystem = timings.evaluation().stream()
                    .filter(c -> "FULL_SYSTEM".equals(c.config()))
                    .findFirst().orElseThrow();
            assertThat(fullSystem.detectionMs()).isEqualTo(30_000);
            assertThat(fullSystem.explanationMs()).isEqualTo(600_000);
            assertThat(fullSystem.cakrMs()).isEqualTo(90_000);
            assertThat(fullSystem.totalMs()).isEqualTo(720_000);

            ConfigTiming rulesOnly = timings.evaluation().stream()
                    .filter(c -> "RULES_ONLY".equals(c.config()))
                    .findFirst().orElseThrow();
            assertThat(rulesOnly.detectionMs()).isEqualTo(15_000);
            assertThat(rulesOnly.explanationMs()).isNull();
            assertThat(rulesOnly.cakrMs()).isNull();
            assertThat(rulesOnly.totalMs()).isEqualTo(15_000);

            // evaluationTotalMs should sum across all configs
            assertThat(timings.evaluationTotalMs()).isEqualTo(720_000 + 15_000);
        }

        @Test
        @DisplayName("aggregates RF and XGBoost training timers separately")
        void aggregatesTrainingTimers() {
            meterRegistry.timer("finguard.training.duration", "model", "rf")
                    .record(180_000, TimeUnit.MILLISECONDS);
            meterRegistry.timer("finguard.training.duration", "model", "xgb")
                    .record(45_000, TimeUnit.MILLISECONDS);

            TimingsSummary timings = dashboardService.getTimings();

            assertThat(timings.training()).hasSize(2);
            assertThat(timings.training().get(0).stage()).isEqualTo("Random Forest");
            assertThat(timings.training().get(0).totalMs()).isEqualTo(180_000);
            assertThat(timings.training().get(1).stage()).isEqualTo("XGBoost");
            assertThat(timings.training().get(1).totalMs()).isEqualTo(45_000);
        }
    }
}
