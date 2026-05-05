package dev.finguard.detection.rule;

import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.model.TransactionFeatures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Orchestrates all registered {@link RuleCheck} implementations.
 *
 * <p>Runs every rule against a transaction and collects the results.
 * Spring auto-discovers all {@code @Component} classes implementing
 * {@link RuleCheck} and injects them here.</p>
 *
 * <p>The engine is stateless — all state lives in the rule results.
 * Rules are executed sequentially (no short-circuit) so that all
 * triggered rules are reported in the alert.</p>
 */
@Component
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final List<RuleCheck> rules;

    public RuleEngine(List<RuleCheck> rules) {
        this.rules = rules;
        log.info("RuleEngine initialized with {} rules: {}",
                rules.size(),
                rules.stream().map(RuleCheck::name).toList());
    }

    /**
     * Evaluate all rules against a transaction.
     *
     * @param transaction the transaction to evaluate
     * @param features    pre-computed features (may be null)
     * @return list of results from all rules (both triggered and clean)
     */
    public List<RuleResult> evaluate(Transaction transaction, TransactionFeatures features) {
        return rules.stream()
                .map(rule -> rule.evaluate(transaction, features))
                .toList();
    }

    /**
     * Evaluate all rules and return only the triggered results.
     *
     * @param transaction the transaction to evaluate
     * @param features    pre-computed features (may be null)
     * @return list of triggered rule results (empty if no rules fired)
     */
    public List<RuleResult> evaluateTriggered(Transaction transaction, TransactionFeatures features) {
        return evaluate(transaction, features).stream()
                .filter(RuleResult::triggered)
                .toList();
    }

    /**
     * @return number of registered rules
     */
    public int ruleCount() {
        return rules.size();
    }
}
