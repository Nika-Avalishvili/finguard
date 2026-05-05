package dev.finguard.detection.service;

import dev.finguard.config.MockAiConfig;
import dev.finguard.config.TestContainersConfig;
import dev.finguard.domain.enums.DatasetSource;
import dev.finguard.domain.enums.DetectionConfig;
import dev.finguard.domain.enums.TransactionType;
import dev.finguard.domain.model.Alert;
import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.repository.AlertRepository;
import dev.finguard.domain.repository.ExplanationRepository;
import dev.finguard.domain.repository.TransactionFeaturesRepository;
import dev.finguard.domain.repository.TransactionRepository;
import dev.finguard.detection.ml.MLPredictionResult;
import dev.finguard.detection.ml.TribuoModelService;
import dev.finguard.explanation.llm.LLMExplanationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Concurrency tests for the detection pipeline.
 *
 * <p>Verifies that the pipeline behaves correctly when multiple threads
 * analyze transactions simultaneously. Tests focus on data integrity,
 * duplicate prevention, and thread safety under concurrent access.</p>
 */
@SpringBootTest
@Import({TestContainersConfig.class, MockAiConfig.class})
@DisplayName("Detection Pipeline Concurrency (integration)")
class DetectionPipelineConcurrencyIT {

    @Autowired
    private DetectionPipelineService detectionPipelineService;

    @Autowired
    private dev.finguard.detection.service.PipelineStatusTracker pipelineStatusTracker;

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

    @BeforeEach
    void cleanUp() {
        explanationRepository.deleteAll();
        alertRepository.deleteAll();
        featuresRepository.deleteAll();
        transactionRepository.deleteAll();
        reset(mlService, llmExplanationService);
    }

    // ==============================================================
    // Concurrent single-transaction analysis
    // ==============================================================

    @Nested
    @DisplayName("Concurrent Single Transaction Analysis")
    class ConcurrentSingleAnalysis {

        @Test
        @DisplayName("Should handle multiple threads analyzing different transactions simultaneously")
        void concurrentAnalysis_differentTransactions_allSucceed() throws Exception {
            int threadCount = 8;
            List<Transaction> transactions = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                transactions.add(persistTransaction("500000.00", TransactionType.TRANSFER));
            }

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            List<Optional<Alert>> results = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger errors = new AtomicInteger(0);

            for (Transaction tx : transactions) {
                executor.submit(() -> {
                    try {
                        startLatch.await(); // all threads start together
                        Optional<Alert> alert = detectionPipelineService
                                .analyzeTransaction(tx, DetectionConfig.RULES_ONLY);
                        results.add(alert);
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                });
            }

            startLatch.countDown(); // release all threads simultaneously
            executor.shutdown();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

            assertThat(errors.get()).isZero();
            assertThat(results).hasSize(threadCount);
            assertThat(results).allMatch(Optional::isPresent);

            // Each alert should reference a different transaction
            Set<Long> transactionIds = results.stream()
                    .filter(Optional::isPresent)
                    .map(opt -> opt.get().getTransaction().getId())
                    .collect(Collectors.toSet());
            assertThat(transactionIds).hasSize(threadCount);
        }

        @Test
        @DisplayName("Should not produce corrupt data when analyzing same transaction concurrently")
        void concurrentAnalysis_sameTransaction_noCorruption() throws Exception {
            Transaction tx = persistTransaction("500000.00", TransactionType.TRANSFER);

            int threadCount = 5;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            List<Optional<Alert>> results = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger errors = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        Optional<Alert> alert = detectionPipelineService
                                .analyzeTransaction(tx, DetectionConfig.RULES_ONLY);
                        results.add(alert);
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                });
            }

            startLatch.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

            assertThat(errors.get()).isZero();
            // All threads should produce a valid alert (analyzeTransaction is stateless)
            assertThat(results).allMatch(Optional::isPresent);

            // All alerts reference the same transaction and have correct rule
            for (Optional<Alert> result : results) {
                Alert alert = result.get();
                assertThat(alert.getTransaction().getId()).isEqualTo(tx.getId());
                assertThat(alert.getRuleTriggered()).contains("LARGE_TRANSACTION");
                assertThat(alert.getIsAnomaly()).isTrue();
                assertThat(alert.getDetectionConfig()).isEqualTo(DetectionConfig.RULES_ONLY);
            }
        }

        @Test
        @DisplayName("Should handle concurrent analysis with mixed detection configs")
        void concurrentAnalysis_mixedConfigs_allSucceed() throws Exception {
            when(mlService.isModelAvailable()).thenReturn(true);
            when(mlService.predict(any(), any())).thenReturn(
                    new MLPredictionResult("RandomForest", 0.87, true, Map.of("amount_zscore", 0.35))
            );

            Transaction tx = persistTransaction("500000.00", TransactionType.TRANSFER);

            DetectionConfig[] configs = {
                    DetectionConfig.RULES_ONLY,
                    DetectionConfig.ML_ONLY,
                    DetectionConfig.RULES_ONLY,
                    DetectionConfig.ML_ONLY
            };

            ExecutorService executor = Executors.newFixedThreadPool(configs.length);
            CountDownLatch startLatch = new CountDownLatch(1);
            List<Optional<Alert>> results = Collections.synchronizedList(new ArrayList<>());
            AtomicInteger errors = new AtomicInteger(0);

            for (DetectionConfig config : configs) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        Optional<Alert> alert = detectionPipelineService
                                .analyzeTransaction(tx, config);
                        results.add(alert);
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                });
            }

            startLatch.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

            assertThat(errors.get()).isZero();
            assertThat(results).hasSize(configs.length);
            assertThat(results).allMatch(Optional::isPresent);
        }
    }

    // ==============================================================
    // Concurrent batch analysis
    // ==============================================================

    @Nested
    @DisplayName("Concurrent Batch Analysis")
    class ConcurrentBatchAnalysis {

        @Test
        @DisplayName("Should handle parallel batch runs with different configs without data loss")
        void concurrentBatch_differentConfigs_noDuplicates() throws Exception {
            when(mlService.isModelAvailable()).thenReturn(true);
            when(mlService.predict(any(), any())).thenReturn(
                    new MLPredictionResult("RandomForest", 0.87, true, Map.of())
            );

            // Create a set of transactions that trigger rules and ML
            for (int i = 0; i < 5; i++) {
                persistTransaction("500000.00", TransactionType.TRANSFER);
            }

            // Run RULES_ONLY batch
            long rulesAlerts = detectionPipelineService.analyzeAllTransactions(DetectionConfig.RULES_ONLY);
            assertThat(rulesAlerts).isEqualTo(5);

            // Run ML_ONLY batch concurrently with a second RULES_ONLY batch (which should find nothing new)
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch startLatch = new CountDownLatch(1);

            CompletableFuture<Long> mlFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    startLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return detectionPipelineService.analyzeAllTransactions(DetectionConfig.ML_ONLY);
            }, executor);

            CompletableFuture<Long> rulesFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    startLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return detectionPipelineService.analyzeAllTransactions(DetectionConfig.RULES_ONLY);
            }, executor);

            startLatch.countDown();

            Long mlAlerts = mlFuture.get(60, TimeUnit.SECONDS);
            Long rulesAlerts2 = rulesFuture.get(60, TimeUnit.SECONDS);

            executor.shutdown();

            // ML_ONLY should create 5 new alerts (different config)
            assertThat(mlAlerts).isEqualTo(5);
            // Second RULES_ONLY should create 0 (already exist)
            assertThat(rulesAlerts2).isZero();

            // Total: 10 alerts (5 RULES_ONLY + 5 ML_ONLY)
            assertThat(alertRepository.count()).isEqualTo(10);
        }

        @Test
        @DisplayName("Should maintain accurate alert count under concurrent access")
        void concurrentBatch_alertCountConsistency() throws Exception {
            // Create transactions: some trigger rules, some don't
            persistTransaction("500000.00", TransactionType.TRANSFER); // triggers
            persistTransaction("100.00", TransactionType.PAYMENT);       // clean
            persistTransaction("300000.00", TransactionType.TRANSFER); // triggers

            // Run batch in multiple threads with the same config
            // Due to @Transactional, only one should actually create alerts
            int attempts = 3;
            ExecutorService executor = Executors.newFixedThreadPool(attempts);
            CountDownLatch startLatch = new CountDownLatch(1);
            List<Long> results = Collections.synchronizedList(new ArrayList<>());

            for (int i = 0; i < attempts; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        long alerts = detectionPipelineService
                                .analyzeAllTransactions(DetectionConfig.RULES_ONLY);
                        results.add(alerts);
                    } catch (Exception e) {
                        results.add(-1L);
                    }
                });
            }

            startLatch.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

            // The sum of all individual results should equal total alerts in DB
            // (some threads may see 0 because another thread already processed those txns)
            long totalAlertsInDb = alertRepository.count();
            assertThat(totalAlertsInDb).isEqualTo(2); // only 2 transactions trigger rules

            // No thread should have returned an error
            assertThat(results).doesNotContain(-1L);
        }
    }

    // ==============================================================
    // Async pipeline
    // ==============================================================

    @Nested
    @DisplayName("Async Pipeline Thread Safety")
    class AsyncPipelineThreadSafety {

        @Test
        @DisplayName("Should complete async analysis without errors")
        void asyncAnalysis_completesSuccessfully() throws Exception {
            persistTransaction("500000.00", TransactionType.TRANSFER);
            persistTransaction("300000.00", TransactionType.TRANSFER);

            // Migrated from the deprecated analyzeAllTransactionsAsync: caller
            // now creates the job id + registers with the status tracker, then
            // invokes the void @Async runAsync.
            String jobId = java.util.UUID.randomUUID().toString().substring(0, 8);
            pipelineStatusTracker.start(jobId, DetectionConfig.RULES_ONLY.name());
            detectionPipelineService.runAsync(DetectionConfig.RULES_ONLY, jobId);

            // The @Async method runs on a worker thread — poll the status
            // tracker until the job reports terminal phase, bounded by a timeout
            // so a regression doesn't hang the suite.
            long deadline = System.currentTimeMillis() + 30_000L;
            while (System.currentTimeMillis() < deadline) {
                var status = pipelineStatusTracker.getStatus(jobId);
                if (status != null
                        && (status.phase() == dev.finguard.detection.service.PipelineStatusTracker.Phase.COMPLETED
                         || status.phase() == dev.finguard.detection.service.PipelineStatusTracker.Phase.FAILED)) {
                    break;
                }
                Thread.sleep(50);
            }

            // Verify alerts were created
            assertThat(alertRepository.count()).isEqualTo(2);
        }
    }

    // ==============================================================
    // Helpers
    // ==============================================================

    private Transaction persistTransaction(String amount, TransactionType type) {
        Transaction tx = new Transaction();
        tx.setAmount(new BigDecimal(amount));
        tx.setTransactionType(type);
        tx.setSenderAccount("SENDER_CC");
        tx.setReceiverAccount("RECEIVER_CC");
        tx.setTimestamp(LocalDateTime.now());
        tx.setDatasetSource(DatasetSource.PAYSIM);
        tx.setExternalId("CC-" + System.nanoTime());
        // Detection now scopes to test-set rows only — see DetectionPipelineService.
        tx.setIsTrainingSet(false);
        return transactionRepository.save(tx);
    }
}
