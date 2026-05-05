package dev.finguard.detection.rule;

import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.model.TransactionFeatures;
import org.springframework.stereotype.Component;

/**
 * Flags transactions from senders with unusually high velocity.
 *
 * <p>A burst of transactions in a short time window (e.g., 3+ in 1 hour)
 * is a common pattern in account takeover attacks and automated fraud.
 * This rule uses the pre-computed {@code txVelocity1h} feature from
 * {@link TransactionFeatures}.</p>
 *
 * <p>The count threshold is read from {@link RuleThresholdService}; see
 * {@link RuleMetadata#RAPID_VELOCITY} for the industry citation.</p>
 */
@Component
public class RapidVelocityRule implements RuleCheck {

    private static final String NAME = "RAPID_VELOCITY";

    private final RuleThresholdService thresholdService;

    public RapidVelocityRule(RuleThresholdService thresholdService) {
        this.thresholdService = thresholdService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public RuleResult evaluate(Transaction transaction, TransactionFeatures features) {
        if (features == null || features.getTxVelocity1h() == null) {
            return RuleResult.clean(NAME);
        }

        int velocity = features.getTxVelocity1h();
        int countThreshold = thresholdService.getCurrentIntValue(RuleMetadata.RAPID_VELOCITY);
        if (velocity >= countThreshold) {
            return RuleResult.triggered(NAME,
                    String.format("Sender %s has %d transactions in the last hour (threshold: %d)",
                            transaction.getSenderAccount(), velocity, countThreshold));
        }
        return RuleResult.clean(NAME);
    }
}
