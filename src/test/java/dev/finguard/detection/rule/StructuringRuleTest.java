package dev.finguard.detection.rule;

import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.model.TransactionFeatures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("StructuringRule")
class StructuringRuleTest {

    private static StructuringRule ruleWithThreshold(BigDecimal threshold) {
        RuleThresholdService svc = mock(RuleThresholdService.class);
        when(svc.getCurrentValue(RuleMetadata.STRUCTURING)).thenReturn(threshold);
        return new StructuringRule(svc);
    }

    private final StructuringRule rule = ruleWithThreshold(new BigDecimal("10000.00"));

    @Test
    @DisplayName("Should trigger when all conditions met: round, below threshold, high velocity")
    void shouldTrigger_whenAllConditionsMet() {
        Transaction tx = txWithAmount("9000.00");
        TransactionFeatures features = structuringFeatures(true, 5);

        RuleResult result = rule.evaluate(tx, features);

        assertThat(result.triggered()).isTrue();
        assertThat(result.ruleName()).isEqualTo("STRUCTURING");
        assertThat(result.reason()).contains("structuring");
    }

    @Test
    @DisplayName("Should trigger at exact threshold")
    void shouldTrigger_atExactThreshold() {
        Transaction tx = txWithAmount("10000.00");
        TransactionFeatures features = structuringFeatures(true, 3);

        RuleResult result = rule.evaluate(tx, features);

        assertThat(result.triggered()).isTrue();
    }

    @Test
    @DisplayName("Should not trigger when amount is not round")
    void shouldNotTrigger_whenNotRound() {
        Transaction tx = txWithAmount("9999.50");
        TransactionFeatures features = structuringFeatures(false, 5);

        RuleResult result = rule.evaluate(tx, features);

        assertThat(result.triggered()).isFalse();
    }

    @Test
    @DisplayName("Should not trigger when amount exceeds threshold")
    void shouldNotTrigger_whenAboveThreshold() {
        Transaction tx = txWithAmount("15000.00");
        TransactionFeatures features = structuringFeatures(true, 5);

        RuleResult result = rule.evaluate(tx, features);

        assertThat(result.triggered()).isFalse();
    }

    @Test
    @DisplayName("Should not trigger when velocity is too low")
    void shouldNotTrigger_whenLowVelocity() {
        Transaction tx = txWithAmount("5000.00");
        TransactionFeatures features = structuringFeatures(true, 2);

        RuleResult result = rule.evaluate(tx, features);

        assertThat(result.triggered()).isFalse();
    }

    @Test
    @DisplayName("Should not trigger when features are null")
    void shouldNotTrigger_whenFeaturesNull() {
        Transaction tx = txWithAmount("5000.00");

        RuleResult result = rule.evaluate(tx, null);

        assertThat(result.triggered()).isFalse();
    }

    private Transaction txWithAmount(String amount) {
        Transaction tx = new Transaction();
        tx.setAmount(new BigDecimal(amount));
        return tx;
    }

    private TransactionFeatures structuringFeatures(boolean isRound, int velocity24h) {
        TransactionFeatures f = new TransactionFeatures();
        f.setIsRoundAmount(isRound);
        f.setTxVelocity24h(velocity24h);
        return f;
    }
}
