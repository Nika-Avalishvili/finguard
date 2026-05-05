package dev.finguard.evaluation.metrics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

/**
 * Unit tests for {@link DetectionMetrics#computeAuc(double[], boolean[])}.
 *
 * <p>Verifies the classic edge cases a reviewer will probe for (all-positive,
 * all-negative, perfect separation, random, tied scores) and confirms that
 * our trapezoidal implementation matches the scikit-learn-equivalent outputs
 * on the small reference vectors below.</p>
 */
@DisplayName("DetectionMetrics AUC computation")
class DetectionMetricsAucTest {

    private final DetectionMetrics metrics = new DetectionMetrics();

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("empty input returns NaN for both AUCs")
        void emptyInput() {
            DetectionMetrics.AucResult r = metrics.computeAuc(new double[0], new boolean[0]);
            assertThat(r.aucRoc()).isNaN();
            assertThat(r.aucPr()).isNaN();
        }

        @Test
        @DisplayName("all positives → ROC undefined, PR = 1.0 (perfect)")
        void allPositives() {
            double[] scores = {0.1, 0.5, 0.9};
            boolean[] labels = {true, true, true};
            DetectionMetrics.AucResult r = metrics.computeAuc(scores, labels);
            assertThat(r.aucRoc()).isNaN();
            assertThat(r.aucPr()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("all negatives → ROC undefined, PR = 0.0")
        void allNegatives() {
            double[] scores = {0.1, 0.5, 0.9};
            boolean[] labels = {false, false, false};
            DetectionMetrics.AucResult r = metrics.computeAuc(scores, labels);
            assertThat(r.aucRoc()).isNaN();
            assertThat(r.aucPr()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("mismatched array lengths throws IllegalArgumentException")
        void mismatchedLengths() {
            assertThat(
                    org.junit.jupiter.api.Assertions.assertThrows(
                            IllegalArgumentException.class,
                            () -> metrics.computeAuc(new double[]{0.1}, new boolean[]{true, false})))
                    .hasMessageContaining("equal length");
        }
    }

    @Nested
    @DisplayName("Classic reference cases")
    class ReferenceCases {

        @Test
        @DisplayName("perfect separation: all positives score above all negatives → AUC-ROC = AUC-PR = 1.0")
        void perfectSeparation() {
            double[] scores = {0.9, 0.8, 0.7, 0.3, 0.2, 0.1};
            boolean[] labels = {true, true, true, false, false, false};
            DetectionMetrics.AucResult r = metrics.computeAuc(scores, labels);
            assertThat(r.aucRoc()).isEqualTo(1.0);
            assertThat(r.aucPr()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("inverse separation: all positives score below all negatives → AUC-ROC = 0")
        void inverseSeparation() {
            double[] scores = {0.1, 0.2, 0.3, 0.7, 0.8, 0.9};
            boolean[] labels = {true, true, true, false, false, false};
            DetectionMetrics.AucResult r = metrics.computeAuc(scores, labels);
            assertThat(r.aucRoc()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("all scores tied → AUC-ROC = 0.5 (random ranking)")
        void tiedScores() {
            double[] scores = {0.5, 0.5, 0.5, 0.5, 0.5, 0.5};
            boolean[] labels = {true, false, true, false, true, false};
            DetectionMetrics.AucResult r = metrics.computeAuc(scores, labels);
            assertThat(r.aucRoc()).isCloseTo(0.5, offset(1e-9));
        }

        @Test
        @DisplayName("realistic mixed case: AUC-ROC > AUC-PR when class-imbalanced")
        void imbalancedMixed() {
            // 2 positives out of 10 → 20% base rate.
            // Top 3 scores include both positives; ranking is good but not perfect.
            double[] scores = {0.9, 0.8, 0.75, 0.6, 0.5, 0.4, 0.3, 0.2, 0.15, 0.1};
            boolean[] labels = {true, false, true, false, false, false, false, false, false, false};
            DetectionMetrics.AucResult r = metrics.computeAuc(scores, labels);
            assertThat(r.aucRoc()).isCloseTo(0.9375, offset(0.01));  // 15/16
            assertThat(r.aucPr()).isGreaterThan(0.75);
            assertThat(r.aucPr()).isLessThanOrEqualTo(1.0);
        }
    }
}
