package dev.finguard.detection.rule;

import dev.finguard.domain.model.Transaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("LargeTransactionRule")
class LargeTransactionRuleTest {

    /**
     * Stub the threshold service so tests stay isolated from the DB-backed
     * {@link RuleThresholdService}. Tests exercise rule *logic* against a
     * fixed threshold; the service's own tests cover caching / validation.
     */
    private static LargeTransactionRule ruleWithThreshold(BigDecimal threshold) {
        RuleThresholdService svc = mock(RuleThresholdService.class);
        when(svc.getCurrentValue(RuleMetadata.LARGE_TRANSACTION)).thenReturn(threshold);
        return new LargeTransactionRule(svc);
    }

    private final LargeTransactionRule rule = ruleWithThreshold(new BigDecimal("200000.00"));

    @Test
    @DisplayName("Should trigger when amount equals threshold")
    void shouldTrigger_whenAmountEqualsThreshold() {
        Transaction tx = txWithAmount("200000.00");

        RuleResult result = rule.evaluate(tx, null);

        assertThat(result.triggered()).isTrue();
        assertThat(result.ruleName()).isEqualTo("LARGE_TRANSACTION");
        assertThat(result.reason()).contains("200000.00");
    }

    @Test
    @DisplayName("Should trigger when amount exceeds threshold")
    void shouldTrigger_whenAmountExceedsThreshold() {
        Transaction tx = txWithAmount("500000.00");

        RuleResult result = rule.evaluate(tx, null);

        assertThat(result.triggered()).isTrue();
    }

    @Test
    @DisplayName("Should not trigger when amount is below threshold")
    void shouldNotTrigger_whenAmountBelowThreshold() {
        Transaction tx = txWithAmount("199999.99");

        RuleResult result = rule.evaluate(tx, null);

        assertThat(result.triggered()).isFalse();
        assertThat(result.reason()).isNull();
    }

    @Test
    @DisplayName("Should not trigger for zero amount")
    void shouldNotTrigger_forZeroAmount() {
        Transaction tx = txWithAmount("0.00");

        RuleResult result = rule.evaluate(tx, null);

        assertThat(result.triggered()).isFalse();
    }

    @Test
    @DisplayName("Should work without features (null features)")
    void shouldWorkWithoutFeatures() {
        Transaction tx = txWithAmount("300000.00");

        RuleResult result = rule.evaluate(tx, null);

        assertThat(result.triggered()).isTrue();
    }

    private Transaction txWithAmount(String amount) {
        Transaction tx = new Transaction();
        tx.setAmount(new BigDecimal(amount));
        return tx;
    }
}
