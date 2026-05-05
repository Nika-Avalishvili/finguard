package dev.finguard.dashboard.controller;

import dev.finguard.config.MockAiConfig;
import dev.finguard.config.TestContainersConfig;
import dev.finguard.domain.enums.*;
import dev.finguard.domain.model.*;
import dev.finguard.domain.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the DashboardController REST API.
 *
 * <p>Tests the full HTTP layer against a real PostgreSQL database.
 * Verifies that aggregation queries, JSON serialization, and endpoint
 * routing all work correctly end-to-end.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, MockAiConfig.class})
@DisplayName("DashboardController (REST API integration)")
class DashboardControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private TransactionFeaturesRepository featuresRepository;
    @Autowired private AlertRepository alertRepository;
    @Autowired private ExplanationRepository explanationRepository;
    @Autowired private ExperimentResultRepository experimentResultRepository;
    @Autowired private FraudPatternRepository fraudPatternRepository;
    @Autowired private MetricSnapshotRepository metricSnapshotRepository;
    @Autowired private CacheManager cacheManager;

    @BeforeEach
    void cleanUp() {
        experimentResultRepository.deleteAll();
        explanationRepository.deleteAll();
        alertRepository.deleteAll();
        featuresRepository.deleteAll();
        transactionRepository.deleteAll();
        fraudPatternRepository.deleteAll();
        // Must also drop any snapshot rows left behind by the scheduled
        // MetricSnapshotRefresher (fires 30s after context startup). The dashboard
        // service reads the latest snapshot when fresh, so a stale row from a prior
        // long-running test (e.g. DetectionPipelinePerformanceIT seeds 500 tx) would
        // leak through and return stale counts here.
        metricSnapshotRepository.deleteAll();
        var cache = cacheManager.getCache("dashboard-stats");
        if (cache != null) {
            cache.clear();
        }
    }

    // ==============================================================
    // System Overview
    // ==============================================================

    @Nested
    @DisplayName("GET /api/v1/dashboard/overview")
    class OverviewEndpoint {

        @Test
        @DisplayName("returns zeros when database is empty")
        void emptyDatabase() throws Exception {
            mockMvc.perform(get("/api/v1/dashboard/overview"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalTransactions").value(0))
                    .andExpect(jsonPath("$.fraudTransactions").value(0))
                    .andExpect(jsonPath("$.fraudRatePercent").value(0.0))
                    .andExpect(jsonPath("$.totalAlerts").value(0))
                    .andExpect(jsonPath("$.totalExplanations").value(0));
        }

        @Test
        @DisplayName("computes correct counts and fraud rate with data")
        void withData() throws Exception {
            // 3 transactions, 1 fraud
            persistTransaction(false);
            persistTransaction(false);
            Transaction fraudTx = persistTransaction(true);

            // 1 alert on the fraud transaction
            Alert alert = persistAlert(fraudTx, DetectionConfig.RULES_ONLY, 0.9);

            // 1 explanation
            persistExplanation(alert, ExplanationType.LLM_DIRECT);

            // 1 fraud pattern
            persistFraudPattern("STRUCTURING", "Test Pattern");

            mockMvc.perform(get("/api/v1/dashboard/overview"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalTransactions").value(3))
                    .andExpect(jsonPath("$.fraudTransactions").value(1))
                    .andExpect(jsonPath("$.fraudRatePercent").value(closeTo(33.33, 0.01)))
                    .andExpect(jsonPath("$.totalAlerts").value(1))
                    .andExpect(jsonPath("$.totalExplanations").value(1))
                    .andExpect(jsonPath("$.totalFraudPatterns").value(1));
        }
    }

    // ==============================================================
    // Detection Analytics
    // ==============================================================

    @Nested
    @DisplayName("GET /api/v1/dashboard/detection")
    class DetectionEndpoint {

        @Test
        @DisplayName("returns alert distributions by status and config")
        void alertDistributions() throws Exception {
            Transaction tx1 = persistTransaction(true);
            Transaction tx2 = persistTransaction(true);
            Transaction tx3 = persistTransaction(false);

            Alert a1 = persistAlert(tx1, DetectionConfig.RULES_ONLY, null);
            Alert a2 = persistAlert(tx2, DetectionConfig.FULL_SYSTEM, 0.92);
            Alert a3 = persistAlert(tx3, DetectionConfig.FULL_SYSTEM, 0.75);

            // Update one alert status
            a1.setStatus(AlertStatus.CONFIRMED_FRAUD);
            alertRepository.save(a1);

            mockMvc.perform(get("/api/v1/dashboard/detection"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.alertsByStatus.NEW").value(2))
                    .andExpect(jsonPath("$.alertsByStatus.CONFIRMED_FRAUD").value(1))
                    .andExpect(jsonPath("$.alertsByConfig.RULES_ONLY").value(1))
                    .andExpect(jsonPath("$.alertsByConfig.FULL_SYSTEM").value(2))
                    .andExpect(jsonPath("$.avgMlRiskScore").isNumber());
        }

        @Test
        @DisplayName("counts total anomalies across configs")
        void anomalyCount() throws Exception {
            Transaction tx = persistTransaction(true);
            Alert alert = persistAlert(tx, DetectionConfig.ML_ONLY, 0.95);
            alert.setIsAnomaly(true);
            alertRepository.save(alert);

            mockMvc.perform(get("/api/v1/dashboard/detection"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalAnomalies").value(greaterThanOrEqualTo(1)));
        }
    }

    // ==============================================================
    // Explanation Quality
    // ==============================================================

    @Nested
    @DisplayName("GET /api/v1/dashboard/explanation-quality")
    class ExplanationQualityEndpoint {

        @Test
        @DisplayName("returns CAKR averages, hallucination stats, and type breakdown")
        void explanationQualityMetrics() throws Exception {
            Transaction tx = persistTransaction(true);
            Alert alert = persistAlert(tx, DetectionConfig.ML_LLM_RAG, 0.88);

            // Two explanations: one hallucination-free, one flagged
            Explanation e1 = persistExplanation(alert, ExplanationType.LLM_RAG);
            e1.setHallucinationFree(true);
            e1.setCakrCompleteness(4.5);
            e1.setCakrCorrectness(4.0);
            e1.setCakrActionability(3.5);
            e1.setCakrRegulatory(4.0);
            e1.setLatencyMs(1200);
            e1.setConfidenceScore(0.9);
            explanationRepository.save(e1);

            Explanation e2 = persistExplanation(alert, ExplanationType.LLM_DIRECT);
            e2.setHallucinationFree(false);
            e2.setCakrCompleteness(3.0);
            e2.setCakrCorrectness(3.0);
            e2.setCakrActionability(2.5);
            e2.setCakrRegulatory(3.0);
            e2.setLatencyMs(800);
            e2.setConfidenceScore(0.7);
            explanationRepository.save(e2);

            mockMvc.perform(get("/api/v1/dashboard/explanation-quality"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cakrAverages.completeness").isNumber())
                    .andExpect(jsonPath("$.cakrAverages.correctness").isNumber())
                    .andExpect(jsonPath("$.cakrAverages.actionability").isNumber())
                    .andExpect(jsonPath("$.cakrAverages.regulatory").isNumber())
                    .andExpect(jsonPath("$.hallucinationFree").value(1))
                    .andExpect(jsonPath("$.hallucinationFlagged").value(1))
                    .andExpect(jsonPath("$.hallucinationRatePercent").value(50.0))
                    .andExpect(jsonPath("$.avgLatencyMs").isNumber())
                    .andExpect(jsonPath("$.avgConfidenceScore").isNumber())
                    .andExpect(jsonPath("$.explanationsByType").isMap());
        }

        @Test
        @DisplayName("handles empty state with null CAKR scores")
        void emptyExplanations() throws Exception {
            mockMvc.perform(get("/api/v1/dashboard/explanation-quality"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.cakrAverages.completeness").isEmpty())
                    .andExpect(jsonPath("$.hallucinationRatePercent").value(0.0))
                    .andExpect(jsonPath("$.avgLatencyMs").isEmpty());
        }
    }

    // ==============================================================
    // Dataset Breakdown
    // ==============================================================

    @Nested
    @DisplayName("GET /api/v1/dashboard/datasets")
    class DatasetsEndpoint {

        @Test
        @DisplayName("returns breakdown per dataset source with fraud rates")
        void datasetBreakdown() throws Exception {
            // 2 PAYSIM transactions (1 fraud), 1 IBM_AML (0 fraud)
            persistTransaction(DatasetSource.PAYSIM, true);
            persistTransaction(DatasetSource.PAYSIM, false);
            persistTransaction(DatasetSource.IBM_AML, false);

            mockMvc.perform(get("/api/v1/dashboard/datasets"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[?(@.datasetSource=='PaySim')].totalTransactions").value(hasItem(2)))
                    .andExpect(jsonPath("$[?(@.datasetSource=='PaySim')].fraudTransactions").value(hasItem(1)))
                    // Post-audit: DatasetSource.IBM_AML displayName was updated to flag it
                    // as future-work (no loader exists yet). See DatasetSource.java.
                    .andExpect(jsonPath("$[?(@.datasetSource=='IBM AML (future work)')].totalTransactions").value(hasItem(1)));
        }

        @Test
        @DisplayName("filters out datasets with zero transactions")
        void filtersEmptyDatasets() throws Exception {
            persistTransaction(DatasetSource.PAYSIM, false);

            mockMvc.perform(get("/api/v1/dashboard/datasets"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].datasetSource").value("PaySim"));
        }
    }

    // ==============================================================
    // Transaction Types
    // ==============================================================

    @Nested
    @DisplayName("GET /api/v1/dashboard/transaction-types")
    class TransactionTypesEndpoint {

        @Test
        @DisplayName("returns counts grouped by transaction type")
        void transactionTypeDistribution() throws Exception {
            persistTransactionOfType(TransactionType.TRANSFER);
            persistTransactionOfType(TransactionType.TRANSFER);
            persistTransactionOfType(TransactionType.CASH_OUT);

            mockMvc.perform(get("/api/v1/dashboard/transaction-types"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.TRANSFER").value(2))
                    .andExpect(jsonPath("$.CASH_OUT").value(1));
        }
    }

    // ==============================================================
    // Experiment Summaries
    // ==============================================================

    @Nested
    @DisplayName("GET /api/v1/dashboard/experiments")
    class ExperimentsEndpoint {

        @Test
        @DisplayName("returns all experiment summaries")
        void allExperiments() throws Exception {
            persistExperimentResult("exp-v1", DetectionConfig.RULES_ONLY, 0.85, 0.80, 0.82);
            persistExperimentResult("exp-v1", DetectionConfig.FULL_SYSTEM, 0.92, 0.88, 0.90);

            mockMvc.perform(get("/api/v1/dashboard/experiments"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].experimentName").value("exp-v1"))
                    .andExpect(jsonPath("$[0].precision").isNumber())
                    .andExpect(jsonPath("$[0].recall").isNumber())
                    .andExpect(jsonPath("$[0].f1Score").isNumber());
        }

        @Test
        @DisplayName("filters experiments by name")
        void filterByName() throws Exception {
            persistExperimentResult("baseline", DetectionConfig.RULES_ONLY, 0.80, 0.75, 0.77);
            persistExperimentResult("improved", DetectionConfig.FULL_SYSTEM, 0.92, 0.90, 0.91);

            mockMvc.perform(get("/api/v1/dashboard/experiments")
                            .param("experimentName", "baseline"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].experimentName").value("baseline"));
        }
    }

    // ==============================================================
    // Full Dashboard
    // ==============================================================

    @Nested
    @DisplayName("GET /api/v1/dashboard/full")
    class FullDashboardEndpoint {

        @Test
        @DisplayName("returns all dashboard sections in a single response")
        void fullDashboard() throws Exception {
            Transaction tx = persistTransaction(true);
            persistAlert(tx, DetectionConfig.RULES_ONLY, null);

            mockMvc.perform(get("/api/v1/dashboard/full"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.overview").exists())
                    .andExpect(jsonPath("$.overview.totalTransactions").value(1))
                    .andExpect(jsonPath("$.detection").exists())
                    .andExpect(jsonPath("$.detection.alertsByStatus").isMap())
                    .andExpect(jsonPath("$.explanationQuality").exists())
                    .andExpect(jsonPath("$.datasets").isArray())
                    .andExpect(jsonPath("$.transactionTypes").isMap())
                    .andExpect(jsonPath("$.experiments").isArray());
        }

        @Test
        @DisplayName("works with completely empty database")
        void emptyDatabase() throws Exception {
            mockMvc.perform(get("/api/v1/dashboard/full"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.overview.totalTransactions").value(0))
                    .andExpect(jsonPath("$.overview.fraudRatePercent").value(0.0))
                    .andExpect(jsonPath("$.detection.totalAnomalies").value(0))
                    .andExpect(jsonPath("$.explanationQuality.hallucinationRatePercent").value(0.0))
                    .andExpect(jsonPath("$.datasets", hasSize(0)))
                    .andExpect(jsonPath("$.experiments", hasSize(0)));
        }
    }

    // ==============================================================
    // Helpers
    // ==============================================================

    private Transaction persistTransaction(boolean isFraud) {
        return persistTransaction(DatasetSource.PAYSIM, isFraud);
    }

    private Transaction persistTransaction(DatasetSource source, boolean isFraud) {
        Transaction tx = new Transaction();
        tx.setAmount(new BigDecimal("50000.00"));
        tx.setTransactionType(TransactionType.TRANSFER);
        tx.setSenderAccount("SENDER-DASH-" + System.nanoTime());
        tx.setReceiverAccount("RECEIVER-DASH-" + System.nanoTime());
        tx.setTimestamp(LocalDateTime.now());
        tx.setDatasetSource(source);
        tx.setExternalId("DASH-" + System.nanoTime());
        tx.setIsFraud(isFraud);
        return transactionRepository.save(tx);
    }

    private Transaction persistTransactionOfType(TransactionType type) {
        Transaction tx = new Transaction();
        tx.setAmount(new BigDecimal("10000.00"));
        tx.setTransactionType(type);
        tx.setSenderAccount("SENDER-TYPE-" + System.nanoTime());
        tx.setReceiverAccount("RECEIVER-TYPE-" + System.nanoTime());
        tx.setTimestamp(LocalDateTime.now());
        tx.setDatasetSource(DatasetSource.PAYSIM);
        tx.setExternalId("TYPE-" + System.nanoTime());
        return transactionRepository.save(tx);
    }

    private Alert persistAlert(Transaction tx, DetectionConfig config, Double mlRiskScore) {
        Alert alert = new Alert();
        alert.setTransaction(tx);
        alert.setDetectionConfig(config);
        alert.setMlRiskScore(mlRiskScore);
        alert.setIsAnomaly(true);
        alert.setStatus(AlertStatus.NEW);
        return alertRepository.save(alert);
    }

    private Explanation persistExplanation(Alert alert, ExplanationType type) {
        Explanation explanation = new Explanation();
        explanation.setAlert(alert);
        explanation.setExplanationType(type);
        explanation.setRiskSummary("Test risk summary");
        explanation.setExplanationText("Test explanation text");
        explanation.setConfidenceScore(0.85);
        explanation.setLatencyMs(1000);
        explanation.setSuspiciousPatterns("[\"Large amount\"]");
        explanation.setRecommendedActions("[\"Review account\"]");
        return explanationRepository.save(explanation);
    }

    private void persistFraudPattern(String patternType, String title) {
        FraudPattern fp = new FraudPattern();
        fp.setPatternType(patternType);
        fp.setTitle(title);
        fp.setDescription("Test description for " + title);
        fp.setSource("Integration Test");
        fraudPatternRepository.save(fp);
    }

    private void persistExperimentResult(String name, DetectionConfig config,
                                          double precision, double recall, double f1) {
        ExperimentResult result = new ExperimentResult();
        result.setExperimentName(name);
        result.setConfig(config);
        result.setDataset(DatasetSource.PAYSIM);
        result.setPrecisionScore(precision);
        result.setRecallScore(recall);
        result.setF1Score(f1);
        result.setFalsePositiveRate(0.05);
        result.setTotalTransactions(500);
        result.setTotalAlerts(25);
        experimentResultRepository.save(result);
    }
}
