package dev.finguard.evaluation.metrics;

import dev.finguard.domain.enums.DetectionConfig;
import dev.finguard.domain.model.ExperimentResult;
import dev.finguard.domain.repository.ExperimentResultRepository;
import org.apache.commons.math3.stat.inference.TTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pairwise statistical significance testing across detection configs (thesis §3.5).
 *
 * <p>For each named experiment with k-fold CV results, computes paired two-sided
 * t-tests on per-fold metric values between every pair of configs. Applies the
 * <b>Bonferroni correction</b> for multiple comparisons:</p>
 *
 * <pre>
 *   α' = α / num_pairs
 *   num_pairs = C(n, 2) = n × (n-1) / 2  for n configs
 * </pre>
 *
 * <p>For 5 configs (RULES_ONLY, ML_ONLY, ML_LLM_DIRECT, ML_LLM_RAG, FULL_SYSTEM),
 * num_pairs = 10, so the corrected α is {@code 0.05 / 10 = 0.005}. A pair is
 * declared <em>significantly different</em> when the raw two-sided p-value is
 * below this corrected threshold.</p>
 *
 * <p>The math (paired t-test):</p>
 * <ol>
 *   <li>Per-fold differences {@code d[i] = a[i] - b[i]}</li>
 *   <li>{@code t = mean(d) / (sd(d) / √k)}</li>
 *   <li>p-value from Student's t CDF with df = k - 1</li>
 * </ol>
 *
 * <p>Apache Commons Math {@code TTest.pairedTTest} provides both the test
 * statistic and the two-sided p-value out of the box.</p>
 */
@Service
public class StatisticalSignificanceService {

    private static final Logger log = LoggerFactory.getLogger(StatisticalSignificanceService.class);
    private static final TTest T_TEST = new TTest();

    /** Default α for "significant" callout — overridden via {@link #compare(String, double)}. */
    public static final double DEFAULT_ALPHA = 0.05;

    private final ExperimentResultRepository resultRepository;

    public StatisticalSignificanceService(ExperimentResultRepository resultRepository) {
        this.resultRepository = resultRepository;
    }

    /**
     * Compare every pair of configs in the named experiment using α = 0.05 (Bonferroni-corrected).
     *
     * @param experimentName name of the k-fold experiment to analyse
     * @return one significance report per metric (F1, precision, recall, AUC-ROC, AUC-PR)
     */
    public ExperimentSignificanceReport compare(String experimentName) {
        return compare(experimentName, DEFAULT_ALPHA);
    }

    /**
     * Pairwise t-tests with Bonferroni correction at the supplied family-wise α.
     *
     * @param experimentName name of the k-fold experiment to analyse
     * @param alpha          family-wise significance threshold (typical: 0.05)
     */
    public ExperimentSignificanceReport compare(String experimentName, double alpha) {
        List<ExperimentResult> rows = resultRepository.findByExperimentNameOrdered(experimentName);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException(
                    "No results found for experiment '" + experimentName + "'");
        }

        // Group per-fold rows by config — drop summary rows (fold = null).
        Map<DetectionConfig, List<ExperimentResult>> byConfig = new EnumMap<>(DetectionConfig.class);
        for (ExperimentResult r : rows) {
            if (r.getFold() == null) continue;
            byConfig.computeIfAbsent(r.getConfig(), c -> new ArrayList<>()).add(r);
        }

        // Sort each config's folds by fold index so paired alignment is correct.
        for (List<ExperimentResult> list : byConfig.values()) {
            list.sort((a, b) -> Integer.compare(
                    a.getFold() == null ? -1 : a.getFold(),
                    b.getFold() == null ? -1 : b.getFold()));
        }

        if (byConfig.size() < 2) {
            throw new IllegalArgumentException(
                    "Need at least 2 configs with per-fold results to compare; found "
                            + byConfig.keySet());
        }

        // All configs must share the same fold count for paired comparison.
        int k = byConfig.values().iterator().next().size();
        for (var e : byConfig.entrySet()) {
            if (e.getValue().size() != k) {
                throw new IllegalStateException(
                        "Config " + e.getKey() + " has " + e.getValue().size()
                                + " folds, expected " + k);
            }
        }
        if (k < 2) {
            throw new IllegalStateException(
                    "Paired t-test requires k >= 2 folds; got k=" + k);
        }

        int numPairs = byConfig.size() * (byConfig.size() - 1) / 2;
        double bonferroniAlpha = alpha / numPairs;

        log.info("Significance: experiment='{}', configs={}, folds/config={}, pairs={}, α={}, α(Bonferroni)={}",
                experimentName, byConfig.keySet(), k, numPairs, alpha, bonferroniAlpha);

        Map<String, List<PairwiseResult>> byMetric = new LinkedHashMap<>();
        for (Metric metric : Metric.values()) {
            byMetric.put(metric.name, comparePairs(byConfig, metric, bonferroniAlpha));
        }

        return new ExperimentSignificanceReport(
                experimentName, byConfig.keySet().stream().sorted().toList(),
                k, numPairs, alpha, bonferroniAlpha, byMetric);
    }

    private List<PairwiseResult> comparePairs(Map<DetectionConfig, List<ExperimentResult>> byConfig,
                                               Metric metric,
                                               double bonferroniAlpha) {
        List<DetectionConfig> configs = new ArrayList<>(byConfig.keySet());
        Collections.sort(configs);
        List<PairwiseResult> out = new ArrayList<>();
        for (int i = 0; i < configs.size(); i++) {
            for (int j = i + 1; j < configs.size(); j++) {
                DetectionConfig a = configs.get(i);
                DetectionConfig b = configs.get(j);
                double[] xs = extract(byConfig.get(a), metric);
                double[] ys = extract(byConfig.get(b), metric);
                if (xs.length != ys.length || xs.length == 0) {
                    out.add(PairwiseResult.unavailable(a, b, "fold-count mismatch or empty"));
                    continue;
                }
                if (anyNaN(xs) || anyNaN(ys)) {
                    out.add(PairwiseResult.unavailable(a, b, "NaN values in metric"));
                    continue;
                }
                if (allZero(diff(xs, ys))) {
                    // Constant-difference vector → variance 0 → t-test undefined.
                    out.add(new PairwiseResult(a, b, mean(xs), mean(ys),
                            mean(xs) - mean(ys), 0.0, 1.0,
                            false, "zero variance"));
                    continue;
                }
                double pValue = T_TEST.pairedTTest(xs, ys);
                double tStat = T_TEST.pairedT(xs, ys);
                boolean significant = pValue < bonferroniAlpha;
                out.add(new PairwiseResult(a, b,
                        mean(xs), mean(ys), mean(xs) - mean(ys),
                        tStat, pValue,
                        significant, null));
            }
        }
        return out;
    }

    private static double[] extract(List<ExperimentResult> folds, Metric metric) {
        double[] xs = new double[folds.size()];
        for (int i = 0; i < folds.size(); i++) {
            Double v = metric.extractor.apply(folds.get(i));
            xs[i] = v == null ? Double.NaN : v;
        }
        return xs;
    }

    private static double[] diff(double[] a, double[] b) {
        double[] d = new double[a.length];
        for (int i = 0; i < a.length; i++) d[i] = a[i] - b[i];
        return d;
    }

    private static boolean anyNaN(double[] xs) {
        for (double x : xs) if (Double.isNaN(x)) return true;
        return false;
    }

    private static boolean allZero(double[] xs) {
        for (double x : xs) if (x != 0.0) return false;
        return true;
    }

    private static double mean(double[] xs) {
        double s = 0;
        for (double x : xs) s += x;
        return xs.length == 0 ? 0 : s / xs.length;
    }

    /** Metric extractors — each picks the per-fold value out of an {@link ExperimentResult}. */
    public enum Metric {
        F1("f1Score",     ExperimentResult::getF1Score),
        PRECISION("precision", ExperimentResult::getPrecisionScore),
        RECALL("recall",   ExperimentResult::getRecallScore),
        AUC_ROC("aucRoc",  ExperimentResult::getAucRoc),
        AUC_PR("aucPr",    ExperimentResult::getAucPr);

        public final String name;
        public final java.util.function.Function<ExperimentResult, Double> extractor;

        Metric(String name, java.util.function.Function<ExperimentResult, Double> extractor) {
            this.name = name;
            this.extractor = extractor;
        }
    }

    /**
     * One pairwise comparison result.
     *
     * @param configA          first config of the pair
     * @param configB          second config of the pair
     * @param meanA            mean metric value across folds for configA
     * @param meanB            mean metric value across folds for configB
     * @param meanDiff         {@code meanA - meanB}
     * @param tStatistic       paired t-statistic
     * @param pValue           two-sided p-value (raw — already Bonferroni-corrected α applied below)
     * @param significant      {@code pValue < bonferroniAlpha}
     * @param skipReason       non-null when the test was not run (e.g. NaN, zero variance)
     */
    public record PairwiseResult(
            DetectionConfig configA,
            DetectionConfig configB,
            double meanA,
            double meanB,
            double meanDiff,
            double tStatistic,
            double pValue,
            boolean significant,
            String skipReason
    ) {
        public static PairwiseResult unavailable(DetectionConfig a, DetectionConfig b, String reason) {
            return new PairwiseResult(a, b, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, false, reason);
        }
    }

    /**
     * Full report for one experiment. Serialises cleanly to JSON for the dashboard.
     *
     * @param experimentName    experiment name analysed
     * @param configs           configs included
     * @param k                 number of folds per config
     * @param numPairs          {@code configs.size() × (configs.size() - 1) / 2}
     * @param alpha             user-supplied family-wise α (typical: 0.05)
     * @param bonferroniAlpha   {@code alpha / numPairs} — used as the per-test cutoff
     * @param byMetric          map: metric name → list of pairwise results
     */
    public record ExperimentSignificanceReport(
            String experimentName,
            List<DetectionConfig> configs,
            int k,
            int numPairs,
            double alpha,
            double bonferroniAlpha,
            Map<String, List<PairwiseResult>> byMetric
    ) { }
}
