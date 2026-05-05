package dev.finguard.detection.rule;

import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.model.TransactionFeatures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("RuleEngine")
class RuleEngineTest {

    /**
     * One stub service wired to every rule via mocked getters — tests the
     * engine's composition, not the service's caching.
     */
    private static RuleThresholdService thresholdStub() {
        RuleThresholdService svc = mock(RuleThresholdService.class);
        when(svc.getCurrentValue(RuleMetadata.LARGE_TRANSACTION)).thenReturn(new BigDecimal("200000.00"));
        when(svc.getCurrentValue(RuleMetadata.STRUCTURING)).thenReturn(new BigDecimal("10000.00"));
        when(svc.getCurrentValue(RuleMetadata.NEW_RECEIVER_HIGH_VALUE)).thenReturn(new BigDecimal("50000.00"));
        when(svc.getCurrentIntValue(RuleMetadata.RAPID_VELOCITY)).thenReturn(3);
        return svc;
    }

    private final RuleThresholdService thresholdService = thresholdStub();

    private final RuleEngine engine = new RuleEngine(List.of(
            new LargeTransactionRule(thresholdService),
            new RapidVelocityRule(thresholdService),
            new StructuringRule(thresholdService),
            new NewReceiverHighValueRule(thresholdService)
    ));

    @Test
    @DisplayName("Should return results from all registered rules")
    void evaluate_returnsResultFromEveryRule() {
        Transaction tx = txWithAmount("1000.00");

        List<RuleResult> results = engine.evaluate(tx, null);

        assertThat(results).hasSize(4);
    }

    @Test
    @DisplayName("Should report correct rule count")
    void ruleCount_matchesRegisteredRules() {
        assertThat(engine.ruleCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("Should return only triggered rules from evaluateTriggered")
    void evaluateTriggered_filtersCleanResults() {
        Transaction tx = txWithAmount("500000.00");
        // Large amount triggers LargeTransactionRule only (no features → other rules won't fire)

        List<RuleResult> triggered = engine.evaluateTriggered(tx, null);

        assertThat(triggered).hasSize(1);
        assertThat(triggered.get(0).ruleName()).isEqualTo("LARGE_TRANSACTION");
    }

    @Test
    @DisplayName("Should return empty list when no rules trigger")
    void evaluateTriggered_emptyWhenNoRulesFire() {
        Transaction tx = txWithAmount("100.00");

        List<RuleResult> triggered = engine.evaluateTriggered(tx, null);

        assertThat(triggered).isEmpty();
    }

    @Test
    @DisplayName("Should trigger multiple rules when conditions overlap")
    void evaluateTriggered_multipleRulesCanFire() {
        // Large amount + new receiver → LargeTransactionRule + NewReceiverHighValueRule
        Transaction tx = txWithAmount("250000.00");
        tx.setReceiverAccount("NEW_RECV");

        TransactionFeatures features = new TransactionFeatures();
        features.setIsNewReceiver(true);
        features.setTxVelocity1h(5);

        List<RuleResult> triggered = engine.evaluateTriggered(tx, features);

        List<String> ruleNames = triggered.stream().map(RuleResult::ruleName).toList();
        assertThat(ruleNames).contains("LARGE_TRANSACTION", "NEW_RECEIVER_HIGH_VALUE", "RAPID_VELOCITY");
    }

    @Test
    @DisplayName("Should handle structuring detection correctly")
    void evaluateTriggered_structuringDetection() {
        Transaction tx = txWithAmount("9000.00");

        TransactionFeatures features = new TransactionFeatures();
        features.setIsRoundAmount(true);
        features.setTxVelocity24h(5);

        List<RuleResult> triggered = engine.evaluateTriggered(tx, features);

        List<String> ruleNames = triggered.stream().map(RuleResult::ruleName).toList();
        assertThat(ruleNames).contains("STRUCTURING");
    }

    @Test
    @DisplayName("Should work with empty rule list")
    void evaluate_withNoRules() {
        RuleEngine emptyEngine = new RuleEngine(List.of());

        Transaction tx = txWithAmount("500000.00");
        List<RuleResult> results = emptyEngine.evaluate(tx, null);

        assertThat(results).isEmpty();
        assertThat(emptyEngine.ruleCount()).isZero();
    }

    private Transaction txWithAmount(String amount) {
        Transaction tx = new Transaction();
        tx.setAmount(new BigDecimal(amount));
        tx.setSenderAccount("SENDER_001");
        tx.setReceiverAccount("RECEIVER_001");
        return tx;
    }
}
