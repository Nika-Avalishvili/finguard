package dev.finguard.evaluation.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ExperimentRunner}'s fold aggregators
 * (mean, sample standard deviation).
 *
 * <p>These back the summary row persisted by the {@code runKFold} path
 * (audit A-1). The summary's {@code precisionStd/recallStd/f1Std/fprStd}
 * must be the Bessel-corrected (n-1) sample stddev — not the population
 * stddev — because folds are a sample from the (infinite) population of
 * random splits.</p>
 */
@DisplayName("ExperimentRunner fold aggregators")
class FoldAggregatorTest {

    @Nested
    @DisplayName("mean")
    class Mean {

        @Test
        @DisplayName("empty array → 0")
        void emptyArray_returnsZero() {
            assertThat(ExperimentRunner.mean(new double[0])).isEqualTo(0.0);
        }

        @Test
        @DisplayName("single value → that value")
        void singleValue_returnsValue() {
            assertThat(ExperimentRunner.mean(new double[]{0.75})).isEqualTo(0.75);
        }

        @Test
        @DisplayName("multiple values → arithmetic mean")
        void multipleValues_returnsMean() {
            double[] folds = {0.80, 0.85, 0.82, 0.88, 0.90};
            assertThat(ExperimentRunner.mean(folds)).isEqualTo(0.85, org.assertj.core.data.Offset.offset(1e-9));
        }

        @Test
        @DisplayName("all identical values → that value")
        void identicalValues_returnsValue() {
            assertThat(ExperimentRunner.mean(new double[]{0.5, 0.5, 0.5, 0.5}))
                    .isEqualTo(0.5);
        }
    }

    @Nested
    @DisplayName("stddev (sample, n-1)")
    class Stddev {

        @Test
        @DisplayName("empty array → 0 (no variance reportable)")
        void emptyArray_returnsZero() {
            assertThat(ExperimentRunner.stddev(new double[0])).isEqualTo(0.0);
        }

        @Test
        @DisplayName("single observation → 0 (n-1 denominator undefined, clamped to 0)")
        void singleObservation_returnsZero() {
            assertThat(ExperimentRunner.stddev(new double[]{0.75})).isEqualTo(0.0);
        }

        @Test
        @DisplayName("identical values → 0 (no variance)")
        void identicalValues_returnsZero() {
            assertThat(ExperimentRunner.stddev(new double[]{0.5, 0.5, 0.5, 0.5, 0.5}))
                    .isEqualTo(0.0);
        }

        @Test
        @DisplayName("two observations → sample stddev uses n-1 denominator")
        void twoObservations_usesBesselCorrection() {
            // values: 0.8, 1.0 → mean = 0.9 → sq diffs = 0.01 + 0.01 = 0.02
            // population stddev: sqrt(0.02/2) = 0.1
            // sample stddev:     sqrt(0.02/1) = 0.1414...
            double s = ExperimentRunner.stddev(new double[]{0.8, 1.0});
            assertThat(s).isEqualTo(Math.sqrt(0.02), org.assertj.core.data.Offset.offset(1e-9));
        }

        @Test
        @DisplayName("five-fold textbook example")
        void fiveFoldExample_matchesTextbook() {
            // Classic textbook example: values 2, 4, 4, 4, 5, 5, 7, 9
            // mean = 5; squared diffs = 9+1+1+1+0+0+4+16 = 32
            // sample stddev = sqrt(32/7) ≈ 2.138...
            double s = ExperimentRunner.stddev(new double[]{2, 4, 4, 4, 5, 5, 7, 9});
            assertThat(s).isEqualTo(Math.sqrt(32.0 / 7.0),
                    org.assertj.core.data.Offset.offset(1e-9));
        }
    }
}
