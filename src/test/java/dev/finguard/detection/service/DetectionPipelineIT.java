package dev.finguard.detection.service;

import dev.finguard.config.MockAiConfig;
import dev.finguard.config.TestContainersConfig;
import dev.finguard.detection.ml.MLPredictionResult;
import dev.finguard.detection.ml.TribuoModelService;
import dev.finguard.detection.rule.RuleMetadata;
import dev.finguard.detection.rule.RuleThresholdService;
import dev.finguard.domain.enums.AlertStatus;
import dev.finguard.domain.enums.DatasetSource;
import dev.finguard.domain.enums.DetectionConfig;
import dev.finguard.domain.enums.ExplanationType;
import dev.finguard.domain.enums.TransactionType;
import dev.finguard.domain.model.Alert;
import dev.finguard.domain.model.Explanation;
import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.model.TransactionFeatures;
import dev.finguard.domain.repository.AlertRepository;
import dev.finguard.domain.repository.ExplanationRepository;
import dev.finguard.domain.repository.TransactionFeaturesRepository;
import dev.finguard.domain.repository.TransactionRepository;
import dev.finguard.explanation.llm.ExplanationBatchAsyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * End-to-end integration test for the detection pipeline.
 *
 * <p>Tests the real flow: Transaction (with features) → DetectionPipelineService →
 * Rule evaluation → Alert creation in DB.
 * Runs against a real PostgreSQL (pgvector) instance via Docker Compose.
 * Spring AI beans are mocked since they are not needed for rules-only detection.</p>
 */
@SpringBootTest
@Import({TestContainersConfig.class, MockAiConfig.class})
@DisplayName("Detection Pipeline (end-to-end integration)")
class DetectionPipelineIT {

    @Autowired
    private DetectionPipelineService detectionPipelineService;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private TransactionFeaturesRepository featuresRepository;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private ExplanationRepository explanationRepository;

    @Autowired
    private RuleThresholdService ruleThresholdService;

    /**
     * Mock the ML service so we can control prediction outcomes without
     * a trained model. Rules-only tests are unaffected since they don't call ML.
     */
    @MockitoBean
    private TribuoModelService mlService;

    /**
     * Mock the batch explanation service. In the decoupled pipeline, LLM explanation
     * is dispatched here after detection completes — not called inline per transaction.
     */
    @MockitoBean
    private ExplanationBatchAsyncService explanationBatchService;

    @BeforeEach
    void cleanUp() {
        explanationRepository.deleteAll();
        alertRepository.deleteAll();
        featuresRepository.deleteAll();
        transactionRepository.deleteAll();
        reset(mlService, explanationBatchService);
        // Pin thresholds to the paper defaults ($200 k / $50 k / $10 k / 3) for
        // the duration of each test — the thesis-calibrated defaults in
        // Liquibase 020 ($1 M / $500 k / $10 k / 3) would make fixture amounts
        // like "$300 000 triggers LARGE_TRANSACTION" no longer fire. These
        // ITs test detection logic, not threshold calibration.
        ruleThresholdService.updateThreshold(RuleMetadata.LARGE_TRANSACTION.name(),
                RuleMetadata.LARGE_TRANSACTION.getPaperDefault());
        ruleThresholdService.updateThreshold(RuleMetadata.NEW_RECEIVER_HIGH_VALUE.name(),
                RuleMetadata.NEW_RECEIVER_HIGH_VALUE.getPaperDefault());
        ruleThresholdService.updateThreshold(RuleMetadata.STRUCTURING.name(),
                RuleMetadata.STRUCTURING.getPaperDefault());
        ruleThresholdService.updateThreshold(RuleMetadata.RAPID_VELOCITY.name(),
                RuleMetadata.RAPID_VELOCITY.getPaperDefault());
    }

    // ==============================================================
    // Single transaction analysis
    // ==============================================================

    @Nested
    @DisplayName("Single Transaction Analysis")
    class SingleTransactionAnalysis {

        @Test
        @DisplayName("Should create alert for large transaction")
        void shouldCreateAlert_forLargeTransaction() {
            Transaction tx = persistTransaction("500000.00", TransactionType.TRANSFER);

            Optional<Alert> alert = detectionPipelineService.analyzeTransaction(tx, DetectionConfig.RULES_ONLY);

            assertThat(alert).isPresent();
            assertThat(alert.get().getRuleTriggered()).contains("LARGE_TRANSACTION");
            assertThat(alert.get().getIsAnomaly()).isTrue();
            assertThat(alert.get().getDetectionConfig()).isEqualTo(DetectionConfig.RULES_ONLY);
        }

        @Test
        @DisplayName("Should create alert for rapid velocity")
        void shouldCreateAlert_forRapidVelocity() {
            Transaction tx = persistTransaction("5000.00", TransactionType.PAYMENT);
            persistFeatures(tx, f -> f.setTxVelocity1h(10));

            Optional<Alert> alert = detectionPipelineService.analyzeTransaction(tx, DetectionConfig.RULES_ONLY);

            assertThat(alert).isPresent();
            assertThat(alert.get().getRuleTriggered()).contains("RAPID_VELOCITY");
        }

        @Test
        @DisplayName("Should create alert for structuring pattern")
        void shouldCreateAlert_forStructuring() {
            Transaction tx = persistTransaction("9000.00", TransactionType.TRANSFER);
            persistFeatures(tx, f -> {
                f.setIsRoundAmount(true);
                f.setTxVelocity24h(5);
            });

            Optional<Alert> alert = detectionPipelineService.analyzeTransaction(tx, DetectionConfig.RULES_ONLY);

            assertThat(alert).isPresent();
            assertThat(alert.get().getRuleTriggered()).contains("STRUCTURING");
        }

        @Test
        @DisplayName("Should create alert for new receiver high value")
        void shouldCreateAlert_forNewReceiverHighValue() {
            Transaction tx = persistTransaction("75000.00", TransactionType.TRANSFER);
            persistFeatures(tx, f -> f.setIsNewReceiver(true));

            Optional<Alert> alert = detectionPipelineService.analyzeTransaction(tx, DetectionConfig.RULES_ONLY);

            assertThat(alert).isPresent();
            assertThat(alert.get().getRuleTriggered()).contains("NEW_RECEIVER_HIGH_VALUE");
        }

        @Test
        @DisplayName("Should combine multiple triggered rules in one alert")
        void shouldCombineMultipleRules() {
            Transaction tx = persistTransaction("300000.00", TransactionType.TRANSFER);
            persistFeatures(tx, f -> {
                f.setIsNewReceiver(true);
                f.setTxVelocity1h(5);
            });

            Optional<Alert> alert = detectionPipelineService.analyzeTransaction(tx, DetectionConfig.RULES_ONLY);

            assertThat(alert).isPresent();
            String ruleTriggered = alert.get().getRuleTriggered();
            assertThat(ruleTriggered).contains("LARGE_TRANSACTION");
            assertThat(ruleTriggered).contains("NEW_RECEIVER_HIGH_VALUE");
            assertThat(ruleTriggered).contains("RAPID_VELOCITY");
        }

        @Test
        @DisplayName("Should return empty when no rules trigger")
        void shouldReturnEmpty_whenNoRulesTrigger() {
            Transaction tx = persistTransaction("100.00", TransactionType.PAYMENT);

            Optional<Alert> alert = detectionPipelineService.analyzeTransaction(tx, DetectionConfig.RULES_ONLY);

            assertThat(alert).isEmpty();
        }

        @Test
        @DisplayName("Should set alert status to NEW by default")
        void shouldSetDefaultAlertStatus() {
            Transaction tx = persistTransaction("500000.00", TransactionType.TRANSFER);

            Optional<Alert> alert = detectionPipelineService.analyzeTransaction(tx, DetectionConfig.RULES_ONLY);

            assertThat(alert).isPresent();
            assertThat(alert.get().getStatus()).isEqualTo(AlertStatus.NEW);
        }
    }

    // ==============================================================
    // Batch analysis
    // ==============================================================

    @Nested
    @DisplayName("Batch Analysis (analyzeAllTransactions)")
    class BatchAnalysis {

        @Test
        @DisplayName("Should create alerts for all flagged transactions")
        void shouldCreateAlertsForAllFlagged() {
            // 2 transactions that should trigger, 1 that shouldn't
            persistTransaction("500000.00", TransactionType.TRANSFER);    // triggers LARGE_TRANSACTION
            persistTransaction("1000.00", TransactionType.PAYMENT);       // clean
            Transaction tx3 = persistTransaction("75000.00", TransactionType.TRANSFER);
            persistFeatures(tx3, f -> f.setIsNewReceiver(true));          // triggers NEW_RECEIVER_HIGH_VALUE

            long alertsCreated = detectionPipelineService.analyzeAllTransactions(DetectionConfig.RULES_ONLY);

            assertThat(alertsCreated).isEqualTo(2);
            assertThat(alertRepository.count()).isEqualTo(2);
        }

        @Test
        @DisplayName("Should skip transactions that already have alerts")
        void shouldSkipAlreadyAlertedTransactions() {
            persistTransaction("500000.00", TransactionType.TRANSFER);

            // First run
            long first = detectionPipelineService.analyzeAllTransactions(DetectionConfig.RULES_ONLY);
            assertThat(first).isEqualTo(1);

            // Second run should skip the already-alerted transaction
            long second = detectionPipelineService.analyzeAllTransactions(DetectionConfig.RULES_ONLY);
            assertThat(second).isZero();

            // Still just 1 alert total
            assertThat(alertRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should return zero when no transactions exist")
        void shouldReturnZero_whenNoTransactions() {
            long alertsCreated = detectionPipelineService.analyzeAllTransactions(DetectionConfig.RULES_ONLY);

            assertThat(alertsCreated).isZero();
            assertThat(alertRepository.count()).isZero();
        }

        @Test
        @DisplayName("Should return zero when all transactions are clean")
        void shouldReturnZero_whenAllClean() {
            persistTransaction("100.00", TransactionType.PAYMENT);
            persistTransaction("200.00", TransactionType.CASH_IN);
            persistTransaction("50.00", TransactionType.DEBIT);

            long alertsCreated = detectionPipelineService.analyzeAllTransactions(DetectionConfig.RULES_ONLY);

            assertThat(alertsCreated).isZero();
        }

        @Test
        @DisplayName("Should persist alerts with correct references")
        void shouldPersistAlertsWithCorrectReferences() {
            Transaction tx = persistTransaction("500000.00", TransactionType.TRANSFER);

            detectionPipelineService.analyzeAllTransactions(DetectionConfig.RULES_ONLY);

            List<Alert> alerts = alertRepository.findByTransactionId(tx.getId());
            assertThat(alerts).hasSize(1);

            Alert alert = alerts.get(0);
            assertThat(alert.getTransaction().getId()).isEqualTo(tx.getId());
            assertThat(alert.getDetectionConfig()).isEqualTo(DetectionConfig.RULES_ONLY);
            assertThat(alert.getIsAnomaly()).isTrue();
            assertThat(alert.getCreatedAt()).isNotNull();
        }
    }

    // ==============================================================
    // ML + LLM detection (ML_LLM_DIRECT, ML_LLM_RAG)
    // Note: LLM explanation is now decoupled — dispatched async after detection,
    // not called inline during analyzeTransaction. Tests verify alert creation only.
    // ==============================================================

    @Nested
    @DisplayName("ML + LLM Detection Configs")
    class MlLlmDetection {

        @BeforeEach
        void configureMocks() {
            when(mlService.isModelAvailable()).thenReturn(true);
            when(mlService.predict(any(), any())).thenReturn(
                    new MLPredictionResult("RandomForest", 0.87, true,
                            Map.of("amount_zscore", 0.35, "tx_velocity_1h", 0.22))
            );
        }

        @Test
        @DisplayName("ML_LLM_DIRECT should create and persist alert with ML score")
        void mlLlmDirect_createsAlert() {
            Transaction tx = persistTransaction("500000.00", TransactionType.TRANSFER);

            Optional<Alert> result = detectionPipelineService.analyzeTransaction(tx, DetectionConfig.ML_LLM_DIRECT);

            assertThat(result).isPresent();
            Alert alert = result.get();
            assertThat(alert.getDetectionConfig()).isEqualTo(DetectionConfig.ML_LLM_DIRECT);
            assertThat(alert.getMlRiskScore()).isEqualTo(0.87);
            assertThat(alert.getMlModelName()).isEqualTo("RandomForest");
            assertThat(alert.getIsAnomaly()).isTrue();
            assertThat(alert.getFeatureImportances()).isNotNull();
            assertThat(alert.getId()).isNotNull(); // persisted immediately
        }

        @Test
        @DisplayName("ML_LLM_RAG should create and persist alert with ML score")
        void mlLlmRag_createsAlert() {
            Transaction tx = persistTransaction("500000.00", TransactionType.TRANSFER);

            Optional<Alert> result = detectionPipelineService.analyzeTransaction(tx, DetectionConfig.ML_LLM_RAG);

            assertThat(result).isPresent();
            Alert alert = result.get();
            assertThat(alert.getDetectionConfig()).isEqualTo(DetectionConfig.ML_LLM_RAG);
            assertThat(alert.getMlRiskScore()).isEqualTo(0.87);
            assertThat(alert.getIsAnomaly()).isTrue();
            assertThat(alert.getId()).isNotNull(); // persisted immediately
        }

        @Test
        @DisplayName("ML_LLM_DIRECT should return empty when ML does not predict fraud")
        void mlLlmDirect_emptyWhenNotFraud() {
            when(mlService.predict(any(), any())).thenReturn(
                    new MLPredictionResult("RandomForest", 0.15, false, Map.of())
            );

            Transaction tx = persistTransaction("100.00", TransactionType.PAYMENT);

            Optional<Alert> result = detectionPipelineService.analyzeTransaction(tx, DetectionConfig.ML_LLM_DIRECT);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("ML_LLM_RAG alert is persisted in DB immediately after detection")
        void mlLlmRag_alertPersistedImmediately() {
            Transaction tx = persistTransaction("500000.00", TransactionType.TRANSFER);

            Optional<Alert> result = detectionPipelineService.analyzeTransaction(tx, DetectionConfig.ML_LLM_RAG);

            assertThat(result).isPresent();
            assertThat(result.get().getId()).isNotNull();
            assertThat(alertRepository.findById(result.get().getId())).isPresent();
        }
    }

    // ==============================================================
    // FULL_SYSTEM detection (rules + ML + LLM RAG)
    // ==============================================================

    @Nested
    @DisplayName("Full System Detection (rules + ML + LLM RAG)")
    class FullSystemDetection {

        @BeforeEach
        void configureMocks() {
            when(mlService.isModelAvailable()).thenReturn(true);
        }

        @Test
        @DisplayName("Should create alert when both rules and ML trigger")
        void fullSystem_bothRulesAndMlTrigger() {
            when(mlService.predict(any(), any())).thenReturn(
                    new MLPredictionResult("RandomForest", 0.92, true,
                            Map.of("amount_zscore", 0.45))
            );

            Transaction tx = persistTransaction("500000.00", TransactionType.TRANSFER);

            Optional<Alert> result = detectionPipelineService.analyzeTransaction(tx, DetectionConfig.FULL_SYSTEM);

            assertThat(result).isPresent();
            Alert alert = result.get();
            assertThat(alert.getDetectionConfig()).isEqualTo(DetectionConfig.FULL_SYSTEM);
            assertThat(alert.getRuleTriggered()).contains("LARGE_TRANSACTION");
            assertThat(alert.getMlRiskScore()).isEqualTo(0.92);
            assertThat(alert.getIsAnomaly()).isTrue();

        }

        @Test
        @DisplayName("Should create alert when only rules trigger (ML says no fraud)")
        void fullSystem_onlyRulesTrigger() {
            when(mlService.predict(any(), any())).thenReturn(
                    new MLPredictionResult("RandomForest", 0.2, false, Map.of())
            );

            Transaction tx = persistTransaction("500000.00", TransactionType.TRANSFER);

            Optional<Alert> result = detectionPipelineService.analyzeTransaction(tx, DetectionConfig.FULL_SYSTEM);

            assertThat(result).isPresent();
            Alert alert = result.get();
            assertThat(alert.getRuleTriggered()).contains("LARGE_TRANSACTION");
            assertThat(alert.getMlRiskScore()).isNull(); // ML didn't flag it
            assertThat(alert.getIsAnomaly()).isTrue();

            // Explanation is still generated
        }

        @Test
        @DisplayName("Should create alert when only ML triggers (no rules fire)")
        void fullSystem_onlyMlTriggers() {
            when(mlService.predict(any(), any())).thenReturn(
                    new MLPredictionResult("RandomForest", 0.85, true,
                            Map.of("tx_velocity_1h", 0.5))
            );

            // Small transaction that won't trigger rules
            Transaction tx = persistTransaction("100.00", TransactionType.PAYMENT);

            Optional<Alert> result = detectionPipelineService.analyzeTransaction(tx, DetectionConfig.FULL_SYSTEM);

            assertThat(result).isPresent();
            Alert alert = result.get();
            assertThat(alert.getRuleTriggered()).isNull();
            assertThat(alert.getMlRiskScore()).isEqualTo(0.85);
            assertThat(alert.getIsAnomaly()).isTrue();

        }

        @Test
        @DisplayName("Should return empty when neither rules nor ML trigger")
        void fullSystem_neitherTriggered() {
            when(mlService.predict(any(), any())).thenReturn(
                    new MLPredictionResult("RandomForest", 0.1, false, Map.of())
            );

            Transaction tx = persistTransaction("100.00", TransactionType.PAYMENT);

            Optional<Alert> result = detectionPipelineService.analyzeTransaction(tx, DetectionConfig.FULL_SYSTEM);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should persist alert with combined rule and ML signals")
        void fullSystem_alertHasCombinedSignals() {
            when(mlService.predict(any(), any())).thenReturn(
                    new MLPredictionResult("Ensemble", 0.95, true,
                            Map.of("amount_zscore", 0.4, "is_new_receiver", 0.3))
            );

            Transaction tx = persistTransaction("300000.00", TransactionType.TRANSFER);
            persistFeatures(tx, f -> {
                f.setIsNewReceiver(true);
                f.setTxVelocity1h(5);
            });

            Optional<Alert> result = detectionPipelineService.analyzeTransaction(tx, DetectionConfig.FULL_SYSTEM);

            assertThat(result).isPresent();
            Alert alert = result.get();

            // Rules: LARGE_TRANSACTION + NEW_RECEIVER_HIGH_VALUE + RAPID_VELOCITY
            assertThat(alert.getRuleTriggered()).contains("LARGE_TRANSACTION");
            assertThat(alert.getRuleTriggered()).contains("NEW_RECEIVER_HIGH_VALUE");
            assertThat(alert.getRuleTriggered()).contains("RAPID_VELOCITY");

            // ML signals
            assertThat(alert.getMlRiskScore()).isEqualTo(0.95);
            assertThat(alert.getMlModelName()).isEqualTo("Ensemble");
            assertThat(alert.getFeatureImportances()).contains("amount_zscore");
        }
    }

    // ==============================================================
    // Batch analysis with ML configs
    // ==============================================================

    @Nested
    @DisplayName("Batch Analysis with ML Configs")
    class BatchAnalysisWithMl {

        @BeforeEach
        void configureMocks() {
            when(mlService.isModelAvailable()).thenReturn(true);
            when(mlService.predict(any(), any())).thenReturn(
                    new MLPredictionResult("RandomForest", 0.87, true, Map.of())
            );
        }

        @Test
        @DisplayName("Batch ML_LLM_RAG should refuse when no models are trained")
        void batchMlLlmRag_refusesWithoutModels() {
            when(mlService.isModelAvailable()).thenReturn(false);
            persistTransaction("500000.00", TransactionType.TRANSFER);

            long alertsCreated = detectionPipelineService.analyzeAllTransactions(DetectionConfig.ML_LLM_RAG);

            assertThat(alertsCreated).isZero();
        }

        @Test
        @DisplayName("Batch FULL_SYSTEM should create alerts for ML-flagged transactions")
        void batchFullSystem_createsAlerts() {
            persistTransaction("500000.00", TransactionType.TRANSFER);
            persistTransaction("100.00", TransactionType.PAYMENT);    // ML flags everything

            long alertsCreated = detectionPipelineService.analyzeAllTransactions(DetectionConfig.FULL_SYSTEM);

            assertThat(alertsCreated).isEqualTo(2);
            assertThat(alertRepository.count()).isEqualTo(2);
        }

        @Test
        @DisplayName("Batch should not duplicate alerts for same config")
        void batchFullSystem_noDuplicates() {
            persistTransaction("500000.00", TransactionType.TRANSFER);

            long first = detectionPipelineService.analyzeAllTransactions(DetectionConfig.FULL_SYSTEM);
            long second = detectionPipelineService.analyzeAllTransactions(DetectionConfig.FULL_SYSTEM);

            assertThat(first).isEqualTo(1);
            assertThat(second).isZero();
            assertThat(alertRepository.count()).isEqualTo(1);
        }
    }

    // ==============================================================
    // Helpers
    // ==============================================================

    private Transaction persistTransaction(String amount, TransactionType type) {
        Transaction tx = new Transaction();
        tx.setAmount(new BigDecimal(amount));
        tx.setTransactionType(type);
        tx.setSenderAccount("SENDER_IT");
        tx.setReceiverAccount("RECEIVER_IT");
        tx.setTimestamp(LocalDateTime.now());
        tx.setDatasetSource(DatasetSource.PAYSIM);
        tx.setExternalId("IT-" + System.nanoTime());
        // Detection scopes to the test partition (is_training_set=false).
        // Entity default is true (legacy from pre-temporal-split era), so seeds
        // that exist for the purpose of being scored must explicitly opt out.
        tx.setIsTrainingSet(false);
        return transactionRepository.save(tx);
    }

    private void persistFeatures(Transaction tx, java.util.function.Consumer<TransactionFeatures> customizer) {
        TransactionFeatures features = new TransactionFeatures();
        features.setTransaction(tx);
        customizer.accept(features);
        featuresRepository.save(features);
    }

    /**
     * Create a mock Explanation entity (simulates what LLMExplanationService would return).
     */
    private Explanation createMockExplanation(Alert alert, ExplanationType type) {
        Explanation explanation = new Explanation();
        explanation.setAlert(alert);
        explanation.setExplanationType(type);
        explanation.setRiskSummary("Mock explanation for testing");
        explanation.setExplanationText("This is a mock explanation generated during integration testing.");
        explanation.setConfidenceScore(0.85);
        explanation.setLatencyMs(100);
        explanation.setHallucinationFree(true);
        return explanationRepository.save(explanation);
    }
}
