package dev.finguard.detection.rule;

/**
 * Immutable result of a single rule evaluation.
 *
 * @param ruleName  identifier of the rule that produced this result
 * @param triggered true if the rule flagged the transaction as suspicious
 * @param reason    human-readable explanation of why the rule triggered (or null if not triggered)
 */
public record RuleResult(
        String ruleName,
        boolean triggered,
        String reason
) {

    /**
     * Convenience factory for a triggered result.
     */
    public static RuleResult triggered(String ruleName, String reason) {
        return new RuleResult(ruleName, true, reason);
    }

    /**
     * Convenience factory for a non-triggered (clean) result.
     */
    public static RuleResult clean(String ruleName) {
        return new RuleResult(ruleName, false, null);
    }
}
