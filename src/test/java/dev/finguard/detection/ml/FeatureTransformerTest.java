package dev.finguard.detection.ml;

import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.model.TransactionFeatures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.tribuo.Example;
import org.tribuo.Feature;
import org.tribuo.classification.Label;

import java.math.BigDecimal;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FeatureTransformer")
class FeatureTransformerTest {

    private final FeatureTransformer transformer = new FeatureTransformer();

    @Test
    @DisplayName("Should create FRAUD-labeled example for fraud transaction")
    void toTrainingExample_fraudLabel() {
        Transaction tx = txWithAmount("50000.00", true);

        Example<Label> example = transformer.toTrainingExample(tx, null);

        assertThat(example.getOutput().getLabel()).isEqualTo("FRAUD");
    }

    @Test
    @DisplayName("Should create LEGIT-labeled example for non-fraud transaction")
    void toTrainingExample_legitLabel() {
        Transaction tx = txWithAmount("100.00", false);

        Example<Label> example = transformer.toTrainingExample(tx, null);

        assertThat(example.getOutput().getLabel()).isEqualTo("LEGIT");
    }

    @Test
    @DisplayName("Should create UNKNOWN-labeled example for prediction")
    void toPredictionExample_unknownLabel() {
        Transaction tx = txWithAmount("5000.00", false);

        Example<Label> example = transformer.toPredictionExample(tx, null);

        assertThat(example.getOutput().getLabel()).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("Should produce 13 features when TransactionFeatures is present")
    void shouldProduce13Features_withFeatures() {
        Transaction tx = txWithAmount("10000.00", false);
        TransactionFeatures features = fullFeatures();

        Example<Label> example = transformer.toTrainingExample(tx, features);

        assertThat(example.size()).isEqualTo(FeatureTransformer.FEATURE_NAMES.size());
    }

    @Test
    @DisplayName("Should produce 13 features even when TransactionFeatures is null")
    void shouldProduce13Features_withoutFeatures() {
        Transaction tx = txWithAmount("10000.00", false);

        Example<Label> example = transformer.toTrainingExample(tx, null);

        assertThat(example.size()).isEqualTo(FeatureTransformer.FEATURE_NAMES.size());
    }

    @Test
    @DisplayName("Should set amount feature from transaction")
    void shouldSetAmountFromTransaction() {
        Transaction tx = txWithAmount("42500.75", false);

        Example<Label> example = transformer.toPredictionExample(tx, null);
        Map<String, Double> featureMap = toFeatureMap(example);

        assertThat(featureMap.get("amount")).isEqualTo(42500.75);
    }

    @Test
    @DisplayName("Should correctly transform boolean features to 0.0/1.0")
    void shouldTransformBooleanFeatures() {
        Transaction tx = txWithAmount("5000.00", false);
        TransactionFeatures features = new TransactionFeatures();
        features.setIsNewReceiver(true);
        features.setIsRoundAmount(false);
        features.setIsHighRiskType(true);

        Example<Label> example = transformer.toPredictionExample(tx, features);
        Map<String, Double> featureMap = toFeatureMap(example);

        assertThat(featureMap.get("is_new_receiver")).isEqualTo(1.0);
        assertThat(featureMap.get("is_round_amount")).isEqualTo(0.0);
        assertThat(featureMap.get("is_high_risk_type")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Should use zero defaults for null feature values")
    void shouldUseZeroDefaults_forNullValues() {
        Transaction tx = txWithAmount("5000.00", false);
        TransactionFeatures features = new TransactionFeatures();
        // All fields null except what TransactionFeatures defaults to

        Example<Label> example = transformer.toPredictionExample(tx, features);
        Map<String, Double> featureMap = toFeatureMap(example);

        assertThat(featureMap.get("amount_zscore")).isEqualTo(0.0);
        assertThat(featureMap.get("tx_velocity_1h")).isEqualTo(0.0);
        assertThat(featureMap.get("avg_amount_7d")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Should correctly map velocity features")
    void shouldMapVelocityFeatures() {
        Transaction tx = txWithAmount("1000.00", false);
        TransactionFeatures features = new TransactionFeatures();
        features.setTxVelocity1h(5);
        features.setTxVelocity24h(12);

        Example<Label> example = transformer.toPredictionExample(tx, features);
        Map<String, Double> featureMap = toFeatureMap(example);

        assertThat(featureMap.get("tx_velocity_1h")).isEqualTo(5.0);
        assertThat(featureMap.get("tx_velocity_24h")).isEqualTo(12.0);
    }

    @Test
    @DisplayName("Should handle null transaction amount gracefully")
    void shouldHandleNullAmount() {
        Transaction tx = new Transaction();
        tx.setAmount(null);
        tx.setIsFraud(false);

        Example<Label> example = transformer.toPredictionExample(tx, null);
        Map<String, Double> featureMap = toFeatureMap(example);

        assertThat(featureMap.get("amount")).isEqualTo(0.0);
    }

    // ================================================================
    // Helpers
    // ================================================================

    private Transaction txWithAmount(String amount, boolean fraud) {
        Transaction tx = new Transaction();
        tx.setAmount(new BigDecimal(amount));
        tx.setIsFraud(fraud);
        return tx;
    }

    private TransactionFeatures fullFeatures() {
        TransactionFeatures f = new TransactionFeatures();
        f.setAmountZscore(2.5);
        f.setTxVelocity1h(3);
        f.setTxVelocity24h(10);
        f.setAvgAmount7d(new BigDecimal("5000.00"));
        f.setAmountRatioToAvg(2.0);
        f.setBalanceChangeRatio(0.8);
        f.setIsNewReceiver(true);
        f.setReceiverDiversity7d(5);
        f.setHourOfDay((short) 14);
        f.setDayOfWeek((short) 3);
        f.setIsRoundAmount(true);
        f.setIsHighRiskType(false);
        return f;
    }

    private Map<String, Double> toFeatureMap(Example<Label> example) {
        return StreamSupport.stream(example.spliterator(), false)
                .collect(Collectors.toMap(Feature::getName, Feature::getValue));
    }

    // ================================================================
    // NaN / Infinity guards
    // ================================================================

    @Nested
    @DisplayName("NaN and Infinity sanitization")
    class SafeValueTests {

        @Test
        @DisplayName("NaN in amountZscore is coerced to 0.0")
        void safe_shouldCoerceNaN_toZero() {
            Transaction tx = txWithAmount("5000.00", false);
            TransactionFeatures features = new TransactionFeatures();
            features.setAmountZscore(Double.NaN);

            Example<Label> example = transformer.toPredictionExample(tx, features);
            Map<String, Double> featureMap = toFeatureMap(example);

            assertThat(featureMap.get("amount_zscore")).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Positive Infinity in balanceChangeRatio is coerced to 0.0")
        void safe_shouldCoerceInfinity_toZero() {
            Transaction tx = txWithAmount("5000.00", false);
            TransactionFeatures features = new TransactionFeatures();
            features.setBalanceChangeRatio(Double.POSITIVE_INFINITY);

            Example<Label> example = transformer.toPredictionExample(tx, features);
            Map<String, Double> featureMap = toFeatureMap(example);

            assertThat(featureMap.get("balance_change_ratio")).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Negative Infinity in amountRatioToAvg is coerced to 0.0")
        void safe_shouldCoerceNegativeInfinity_toZero() {
            Transaction tx = txWithAmount("5000.00", false);
            TransactionFeatures features = new TransactionFeatures();
            features.setAmountRatioToAvg(Double.NEGATIVE_INFINITY);

            Example<Label> example = transformer.toPredictionExample(tx, features);
            Map<String, Double> featureMap = toFeatureMap(example);

            assertThat(featureMap.get("amount_ratio_to_avg")).isEqualTo(0.0);
        }

        @Test
        @DisplayName("toTrainingExampleFromValues sanitizes NaN amount to 0.0")
        void fromValues_shouldSanitizeNaN_inAmount() {
            Example<Label> example = transformer.toTrainingExampleFromValues(
                    false, Double.NaN,
                    1.5, 2, 5,
                    5000.0, 2.0, 0.8,
                    false, 3,
                    14, 3,
                    false, false);

            Map<String, Double> featureMap = toFeatureMap(example);
            assertThat(featureMap.get("amount")).isEqualTo(0.0);
        }

        @Test
        @DisplayName("toTrainingExampleFromValues sanitizes Infinity amountZscore to 0.0")
        void fromValues_shouldSanitizeInfinity_inAmountZscore() {
            Example<Label> example = transformer.toTrainingExampleFromValues(
                    false, 50000.0,
                    Double.POSITIVE_INFINITY, 2, 5,
                    5000.0, 2.0, 0.8,
                    false, 3,
                    14, 3,
                    false, false);

            Map<String, Double> featureMap = toFeatureMap(example);
            assertThat(featureMap.get("amount_zscore")).isEqualTo(0.0);
        }

        @Test
        @DisplayName("toTrainingExampleFromValues preserves valid finite doubles")
        void fromValues_shouldPreserveFiniteValues() {
            Example<Label> example = transformer.toTrainingExampleFromValues(
                    true, 99999.99,
                    3.5, 4, 10,
                    12000.0, 8.33, -0.5,
                    true, 7,
                    23, 6,
                    true, true);

            Map<String, Double> featureMap = toFeatureMap(example);
            assertThat(featureMap.get("amount")).isEqualTo(99999.99);
            assertThat(featureMap.get("amount_zscore")).isEqualTo(3.5);
            assertThat(featureMap.get("balance_change_ratio")).isEqualTo(-0.5);
        }
    }
}
