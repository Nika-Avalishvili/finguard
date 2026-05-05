package dev.finguard.detection.ml;

import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.model.TransactionFeatures;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tribuo.Example;
import org.tribuo.Feature;
import org.tribuo.classification.Label;
import org.tribuo.impl.ArrayExample;

import java.util.ArrayList;
import java.util.List;

/**
 * Transforms {@link TransactionFeatures} into Tribuo {@link Example} instances
 * suitable for classification model training and prediction.
 *
 * <p>Extracts 12 numeric features from each transaction + its pre-computed features.
 * Null values are replaced with safe defaults (0.0 for numeric, false for boolean)
 * to handle incomplete feature data gracefully.</p>
 *
 * <p>Feature names are stable strings used across training and prediction — changing
 * them requires retraining the model.</p>
 */
@Component
public class FeatureTransformer {

    /** Label for fraudulent transactions. */
    public static final Label FRAUD_LABEL = new Label("FRAUD");

    /** Label for legitimate transactions. */
    public static final Label LEGIT_LABEL = new Label("LEGIT");

    /** Sentinel label used for prediction (when the true label is unknown). */
    public static final Label UNKNOWN_LABEL = new Label("UNKNOWN");

    /**
     * When false, the {@code is_high_risk_type} feature is excluded from training and prediction.
     * Set to false for ablation studies to measure how much the model relies on transaction type.
     */
    @Value("${finguard.detection.ml.include-high-risk-type:true}")
    private boolean includeHighRiskType = true;  // default true; @Value overrides in Spring context

    /** Ordered feature names — used for SHAP-like importance mapping. */
    public static final List<String> FEATURE_NAMES = List.of(
            "amount",
            "amount_zscore",
            "tx_velocity_1h",
            "tx_velocity_24h",
            "avg_amount_7d",
            "amount_ratio_to_avg",
            "balance_change_ratio",
            "is_new_receiver",
            "receiver_diversity_7d",
            "hour_of_day",
            "day_of_week",
            "is_round_amount",
            "is_high_risk_type"
    );

    /**
     * Create a labeled training example from a transaction and its features.
     *
     * @param transaction the source transaction (used for amount and fraud label)
     * @param features    pre-computed features (may be null — defaults apply)
     * @return a labeled Tribuo example for training
     */
    public Example<Label> toTrainingExample(Transaction transaction, TransactionFeatures features) {
        Label label = Boolean.TRUE.equals(transaction.getIsFraud()) ? FRAUD_LABEL : LEGIT_LABEL;
        return buildExample(label, transaction, features);
    }

    /**
     * Create an unlabeled prediction example from a transaction and its features.
     *
     * @param transaction the source transaction
     * @param features    pre-computed features (may be null — defaults apply)
     * @return an unlabeled Tribuo example for prediction
     */
    public Example<Label> toPredictionExample(Transaction transaction, TransactionFeatures features) {
        return buildExample(UNKNOWN_LABEL, transaction, features);
    }

    private Example<Label> buildExample(Label label, Transaction transaction, TransactionFeatures features) {
        List<Feature> featureList = new ArrayList<>(FEATURE_NAMES.size());

        double amount = transaction.getAmount() != null
                ? safeD(transaction.getAmount().doubleValue()) : 0.0;
        featureList.add(new Feature("amount", amount));

        if (features != null) {
            featureList.add(new Feature("amount_zscore", safe(features.getAmountZscore())));
            featureList.add(new Feature("tx_velocity_1h", safe(features.getTxVelocity1h())));
            featureList.add(new Feature("tx_velocity_24h", safe(features.getTxVelocity24h())));
            featureList.add(new Feature("avg_amount_7d",
                    features.getAvgAmount7d() != null ? safeD(features.getAvgAmount7d().doubleValue()) : 0.0));
            featureList.add(new Feature("amount_ratio_to_avg", safe(features.getAmountRatioToAvg())));
            featureList.add(new Feature("balance_change_ratio", safe(features.getBalanceChangeRatio())));
            featureList.add(new Feature("is_new_receiver", boolToDouble(features.getIsNewReceiver())));
            featureList.add(new Feature("receiver_diversity_7d", safe(features.getReceiverDiversity7d())));
            featureList.add(new Feature("hour_of_day", safe(features.getHourOfDay())));
            featureList.add(new Feature("day_of_week", safe(features.getDayOfWeek())));
            featureList.add(new Feature("is_round_amount", boolToDouble(features.getIsRoundAmount())));
            if (includeHighRiskType) {
                featureList.add(new Feature("is_high_risk_type", boolToDouble(features.getIsHighRiskType())));
            }
        } else {
            // No features available — fill with zeros
            featureList.add(new Feature("amount_zscore", 0.0));
            featureList.add(new Feature("tx_velocity_1h", 0.0));
            featureList.add(new Feature("tx_velocity_24h", 0.0));
            featureList.add(new Feature("avg_amount_7d", 0.0));
            featureList.add(new Feature("amount_ratio_to_avg", 0.0));
            featureList.add(new Feature("balance_change_ratio", 0.0));
            featureList.add(new Feature("is_new_receiver", 0.0));
            featureList.add(new Feature("receiver_diversity_7d", 0.0));
            featureList.add(new Feature("hour_of_day", 0.0));
            featureList.add(new Feature("day_of_week", 0.0));
            featureList.add(new Feature("is_round_amount", 0.0));
            if (includeHighRiskType) {
                featureList.add(new Feature("is_high_risk_type", 0.0));
            }
        }

        return new ArrayExample<>(label, featureList);
    }

    /**
     * Create a labeled training example directly from primitive values.
     * Used by the JDBC-based training data loader to avoid JPA entity overhead.
     */
    public Example<Label> toTrainingExampleFromValues(
            boolean isFraud, double amount,
            double amountZscore, int txVelocity1h, int txVelocity24h,
            double avgAmount7d, double amountRatioToAvg, double balanceChangeRatio,
            boolean isNewReceiver, int receiverDiversity7d,
            int hourOfDay, int dayOfWeek,
            boolean isRoundAmount, boolean isHighRiskType) {
        Label label = isFraud ? FRAUD_LABEL : LEGIT_LABEL;
        List<Feature> featureList = new ArrayList<>(FEATURE_NAMES.size());
        featureList.add(new Feature("amount",               safeD(amount)));
        featureList.add(new Feature("amount_zscore",        safeD(amountZscore)));
        featureList.add(new Feature("tx_velocity_1h",       txVelocity1h));
        featureList.add(new Feature("tx_velocity_24h",      txVelocity24h));
        featureList.add(new Feature("avg_amount_7d",        safeD(avgAmount7d)));
        featureList.add(new Feature("amount_ratio_to_avg",  safeD(amountRatioToAvg)));
        featureList.add(new Feature("balance_change_ratio", safeD(balanceChangeRatio)));
        featureList.add(new Feature("is_new_receiver",      isNewReceiver ? 1.0 : 0.0));
        featureList.add(new Feature("receiver_diversity_7d", receiverDiversity7d));
        featureList.add(new Feature("hour_of_day",          hourOfDay));
        featureList.add(new Feature("day_of_week",          dayOfWeek));
        featureList.add(new Feature("is_round_amount",      isRoundAmount ? 1.0 : 0.0));
        if (includeHighRiskType) {
            featureList.add(new Feature("is_high_risk_type", isHighRiskType ? 1.0 : 0.0));
        }
        return new ArrayExample<>(label, featureList);
    }

    private static double safe(Double val) {
        if (val == null || Double.isNaN(val) || Double.isInfinite(val)) return 0.0;
        return val;
    }

    private static double safe(Integer val) {
        return val != null ? val.doubleValue() : 0.0;
    }

    private static double safe(Short val) {
        return val != null ? val.doubleValue() : 0.0;
    }

    /** Guard primitive double against NaN and Infinity. */
    private static double safeD(double val) {
        return Double.isNaN(val) || Double.isInfinite(val) ? 0.0 : val;
    }

    private static double boolToDouble(Boolean val) {
        return Boolean.TRUE.equals(val) ? 1.0 : 0.0;
    }
}
