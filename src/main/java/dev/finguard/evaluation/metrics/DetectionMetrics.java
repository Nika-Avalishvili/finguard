package dev.finguard.evaluation.metrics;

import dev.finguard.domain.enums.DetectionConfig;
import dev.finguard.domain.model.Alert;
import dev.finguard.domain.model.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Computes standard binary classification metrics for the detection pipeline.
 *
 * <p>Compares the pipeline's anomaly alerts against the ground-truth {@code isFraud}
 * labels from the ingested dataset. Metrics include precision, recall, F1 score,
 * false positive rate (FPR), and a full confusion matrix.</p>
 *
 * <p>Usage: pass all transactions (with ground-truth labels) and all alerts produced
 * by a specific {@link DetectionConfig}. The class computes TP/FP/TN/FN counts
 * and derives all standard metrics.</p>
 */
@Component
public class DetectionMetrics {

    private static final Logger log = LoggerFactory.getLogger(DetectionMetrics.class);

    /**
     * Compute detection metrics by comparing alerts against ground-truth fraud labels.
     *
     * @param transactions all transactions in the evaluation set
     * @param alerts       alerts produced by the detection pipeline for a specific config
     * @param config       the detection config being evaluated
     * @return computed metrics
     */
    public MetricsResult compute(List<Transaction> transactions, List<Alert> alerts,
                                  DetectionConfig config) {
        // Transaction IDs that were flagged as anomalies
        Set<Long> alertedTxIds = alerts.stream()
                .filter(a -> Boolean.TRUE.equals(a.getIsAnomaly()))
                .map(a -> a.getTransaction().getId())
                .collect(Collectors.toSet());

        long tp = 0; // Predicted fraud, actually fraud
        long fp = 0; // Predicted fraud, actually legit
        long tn = 0; // Predicted legit, actually legit
        long fn = 0; // Predicted legit, actually fraud

        for (Transaction tx : transactions) {
            boolean actualFraud = Boolean.TRUE.equals(tx.getIsFraud());
            boolean predictedFraud = alertedTxIds.contains(tx.getId());

            if (predictedFraud && actualFraud) tp++;
            else if (predictedFraud && !actualFraud) fp++;
            else if (!predictedFraud && !actualFraud) tn++;
            else fn++; // !predictedFraud && actualFraud
        }

        double precision = (tp + fp) > 0 ? (double) tp / (tp + fp) : 0.0;
        double recall = (tp + fn) > 0 ? (double) tp / (tp + fn) : 0.0;
        double f1 = (precision + recall) > 0 ? 2.0 * precision * recall / (precision + recall) : 0.0;
        double fpr = (fp + tn) > 0 ? (double) fp / (fp + tn) : 0.0;

        MetricsResult result = new MetricsResult(
                config, transactions.size(), alerts.size(),
                tp, fp, tn, fn,
                precision, recall, f1, fpr
        );

        log.info("Detection metrics [config={}]: precision={}, recall={}, F1={}, FPR={}",
                config,
                String.format("%.4f", precision),
                String.format("%.4f", recall),
                String.format("%.4f", f1),
                String.format("%.4f", fpr));

        return result;
    }

    /**
     * Compute metrics at multiple risk-score thresholds for ROC/PR curve data.
     *
     * <p>Only applicable for configs that produce ML risk scores (ML_ONLY, ML_LLM_*, FULL_SYSTEM).
     * Returns a list of threshold-metrics pairs that can be plotted as ROC or PR curves.</p>
     *
     * @param transactions all transactions in the evaluation set
     * @param alerts       alerts with ML risk scores
     * @param thresholds   risk-score thresholds to evaluate at (e.g., 0.1, 0.2, ..., 0.9)
     * @return map of threshold → metrics result
     */
    public Map<Double, MetricsResult> computeAtThresholds(List<Transaction> transactions,
                                                            List<Alert> alerts,
                                                            List<Double> thresholds,
                                                            DetectionConfig config) {
        return thresholds.stream()
                .collect(Collectors.toMap(
                        threshold -> threshold,
                        threshold -> {
                            // Filter alerts to only those above this threshold
                            List<Alert> filtered = alerts.stream()
                                    .filter(a -> a.getMlRiskScore() != null
                                            && a.getMlRiskScore() >= threshold)
                                    .toList();
                            return compute(transactions, filtered, config);
                        }
                ));
    }

    /**
     * Compute metrics using pre-fetched aggregate counts for total/fraud transactions.
     *
     * <p>Avoids loading all transactions into memory — uses {@code COUNT} queries instead.
     * The alerts list (already loaded for the config) is used to derive TP/FP counts.</p>
     *
     * @param config   detection config evaluated
     * @param totalTx  total transactions in the dataset
     * @param fraudTx  actual fraud transactions in the dataset (ground-truth)
     * @param alerts   anomaly alerts produced by the pipeline for this config
     */
    public MetricsResult computeFromCounts(DetectionConfig config, long totalTx, long fraudTx,
                                            List<Alert> alerts) {
        Set<Long> alertedTxIds = alerts.stream()
                .filter(a -> Boolean.TRUE.equals(a.getIsAnomaly()))
                .map(a -> a.getTransaction().getId())
                .collect(Collectors.toSet());

        // We know total fraud and total transactions; derive TP/FP from alerts.
        // TP = fraud transactions that were flagged; FP = legit transactions that were flagged.
        // To compute TP we need to know which alerts correspond to actual fraud.
        // The Alert entity has the Transaction with isFraud label (eagerly loaded via JOIN FETCH).
        long tp = alerts.stream()
                .filter(a -> Boolean.TRUE.equals(a.getIsAnomaly()))
                .filter(a -> Boolean.TRUE.equals(a.getTransaction().getIsFraud()))
                .count();
        return computeFromAggregates(config, totalTx, fraudTx, alertedTxIds.size(), tp);
    }

    /**
     * OOM-safe metric computation: purely from scalar counts.
     *
     * <p>Prefer this over {@link #computeFromCounts(DetectionConfig, long, long, List)} when
     * the alert count is large (say &gt; 100 k). Loading a million-alert {@code List<Alert>}
     * with eager {@code JOIN FETCH} transactions fits happily in a couple of gigabytes of
     * heap, which is fine on a server but wrecks a thesis laptop. The caller runs two
     * {@code COUNT} queries instead:
     * <ul>
     *   <li>{@code totalAlerts} — {@code COUNT(a) WHERE isAnomaly = true AND scope ...}</li>
     *   <li>{@code tp}          — as above + {@code AND t.isFraud = true}</li>
     * </ul>
     * Everything else is scalar arithmetic — no entities touched.</p>
     *
     * <p>All four (TP / FP / TN / FN) derive from the four inputs:
     * <pre>
     *   FP = totalAlerts - TP
     *   FN = fraudTx     - TP
     *   TN = totalTx     - fraudTx - FP
     * </pre></p>
     */
    public MetricsResult computeFromAggregates(DetectionConfig config,
                                                long totalTx,
                                                long fraudTx,
                                                long totalAlerts,
                                                long tp) {
        long fp = Math.max(0L, totalAlerts - tp);
        long fn = Math.max(0L, fraudTx - tp);
        long tn = Math.max(0L, totalTx - fraudTx - fp);

        double precision = (tp + fp) > 0 ? (double) tp / (tp + fp) : 0.0;
        double recall = (tp + fn) > 0 ? (double) tp / (tp + fn) : 0.0;
        double f1 = (precision + recall) > 0 ? 2.0 * precision * recall / (precision + recall) : 0.0;
        double fpr = (fp + tn) > 0 ? (double) fp / (fp + tn) : 0.0;

        MetricsResult result = new MetricsResult(
                config, (int) totalTx, (int) totalAlerts,
                tp, fp, tn, fn,
                precision, recall, f1, fpr
        );

        log.info("Detection metrics [config={}]: precision={}, recall={}, F1={}, FPR={}",
                config,
                String.format("%.4f", precision),
                String.format("%.4f", recall),
                String.format("%.4f", f1),
                String.format("%.4f", fpr));

        return result;
    }

    // ====================================================================
    // AUC-ROC and AUC-PR (threshold-independent metrics, thesis §3.4.1)
    // ====================================================================

    /**
     * Compute the area under the ROC curve and the area under the precision-recall
     * curve from a vector of continuous risk scores and binary ground-truth labels.
     *
     * <p>Trapezoidal integration over thresholds at every distinct score; classic
     * scikit-learn-equivalent semantics. Both metrics are robust to extreme class
     * imbalance — AUC-PR especially so, which is why the thesis (§3.4.1) names it
     * the "more informative" rare-event metric.</p>
     *
     * <p>Edge cases:</p>
     * <ul>
     *   <li>Empty input → {@code AucResult(NaN, NaN)}.</li>
     *   <li>All labels positive → AUC-ROC undefined (no negatives) → {@code NaN}; AUC-PR = 1.</li>
     *   <li>All labels negative → AUC-ROC = NaN; AUC-PR = 0.</li>
     *   <li>All scores tied → ROC = 0.5 (random); PR = base rate.</li>
     * </ul>
     *
     * @param scores continuous risk scores, length N (any range; only ordering matters)
     * @param labels true = positive class (fraud), length N (must match scores)
     * @return AUC-ROC and AUC-PR, both in {@code [0, 1]} when defined
     */
    public AucResult computeAuc(double[] scores, boolean[] labels) {
        if (scores == null || labels == null || scores.length != labels.length) {
            throw new IllegalArgumentException("scores and labels must be non-null and equal length");
        }
        int n = scores.length;
        if (n == 0) {
            return new AucResult(Double.NaN, Double.NaN);
        }

        // Sort descending by score, carrying the label along.
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        Arrays.sort(idx, Comparator.comparingDouble((Integer i) -> -scores[i]));

        long totalPos = 0;
        long totalNeg = 0;
        for (boolean l : labels) {
            if (l) totalPos++; else totalNeg++;
        }

        if (totalPos == 0 && totalNeg == 0) {
            return new AucResult(Double.NaN, Double.NaN);
        }
        if (totalPos == 0) {
            return new AucResult(Double.NaN, 0.0);  // PR undefined / 0; ROC undefined.
        }
        if (totalNeg == 0) {
            return new AucResult(Double.NaN, 1.0);  // PR perfect; ROC undefined.
        }

        // Sweep thresholds. At each unique score boundary, record (fpr, tpr) for ROC
        // and (recall, precision) for PR. Tied scores are batched together so the
        // curve makes one diagonal segment for the whole tie group (avoids spurious
        // step-function bias).
        long tp = 0, fp = 0;
        List<double[]> rocPoints = new ArrayList<>();
        List<double[]> prPoints = new ArrayList<>();
        rocPoints.add(new double[]{0.0, 0.0});
        // First PR point at the highest threshold = (recall=0, precision=1).
        prPoints.add(new double[]{0.0, 1.0});

        int i = 0;
        while (i < n) {
            double t = scores[idx[i]];
            int j = i;
            while (j < n && scores[idx[j]] == t) {
                if (labels[idx[j]]) tp++; else fp++;
                j++;
            }
            double fpr = (double) fp / totalNeg;
            double tpr = (double) tp / totalPos;
            double recall = tpr;
            double precision = (tp + fp) > 0 ? (double) tp / (tp + fp) : 1.0;
            rocPoints.add(new double[]{fpr, tpr});
            prPoints.add(new double[]{recall, precision});
            i = j;
        }

        double aucRoc = trapezoidal(rocPoints);
        double aucPr  = trapezoidal(prPoints);
        return new AucResult(aucRoc, aucPr);
    }

    /** Trapezoidal area under a polyline given by (x, y) points sorted by x ascending. */
    private static double trapezoidal(List<double[]> points) {
        if (points.size() < 2) return 0.0;
        // Sort by x in case the sweep produced unsorted points (defensive).
        points.sort(Comparator.comparingDouble(p -> p[0]));
        double area = 0.0;
        for (int k = 1; k < points.size(); k++) {
            double x0 = points.get(k - 1)[0], y0 = points.get(k - 1)[1];
            double x1 = points.get(k)[0],     y1 = points.get(k)[1];
            area += (x1 - x0) * (y0 + y1) / 2.0;
        }
        return area;
    }

    /**
     * Immutable AUC pair returned by {@link #computeAuc(double[], boolean[])}.
     *
     * @param aucRoc area under ROC curve in [0, 1]; NaN when undefined (only-one-class)
     * @param aucPr  area under precision-recall curve in [0, 1]; NaN when undefined (no samples)
     */
    public record AucResult(double aucRoc, double aucPr) { }

    /**
     * Immutable result of detection metric computation.
     *
     * @param config            the detection config evaluated
     * @param totalTransactions total transactions in the evaluation set
     * @param totalAlerts       total alerts produced
     * @param truePositives     correctly identified fraud
     * @param falsePositives    legitimate transactions incorrectly flagged
     * @param trueNegatives     correctly identified legitimate transactions
     * @param falseNegatives    fraud missed by the detector
     * @param precision         TP / (TP + FP)
     * @param recall            TP / (TP + FN), a.k.a. sensitivity/TPR
     * @param f1Score           harmonic mean of precision and recall
     * @param falsePositiveRate FP / (FP + TN), a.k.a. fall-out
     */
    public record MetricsResult(
            DetectionConfig config,
            int totalTransactions,
            int totalAlerts,
            long truePositives,
            long falsePositives,
            long trueNegatives,
            long falseNegatives,
            double precision,
            double recall,
            double f1Score,
            double falsePositiveRate
    ) {
        /**
         * The number of actual frauds in the dataset.
         */
        public long actualFrauds() {
            return truePositives + falseNegatives;
        }

        /**
         * The number of actual legitimate transactions.
         */
        public long actualLegitimate() {
            return trueNegatives + falsePositives;
        }
    }
}
