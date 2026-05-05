package dev.finguard.detection.rule;

import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.model.TransactionFeatures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("NewReceiverHighValueRule")
class NewReceiverHighValueRuleTest {

    private static NewReceiverHighValueRule ruleWithThreshold(BigDecimal threshold) {
        RuleThresholdService svc = mock(RuleThresholdService.class);
        when(svc.getCurrentValue(RuleMetadata.NEW_RECEIVER_HIGH_VALUE)).thenReturn(threshold);
        return new NewReceiverHighValueRule(svc);
    }

    private final NewReceiverHighValueRule rule = ruleWithThreshold(new BigDecimal("50000.00"));

    @Test
    @DisplayName("Should trigger when new receiver and high value")
    void shouldTrigger_whenNewReceiverAndHighValue() {
        Transaction tx = txWithAmount("75000.00");
        TransactionFeatures features = featuresWithNewReceiver(true);

        RuleResult result = rule.evaluate(tx, features);

        assertThat(result.triggered()).isTrue();
        assertThat(result.ruleName()).isEqualTo("NEW_RECEIVER_HIGH_VALUE");
        assertThat(result.reason()).contains("new receiver");
    }

    @Test
    @DisplayName("Should trigger at exact threshold with new receiver")
    void shouldTrigger_atExactThreshold() {
        Transaction tx = txWithAmount("50000.00");
        TransactionFeatures features = featuresWithNewReceiver(true);

        RuleResult result = rule.evaluate(tx, features);

        assertThat(result.triggered()).isTrue();
    }

    @Test
    @DisplayName("Should not trigger when receiver is known")
    void shouldNotTrigger_whenReceiverKnown() {
        Transaction tx = txWithAmount("100000.00");
        TransactionFeatures features = featuresWithNewReceiver(false);

        RuleResult result = rule.evaluate(tx, features);

        assertThat(result.triggered()).isFalse();
    }

    @Test
    @DisplayName("Should not trigger when amount is below threshold")
    void shouldNotTrigger_whenLowAmount() {
        Transaction tx = txWithAmount("25000.00");
        TransactionFeatures features = featuresWithNewReceiver(true);

        RuleResult result = rule.evaluate(tx, features);

        assertThat(result.triggered()).isFalse();
    }

    @Test
    @DisplayName("Should not trigger when features are null")
    void shouldNotTrigger_whenFeaturesNull() {
        Transaction tx = txWithAmount("100000.00");

        RuleResult result = rule.evaluate(tx, null);

        assertThat(result.triggered()).isFalse();
    }

    @Test
    @DisplayName("Should not trigger when isNewReceiver is null")
    void shouldNotTrigger_whenNewReceiverFieldNull() {
        Transaction tx = txWithAmount("100000.00");
        TransactionFeatures features = new TransactionFeatures();

        RuleResult result = rule.evaluate(tx, features);

        assertThat(result.triggered()).isFalse();
    }

    private Transaction txWithAmount(String amount) {
        Transaction tx = new Transaction();
        tx.setAmount(new BigDecimal(amount));
        tx.setReceiverAccount("RECEIVER_X");
        return tx;
    }

    private TransactionFeatures featuresWithNewReceiver(boolean isNew) {
        TransactionFeatures f = new TransactionFeatures();
        f.setIsNewReceiver(isNew);
        return f;
    }
}
