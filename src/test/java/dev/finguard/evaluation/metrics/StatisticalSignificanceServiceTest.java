package dev.finguard.evaluation.metrics;

import dev.finguard.domain.enums.DatasetSource;
import dev.finguard.domain.enums.DetectionConfig;
import dev.finguard.domain.model.ExperimentResult;
import dev.finguard.domain.repository.ExperimentResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StatisticalSignificanceService}.
 *
 * <p>Verifies the paired t-test math hooks into {@code Apache Commons Math}
 * correctly for the expected fold-count and config-count shapes, and that
 * Bonferroni correction is applied as {@code α / numPairs}.</p>
 */
@DisplayName("Statistical significance service")
class StatisticalSignificanceServiceTest {

    private ExperimentResultRepository repo;
    private StatisticalSignificanceService service;

    @BeforeEach
    void setUp() {
        repo = mock(ExperimentResultRepository.class);
        service = new StatisticalSignificanceService(repo);
    }

    @Nested
    @DisplayName("compare(experimentName)")
    class Compare {

        @Test
        @DisplayName("returns one PairwiseResult per metric, pair for a 2-config × 5-fold experiment")
        void twoConfigsFiveFolds() {
            List<ExperimentResult> rows = new ArrayList<>();
            // ML_ONLY: F1 = 0.80, 0.82, 0.81, 0.83, 0.79
            rows.addAll(foldsFor(DetectionConfig.ML_ONLY,
                    new double[]{0.80, 0.82, 0.81, 0.83, 0.79}));
            // FULL_SYSTEM: F1 = 0.88, 0.90, 0.89, 0.91, 0.87 — clearly higher
            rows.addAll(foldsFor(DetectionConfig.FULL_SYSTEM,
                    new double[]{0.88, 0.90, 0.89, 0.91, 0.87}));
            when(repo.findByExperimentNameOrdered("demo")).thenReturn(rows);

            var report = service.compare("demo", 0.05);

            assertThat(report.experimentName()).isEqualTo("demo");
            assertThat(report.k()).isEqualTo(5);
            assertThat(report.numPairs()).isEqualTo(1);      // 2-choose-2 = 1
            assertThat(report.bonferroniAlpha()).isEqualTo(0.05);  // α / 1 pair
            assertThat(report.configs()).containsExactly(
                    DetectionConfig.ML_ONLY, DetectionConfig.FULL_SYSTEM);

            // F1 metric specifically
            var f1Results = report.byMetric().get("f1Score");
            assertThat(f1Results).hasSize(1);
            var pair = f1Results.get(0);
            assertThat(pair.configA()).isEqualTo(DetectionConfig.ML_ONLY);
            assertThat(pair.configB()).isEqualTo(DetectionConfig.FULL_SYSTEM);
            assertThat(pair.meanA()).isEqualTo((0.80 + 0.82 + 0.81 + 0.83 + 0.79) / 5);
            assertThat(pair.meanB()).isEqualTo((0.88 + 0.90 + 0.89 + 0.91 + 0.87) / 5);
            assertThat(pair.meanDiff()).isNegative();        // A < B
            assertThat(pair.pValue()).isBetween(0.0, 1e-5);  // consistent ~0.08 diff → tiny p
            assertThat(pair.significant()).isTrue();
        }

        @Test
        @DisplayName("Bonferroni: 5 configs → 10 pairs → α' = α / 10")
        void bonferroniScalesWithPairCount() {
            List<ExperimentResult> rows = new ArrayList<>();
            for (DetectionConfig c : DetectionConfig.values()) {
                rows.addAll(foldsFor(c, new double[]{0.5, 0.5, 0.5, 0.5, 0.5}));
            }
            when(repo.findByExperimentNameOrdered("fivecfg")).thenReturn(rows);

            var report = service.compare("fivecfg", 0.05);
            assertThat(report.numPairs()).isEqualTo(10);
            assertThat(report.bonferroniAlpha()).isEqualTo(0.005);
        }

        @Test
        @DisplayName("identical fold values → zero-variance pair is flagged, not NaN-crashed")
        void zeroVarianceHandled() {
            List<ExperimentResult> rows = new ArrayList<>();
            rows.addAll(foldsFor(DetectionConfig.ML_ONLY,
                    new double[]{0.8, 0.8, 0.8, 0.8, 0.8}));
            rows.addAll(foldsFor(DetectionConfig.FULL_SYSTEM,
                    new double[]{0.8, 0.8, 0.8, 0.8, 0.8}));
            when(repo.findByExperimentNameOrdered("zv")).thenReturn(rows);

            var report = service.compare("zv", 0.05);
            var pair = report.byMetric().get("f1Score").get(0);
            assertThat(pair.skipReason()).isEqualTo("zero variance");
            assertThat(pair.significant()).isFalse();
        }

        @Test
        @DisplayName("empty results throws IllegalArgumentException")
        void emptyResults() {
            when(repo.findByExperimentNameOrdered("missing")).thenReturn(List.of());
            assertThatThrownBy(() -> service.compare("missing"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No results found");
        }

        @Test
        @DisplayName("single config (no pairs) throws IllegalArgumentException")
        void singleConfig() {
            List<ExperimentResult> rows = foldsFor(DetectionConfig.ML_ONLY,
                    new double[]{0.8, 0.82, 0.81, 0.83, 0.79});
            when(repo.findByExperimentNameOrdered("solo")).thenReturn(rows);

            assertThatThrownBy(() -> service.compare("solo"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Need at least 2 configs");
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static List<ExperimentResult> foldsFor(DetectionConfig config, double[] f1s) {
        List<ExperimentResult> rows = new ArrayList<>(f1s.length);
        for (int i = 0; i < f1s.length; i++) {
            ExperimentResult r = new ExperimentResult();
            r.setExperimentName("demo");
            r.setConfig(config);
            r.setDataset(DatasetSource.PAYSIM);
            r.setFold(i);
            r.setF1Score(f1s[i]);
            // stub precision/recall/auc to the same value so the extractor never hits null.
            r.setPrecisionScore(f1s[i]);
            r.setRecallScore(f1s[i]);
            r.setAucRoc(f1s[i]);
            r.setAucPr(f1s[i]);
            rows.add(r);
        }
        return rows;
    }
}
