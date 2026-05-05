package dev.finguard.detection.rule;

import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.model.TransactionFeatures;

/**
 * Strategy interface for individual fraud detection rules.
 *
 * <p>Each implementation encapsulates a single detection heuristic
 * (e.g., large transaction amount, rapid velocity). Rules are stateless
 * and receive all context they need via method parameters.</p>
 *
 * <p>Rules produce a {@link RuleResult} that indicates whether the
 * rule triggered and why. Multiple rules are orchestrated by
 * {@link RuleEngine}.</p>
 */
public interface RuleCheck {

    /**
     * Unique identifier for this rule, used in alert logs and API responses.
     * Convention: SCREAMING_SNAKE_CASE (e.g., "LARGE_TRANSACTION", "RAPID_VELOCITY").
     */
    String name();

    /**
     * Evaluate a transaction against this rule.
     *
     * @param transaction the transaction to evaluate
     * @param features    pre-computed features for the transaction (may be null for
     *                    transactions that haven't been through feature engineering)
     * @return result indicating whether the rule triggered
     */
    RuleResult evaluate(Transaction transaction, TransactionFeatures features);
}
