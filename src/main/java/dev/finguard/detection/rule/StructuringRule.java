package dev.finguard.detection.rule;

import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.model.TransactionFeatures;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Detects potential structuring (smurfing) patterns.
 *
 * <p>Structuring is the practice of breaking a large transaction into multiple
 * smaller ones, each just below the Currency Transaction Report threshold
 * ($10,000 per 31 CFR 1010.311), to evade reporting. This rule checks for
 * round amounts at/below the threshold combined with high velocity.</p>
 *
 * <p>The threshold is read from {@link RuleThresholdService} — see
 * {@link RuleMetadata#STRUCTURING} for the legal citation (31 USC 5324 /
 * 31 CFR 1010.311). It's editable like any other rule, but changing it
 * diverges from the legal threshold and should be documented as a
 * sensitivity-analysis in the thesis methodology chapter.</p>
 */
@Component
public class StructuringRule implements RuleCheck {

    private static final String NAME = "STRUCTURING";
    private static final int MIN_VELOCITY_24H = 3;

    private final RuleThresholdService thresholdService;

    public StructuringRule(RuleThresholdService thresholdService) {
        this.thresholdService = thresholdService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public RuleResult evaluate(Transaction transaction, TransactionFeatures features) {
        if (features == null) {
            return RuleResult.clean(NAME);
        }

        BigDecimal structuringThreshold = thresholdService.getCurrentValue(RuleMetadata.STRUCTURING);
        boolean isRound = Boolean.TRUE.equals(features.getIsRoundAmount());
        boolean belowThreshold = transaction.getAmount().compareTo(structuringThreshold) <= 0
                && transaction.getAmount().compareTo(BigDecimal.ZERO) > 0;
        boolean highVelocity = features.getTxVelocity24h() != null
                && features.getTxVelocity24h() >= MIN_VELOCITY_24H;

        if (isRound && belowThreshold && highVelocity) {
            return RuleResult.triggered(NAME,
                    String.format("Potential structuring: round amount %s at/below %s with %d transactions in 24h",
                            transaction.getAmount().toPlainString(),
                            structuringThreshold.toPlainString(),
                            features.getTxVelocity24h()));
        }
        return RuleResult.clean(NAME);
    }
}
