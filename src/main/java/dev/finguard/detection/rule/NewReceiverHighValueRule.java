package dev.finguard.detection.rule;

import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.model.TransactionFeatures;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Flags high-value transactions sent to a first-time receiver.
 *
 * <p>Sending a large sum to an account you've never transacted with before
 * is a strong fraud indicator — especially in account takeover scenarios
 * where the attacker redirects funds to their own accounts.</p>
 *
 * <p>A transaction is flagged when:
 * <ol>
 *   <li>The receiver is new (no prior transaction history between this sender-receiver pair)</li>
 *   <li>The amount exceeds the threshold read from {@link RuleThresholdService}</li>
 * </ol></p>
 *
 * <p>See {@link RuleMetadata#NEW_RECEIVER_HIGH_VALUE} for the thesis-calibrated
 * default and the PaySim-specific rationale (synthetic unique-receiver quirk).</p>
 */
@Component
public class NewReceiverHighValueRule implements RuleCheck {

    private static final String NAME = "NEW_RECEIVER_HIGH_VALUE";

    private final RuleThresholdService thresholdService;

    public NewReceiverHighValueRule(RuleThresholdService thresholdService) {
        this.thresholdService = thresholdService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public RuleResult evaluate(Transaction transaction, TransactionFeatures features) {
        if (features == null || features.getIsNewReceiver() == null) {
            return RuleResult.clean(NAME);
        }

        boolean isNew = features.getIsNewReceiver();
        BigDecimal threshold = thresholdService.getCurrentValue(RuleMetadata.NEW_RECEIVER_HIGH_VALUE);
        boolean highValue = transaction.getAmount().compareTo(threshold) >= 0;

        if (isNew && highValue) {
            return RuleResult.triggered(NAME,
                    String.format("High-value transfer of %s to new receiver %s (threshold: %s)",
                            transaction.getAmount().toPlainString(),
                            transaction.getReceiverAccount(),
                            threshold.toPlainString()));
        }
        return RuleResult.clean(NAME);
    }
}
