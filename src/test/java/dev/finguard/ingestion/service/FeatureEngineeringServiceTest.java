package dev.finguard.ingestion.service;

import dev.finguard.domain.enums.TransactionType;
import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.model.TransactionFeatures;
import dev.finguard.domain.repository.TransactionFeaturesRepository;
import dev.finguard.domain.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static dev.finguard.testutil.TestTransactionBuilder.aTransaction;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FeatureEngineeringService")
class FeatureEngineeringServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private TransactionFeaturesRepository featuresRepository;

    @Mock
    private FeatureEngineeringBatchService batchSaver;

    @InjectMocks
    private FeatureEngineeringService service;

    private static final LocalDateTime TX_TIME = LocalDateTime.of(2025, 6, 15, 14, 30);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "batchSize", 100);
    }

    /**
     * Stub the two new combined queries and existsPriorTransfer for a sender with no history.
     * velocity: [count_24h=0, count_1h=0]
     * stats7d:  [avg=0, stddev=0.0, diversity=0]
     */
    private void stubEmptyHistory(String sender) {
        when(transactionRepository.findVelocityCounts(
                eq(sender), any(), any(), any()))
                .thenReturn(Collections.singletonList(new Object[]{0L, 0L}));
        when(transactionRepository.findSenderStats7d(
                eq(sender), any(), any()))
                .thenReturn(Collections.singletonList(new Object[]{BigDecimal.ZERO, 0.0, 0L}));
        when(transactionRepository.existsPriorTransfer(
                eq(sender), any(), any())).thenReturn(false);
    }

    // ==============================================================
    // Temporal features
    // ==============================================================

    @Nested
    @DisplayName("Temporal features")
    class TemporalFeatures {

        @Test
        @DisplayName("Should extract hour of day from transaction timestamp")
        void computeFeatures_shouldExtractHourOfDay() {
            Transaction tx = aTransaction().at(LocalDateTime.of(2025, 3, 10, 3, 15)).from("S1").build();
            stubEmptyHistory("S1");

            TransactionFeatures result = service.computeFeatures(tx);

            assertThat(result.getHourOfDay()).isEqualTo((short) 3);
        }

        @Test
        @DisplayName("Should extract day of week (ISO: Monday=1, Sunday=7)")
        void computeFeatures_shouldExtractDayOfWeek() {
            // 2025-06-15 is a Sunday (ISO day 7)
            Transaction tx = aTransaction().at(TX_TIME).from("S1").build();
            stubEmptyHistory("S1");

            TransactionFeatures result = service.computeFeatures(tx);

            assertThat(result.getDayOfWeek()).isEqualTo((short) 7);
        }

        @Test
        @DisplayName("Should handle midnight edge case (hour 0)")
        void computeFeatures_shouldHandleMidnight() {
            Transaction tx = aTransaction().at(LocalDateTime.of(2025, 1, 1, 0, 0)).from("S1").build();
            stubEmptyHistory("S1");

            TransactionFeatures result = service.computeFeatures(tx);

            assertThat(result.getHourOfDay()).isEqualTo((short) 0);
        }
    }

    // ==============================================================
    // Velocity features
    // ==============================================================

    @Nested
    @DisplayName("Velocity features")
    class VelocityFeatures {

        @Test
        @DisplayName("Should count transactions in 1h and 24h windows")
        void computeFeatures_shouldCountVelocity() {
            Transaction tx = aTransaction().at(TX_TIME).from("SENDER_A").build();

            // findVelocityCounts returns [count_24h, count_1h] directly — no object materialization
            when(transactionRepository.findVelocityCounts(
                    eq("SENDER_A"), eq(TX_TIME.minusHours(24)), eq(TX_TIME.minusNanos(1_000)),
                    eq(TX_TIME.minusHours(1))))
                    .thenReturn(Collections.singletonList(new Object[]{5L, 2L}));
            when(transactionRepository.findSenderStats7d(eq("SENDER_A"), any(), any()))
                    .thenReturn(Collections.singletonList(new Object[]{BigDecimal.ZERO, 0.0, 0L}));
            when(transactionRepository.existsPriorTransfer(eq("SENDER_A"), any(), any())).thenReturn(false);

            TransactionFeatures result = service.computeFeatures(tx);

            assertThat(result.getTxVelocity1h()).isEqualTo(2);
            assertThat(result.getTxVelocity24h()).isEqualTo(5);
        }

        @Test
        @DisplayName("Should return zero velocity when no prior history exists")
        void computeFeatures_shouldReturnZeroVelocity_whenNoHistory() {
            Transaction tx = aTransaction().at(TX_TIME).from("NEW_SENDER").build();
            stubEmptyHistory("NEW_SENDER");

            TransactionFeatures result = service.computeFeatures(tx);

            assertThat(result.getTxVelocity1h()).isZero();
            assertThat(result.getTxVelocity24h()).isZero();
        }
    }

    // ==============================================================
    // Amount statistics
    // ==============================================================

    @Nested
    @DisplayName("Amount statistics (z-score, avg, ratio)")
    class AmountStatistics {

        @Test
        @DisplayName("Should compute z-score = (amount - mean) / stddev")
        void computeFeatures_shouldComputeZScore() {
            Transaction tx = aTransaction().withAmount("150").at(TX_TIME).from("S1").build();

            // findSenderStats7d returns [avg=100, stddev=25.0 (Double), diversity=0]
            when(transactionRepository.findVelocityCounts(eq("S1"), any(), any(), any()))
                    .thenReturn(Collections.singletonList(new Object[]{0L, 0L}));
            when(transactionRepository.findSenderStats7d(eq("S1"), any(), any()))
                    .thenReturn(Collections.singletonList(new Object[]{new BigDecimal("100"), 25.0, 0L}));

            TransactionFeatures result = service.computeFeatures(tx);

            // z = (150 - 100) / 25 = 2.0
            assertThat(result.getAmountZscore()).isCloseTo(2.0, within(0.001));
        }

        @Test
        @DisplayName("Should default z-score to 0 when stddev is zero")
        void computeFeatures_shouldDefaultZScore_whenStddevIsZero() {
            Transaction tx = aTransaction().withAmount("500").at(TX_TIME).from("S1").build();

            when(transactionRepository.findVelocityCounts(eq("S1"), any(), any(), any()))
                    .thenReturn(Collections.singletonList(new Object[]{0L, 0L}));
            when(transactionRepository.findSenderStats7d(eq("S1"), any(), any()))
                    .thenReturn(Collections.singletonList(new Object[]{new BigDecimal("500"), 0.0, 0L}));

            TransactionFeatures result = service.computeFeatures(tx);

            assertThat(result.getAmountZscore()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Should default z-score to 0 when no historical data exists")
        void computeFeatures_shouldDefaultZScore_whenNoHistory() {
            Transaction tx = aTransaction().at(TX_TIME).from("NEW").build();
            stubEmptyHistory("NEW");

            TransactionFeatures result = service.computeFeatures(tx);

            assertThat(result.getAmountZscore()).isEqualTo(0.0);
            assertThat(result.getAvgAmount7d()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(result.getAmountRatioToAvg()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Should compute amount ratio to 7-day average")
        void computeFeatures_shouldComputeAmountRatio() {
            Transaction tx = aTransaction().withAmount("300").at(TX_TIME).from("S1").build();

            when(transactionRepository.findVelocityCounts(eq("S1"), any(), any(), any()))
                    .thenReturn(Collections.singletonList(new Object[]{0L, 0L}));
            when(transactionRepository.findSenderStats7d(eq("S1"), any(), any()))
                    .thenReturn(Collections.singletonList(new Object[]{new BigDecimal("100"), 50.0, 0L}));

            TransactionFeatures result = service.computeFeatures(tx);

            // ratio = 300 / 100 = 3.0
            assertThat(result.getAmountRatioToAvg()).isCloseTo(3.0, within(0.001));
        }
    }

    // ==============================================================
    // Balance change ratio
    // ==============================================================

    @Nested
    @DisplayName("Balance change ratio")
    class BalanceChangeRatio {

        @Test
        @DisplayName("Should compute ratio = amount / senderBalanceBefore")
        void computeFeatures_shouldComputeBalanceRatio() {
            Transaction tx = aTransaction()
                    .withAmount("10000")
                    .withSenderBalanceBefore("50000")
                    .at(TX_TIME).from("S1").build();
            stubEmptyHistory("S1");

            TransactionFeatures result = service.computeFeatures(tx);

            // 10000 / 50000 = 0.2
            assertThat(result.getBalanceChangeRatio()).isCloseTo(0.2, within(0.001));
        }

        @Test
        @DisplayName("Should default to 0 when sender balance is zero")
        void computeFeatures_shouldDefaultToZero_whenBalanceIsZero() {
            Transaction tx = aTransaction()
                    .withAmount("1000")
                    .withSenderBalanceBefore("0")
                    .at(TX_TIME).from("S1").build();
            stubEmptyHistory("S1");

            TransactionFeatures result = service.computeFeatures(tx);

            assertThat(result.getBalanceChangeRatio()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Should default to 0 when sender balance is null")
        void computeFeatures_shouldDefaultToZero_whenBalanceIsNull() {
            Transaction tx = aTransaction().withAmount("1000").at(TX_TIME).from("S1").build();
            tx.setSenderBalanceBefore(null);
            stubEmptyHistory("S1");

            TransactionFeatures result = service.computeFeatures(tx);

            assertThat(result.getBalanceChangeRatio()).isEqualTo(0.0);
        }
    }

    // ==============================================================
    // Receiver diversity & new receiver
    // ==============================================================

    @Nested
    @DisplayName("Receiver features")
    class ReceiverFeatures {

        @Test
        @DisplayName("Should record receiver diversity count from repository")
        void computeFeatures_shouldRecordReceiverDiversity() {
            Transaction tx = aTransaction().at(TX_TIME).from("S1").build();

            when(transactionRepository.findVelocityCounts(eq("S1"), any(), any(), any()))
                    .thenReturn(Collections.singletonList(new Object[]{0L, 0L}));
            // diversity=7 is in position [2] of the combined stats result
            when(transactionRepository.findSenderStats7d(eq("S1"), any(), any()))
                    .thenReturn(Collections.singletonList(new Object[]{BigDecimal.ZERO, 0.0, 7L}));

            TransactionFeatures result = service.computeFeatures(tx);

            assertThat(result.getReceiverDiversity7d()).isEqualTo(7);
        }

        @Test
        @DisplayName("Should flag as new receiver when no prior history to same receiver")
        void computeFeatures_shouldFlagNewReceiver_whenFirstTimePair() {
            Transaction tx = aTransaction().at(TX_TIME).from("S1").to("R_NEW").build();
            stubEmptyHistory("S1");

            TransactionFeatures result = service.computeFeatures(tx);

            assertThat(result.getIsNewReceiver()).isTrue();
        }

        @Test
        @DisplayName("Should NOT flag as new receiver when prior transactions exist to same receiver")
        void computeFeatures_shouldNotFlagNewReceiver_whenPriorPairExists() {
            Transaction tx = aTransaction().at(TX_TIME).from("S1").to("R_KNOWN").build();

            when(transactionRepository.findVelocityCounts(eq("S1"), any(), any(), any()))
                    .thenReturn(Collections.singletonList(new Object[]{0L, 0L}));
            when(transactionRepository.findSenderStats7d(eq("S1"), any(), any()))
                    .thenReturn(Collections.singletonList(new Object[]{BigDecimal.ZERO, 0.0, 1L}));
            // Prior transfer to same receiver exists
            when(transactionRepository.existsPriorTransfer(eq("S1"), eq("R_KNOWN"), eq(TX_TIME)))
                    .thenReturn(true);

            TransactionFeatures result = service.computeFeatures(tx);

            assertThat(result.getIsNewReceiver()).isFalse();
        }
    }

    // ==============================================================
    // Round amount & high-risk type
    // ==============================================================

    @Nested
    @DisplayName("Derived features")
    class DerivedFeatures {

        @ParameterizedTest
        @CsvSource({
                "10000.00, true",
                "5000.00, true",
                "1000.00, true",
                "99000.00, true",
                "1500.00, false",
                "999.99, false",
                "0.00, false",
                "10000.50, false"
        })
        @DisplayName("Should detect round amounts (multiples of 1000)")
        void computeFeatures_shouldDetectRoundAmounts(String amount, boolean expected) {
            Transaction tx = aTransaction().withAmount(amount).at(TX_TIME).from("S1").build();
            stubEmptyHistory("S1");

            TransactionFeatures result = service.computeFeatures(tx);

            assertThat(result.getIsRoundAmount()).isEqualTo(expected);
        }

        @ParameterizedTest
        @CsvSource({
                "TRANSFER, true",
                "CASH_OUT, true",
                "PAYMENT, false",
                "CASH_IN, false",
                "DEBIT, false"
        })
        @DisplayName("Should flag TRANSFER and CASH_OUT as high-risk types")
        void computeFeatures_shouldFlagHighRiskTypes(String type, boolean expected) {
            Transaction tx = aTransaction()
                    .withType(TransactionType.valueOf(type))
                    .at(TX_TIME).from("S1").build();
            stubEmptyHistory("S1");

            TransactionFeatures result = service.computeFeatures(tx);

            assertThat(result.getIsHighRiskType()).isEqualTo(expected);
        }
    }

    // ==============================================================
    // Feature vector JSON
    // ==============================================================

    @Test
    @DisplayName("Should produce valid JSON feature vector containing all keys")
    void computeFeatures_shouldBuildFeatureVectorJson() {
        Transaction tx = aTransaction().withAmount("5000").at(TX_TIME).from("S1").build();
        stubEmptyHistory("S1");

        TransactionFeatures result = service.computeFeatures(tx);

        String json = result.getFeatureVector();
        assertThat(json).isNotNull();
        assertThat(json).contains("\"amount\":");
        assertThat(json).contains("\"amountZscore\":");
        assertThat(json).contains("\"txVelocity1h\":");
        assertThat(json).contains("\"txVelocity24h\":");
        assertThat(json).contains("\"avgAmount7d\":");
        assertThat(json).contains("\"balanceChangeRatio\":");
        assertThat(json).contains("\"receiverDiversity7d\":");
        assertThat(json).contains("\"isNewReceiver\":");
        assertThat(json).contains("\"hourOfDay\":");
        assertThat(json).contains("\"dayOfWeek\":");
        assertThat(json).contains("\"isRoundAmount\":");
        assertThat(json).contains("\"isHighRiskType\":");
    }

    // ==============================================================
    // computeAllFeatures() batch processing
    // ==============================================================

    @Nested
    @DisplayName("computeAllFeatures() batch processing")
    class ComputeAllFeatures {

        @Test
        @DisplayName("Should process transactions that do not have features yet")
        void computeAllFeatures_shouldProcessAllWithoutFeatures() {
            Transaction tx1 = aTransaction().withId(1L).at(TX_TIME).from("S1").build();
            Transaction tx2 = aTransaction().withId(2L).at(TX_TIME).from("S2").build();

            when(transactionRepository.findWithoutFeaturesAfterId(eq(0L), any(PageRequest.class)))
                    .thenReturn(List.of(tx1, tx2));
            when(transactionRepository.findWithoutFeaturesAfterId(eq(2L), any(PageRequest.class)))
                    .thenReturn(Collections.emptyList());

            stubEmptyHistory("S1");
            stubEmptyHistory("S2");

            long computed = service.computeAllFeatures(FeatureEngineeringService.MAX_PER_ROW_LIMIT);

            assertThat(computed).isEqualTo(2);
            // Each batch is saved via batchSaver (REQUIRES_NEW), not directly via featuresRepository
            verify(batchSaver, times(1)).saveBatch(anyList());
        }

        @Test
        @DisplayName("Should return 0 when all transactions already have features")
        void computeAllFeatures_shouldSkipExisting() {
            when(transactionRepository.findWithoutFeaturesAfterId(eq(0L), any(PageRequest.class)))
                    .thenReturn(Collections.emptyList());

            long computed = service.computeAllFeatures(FeatureEngineeringService.MAX_PER_ROW_LIMIT);

            assertThat(computed).isZero();
            verify(batchSaver, never()).saveBatch(any());
        }

        @Test
        @DisplayName("Should return 0 when no transactions exist")
        void computeAllFeatures_shouldReturnZero_whenEmpty() {
            when(transactionRepository.findWithoutFeaturesAfterId(eq(0L), any(PageRequest.class)))
                    .thenReturn(Collections.emptyList());

            long computed = service.computeAllFeatures(FeatureEngineeringService.MAX_PER_ROW_LIMIT);

            assertThat(computed).isZero();
        }

        @Test
        @DisplayName("Should respect limit parameter")
        void computeAllFeatures_shouldRespectLimit() {
            Transaction tx1 = aTransaction().withId(1L).at(TX_TIME).from("S1").build();

            when(transactionRepository.findWithoutFeaturesAfterId(eq(0L), any(PageRequest.class)))
                    .thenReturn(List.of(tx1));

            stubEmptyHistory("S1");

            long computed = service.computeAllFeatures(1L);

            assertThat(computed).isEqualTo(1);
            verify(batchSaver, times(1)).saveBatch(anyList());
        }
    }
}
