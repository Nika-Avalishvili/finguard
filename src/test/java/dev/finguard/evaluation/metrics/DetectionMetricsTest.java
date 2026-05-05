package dev.finguard.evaluation.metrics;

import dev.finguard.domain.enums.DetectionConfig;
import dev.finguard.domain.model.Alert;
import dev.finguard.domain.model.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("DetectionMetrics")
class DetectionMetricsTest {

    private DetectionMetrics detectionMetrics;

    @BeforeEach
    void setUp() {
        detectionMetrics = new DetectionMetrics();
    }

    @Nested
    @DisplayName("Confusion Matrix Computation")
    class ConfusionMatrix {

        @Test
        @DisplayName("Should compute perfect detection (all fraud detected, no false positives)")
        void perfectDetection() {
            // 3 fraud, 2 legit — all correctly classified
            List<Transaction> transactions = List.of(
                    fraud(1L), fraud(2L), fraud(3L), legit(4L), legit(5L));
            List<Alert> alerts = List.of(
                    alert(1L), alert(2L), alert(3L));

            var result = detectionMetrics.compute(transactions, alerts, DetectionConfig.RULES_ONLY);

            assertThat(result.truePositives()).isEqualTo(3);
            assertThat(result.falsePositives()).isEqualTo(0);
            assertThat(result.trueNegatives()).isEqualTo(2);
            assertThat(result.falseNegatives()).isEqualTo(0);
            assertThat(result.precision()).isEqualTo(1.0);
            assertThat(result.recall()).isEqualTo(1.0);
            assertThat(result.f1Score()).isEqualTo(1.0);
            assertThat(result.falsePositiveRate()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Should compute zero detection (all fraud missed)")
        void zeroDetection() {
            List<Transaction> transactions = List.of(
                    fraud(1L), fraud(2L), legit(3L));
            List<Alert> alerts = List.of(); // No alerts generated

            var result = detectionMetrics.compute(transactions, alerts, DetectionConfig.ML_ONLY);

            assertThat(result.truePositives()).isEqualTo(0);
            assertThat(result.falseNegatives()).isEqualTo(2);
            assertThat(result.trueNegatives()).isEqualTo(1);
            assertThat(result.precision()).isEqualTo(0.0);
            assertThat(result.recall()).isEqualTo(0.0);
            assertThat(result.f1Score()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Should compute all-positive detection (flag everything)")
        void allPositive() {
            List<Transaction> transactions = List.of(
                    fraud(1L), legit(2L), legit(3L), legit(4L));
            List<Alert> alerts = List.of(
                    alert(1L), alert(2L), alert(3L), alert(4L));

            var result = detectionMetrics.compute(transactions, alerts, DetectionConfig.RULES_ONLY);

            assertThat(result.truePositives()).isEqualTo(1);
            assertThat(result.falsePositives()).isEqualTo(3);
            assertThat(result.precision()).isCloseTo(0.25, within(0.001));
            assertThat(result.recall()).isEqualTo(1.0);
            assertThat(result.falsePositiveRate()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Should handle mixed results correctly")
        void mixedResults() {
            // 5 fraud, 5 legit: detect 3 fraud + 1 false positive
            List<Transaction> transactions = List.of(
                    fraud(1L), fraud(2L), fraud(3L), fraud(4L), fraud(5L),
                    legit(6L), legit(7L), legit(8L), legit(9L), legit(10L));
            List<Alert> alerts = List.of(
                    alert(1L), alert(2L), alert(3L), alert(6L)); // 3 TP + 1 FP

            var result = detectionMetrics.compute(transactions, alerts, DetectionConfig.ML_LLM_RAG);

            assertThat(result.truePositives()).isEqualTo(3);
            assertThat(result.falsePositives()).isEqualTo(1);
            assertThat(result.falseNegatives()).isEqualTo(2);
            assertThat(result.trueNegatives()).isEqualTo(4);
            assertThat(result.precision()).isCloseTo(0.75, within(0.001));  // 3/4
            assertThat(result.recall()).isCloseTo(0.6, within(0.001));     // 3/5
            assertThat(result.f1Score()).isCloseTo(0.6667, within(0.001)); // 2*0.75*0.6/(0.75+0.6)
            assertThat(result.falsePositiveRate()).isCloseTo(0.2, within(0.001)); // 1/5
        }

        @Test
        @DisplayName("Should handle empty transaction list")
        void emptyTransactions() {
            var result = detectionMetrics.compute(List.of(), List.of(), DetectionConfig.RULES_ONLY);

            assertThat(result.totalTransactions()).isEqualTo(0);
            assertThat(result.precision()).isEqualTo(0.0);
            assertThat(result.recall()).isEqualTo(0.0);
            assertThat(result.f1Score()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Should compute correct actual fraud and legit counts")
        void actualCounts() {
            List<Transaction> transactions = List.of(
                    fraud(1L), fraud(2L), legit(3L), legit(4L), legit(5L));
            List<Alert> alerts = List.of(alert(1L));

            var result = detectionMetrics.compute(transactions, alerts, DetectionConfig.RULES_ONLY);

            assertThat(result.actualFrauds()).isEqualTo(2);
            assertThat(result.actualLegitimate()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("Threshold-based Metrics")
    class ThresholdMetrics {

        @Test
        @DisplayName("Should compute metrics at multiple thresholds")
        void metricsAtThresholds() {
            List<Transaction> transactions = List.of(
                    fraud(1L), fraud(2L), legit(3L), legit(4L));

            // Alerts with different ML risk scores
            Alert a1 = alertWithScore(1L, 0.9);  // TP
            Alert a2 = alertWithScore(2L, 0.7);  // TP
            Alert a3 = alertWithScore(3L, 0.6);  // FP
            Alert a4 = alertWithScore(4L, 0.3);  // FP
            List<Alert> alerts = List.of(a1, a2, a3, a4);

            List<Double> thresholds = List.of(0.5, 0.8);

            Map<Double, DetectionMetrics.MetricsResult> results =
                    detectionMetrics.computeAtThresholds(transactions, alerts, thresholds, DetectionConfig.ML_ONLY);

            // At threshold 0.5: alerts 1,2,3 qualify → 2 TP + 1 FP
            assertThat(results.get(0.5).truePositives()).isEqualTo(2);
            assertThat(results.get(0.5).falsePositives()).isEqualTo(1);

            // At threshold 0.8: only alert 1 qualifies → 1 TP + 0 FP
            assertThat(results.get(0.8).truePositives()).isEqualTo(1);
            assertThat(results.get(0.8).falsePositives()).isEqualTo(0);
        }
    }

    // ================================================================
    // Helpers
    // ================================================================

    private Transaction fraud(Long id) {
        Transaction tx = new Transaction();
        tx.setId(id);
        tx.setIsFraud(true);
        return tx;
    }

    private Transaction legit(Long id) {
        Transaction tx = new Transaction();
        tx.setId(id);
        tx.setIsFraud(false);
        return tx;
    }

    private Alert alert(Long transactionId) {
        Transaction tx = new Transaction();
        tx.setId(transactionId);

        Alert alert = new Alert();
        alert.setTransaction(tx);
        alert.setIsAnomaly(true);
        return alert;
    }

    private Alert alertWithScore(Long transactionId, double score) {
        Alert a = alert(transactionId);
        a.setMlRiskScore(score);
        return a;
    }

    /**
     * Aggregate-only metric path. No entity materialisation — these are the only
     * test cases that prove the formula still holds when we skip the
     * {@code List<Alert>} round-trip.
     */
    @Nested
    @DisplayName("computeFromAggregates (OOM-safe path)")
    class AggregateMetrics {

        @Test
        @DisplayName("Perfect detection: all fraud flagged, no false positives")
        void perfectDetection() {
            // 100 total, 10 fraud. 10 alerts, all correct (TP = 10).
            var r = detectionMetrics.computeFromAggregates(
                    DetectionConfig.RULES_ONLY, 100L, 10L, 10L, 10L);
            assertThat(r.precision()).isEqualTo(1.0);
            assertThat(r.recall()).isEqualTo(1.0);
            assertThat(r.f1Score()).isEqualTo(1.0);
            assertThat(r.falsePositiveRate()).isEqualTo(0.0);
            assertThat(r.truePositives()).isEqualTo(10L);
            assertThat(r.falsePositives()).isZero();
            assertThat(r.falseNegatives()).isZero();
            assertThat(r.trueNegatives()).isEqualTo(90L);
        }

        @Test
        @DisplayName("Mixed case: matches closed-form confusion matrix")
        void mixedCase() {
            // 1000 total, 50 fraud. 80 alerts, 40 of them TP.
            var r = detectionMetrics.computeFromAggregates(
                    DetectionConfig.ML_ONLY, 1000L, 50L, 80L, 40L);
            // TP=40, FP=40, FN=10, TN=910
            assertThat(r.truePositives()).isEqualTo(40L);
            assertThat(r.falsePositives()).isEqualTo(40L);
            assertThat(r.falseNegatives()).isEqualTo(10L);
            assertThat(r.trueNegatives()).isEqualTo(910L);
            assertThat(r.precision()).isEqualTo(0.5);
            assertThat(r.recall()).isEqualTo(0.8);
            assertThat(r.f1Score()).isCloseTo(0.615384, within(0.001));
            assertThat(r.falsePositiveRate()).isCloseTo(40.0 / 950.0, within(0.001));
        }

        @Test
        @DisplayName("No alerts: metrics are all zero, not NaN")
        void zeroAlerts() {
            var r = detectionMetrics.computeFromAggregates(
                    DetectionConfig.RULES_ONLY, 1000L, 50L, 0L, 0L);
            assertThat(r.precision()).isEqualTo(0.0);
            assertThat(r.recall()).isEqualTo(0.0);
            assertThat(r.f1Score()).isEqualTo(0.0);
            assertThat(r.falsePositiveRate()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("No fraud in the test set: recall stays zero, precision defined from FP")
        void zeroFraud() {
            var r = detectionMetrics.computeFromAggregates(
                    DetectionConfig.FULL_SYSTEM, 1000L, 0L, 20L, 0L);
            // All 20 alerts are false positives.
            assertThat(r.truePositives()).isZero();
            assertThat(r.falsePositives()).isEqualTo(20L);
            assertThat(r.trueNegatives()).isEqualTo(980L);
            assertThat(r.precision()).isEqualTo(0.0);
            assertThat(r.recall()).isEqualTo(0.0);       // no fraud → undefined, reported as 0
            assertThat(r.falsePositiveRate()).isCloseTo(0.02, within(0.001));
        }

        @Test
        @DisplayName("Clamps: TP > fraud would make FN negative — guard keeps it zero")
        void defensiveClampAgainstDataInconsistency() {
            // Caller passes TP > fraudTx (possible if alerts + transactions are out of sync).
            // computeFromAggregates must not produce negative FN/TN via arithmetic underflow.
            var r = detectionMetrics.computeFromAggregates(
                    DetectionConfig.ML_ONLY, 100L, 5L, 20L, 10L);
            assertThat(r.falseNegatives()).isZero();
            assertThat(r.trueNegatives()).isNotNegative();
        }

        @Test
        @DisplayName("Agrees with computeFromCounts on the same input")
        void agreesWithListPath() {
            // Construct a list path that should produce identical metrics.
            List<Alert> alerts = List.of(
                    alert(1L), alert(2L), alert(3L), alert(4L));
            // TPs: tx ids 1, 2 — mark those transactions as fraud.
            alerts.get(0).getTransaction().setIsFraud(true);
            alerts.get(1).getTransaction().setIsFraud(true);
            alerts.get(2).getTransaction().setIsFraud(false);
            alerts.get(3).getTransaction().setIsFraud(false);

            var fromList = detectionMetrics.computeFromCounts(
                    DetectionConfig.ML_ONLY, 100L, 5L, alerts);
            var fromCounts = detectionMetrics.computeFromAggregates(
                    DetectionConfig.ML_ONLY, 100L, 5L, 4L, 2L);

            assertThat(fromCounts.precision()).isEqualTo(fromList.precision());
            assertThat(fromCounts.recall()).isEqualTo(fromList.recall());
            assertThat(fromCounts.f1Score()).isEqualTo(fromList.f1Score());
            assertThat(fromCounts.falsePositiveRate()).isEqualTo(fromList.falsePositiveRate());
            assertThat(fromCounts.truePositives()).isEqualTo(fromList.truePositives());
            assertThat(fromCounts.falsePositives()).isEqualTo(fromList.falsePositives());
        }
    }
}
