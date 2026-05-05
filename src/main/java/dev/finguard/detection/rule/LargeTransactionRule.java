package dev.finguard.detection.rule;

import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.model.TransactionFeatures;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Flags transactions whose amount exceeds a configurable threshold.
 *
 * <p>Large single transactions are a classic indicator of money laundering
 * or account takeover. The threshold is now read from
 * {@link RuleThresholdService} — user-editable at runtime via
 * {@code PUT /api/v1/rules/LARGE_TRANSACTION}; see {@link RuleMetadata} for
 * the thesis-calibrated default and citation.</p>
 */
@Component
public class LargeTransactionRule implements RuleCheck {

    private static final String NAME = "LARGE_TRANSACTION";

    private final RuleThresholdService thresholdService;

    public LargeTransactionRule(RuleThresholdService thresholdService) {
        this.thresholdService = thresholdService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public RuleResult evaluate(Transaction transaction, TransactionFeatures features) {
        BigDecimal threshold = thresholdService.getCurrentValue(RuleMetadata.LARGE_TRANSACTION);
        if (transaction.getAmount().compareTo(threshold) >= 0) {
            return RuleResult.triggered(NAME,
                    String.format("Transaction amount %s exceeds threshold %s",
                            transaction.getAmount().toPlainString(),
                            threshold.toPlainString()));
        }
        return RuleResult.clean(NAME);
    }
}
