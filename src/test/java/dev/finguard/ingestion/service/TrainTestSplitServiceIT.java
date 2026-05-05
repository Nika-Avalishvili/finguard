package dev.finguard.ingestion.service;

import dev.finguard.config.MockAiConfig;
import dev.finguard.config.TestContainersConfig;
import dev.finguard.domain.enums.DatasetSource;
import dev.finguard.domain.enums.TransactionType;
import dev.finguard.domain.model.Transaction;
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

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests covering:
 *
 * <ul>
 *   <li>{@link IngestionService} now imports rows with {@code is_training_set = TRUE} via
 *       the COPY column list, so ML training works out-of-the-box after import.</li>
 *   <li>{@link TrainTestSplitService#finalizeTemporalSplit(double)} carves the newest
 *       {@code (1 − ratio)} fraction of rows into the test set.</li>
 * </ul>
 */
@SpringBootTest
@Import({TestContainersConfig.class, MockAiConfig.class})
@DisplayName("Train/test split finalization (audit regression)")
class TrainTestSplitServiceIT {

    @Autowired private IngestionService ingestionService;
    @Autowired private TrainTestSplitService trainTestSplitService;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private TransactionFeaturesRepository featuresRepository;
    @Autowired private AlertRepository alertRepository;
    @Autowired private ExplanationRepository explanationRepository;

    @BeforeEach
    void cleanDb() {
        explanationRepository.deleteAll();
        alertRepository.deleteAll();
        featuresRepository.deleteAll();
        transactionRepository.deleteAll();
    }

    @Nested
    @DisplayName("IngestionService COPY defaults is_training_set to TRUE")
    class CopyDefaults {

        @Test
        @DisplayName("Fresh CSV import → every row has is_training_set = true")
        void freshImport_allRowsMarkedTraining() throws Exception {
            Path csv = Path.of("src/test/resources/test-data/paysim-valid.csv");
            assertThat(Files.exists(csv))
                    .as("Fixture paysim-valid.csv must exist at %s", csv.toAbsolutePath())
                    .isTrue();

            ingestionService.importPaySimCsv(csv);

            List<Transaction> rows = transactionRepository.findAll();
            assertThat(rows).isNotEmpty();
            assertThat(rows).allSatisfy(tx ->
                    assertThat(tx.getIsTrainingSet())
                            .as("row %s should default to training", tx.getExternalId())
                            .isTrue());
        }
    }

    @Nested
    @DisplayName("finalizeTemporalSplit(ratio)")
    class Finalize {

        @Test
        @DisplayName("No rows → returns zero counts, no error")
        void emptyTable_returnsZeros() {
            TrainTestSplitService.SplitResult res = trainTestSplitService.finalizeTemporalSplit(0.80);
            assertThat(res.totalRows()).isZero();
            assertThat(res.trainingCount()).isZero();
            assertThat(res.testCount()).isZero();
            assertThat(res.cutoffTimestamp()).isNull();
        }

        @Test
        @DisplayName("10 rows, ratio 0.80 → 7 training (oldest) + 3 test (newest)")
        void tenRows_eightyTwenty() {
            // Seed 10 rows spaced 1 hour apart so the 80th-percentile cutoff is unambiguous.
            LocalDateTime base = LocalDateTime.of(2025, 1, 1, 0, 0);
            List<Transaction> seeded = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                Transaction t = newTx("SPLIT-" + i, base.plusHours(i));
                seeded.add(transactionRepository.save(t));
            }

            // Sanity — all seeded rows default to training (per entity default + COPY default).
            assertThat(seeded).allSatisfy(t -> assertThat(t.getIsTrainingSet()).isTrue());

            TrainTestSplitService.SplitResult res = trainTestSplitService.finalizeTemporalSplit(0.80);

            assertThat(res.totalRows()).isEqualTo(10L);
            // percentile_disc(0.8) on 10 values returns the 8th element (0-indexed: 7),
            // which becomes the cutoff. Rows with timestamp *strictly less than* the
            // cutoff (indices 0..6 = 7 rows) become training; rows at/after the cutoff
            // (indices 7..9 = 3 rows) become test. This quantisation is expected and
            // converges to exact 80/20 at larger N.
            assertThat(res.trainingCount()).isEqualTo(7L);
            assertThat(res.testCount()).isEqualTo(3L);

            // Verify directionality: the newest 2 rows must be test.
            List<Transaction> all = transactionRepository.findAll();
            List<Transaction> testRows = all.stream().filter(t -> !t.getIsTrainingSet()).toList();
            assertThat(testRows).hasSize(3);
            LocalDateTime maxTrainingTs = all.stream()
                    .filter(Transaction::getIsTrainingSet)
                    .map(Transaction::getTimestamp)
                    .max(LocalDateTime::compareTo)
                    .orElseThrow();
            LocalDateTime minTestTs = testRows.stream()
                    .map(Transaction::getTimestamp)
                    .min(LocalDateTime::compareTo)
                    .orElseThrow();
            assertThat(minTestTs)
                    .as("every test row timestamp must be ≥ every training row timestamp")
                    .isAfterOrEqualTo(maxTrainingTs);
        }

        @Test
        @DisplayName("Re-running is idempotent — counts do not drift")
        void idempotent_whenCalledTwice() {
            LocalDateTime base = LocalDateTime.of(2025, 6, 1, 0, 0);
            for (int i = 0; i < 10; i++) {
                transactionRepository.save(newTx("IDEM-" + i, base.plusHours(i)));
            }

            TrainTestSplitService.SplitResult first  = trainTestSplitService.finalizeTemporalSplit(0.80);
            TrainTestSplitService.SplitResult second = trainTestSplitService.finalizeTemporalSplit(0.80);

            assertThat(second.trainingCount()).isEqualTo(first.trainingCount());
            assertThat(second.testCount()).isEqualTo(first.testCount());
            assertThat(second.cutoffTimestamp()).isEqualTo(first.cutoffTimestamp());
        }

        @Test
        @DisplayName("Ratio outside (0,1) → IllegalArgumentException")
        void invalidRatio_throws() {
            assertThatThrownBy(() -> trainTestSplitService.finalizeTemporalSplit(0.0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> trainTestSplitService.finalizeTemporalSplit(1.0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> trainTestSplitService.finalizeTemporalSplit(-0.5))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private Transaction newTx(String externalId, LocalDateTime ts) {
        Transaction t = new Transaction();
        t.setExternalId(externalId);
        t.setDatasetSource(DatasetSource.PAYSIM);
        t.setTimestamp(ts);
        t.setSenderAccount("C" + externalId.hashCode());
        t.setReceiverAccount("M" + externalId.hashCode());
        t.setTransactionType(TransactionType.TRANSFER);
        t.setAmount(new BigDecimal("100.00"));
        t.setIsFraud(false);
        // createdAt is populated by the @PrePersist hook — do not set explicitly.
        return t;
    }
}
