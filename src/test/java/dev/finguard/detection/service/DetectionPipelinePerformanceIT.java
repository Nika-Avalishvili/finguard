package dev.finguard.detection.service;

import dev.finguard.config.MockAiConfig;
import dev.finguard.config.TestContainersConfig;
import dev.finguard.detection.ml.MLPredictionResult;
import dev.finguard.detection.ml.TribuoModelService;
import dev.finguard.detection.rule.RuleMetadata;
import dev.finguard.detection.rule.RuleThresholdService;
import dev.finguard.domain.enums.DatasetSource;
import dev.finguard.domain.enums.DetectionConfig;
import dev.finguard.domain.enums.TransactionType;
import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.repository.AlertRepository;
import dev.finguard.domain.repository.ExplanationRepository;
import dev.finguard.domain.repository.TransactionFeaturesRepository;
import dev.finguard.domain.repository.TransactionRepository;
import dev.finguard.explanation.llm.LLMExplanationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Performance and load tests for the detection pipeline.
 *
 * <p>Verifies that the pipeline can process large datasets within acceptable
 * time bounds and that the batch processing with cursor-based pagination and
 * flush/clear cycles doesn't degrade under load.</p>
 *
 * <p>Tagged with "performance" so they can be run separately:</p>
 * <pre>{@code mvn verify -Dgroups=performance}</pre>
 */
@SpringBootTest
@Import({TestContainersConfig.class, MockAiConfig.class})
@Tag("performance")
@DisplayName("Detection Pipeline Performance (load tests)")
class DetectionPipelinePerformanceIT {

    private static final Logger log = LoggerFactory.getLogger(DetectionPipelinePerformanceIT.class);

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

    @MockitoBean
    private TribuoModelService mlService;

    @MockitoBean
    private LLMExplanationService llmExplanationService;

    @Autowired
    private RuleThresholdService ruleThresholdService;

    @BeforeEach
    void cleanUp() {
        explanationRepository.deleteAll();
        alertRepository.deleteAll();
        featuresRepository.deleteAll();
        transactionRepository.deleteAll();
        reset(mlService, llmExplanationService);
        // Pin thresholds to paper defaults so fixture amounts trigger as expected.
        // See DetectionPipelineIT#cleanUp for rationale.
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
    // Rules-only batch throughput
    // ==============================================================

    @Nested
    @DisplayName("Rules-Only Batch Throughput")
    class RulesOnlyThroughput {

        @Test
        @DisplayName("Should process 500 transactions in under 30 seconds")
        void batchRulesOnly_500transactions_withinTimeLimit() {
            int count = 500;
            insertMixedTransactions(count);

            Instant start = Instant.now();
            long alertsCreated = detectionPipelineService
                    .analyzeAllTransactions(DetectionConfig.RULES_ONLY);
            Duration elapsed = Duration.between(start, Instant.now());

            log.info("PERF: RULES_ONLY processed {} txns in {} ms, created {} alerts",
                    count, elapsed.toMillis(), alertsCreated);

            assertThat(elapsed).isLessThan(Duration.ofSeconds(30));
            assertThat(alertsCreated).isGreaterThan(0);
            assertThat(alertRepository.count()).isEqualTo(alertsCreated);
        }

        @Test
        @DisplayName("Should process 1000 transactions with linear or sub-linear time growth")
        void batchRulesOnly_scalingBehavior() {
            // Run 250 first, then 1000, to check scaling doesn't go quadratic
            insertMixedTransactions(250);
            Instant start250 = Instant.now();
            detectionPipelineService.analyzeAllTransactions(DetectionConfig.RULES_ONLY);
            long elapsed250 = Duration.between(start250, Instant.now()).toMillis();

            // Clean and re-insert for 1000
            cleanUp();
            insertMixedTransactions(1000);
            Instant start1000 = Instant.now();
            detectionPipelineService.analyzeAllTransactions(DetectionConfig.RULES_ONLY);
            long elapsed1000 = Duration.between(start1000, Instant.now()).toMillis();

            double ratio = elapsed250 > 0 ? (double) elapsed1000 / elapsed250 : Double.NaN;
            log.info("PERF: 250 txns = {} ms, 1000 txns = {} ms, ratio = {}",
                    elapsed250, elapsed1000, String.format("%.2f", ratio));

            // 4x data should take less than 8x time (allowing for overhead)
            // If it takes >8x, that signals a quadratic or N+1 problem
            if (elapsed250 > 100) { // only check ratio if 250 took meaningful time
                assertThat(ratio)
                        .as("Time should scale sub-quadratically: 1000/250 ratio should be < 8")
                        .isLessThan(8.0);
            }
        }
    }

    // ==============================================================
    // ML batch throughput
    // ==============================================================

    @Nested
    @DisplayName("ML Batch Throughput")
    class MlBatchThroughput {

        @BeforeEach
        void configureMlMocks() {
            when(mlService.isModelAvailable()).thenReturn(true);
            when(mlService.predict(any(), any())).thenAnswer(invocation -> {
                // Simulate a fast ML prediction (~0ms since it's mocked)
                Transaction tx = invocation.getArgument(0);
                boolean isFraud = tx.getAmount().compareTo(new BigDecimal("200000")) > 0;
                double score = isFraud ? 0.85 : 0.15;
                return new MLPredictionResult("RandomForest", score, isFraud, Map.of());
            });
        }

        @Test
        @DisplayName("Should process 500 ML_ONLY transactions in under 30 seconds")
        void batchMlOnly_500transactions_withinTimeLimit() {
            int count = 500;
            insertMixedTransactions(count);

            Instant start = Instant.now();
            long alertsCreated = detectionPipelineService
                    .analyzeAllTransactions(DetectionConfig.ML_ONLY);
            Duration elapsed = Duration.between(start, Instant.now());

            log.info("PERF: ML_ONLY processed {} txns in {} ms, created {} alerts",
                    count, elapsed.toMillis(), alertsCreated);

            assertThat(elapsed).isLessThan(Duration.ofSeconds(30));
            assertThat(alertsCreated).isGreaterThan(0);
        }

        @Test
        @DisplayName("Should process 500 FULL_SYSTEM transactions in under 45 seconds")
        void batchFullSystem_500transactions_withinTimeLimit() {
            int count = 500;
            insertMixedTransactions(count);

            Instant start = Instant.now();
            long alertsCreated = detectionPipelineService
                    .analyzeAllTransactions(DetectionConfig.FULL_SYSTEM);
            Duration elapsed = Duration.between(start, Instant.now());

            log.info("PERF: FULL_SYSTEM processed {} txns in {} ms, created {} alerts",
                    count, elapsed.toMillis(), alertsCreated);

            assertThat(elapsed).isLessThan(Duration.ofSeconds(45));
            assertThat(alertsCreated).isGreaterThan(0);
        }
    }

    // ==============================================================
    // Batch processing memory safety
    // ==============================================================

    @Nested
    @DisplayName("Batch Processing Memory Safety")
    class BatchMemorySafety {

        @Test
        @DisplayName("Should not accumulate entities in persistence context across batches")
        void batchProcessing_clearsEntityManager_betweenBatches() {
            // Insert enough transactions to span multiple batches (batch-size=100 in test config)
            int count = 350; // 3.5 batches
            insertMixedTransactions(count);

            // If entityManager.flush()/clear() is broken, this would OOM or slow dramatically
            Instant start = Instant.now();
            long alertsCreated = detectionPipelineService
                    .analyzeAllTransactions(DetectionConfig.RULES_ONLY);
            Duration elapsed = Duration.between(start, Instant.now());

            log.info("PERF: {} txns across ~3.5 batches in {} ms, {} alerts",
                    count, elapsed.toMillis(), alertsCreated);

            assertThat(alertsCreated).isGreaterThan(0);
            assertThat(alertRepository.count()).isEqualTo(alertsCreated);
        }

        @Test
        @DisplayName("Should handle re-running pipeline on already-processed data efficiently")
        void rerun_shouldSkipProcessed_quickly() {
            int count = 300;
            insertMixedTransactions(count);

            // First run — processes all
            detectionPipelineService.analyzeAllTransactions(DetectionConfig.RULES_ONLY);

            // Second run — should skip everything
            Instant start = Instant.now();
            long secondRun = detectionPipelineService
                    .analyzeAllTransactions(DetectionConfig.RULES_ONLY);
            Duration elapsed = Duration.between(start, Instant.now());

            log.info("PERF: Re-run on {} already-processed txns took {} ms",
                    count, elapsed.toMillis());

            assertThat(secondRun).isZero();
            // Re-run should be fast since it's just checking existing alerts
            assertThat(elapsed).isLessThan(Duration.ofSeconds(15));
        }
    }

    // ==============================================================
    // Data integrity under load
    // ==============================================================

    @Nested
    @DisplayName("Data Integrity Under Load")
    class DataIntegrityUnderLoad {

        @Test
        @DisplayName("Should maintain alert-transaction referential integrity for all alerts")
        void allAlerts_haveValidTransactionReferences() {
            int count = 200;
            insertMixedTransactions(count);

            detectionPipelineService.analyzeAllTransactions(DetectionConfig.RULES_ONLY);

            List<Long> orphanedAlertIds = new ArrayList<>();
            alertRepository.findAll().forEach(alert -> {
                if (alert.getTransaction() == null || alert.getTransaction().getId() == null) {
                    orphanedAlertIds.add(alert.getId());
                }
            });

            assertThat(orphanedAlertIds)
                    .as("No alerts should have null or orphaned transaction references")
                    .isEmpty();
        }

        @Test
        @DisplayName("Should set correct detection config on every alert")
        void allAlerts_haveCorrectDetectionConfig() {
            insertMixedTransactions(200);

            detectionPipelineService.analyzeAllTransactions(DetectionConfig.RULES_ONLY);

            alertRepository.findAll().forEach(alert -> {
                assertThat(alert.getDetectionConfig())
                        .as("Alert %d should have RULES_ONLY config", alert.getId())
                        .isEqualTo(DetectionConfig.RULES_ONLY);
                assertThat(alert.getIsAnomaly()).isTrue();
                assertThat(alert.getRuleTriggered()).isNotBlank();
            });
        }
    }

    // ==============================================================
    // Helpers
    // ==============================================================

    /**
     * Insert a mix of transactions — roughly 30% will trigger rules
     * (amounts > 200k) and 70% will be clean (amounts < 10k).
     */
    private void insertMixedTransactions(int count) {
        TransactionType[] types = TransactionType.values();
        List<Transaction> batch = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            Transaction tx = new Transaction();

            // ~30% large (trigger LARGE_TRANSACTION rule), 70% small
            if (i % 3 == 0) {
                tx.setAmount(BigDecimal.valueOf(200_000 + ThreadLocalRandom.current().nextInt(300_000)));
            } else {
                tx.setAmount(BigDecimal.valueOf(100 + ThreadLocalRandom.current().nextInt(9_000)));
            }

            tx.setTransactionType(types[i % types.length]);
            tx.setSenderAccount("PERF_SENDER_" + (i % 50));
            tx.setReceiverAccount("PERF_RECEIVER_" + (i % 100));
            tx.setTimestamp(LocalDateTime.now().minusHours(ThreadLocalRandom.current().nextInt(720)));
            tx.setDatasetSource(DatasetSource.PAYSIM);
            tx.setExternalId("PERF-" + i + "-" + System.nanoTime());
            // Detection now scopes to test-set rows only — see DetectionPipelineService.
            tx.setIsTrainingSet(false);
            batch.add(tx);
        }

        transactionRepository.saveAll(batch);
        log.info("Inserted {} mixed transactions for performance test", count);
    }
}
