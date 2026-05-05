package dev.finguard.detection.ml;

import java.util.Map;

/**
 * Immutable result of an ML model prediction on a single transaction.
 *
 * @param modelName          the model that produced this prediction (e.g. "RandomForest", "XGBoost")
 * @param riskScore          fraud probability in [0.0, 1.0]
 * @param predictedFraud     true if riskScore >= threshold
 * @param featureImportances map of feature name → importance weight (model-global, not per-instance)
 */
public record MLPredictionResult(
        String modelName,
        double riskScore,
        boolean predictedFraud,
        Map<String, Double> featureImportances
) {

    /**
     * Create a result indicating no model is available.
     */
    public static MLPredictionResult unavailable() {
        return new MLPredictionResult("NONE", 0.0, false, Map.of());
    }
}
