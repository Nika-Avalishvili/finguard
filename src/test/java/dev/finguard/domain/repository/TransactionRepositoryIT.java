package dev.finguard.domain.repository;

import dev.finguard.config.MockAiConfig;
import dev.finguard.config.TestContainersConfig;
import dev.finguard.domain.enums.DatasetSource;
import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.repository.AlertRepository;
import dev.finguard.domain.repository.ExplanationRepository;
import dev.finguard.domain.repository.TransactionFeaturesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static dev.finguard.testutil.TestTransactionBuilder.aTransaction;
import static org.assertj.core.api.Assertions.*;

/**
 * Integration test for TransactionRepository custom queries.
 *
 * <p>Runs against a real PostgreSQL (pgvector) instance via Testcontainers.
 * Each test method is wrapped in a transaction that rolls back after completion,
 * guaranteeing a clean DB state between tests.</p>
 */
@SpringBootTest
@Import({TestContainersConfig.class, MockAiConfig.class})
@Transactional
@DisplayName("TransactionRepository (integration)")
class TransactionRepositoryIT {

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private ExplanationRepository explanationRepository;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private TransactionFeaturesRepository featuresRepository;

    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2025, 6, 15, 12, 0);

    @BeforeEach
    void cleanUp() {
        explanationRepository.deleteAll();
        alertRepository.deleteAll();
        featuresRepository.deleteAll();
        transactionRepository.deleteAll();
    }

    // ==============================================================
    // findByExternalId
    // ==============================================================

    @Nested
    @DisplayName("findByExternalId()")
    class FindByExternalId {

        @Test
        @DisplayName("Should find transaction by exact external ID")
        void shouldFindByExternalId() {
            Transaction saved = transactionRepository.save(
                    aTransaction().withExternalId("PAYSIM-1-C100-C200-50000").build());

            Optional<Transaction> found = transactionRepository.findByExternalId("PAYSIM-1-C100-C200-50000");

            assertThat(found).isPresent();
            assertThat(found.get().getId()).isEqualTo(saved.getId());
        }

        @Test
        @DisplayName("Should return empty for non-existent external ID")
        void shouldReturnEmpty_whenNotFound() {
            Optional<Transaction> found = transactionRepository.findByExternalId("DOES-NOT-EXIST");

            assertThat(found).isEmpty();
        }
    }

    // ==============================================================
    // findBySenderAccountAndTimestampBetween
    // ==============================================================

    @Nested
    @DisplayName("findBySenderAccountAndTimestampBetween()")
    class SenderHistory {

        @Test
        @DisplayName("Should return only transactions from specified sender within time window")
        void shouldFilterBySenderAndTimeWindow() {
            // Matching: same sender, within window
            transactionRepository.save(aTransaction()
                    .from("SENDER_A").at(BASE_TIME.minusMinutes(30)).withExternalId("A1").build());
            transactionRepository.save(aTransaction()
                    .from("SENDER_A").at(BASE_TIME.minusMinutes(45)).withExternalId("A2").build());

            // Not matching: different sender
            transactionRepository.save(aTransaction()
                    .from("SENDER_B").at(BASE_TIME.minusMinutes(20)).withExternalId("B1").build());

            // Not matching: same sender but outside window
            transactionRepository.save(aTransaction()
                    .from("SENDER_A").at(BASE_TIME.minusHours(3)).withExternalId("A3").build());

            List<Transaction> result = transactionRepository
                    .findBySenderAccountAndTimestampBetween("SENDER_A",
                            BASE_TIME.minusHours(1), BASE_TIME);

            assertThat(result).hasSize(2);
            assertThat(result).allMatch(tx -> tx.getSenderAccount().equals("SENDER_A"));
        }

        @Test
        @DisplayName("Should return empty list when no matching transactions exist")
        void shouldReturnEmpty_whenNoMatches() {
            List<Transaction> result = transactionRepository
                    .findBySenderAccountAndTimestampBetween("NOBODY",
                            BASE_TIME.minusHours(1), BASE_TIME);

            assertThat(result).isEmpty();
        }
    }

    // ==============================================================
    // countDistinctReceiversByAccount
    // ==============================================================

    @Nested
    @DisplayName("countDistinctReceiversByAccount()")
    class ReceiverDiversity {

        @Test
        @DisplayName("Should count unique receivers for a sender in time window")
        void shouldCountDistinctReceivers() {
            // SENDER_A → R1, R2, R3 (3 unique)
            transactionRepository.save(aTransaction()
                    .from("SENDER_A").to("R1").at(BASE_TIME.minusDays(1)).withExternalId("E1").build());
            transactionRepository.save(aTransaction()
                    .from("SENDER_A").to("R2").at(BASE_TIME.minusDays(2)).withExternalId("E2").build());
            transactionRepository.save(aTransaction()
                    .from("SENDER_A").to("R3").at(BASE_TIME.minusDays(3)).withExternalId("E3").build());
            // Duplicate receiver — should not increase count
            transactionRepository.save(aTransaction()
                    .from("SENDER_A").to("R1").at(BASE_TIME.minusDays(4)).withExternalId("E4").build());

            int count = transactionRepository.countDistinctReceiversByAccount(
                    "SENDER_A", BASE_TIME.minusDays(7), BASE_TIME);

            assertThat(count).isEqualTo(3);
        }
    }

    // ==============================================================
    // avgAmountByAccount
    // ==============================================================

    @Nested
    @DisplayName("avgAmountByAccount()")
    class AvgAmount {

        @Test
        @DisplayName("Should compute average amount for sender in time window")
        void shouldComputeAverage() {
            transactionRepository.save(aTransaction()
                    .from("S1").withAmount("100").at(BASE_TIME.minusDays(1)).withExternalId("E1").build());
            transactionRepository.save(aTransaction()
                    .from("S1").withAmount("200").at(BASE_TIME.minusDays(2)).withExternalId("E2").build());
            transactionRepository.save(aTransaction()
                    .from("S1").withAmount("300").at(BASE_TIME.minusDays(3)).withExternalId("E3").build());

            BigDecimal avg = transactionRepository.avgAmountByAccount(
                    "S1", BASE_TIME.minusDays(7), BASE_TIME);

            assertThat(avg).isEqualByComparingTo(new BigDecimal("200"));
        }

        @Test
        @DisplayName("Should return null when no transactions exist for sender")
        void shouldReturnNull_whenNoTransactions() {
            BigDecimal avg = transactionRepository.avgAmountByAccount(
                    "NOBODY", BASE_TIME.minusDays(7), BASE_TIME);

            assertThat(avg).isNull();
        }
    }

    // ==============================================================
    // stddevAmountByAccount (native query)
    // ==============================================================

    @Nested
    @DisplayName("stddevAmountByAccount()")
    class StddevAmount {

        @Test
        @DisplayName("Should compute population stddev for sender amounts")
        void shouldComputeStddev() {
            // Amounts: 100, 200, 300 → mean = 200, stddev_pop ≈ 81.65
            transactionRepository.save(aTransaction()
                    .from("S1").withAmount("100").at(BASE_TIME.minusDays(1)).withExternalId("E1").build());
            transactionRepository.save(aTransaction()
                    .from("S1").withAmount("200").at(BASE_TIME.minusDays(2)).withExternalId("E2").build());
            transactionRepository.save(aTransaction()
                    .from("S1").withAmount("300").at(BASE_TIME.minusDays(3)).withExternalId("E3").build());

            BigDecimal stddev = transactionRepository.stddevAmountByAccount(
                    "S1", BASE_TIME.minusDays(7), BASE_TIME);

            assertThat(stddev).isNotNull();
            assertThat(stddev.doubleValue()).isCloseTo(81.65, within(0.1));
        }

        @Test
        @DisplayName("Should return 0 when no transactions exist (COALESCE)")
        void shouldReturnZero_whenNoTransactions() {
            BigDecimal stddev = transactionRepository.stddevAmountByAccount(
                    "NOBODY", BASE_TIME.minusDays(7), BASE_TIME);

            assertThat(stddev).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    // ==============================================================
    // findVelocityCounts (combined native query)
    // ==============================================================

    @Nested
    @DisplayName("findVelocityCounts()")
    class VelocityCounts {

        @Test
        @DisplayName("Should count 24h and 1h transactions in one query")
        void shouldCountVelocityInBothWindows() {
            // Within 24h but outside 1h window
            transactionRepository.save(aTransaction()
                    .from("V_SENDER").at(BASE_TIME.minusHours(2)).withExternalId("V1").build());
            transactionRepository.save(aTransaction()
                    .from("V_SENDER").at(BASE_TIME.minusHours(3)).withExternalId("V2").build());
            // Within 1h window
            transactionRepository.save(aTransaction()
                    .from("V_SENDER").at(BASE_TIME.minusMinutes(30)).withExternalId("V3").build());
            // Different sender -- must not be counted
            transactionRepository.save(aTransaction()
                    .from("OTHER_V").at(BASE_TIME.minusMinutes(10)).withExternalId("V4").build());

            Object[] result = transactionRepository.findVelocityCounts(
                    "V_SENDER",
                    BASE_TIME.minusHours(24),
                    BASE_TIME,
                    BASE_TIME.minusHours(1)).get(0);

            assertThat(((Number) result[0]).longValue()).isEqualTo(3L); // count_24h
            assertThat(((Number) result[1]).longValue()).isEqualTo(1L); // count_1h (only V3)
        }

        @Test
        @DisplayName("Should return zeros when sender has no transactions")
        void shouldReturnZeros_whenNoTransactions() {
            Object[] result = transactionRepository.findVelocityCounts(
                    "NO_SUCH_SENDER",
                    BASE_TIME.minusHours(24),
                    BASE_TIME,
                    BASE_TIME.minusHours(1)).get(0);

            assertThat(((Number) result[0]).longValue()).isEqualTo(0L);
            assertThat(((Number) result[1]).longValue()).isEqualTo(0L);
        }
    }

    // ==============================================================
    // findSenderStats7d (combined native query)
    // ==============================================================

    @Nested
    @DisplayName("findSenderStats7d()")
    class SenderStats7d {

        @Test
        @DisplayName("Should compute avg, stddev, and receiver diversity in one query")
        void shouldComputeAllStatsInOneQuery() {
            // amounts 100, 200, 300 -> avg=200, stddev_pop~81.65; 2 unique receivers
            transactionRepository.save(aTransaction()
                    .from("S_STATS").to("R1").withAmount("100").at(BASE_TIME.minusDays(1)).withExternalId("SS1").build());
            transactionRepository.save(aTransaction()
                    .from("S_STATS").to("R2").withAmount("200").at(BASE_TIME.minusDays(2)).withExternalId("SS2").build());
            transactionRepository.save(aTransaction()
                    .from("S_STATS").to("R1").withAmount("300").at(BASE_TIME.minusDays(3)).withExternalId("SS3").build());
            // Different sender -- must not appear
            transactionRepository.save(aTransaction()
                    .from("OTHER_S").to("R9").withAmount("999").at(BASE_TIME.minusDays(1)).withExternalId("SS4").build());

            Object[] result = transactionRepository.findSenderStats7d(
                    "S_STATS", BASE_TIME.minusDays(7), BASE_TIME).get(0);

            double avg       = ((Number) result[0]).doubleValue();
            double stddev    = ((Number) result[1]).doubleValue();
            long   diversity = ((Number) result[2]).longValue();

            assertThat(avg).isCloseTo(200.0, within(0.01));
            assertThat(stddev).isCloseTo(81.65, within(0.1));
            assertThat(diversity).isEqualTo(2L);
        }

        @Test
        @DisplayName("Should return zeros when sender has no transactions (COALESCE)")
        void shouldReturnZeros_whenNoTransactions() {
            Object[] result = transactionRepository.findSenderStats7d(
                    "NO_SUCH_SENDER", BASE_TIME.minusDays(7), BASE_TIME).get(0);

            assertThat(((Number) result[0]).doubleValue()).isEqualTo(0.0);
            assertThat(((Number) result[1]).doubleValue()).isEqualTo(0.0);
            assertThat(((Number) result[2]).longValue()).isEqualTo(0L);
        }
    }

        // ==============================================================
    // findAllAfterId (cursor pagination)
    // ==============================================================

    @Nested
    @DisplayName("findAllAfterId()")
    class CursorPagination {

        @Test
        @DisplayName("Should return transactions with ID greater than given value")
        void shouldPaginateByCursor() {
            transactionRepository.save(aTransaction().withExternalId("P1").build());
            transactionRepository.save(aTransaction().withExternalId("P2").build());
            transactionRepository.save(aTransaction().withExternalId("P3").build());

            // Get first page (after ID 0)
            List<Transaction> page1 = transactionRepository
                    .findAllAfterId(0L, PageRequest.of(0, 2));
            assertThat(page1).hasSize(2);

            // Get second page (after last ID from page 1)
            Long lastId = page1.get(page1.size() - 1).getId();
            List<Transaction> page2 = transactionRepository
                    .findAllAfterId(lastId, PageRequest.of(0, 2));
            assertThat(page2).hasSize(1);

            // Third page should be empty
            Long lastId2 = page2.get(0).getId();
            List<Transaction> page3 = transactionRepository
                    .findAllAfterId(lastId2, PageRequest.of(0, 2));
            assertThat(page3).isEmpty();
        }
    }

    // ==============================================================
    // existsBySenderAccountAndReceiverAccount
    // ==============================================================

    @Test
    @DisplayName("existsBySenderAccountAndReceiverAccount should detect existing pairs")
    void shouldDetectExistingSenderReceiverPair() {
        transactionRepository.save(aTransaction()
                .from("S1").to("R1").withExternalId("pair1").build());

        assertThat(transactionRepository.existsBySenderAccountAndReceiverAccount("S1", "R1"))
                .isTrue();
        assertThat(transactionRepository.existsBySenderAccountAndReceiverAccount("S1", "R_UNKNOWN"))
                .isFalse();
    }

    // ==============================================================
    // countByDatasetSource / countFraudByDatasetSource
    // ==============================================================

    @Test
    @DisplayName("Should count transactions and fraud by dataset source")
    void shouldCountByDatasetSource() {
        transactionRepository.save(aTransaction()
                .withDatasetSource(DatasetSource.PAYSIM).withExternalId("PS1").build());
        transactionRepository.save(aTransaction()
                .withDatasetSource(DatasetSource.PAYSIM).thatIsFraudulent().withExternalId("PS2").build());
        transactionRepository.save(aTransaction()
                .withDatasetSource(DatasetSource.IBM_AML).withExternalId("IBM1").build());

        assertThat(transactionRepository.countByDatasetSource(DatasetSource.PAYSIM)).isEqualTo(2);
        assertThat(transactionRepository.countFraudByDatasetSource(DatasetSource.PAYSIM)).isEqualTo(1);
        assertThat(transactionRepository.countByDatasetSource(DatasetSource.IBM_AML)).isEqualTo(1);
    }
}
