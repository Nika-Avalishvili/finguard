package dev.finguard.ingestion.service;

import dev.finguard.config.MockAiConfig;
import dev.finguard.config.TestContainersConfig;
import dev.finguard.domain.enums.TransactionType;
import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.model.TransactionFeatures;
import dev.finguard.domain.repository.AlertRepository;
import dev.finguard.domain.repository.ExplanationRepository;
import dev.finguard.domain.repository.TransactionFeaturesRepository;
import dev.finguard.domain.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * End-to-end integration test for the full ingestion pipeline.
 *
 * <p>Tests the real flow: CSV file → IngestionService → DB → FeatureEngineeringService → features.
 * Runs against a real PostgreSQL (pgvector) instance via Testcontainers.
 * Spring AI beans are mocked since they are not needed for ingestion.</p>
 */
@SpringBootTest
@Import({TestContainersConfig.class, MockAiConfig.class})
@DisplayName("Ingestion Pipeline (end-to-end integration)")
class IngestionPipelineIT {

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private FeatureEngineeringService featureEngineeringService;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private TransactionFeaturesRepository featuresRepository;

    @Autowired
    private ExplanationRepository explanationRepository;

    @Autowired
    private AlertRepository alertRepository;

    @BeforeEach
    void cleanUp() {
        explanationRepository.deleteAll();
        alertRepository.deleteAll();
        featuresRepository.deleteAll();
        transactionRepository.deleteAll();
    }

    // ==============================================================
    // CSV Import tests
    // ==============================================================

    @Nested
    @DisplayName("CSV Import")
    class CsvImport {

        @Test
        @DisplayName("Should import all valid rows from a well-formed CSV")
        void shouldImportAllValidRows() throws Exception {
            Path csv = testDataPath("paysim-valid.csv");

            var result = ingestionService.importPaySimCsv(csv);

            assertThat(result.getSuccessCount()).isEqualTo(5);
            assertThat(result.getSkippedCount()).isZero();
            assertThat(result.getTotalRows()).isEqualTo(5);
            assertThat(result.getDuration()).isNotNull();

            // Verify rows actually persisted in DB
            List<Transaction> allTx = transactionRepository.findAll();
            assertThat(allTx).hasSize(5);
        }

        @Test
        @DisplayName("Should skip invalid rows and continue with valid ones")
        void shouldSkipInvalidRows() throws Exception {
            Path csv = testDataPath("paysim-mixed.csv");

            var result = ingestionService.importPaySimCsv(csv);

            // paysim-mixed.csv: row 1 valid TRANSFER, row 2 INVALID_TYPE, row 3 negative step,
            // row 4 negative amount, row 5 valid CASH_OUT, row 6 parse failure
            assertThat(result.getSuccessCount()).isEqualTo(2);
            assertThat(result.getSkippedCount()).isEqualTo(4);
            assertThat(result.getErrors()).isNotEmpty();

            List<Transaction> allTx = transactionRepository.findAll();
            assertThat(allTx).hasSize(2);
        }

        @Test
        @DisplayName("Should handle empty CSV (header only) gracefully")
        void shouldHandleEmptyCsv() throws Exception {
            Path csv = testDataPath("paysim-empty.csv");

            var result = ingestionService.importPaySimCsv(csv);

            assertThat(result.getSuccessCount()).isZero();
            assertThat(result.getSkippedCount()).isZero();
            assertThat(result.getTotalRows()).isZero();

            assertThat(transactionRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("Should skip duplicates on re-import of same file")
        void shouldSkipDuplicatesOnReimport() throws Exception {
            Path csv = testDataPath("paysim-valid.csv");

            // First import — 5 rows
            var result1 = ingestionService.importPaySimCsv(csv);
            assertThat(result1.getSuccessCount()).isEqualTo(5);

            // Second import — all 5 should be duplicates
            var result2 = ingestionService.importPaySimCsv(csv);
            assertThat(result2.getSuccessCount()).isZero();
            assertThat(result2.getSkippedCount()).isEqualTo(5);

            // DB should still have exactly 5 rows
            assertThat(transactionRepository.findAll()).hasSize(5);
        }
    }

    // ==============================================================
    // Feature Engineering tests
    // ==============================================================

    @Nested
    @DisplayName("Feature Engineering")
    class FeatureEngineering {

        @Test
        @DisplayName("Should compute features for all imported transactions")
        void shouldComputeFeaturesForAll() throws Exception {
            Path csv = testDataPath("paysim-valid.csv");
            ingestionService.importPaySimCsv(csv);

            long computed = featureEngineeringService.computeAllFeatures(dev.finguard.ingestion.service.FeatureEngineeringService.MAX_PER_ROW_LIMIT);

            assertThat(computed).isEqualTo(5);

            // Each transaction should have a corresponding feature row
            List<Transaction> allTx = transactionRepository.findAll();
            for (Transaction tx : allTx) {
                Optional<TransactionFeatures> features =
                        featuresRepository.findByTransactionId(tx.getId());
                assertThat(features)
                        .as("Features should exist for transaction %s", tx.getExternalId())
                        .isPresent();
            }
        }

        @Test
        @DisplayName("Should skip already-processed transactions on second run")
        void shouldSkipExistingFeaturesOnSecondRun() throws Exception {
            Path csv = testDataPath("paysim-valid.csv");
            ingestionService.importPaySimCsv(csv);

            // First run computes all
            long first = featureEngineeringService.computeAllFeatures(dev.finguard.ingestion.service.FeatureEngineeringService.MAX_PER_ROW_LIMIT);
            assertThat(first).isEqualTo(5);

            // Second run should skip all
            long second = featureEngineeringService.computeAllFeatures(dev.finguard.ingestion.service.FeatureEngineeringService.MAX_PER_ROW_LIMIT);
            assertThat(second).isZero();
        }

        @Test
        @DisplayName("Should produce correct feature values for a known transaction")
        void shouldProduceCorrectFeatureValues() throws Exception {
            Path csv = testDataPath("paysim-valid.csv");
            ingestionService.importPaySimCsv(csv);
            featureEngineeringService.computeAllFeatures(dev.finguard.ingestion.service.FeatureEngineeringService.MAX_PER_ROW_LIMIT);

            // The TRANSFER row: step=1, amount=50000, C100→C200, not fraud
            Transaction transferTx = transactionRepository
                    .findByExternalId("PAYSIM-1-C100-C200-50000.00")
                    .orElseThrow(() -> new AssertionError("Expected transfer transaction not found"));

            TransactionFeatures features = featuresRepository
                    .findByTransactionId(transferTx.getId())
                    .orElseThrow(() -> new AssertionError("Features not found"));

            // Temporal: step=1 → BASE_DATE + 1 hour → hour=1, dayOfWeek depends on BASE_DATE (Jan 1, 2025 = Wednesday = 3)
            assertThat(features.getHourOfDay()).isEqualTo((short) 1);
            assertThat(features.getDayOfWeek()).isEqualTo((short) 3); // Wednesday

            // Amount-based
            assertThat(features.getIsRoundAmount()).isTrue(); // 50000 is multiple of 1000
            assertThat(features.getIsHighRiskType()).isTrue(); // TRANSFER is high-risk

            // This is the first transaction for C100, so:
            assertThat(features.getIsNewReceiver()).isTrue();
            assertThat(features.getTxVelocity1h()).isZero(); // no prior transactions in 1h
            assertThat(features.getTxVelocity24h()).isZero(); // no prior transactions in 24h

            // Feature vector JSON should be populated
            assertThat(features.getFeatureVector()).isNotNull();
            assertThat(features.getFeatureVector()).contains("\"amount\":");
        }

        @Test
        @DisplayName("Should detect the fraud flag from CSV row")
        void shouldPreserveFraudFlag() throws Exception {
            Path csv = testDataPath("paysim-valid.csv");
            ingestionService.importPaySimCsv(csv);

            // Row 3 is fraud: step=2, CASH_OUT, 99999, C102→C300, isFraud=1
            Transaction fraudTx = transactionRepository
                    .findByExternalId("PAYSIM-2-C102-C300-99999.00")
                    .orElseThrow(() -> new AssertionError("Expected fraud transaction not found"));

            assertThat(fraudTx.getIsFraud()).isTrue();
            assertThat(fraudTx.getTransactionType()).isEqualTo(TransactionType.CASH_OUT);
            assertThat(fraudTx.getAmount()).isEqualByComparingTo("99999.00");
        }
    }

    // ==============================================================
    // Full pipeline test
    // ==============================================================

    @Test
    @DisplayName("Full pipeline: import → compute features → verify data integrity")
    void fullPipeline_shouldMaintainDataIntegrity() throws Exception {
        Path csv = testDataPath("paysim-valid.csv");

        // Import
        var importResult = ingestionService.importPaySimCsv(csv);
        assertThat(importResult.getSuccessCount()).isEqualTo(5);

        // Compute features
        long featureCount = featureEngineeringService.computeAllFeatures(dev.finguard.ingestion.service.FeatureEngineeringService.MAX_PER_ROW_LIMIT);
        assertThat(featureCount).isEqualTo(5);

        // Verify 1:1 relationship integrity
        long txCount = transactionRepository.count();
        long featuresCount = featuresRepository.count();
        assertThat(txCount).isEqualTo(featuresCount)
                .as("Every transaction should have exactly one feature row");

        // Verify all 5 transaction types are represented
        List<Transaction> allTx = transactionRepository.findAll();
        assertThat(allTx)
                .extracting(Transaction::getTransactionType)
                .containsExactlyInAnyOrder(
                        TransactionType.TRANSFER,
                        TransactionType.PAYMENT,
                        TransactionType.CASH_OUT,
                        TransactionType.CASH_IN,
                        TransactionType.DEBIT
                );
    }

    // ==============================================================
    // Helper
    // ==============================================================

    private Path testDataPath(String filename) throws IOException {
        Path resource = Path.of("src/test/resources/test-data", filename);
        if (!Files.exists(resource)) {
            throw new IOException("Test data file not found: " + resource.toAbsolutePath());
        }
        return resource;
    }
}
