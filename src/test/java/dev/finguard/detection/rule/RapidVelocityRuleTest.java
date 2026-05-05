package dev.finguard.detection.rule;

import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.model.TransactionFeatures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("RapidVelocityRule")
class RapidVelocityRuleTest {

    private static RapidVelocityRule ruleWithThreshold(int countThreshold) {
        RuleThresholdService svc = mock(RuleThresholdService.class);
        when(svc.getCurrentIntValue(RuleMetadata.RAPID_VELOCITY)).thenReturn(countThreshold);
        return new RapidVelocityRule(svc);
    }

    private final RapidVelocityRule rule = ruleWithThreshold(3);

    @Test
    @DisplayName("Should trigger when velocity meets threshold")
    void shouldTrigger_whenVelocityMeetsThreshold() {
        Transaction tx = baseTx();
        TransactionFeatures features = featuresWithVelocity1h(3);

        RuleResult result = rule.evaluate(tx, features);

        assertThat(result.triggered()).isTrue();
        assertThat(result.ruleName()).isEqualTo("RAPID_VELOCITY");
        assertThat(result.reason()).contains("3 transactions");
    }

    @Test
    @DisplayName("Should trigger when velocity exceeds threshold")
    void shouldTrigger_whenVelocityExceedsThreshold() {
        Transaction tx = baseTx();
        TransactionFeatures features = featuresWithVelocity1h(10);

        RuleResult result = rule.evaluate(tx, features);

        assertThat(result.triggered()).isTrue();
    }

    @Test
    @DisplayName("Should not trigger when velocity is below threshold")
    void shouldNotTrigger_whenVelocityBelowThreshold() {
        Transaction tx = baseTx();
        TransactionFeatures features = featuresWithVelocity1h(2);

        RuleResult result = rule.evaluate(tx, features);

        assertThat(result.triggered()).isFalse();
    }

    @Test
    @DisplayName("Should not trigger when features are null")
    void shouldNotTrigger_whenFeaturesNull() {
        Transaction tx = baseTx();

        RuleResult result = rule.evaluate(tx, null);

        assertThat(result.triggered()).isFalse();
    }

    @Test
    @DisplayName("Should not trigger when velocity field is null")
    void shouldNotTrigger_whenVelocityFieldNull() {
        Transaction tx = baseTx();
        TransactionFeatures features = new TransactionFeatures();

        RuleResult result = rule.evaluate(tx, features);

        assertThat(result.triggered()).isFalse();
    }

    private Transaction baseTx() {
        Transaction tx = new Transaction();
        tx.setSenderAccount("SENDER_001");
        tx.setAmount(new BigDecimal("1000.00"));
        return tx;
    }

    private TransactionFeatures featuresWithVelocity1h(int velocity) {
        TransactionFeatures f = new TransactionFeatures();
        f.setTxVelocity1h(velocity);
        return f;
    }
}
