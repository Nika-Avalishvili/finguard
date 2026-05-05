package dev.finguard.ingestion.service;

import dev.finguard.config.MockAiConfig;
import dev.finguard.config.TestContainersConfig;
import dev.finguard.domain.model.TransactionFeatures;
import dev.finguard.domain.repository.AlertRepository;
import dev.finguard.domain.repository.ExplanationRepository;
import dev.finguard.domain.repository.TransactionFeaturesRepository;
import dev.finguard.domain.repository.TransactionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static dev.finguard.testutil.TestTransactionBuilder.aTransaction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Integration tests for {@link FeatureEngineeringBulkService}.
 *
 * <p>Verifies that the bulk SQL window function approach produces correct feature values
 * across all computed fields.</p>
 *
 * <p><b>Flush discipline:</b> {@code computeAllBulk()} uses {@code JdbcTemplate}, which
 * bypasses the JPA first-level cache. Before calling it, all pending JPA writes must be
 * flushed via {@code entityManager.flush()} so the bulk SQL sees an up-to-date DB state.</p>
 */
@SpringBootTest
@Import({TestContainersConfig.class, MockAiConfig.class})
@Transactional
@DisplayName("FeatureEngineeringBulkService (integration)")
class FeatureEngineeringBulkServiceIT {

    @Autowired private FeatureEngineeringBulkService bulkService;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private TransactionFeaturesRepository featuresRepository;
    @Autowired private AlertRepository alertRepository;
    @Autowired private ExplanationRepository explanationRepository;
    @Autowired private EntityManager em;

    private static final LocalDateTime BASE = LocalDateTime.of(2025, 3, 15, 14, 0); // Saturday, 14:00

    @BeforeEach
    void clean() {
        explanationRepository.deleteAll();
        alertRepository.deleteAll();
        featuresRepository.deleteAll();
        transactionRepository.deleteAll();
        // Flush all pending JPA deletes to the JDBC connection so that the
        // JdbcTemplate-based bulk SQL sees an empty transactions table.
        em.flush();
    }

    /** Save a transaction and immediately flush to JDBC so JdbcTemplate can see it. */
    private void saveAndFlush(dev.finguard.domain.model.Transaction tx) {
        transactionRepository.save(tx);
        em.flush();
    }

    // ==============================================================
    // Basic correctness
    // ==============================================================

    @Nested
    @DisplayName("Single transaction — feature values")
    class SingleTransaction {

        @Test
        @DisplayName("Should compute correct temporal features")
        void shouldComputeTemporalFeatures() {
            // Saturday = ISODOW 6, hour 14
            saveAndFlush(aTransaction().from("S1").to("R1").withAmount("500").at(BASE).withExternalId("T1").build());

            long inserted = bulkService.computeAllBulk();

            assertThat(inserted).isEqualTo(1);
            TransactionFeatures f = featuresRepository.findAll().get(0);
            assertThat(f.getHourOfDay()).isEqualTo((short) 14);
            assertThat(f.getDayOfWeek()).isEqualTo((short) 6); // ISO Saturday = 6
        }

        @Test
        @DisplayName("Should set zero velocity when no prior transactions exist")
        void shouldSetZeroVelocity_whenNoPriorHistory() {
            saveAndFlush(aTransaction().from("S1").to("R1").withAmount("1000").at(BASE).withExternalId("T1").build());

            bulkService.computeAllBulk();

            TransactionFeatures f = featuresRepository.findAll().get(0);
            assertThat(f.getTxVelocity1h()).isEqualTo(0);
            assertThat(f.getTxVelocity24h()).isEqualTo(0);
        }

        @Test
        @DisplayName("Should flag is_new_receiver=true for first transaction to a receiver")
        void shouldMarkNewReceiver_onFirstTransaction() {
            saveAndFlush(aTransaction().from("S1").to("R1").withAmount("200").at(BASE).withExternalId("T1").build());

            bulkService.computeAllBulk();

            TransactionFeatures f = featuresRepository.findAll().get(0);
            assertThat(f.getIsNewReceiver()).isTrue();
        }

        @Test
        @DisplayName("Should detect round amounts")
        void shouldDetectRoundAmount() {
            saveAndFlush(aTransaction().from("S1").to("R1").withAmount("5000.00").at(BASE).withExternalId("T1").build());
            saveAndFlush(aTransaction().from("S2").to("R1").withAmount("1234.56").at(BASE).withExternalId("T2").build());

            bulkService.computeAllBulk();

            List<TransactionFeatures> features = featuresRepository.findAll();
            var f5000 = features.stream()
                    .filter(f -> "T1".equals(f.getTransaction().getExternalId()))
                    .findFirst().orElseThrow();
            var f1234 = features.stream()
                    .filter(f -> "T2".equals(f.getTransaction().getExternalId()))
                    .findFirst().orElseThrow();

            assertThat(f5000.getIsRoundAmount()).isTrue();
            assertThat(f1234.getIsRoundAmount()).isFalse();
        }

        @Test
        @DisplayName("Should flag TRANSFER and CASH_OUT as high risk type")
        void shouldFlagHighRiskTypes() {
            saveAndFlush(aTransaction().from("S1").to("R1").withAmount("100").at(BASE)
                    .withType(dev.finguard.domain.enums.TransactionType.TRANSFER).withExternalId("T1").build());
            saveAndFlush(aTransaction().from("S2").to("R1").withAmount("100").at(BASE)
                    .withType(dev.finguard.domain.enums.TransactionType.PAYMENT).withExternalId("T2").build());

            bulkService.computeAllBulk();

            List<TransactionFeatures> features = featuresRepository.findAll();
            var transfer = features.stream()
                    .filter(f -> "T1".equals(f.getTransaction().getExternalId()))
                    .findFirst().orElseThrow();
            var payment = features.stream()
                    .filter(f -> "T2".equals(f.getTransaction().getExternalId()))
                    .findFirst().orElseThrow();

            assertThat(transfer.getIsHighRiskType()).isTrue();
            assertThat(payment.getIsHighRiskType()).isFalse();
        }
    }

    // ==============================================================
    // Velocity counts
    // ==============================================================

    @Nested
    @DisplayName("Velocity window counts")
    class VelocityCounts {

        @Test
        @DisplayName("Should count prior transactions in 1h and 24h windows")
        void shouldCountVelocityInBothWindows() {
            // Two from S1 in 24h but outside 1h
            saveAndFlush(aTransaction().from("S1").at(BASE.minusHours(2)).withExternalId("V1").build());
            saveAndFlush(aTransaction().from("S1").at(BASE.minusHours(3)).withExternalId("V2").build());
            // One from S1 within 1h
            saveAndFlush(aTransaction().from("S1").at(BASE.minusMinutes(30)).withExternalId("V3").build());
            // Current transaction
            saveAndFlush(aTransaction().from("S1").at(BASE).withExternalId("CURRENT").build());
            // Different sender — must not affect S1 counts
            saveAndFlush(aTransaction().from("S2").at(BASE.minusMinutes(10)).withExternalId("OTHER").build());

            bulkService.computeAllBulk();

            TransactionFeatures current = featuresRepository.findAll().stream()
                    .filter(f -> "CURRENT".equals(f.getTransaction().getExternalId()))
                    .findFirst().orElseThrow();

            assertThat(current.getTxVelocity24h()).isEqualTo(3); // V1, V2, V3
            assertThat(current.getTxVelocity1h()).isEqualTo(1);  // only V3
        }
    }

    // ==============================================================
    // 7-day amount statistics
    // ==============================================================

    @Nested
    @DisplayName("7-day amount statistics")
    class AmountStats {

        @Test
        @DisplayName("Should compute avg, zscore, and ratio from prior 7-day history")
        void shouldComputeAmountStats() {
            // Three prior transactions from S1: amounts 100, 200, 300 → avg=200, stddev_pop≈81.65
            saveAndFlush(aTransaction().from("S1").withAmount("100").at(BASE.minusDays(1)).withExternalId("H1").build());
            saveAndFlush(aTransaction().from("S1").withAmount("200").at(BASE.minusDays(2)).withExternalId("H2").build());
            saveAndFlush(aTransaction().from("S1").withAmount("300").at(BASE.minusDays(3)).withExternalId("H3").build());
            // Current tx: amount 200 → zscore = (200−200)/81.65 = 0
            saveAndFlush(aTransaction().from("S1").withAmount("200").at(BASE).withExternalId("CURRENT").build());

            bulkService.computeAllBulk();

            TransactionFeatures f = featuresRepository.findAll().stream()
                    .filter(ft -> "CURRENT".equals(ft.getTransaction().getExternalId()))
                    .findFirst().orElseThrow();

            assertThat(f.getAvgAmount7d().doubleValue()).isCloseTo(200.0, within(0.01));
            assertThat(f.getAmountZscore()).isCloseTo(0.0, within(0.01));
            assertThat(f.getAmountRatioToAvg()).isCloseTo(1.0, within(0.01)); // 200/200
        }
    }

    // ==============================================================
    // Receiver features
    // ==============================================================

    @Nested
    @DisplayName("Receiver features")
    class ReceiverFeatures {

        @Test
        @DisplayName("Should detect repeat receiver (is_new_receiver=false)")
        void shouldDetectRepeatReceiver() {
            saveAndFlush(aTransaction().from("S1").to("R1").at(BASE.minusHours(2)).withExternalId("PRIOR").build());
            saveAndFlush(aTransaction().from("S1").to("R1").at(BASE).withExternalId("CURRENT").build());

            bulkService.computeAllBulk();

            TransactionFeatures f = featuresRepository.findAll().stream()
                    .filter(ft -> "CURRENT".equals(ft.getTransaction().getExternalId()))
                    .findFirst().orElseThrow();
            assertThat(f.getIsNewReceiver()).isFalse();
        }

        @Test
        @DisplayName("Should count distinct receivers in 7-day window")
        void shouldCountReceiverDiversity() {
            // S1 sent to 3 distinct receivers in prior 7 days
            saveAndFlush(aTransaction().from("S1").to("R1").at(BASE.minusDays(1)).withExternalId("D1").build());
            saveAndFlush(aTransaction().from("S1").to("R2").at(BASE.minusDays(2)).withExternalId("D2").build());
            saveAndFlush(aTransaction().from("S1").to("R3").at(BASE.minusDays(3)).withExternalId("D3").build());
            // R1 again — duplicate, must not increase count
            saveAndFlush(aTransaction().from("S1").to("R1").at(BASE.minusDays(4)).withExternalId("D4").build());
            // Current tx to R4 (which is NOT counted in diversity — it's PRIOR diversity)
            saveAndFlush(aTransaction().from("S1").to("R4").at(BASE).withExternalId("CURRENT").build());

            bulkService.computeAllBulk();

            TransactionFeatures f = featuresRepository.findAll().stream()
                    .filter(ft -> "CURRENT".equals(ft.getTransaction().getExternalId()))
                    .findFirst().orElseThrow();
            assertThat(f.getReceiverDiversity7d()).isEqualTo(3); // R1, R2, R3 in the 7d window before BASE
        }
    }

    // ==============================================================
    // Balance change ratio
    // ==============================================================

    @Test
    @DisplayName("Should compute balance change ratio correctly")
    void shouldComputeBalanceChangeRatio() {
        // amount=500, sender_balance_before=2000 → ratio = 500/2000 = 0.25
        saveAndFlush(aTransaction()
                .from("S1").withAmount("500").withSenderBalanceBefore("2000")
                .at(BASE).withExternalId("T1").build());

        bulkService.computeAllBulk();

        TransactionFeatures f = featuresRepository.findAll().get(0);
        assertThat(f.getBalanceChangeRatio()).isCloseTo(0.25, within(0.001));
    }

    // ==============================================================
    // Idempotency
    // ==============================================================

    @Test
    @DisplayName("Should be idempotent — second run inserts nothing")
    void shouldBeIdempotent() {
        saveAndFlush(aTransaction().from("S1").withExternalId("T1").at(BASE).build());
        saveAndFlush(aTransaction().from("S1").withExternalId("T2").at(BASE.plusMinutes(1)).build());

        long first  = bulkService.computeAllBulk();
        long second = bulkService.computeAllBulk();

        assertThat(first).isEqualTo(2);
        assertThat(second).isEqualTo(0); // nothing new to insert
        assertThat(featuresRepository.count()).isEqualTo(2);
    }

    // ==============================================================
    // Feature vector JSON
    // ==============================================================

    @Test
    @DisplayName("Should populate feature_vector JSON with all expected keys")
    void shouldPopulateFeatureVectorJson() {
        saveAndFlush(aTransaction()
                .from("S1").to("R1").withAmount("1000").at(BASE).withExternalId("T1").build());

        bulkService.computeAllBulk();

        TransactionFeatures f = featuresRepository.findAll().get(0);
        assertThat(f.getFeatureVector()).isNotBlank();
        String fv = f.getFeatureVector();
        assertThat(fv).contains("amount");
        assertThat(fv).contains("txVelocity1h");
        assertThat(fv).contains("txVelocity24h");
        assertThat(fv).contains("avgAmount7d");
        assertThat(fv).contains("isNewReceiver");
        assertThat(fv).contains("isHighRiskType");
    }

    // ============================================================================
    // Temporal causality (audit A-5 correctness guarantee)
    // ============================================================================

    /**
     * Regression test: features for a transaction at time T must NOT depend on
     * rows with timestamp >= T. The bulk SQL uses
     * {@code RANGE BETWEEN INTERVAL '24 hours' PRECEDING AND INTERVAL '1 microsecond' PRECEDING}
     * to guarantee this at the query level — this test proves it holds at the
     * data level and will catch any future refactor that relaxes the frame.
     *
     * <p>Why this matters: if feature windows include future rows, evaluation
     * metrics (precision/recall) look artificially strong because the model
     * effectively trained on data it wouldn't see at inference time. This is
     * the leak that audit A-5 guards against; the temporal split (changelog
     * 016) puts training rows strictly before test rows in the timeline, and
     * this invariant keeps that separation meaningful.
     */
    @Test
    @DisplayName("A-5 causality: features at time T only reflect data with timestamp < T")
    void shouldComputeFeaturesWithStrictTemporalCausality() {
        // Sender emits one transaction at T, then FIVE rapid follow-ups within 10 minutes.
        // The velocity counts for the FIRST transaction must be 0 (no history yet).
        // The velocity counts for the LAST transaction should reflect the prior ones only.
        LocalDateTime t0 = BASE;
        saveAndFlush(aTransaction().from("S").to("R1").withAmount("100")
                .at(t0).withExternalId("T0").build());
        for (int i = 1; i <= 5; i++) {
            saveAndFlush(aTransaction().from("S").to("R" + i).withAmount("100")
                    .at(t0.plusMinutes(i)).withExternalId("T" + i).build());
        }

        bulkService.computeAllBulk();
        em.clear();

        List<TransactionFeatures> byTime = featuresRepository.findAll().stream()
                .sorted((a, b) -> a.getTransaction().getTimestamp()
                        .compareTo(b.getTransaction().getTimestamp()))
                .toList();

        // First transaction at t0 must see NO prior traffic.
        TransactionFeatures first = byTime.get(0);
        assertThat(first.getTxVelocity1h())
                .as("first tx has no history → 1h velocity must be 0")
                .isEqualTo(0);
        assertThat(first.getTxVelocity24h())
                .as("first tx has no history → 24h velocity must be 0")
                .isEqualTo(0);

        // Each subsequent transaction's velocity must equal the count of
        // STRICTLY PRIOR transactions in the window — never including itself
        // or any future rows.
        for (int i = 1; i < byTime.size(); i++) {
            TransactionFeatures f = byTime.get(i);
            assertThat(f.getTxVelocity1h())
                    .as("tx %d at %s: 1h velocity must reflect exactly %d prior rows",
                            i, f.getTransaction().getTimestamp(), i)
                    .isEqualTo(i);
            assertThat(f.getTxVelocity24h())
                    .as("tx %d at %s: 24h velocity must reflect exactly %d prior rows",
                            i, f.getTransaction().getTimestamp(), i)
                    .isEqualTo(i);
        }
    }
}
