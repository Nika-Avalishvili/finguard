package dev.finguard.explanation.validation;

import dev.finguard.domain.enums.DetectionConfig;
import dev.finguard.domain.model.Alert;
import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.model.TransactionFeatures;
import dev.finguard.explanation.llm.ExplanationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HallucinationValidator")
class HallucinationValidatorTest {

    private HallucinationValidator validator;

    @BeforeEach
    void setUp() {
        validator = new HallucinationValidator();
        ReflectionTestUtils.setField(validator, "confidenceThreshold", 0.6);
    }

    @Nested
    @DisplayName("Clean Explanations")
    class CleanExplanationTests {

        @Test
        @DisplayName("Should pass validation for a correct explanation")
        void validate_cleanExplanation_passes() {
            Transaction tx = txWithAmount("250000.00");
            Alert alert = alertForTx(tx, 0.92);
            ExplanationResponse response = new ExplanationResponse(
                    "Transaction of 250000.00 is suspicious due to high amount",
                    "The transaction amount of 250000.00 from SENDER_001 to RECV_001 " +
                            "significantly exceeds normal patterns. The ML model flagged this " +
                            "with a risk score of 0.92, indicating high fraud probability.",
                    List.of("Large Transaction", "Structuring"),
                    List.of("Investigate sender account", "File SAR"),
                    0.85
            );

            HallucinationValidator.ValidationResult result =
                    validator.validate(response, alert, tx, null);

            assertThat(result.hallucinationFree()).isTrue();
            assertThat(result.flags()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Empty Field Detection")
    class EmptyFieldTests {

        @Test
        @DisplayName("Should flag empty risk summary")
        void validate_emptyRiskSummary_flags() {
            Transaction tx = txWithAmount("1000.00");
            Alert alert = alertForTx(tx, 0.7);
            ExplanationResponse response = new ExplanationResponse(
                    "",
                    "The transaction of 1000.00 was flagged.",
                    List.of("Pattern"),
                    List.of("Action"),
                    0.7
            );

            var result = validator.validate(response, alert, tx, null);

            assertThat(result.hallucinationFree()).isFalse();
            assertThat(result.flags()).anyMatch(f -> f.contains("EMPTY_RISK_SUMMARY"));
        }

        @Test
        @DisplayName("Should flag empty suspicious patterns")
        void validate_emptyPatterns_flags() {
            Transaction tx = txWithAmount("1000.00");
            Alert alert = alertForTx(tx, 0.7);
            ExplanationResponse response = new ExplanationResponse(
                    "Risk summary",
                    "The transaction of 1000.00 was flagged.",
                    List.of(),
                    List.of("Review"),
                    0.7
            );

            var result = validator.validate(response, alert, tx, null);

            assertThat(result.hallucinationFree()).isFalse();
            assertThat(result.flags()).anyMatch(f -> f.contains("EMPTY_SUSPICIOUS_PATTERNS"));
        }

        @Test
        @DisplayName("Should flag empty recommended actions")
        void validate_emptyActions_flags() {
            Transaction tx = txWithAmount("1000.00");
            Alert alert = alertForTx(tx, 0.7);
            ExplanationResponse response = new ExplanationResponse(
                    "Risk summary",
                    "The transaction of 1000.00 was flagged.",
                    List.of("Pattern"),
                    List.of(),
                    0.7
            );

            var result = validator.validate(response, alert, tx, null);

            assertThat(result.hallucinationFree()).isFalse();
            assertThat(result.flags()).anyMatch(f -> f.contains("EMPTY_RECOMMENDED_ACTIONS"));
        }
    }

    @Nested
    @DisplayName("Confidence Range")
    class ConfidenceTests {

        @Test
        @DisplayName("Should flag confidence above 1.0")
        void validate_confidenceAboveOne_flags() {
            Transaction tx = txWithAmount("5000.00");
            Alert alert = alertForTx(tx, 0.8);
            ExplanationResponse response = new ExplanationResponse(
                    "Summary",
                    "The transaction of 5000.00 was flagged.",
                    List.of("Pattern"),
                    List.of("Action"),
                    1.5
            );

            var result = validator.validate(response, alert, tx, null);

            assertThat(result.flags()).anyMatch(f -> f.contains("CONFIDENCE_OUT_OF_RANGE"));
        }

        @Test
        @DisplayName("Should flag negative confidence")
        void validate_negativeConfidence_flags() {
            Transaction tx = txWithAmount("5000.00");
            Alert alert = alertForTx(tx, 0.8);
            ExplanationResponse response = new ExplanationResponse(
                    "Summary",
                    "The transaction of 5000.00 was flagged.",
                    List.of("Pattern"),
                    List.of("Action"),
                    -0.1
            );

            var result = validator.validate(response, alert, tx, null);

            assertThat(result.flags()).anyMatch(f -> f.contains("CONFIDENCE_OUT_OF_RANGE"));
        }

        @Test
        @DisplayName("Should accept confidence at boundaries (0.0 and 1.0)")
        void validate_confidenceAtBoundaries_passes() {
            Transaction tx = txWithAmount("5000.00");
            Alert alert = alertForTx(tx, 0.8);

            var resultZero = validator.validate(
                    responseWithConfidence(tx, 0.0), alert, tx, null);
            var resultOne = validator.validate(
                    responseWithConfidence(tx, 1.0), alert, tx, null);

            assertThat(resultZero.flags()).noneMatch(f -> f.contains("CONFIDENCE_OUT_OF_RANGE"));
            assertThat(resultOne.flags()).noneMatch(f -> f.contains("CONFIDENCE_OUT_OF_RANGE"));
        }
    }

    @Nested
    @DisplayName("Amount Consistency")
    class AmountConsistencyTests {

        @Test
        @DisplayName("Should flag when transaction amount is not mentioned")
        void validate_amountNotMentioned_flags() {
            Transaction tx = txWithAmount("175432.50");
            Alert alert = alertForTx(tx, 0.9);
            ExplanationResponse response = new ExplanationResponse(
                    "Suspicious transaction detected",
                    "This transaction shows unusual patterns with a very large " +
                            "transfer that exceeds normal thresholds.",
                    List.of("Large Transaction"),
                    List.of("Review"),
                    0.85
            );

            var result = validator.validate(response, alert, tx, null);

            assertThat(result.flags()).anyMatch(f -> f.contains("AMOUNT_NOT_REFERENCED"));
        }

        @Test
        @DisplayName("Should accept amount in dollar format")
        void validate_amountWithDollarSign_passes() {
            Transaction tx = txWithAmount("250000.00");
            Alert alert = alertForTx(tx, 0.9);
            ExplanationResponse response = new ExplanationResponse(
                    "Summary",
                    "The $250000.00 transaction from SENDER_001 to RECV_001 is suspicious.",
                    List.of("Pattern"),
                    List.of("Action"),
                    0.85
            );

            var result = validator.validate(response, alert, tx, null);

            assertThat(result.flags()).noneMatch(f -> f.contains("AMOUNT_NOT_REFERENCED"));
        }

        @Test
        @DisplayName("Should accept amount with commas")
        void validate_amountWithCommas_passes() {
            Transaction tx = txWithAmount("250000.00");
            Alert alert = alertForTx(tx, 0.9);
            ExplanationResponse response = new ExplanationResponse(
                    "Summary",
                    "A 250,000 transfer was detected from sender account to receiver.",
                    List.of("Pattern"),
                    List.of("Action"),
                    0.85
            );

            var result = validator.validate(response, alert, tx, null);

            assertThat(result.flags()).noneMatch(f -> f.contains("AMOUNT_NOT_REFERENCED"));
        }
    }


        @Test
        @DisplayName("Should accept K-abbreviated amount within 20%")
        void validate_kAbbreviatedAmount_passes() {
            Transaction tx = txWithAmount("175432.50");
            Alert alert = alertForTx(tx, 0.9);
            ExplanationResponse response = new ExplanationResponse(
                    "A transfer of approximately $175K was flagged.",
                    "The transaction exceeds normal thresholds for transfers.",
                    List.of("Large Transaction"),
                    List.of("Review"),
                    0.85
            );

            var result = validator.validate(response, alert, tx, null);

            assertThat(result.flags()).noneMatch(f -> f.contains("AMOUNT_NOT_REFERENCED"));
        }

        @Test
        @DisplayName("Should accept amount within 20% fuzzy tolerance")
        void validate_fuzzyAmount_passes() {
            Transaction tx = txWithAmount("181234.23");
            Alert alert = alertForTx(tx, 0.9);
            ExplanationResponse response = new ExplanationResponse(
                    "Summary",
                    "The transaction of approximately $180,000 significantly exceeds normal limits.",
                    List.of("Large Transaction"),
                    List.of("Review"),
                    0.85
            );

            var result = validator.validate(response, alert, tx, null);

            assertThat(result.flags()).noneMatch(f -> f.contains("AMOUNT_NOT_REFERENCED"));
        }

        @Test
        @DisplayName("Should accept amount mentioned only in riskSummary")
        void validate_amountInRiskSummaryOnly_passes() {
            Transaction tx = txWithAmount("50000.00");
            Alert alert = alertForTx(tx, 0.9);
            ExplanationResponse response = new ExplanationResponse(
                    "Transaction of $50,000 flagged as anomalous",
                    "This transfer shows unusual patterns consistent with fraud.",
                    List.of("Pattern"),
                    List.of("Action"),
                    0.85
            );

            var result = validator.validate(response, alert, tx, null);

            assertThat(result.flags()).noneMatch(f -> f.contains("AMOUNT_NOT_REFERENCED"));
        }

        @Test
        @DisplayName("Should flag when number in text is outside 20% tolerance")
        void validate_amountOutsideTolerance_flags() {
            Transaction tx = txWithAmount("175432.50");
            Alert alert = alertForTx(tx, 0.9);
            ExplanationResponse response = new ExplanationResponse(
                    "Suspicious transaction detected",
                    "A transfer of $50,000 was sent from an account with unusual patterns.",
                    List.of("Large Transaction"),
                    List.of("Review"),
                    0.85
            );

            var result = validator.validate(response, alert, tx, null);

            assertThat(result.flags()).anyMatch(f -> f.contains("AMOUNT_NOT_REFERENCED"));
        }

    @Nested
    @DisplayName("Risk Score Consistency")
    class RiskScoreTests {

        @Test
        @DisplayName("Should flag 'low risk' when ML score is very high")
        void validate_lowRiskClaimWithHighScore_flags() {
            Transaction tx = txWithAmount("50000.00");
            Alert alert = alertForTx(tx, 0.95);
            ExplanationResponse response = new ExplanationResponse(
                    "Summary",
                    "The transaction of 50000.00 appears to be low risk and unlikely to be fraudulent.",
                    List.of("Pattern"),
                    List.of("Action"),
                    0.3
            );

            var result = validator.validate(response, alert, tx, null);

            assertThat(result.flags()).anyMatch(f -> f.contains("RISK_DIRECTION_MISMATCH"));
        }

        @Test
        @DisplayName("Should flag certainty claims when ML score is moderate")
        void validate_certaintyWithModerateScore_flags() {
            Transaction tx = txWithAmount("50000.00");
            Alert alert = alertForTx(tx, 0.55);
            ExplanationResponse response = new ExplanationResponse(
                    "Summary",
                    "This 50000.00 transaction is certainly fraud and must be blocked immediately.",
                    List.of("Pattern"),
                    List.of("Action"),
                    0.95
            );

            var result = validator.validate(response, alert, tx, null);

            assertThat(result.flags()).anyMatch(f -> f.contains("RISK_OVERCLAIM"));
        }

        @Test
        @DisplayName("Should not flag when risk assessment matches ML score direction")
        void validate_consistentRiskAssessment_passes() {
            Transaction tx = txWithAmount("50000.00");
            Alert alert = alertForTx(tx, 0.92);
            ExplanationResponse response = new ExplanationResponse(
                    "High-risk transaction",
                    "The 50000.00 transaction is highly suspicious based on the elevated risk score.",
                    List.of("Pattern"),
                    List.of("Action"),
                    0.85
            );

            var result = validator.validate(response, alert, tx, null);

            assertThat(result.flags()).noneMatch(f -> f.contains("RISK_DIRECTION_MISMATCH"));
            assertThat(result.flags()).noneMatch(f -> f.contains("RISK_OVERCLAIM"));
        }
    }

    @Nested
    @DisplayName("Account Consistency")
    class AccountConsistencyTests {

        @Test
        @DisplayName("Should flag when fabricated account IDs appear")
        void validate_fabricatedAccountId_flags() {
            Transaction tx = txWithAmount("50000.00");
            Alert alert = alertForTx(tx, 0.8);
            ExplanationResponse response = new ExplanationResponse(
                    "Summary",
                    "The 50000.00 transfer from sender account C99887766 to receiver " +
                            "account shows suspicious patterns.",
                    List.of("Pattern"),
                    List.of("Action"),
                    0.85
            );

            var result = validator.validate(response, alert, tx, null);

            assertThat(result.flags()).anyMatch(f -> f.contains("ACCOUNT_FABRICATION"));
        }

        @Test
        @DisplayName("Should pass when correct account IDs are referenced")
        void validate_correctAccounts_passes() {
            Transaction tx = txWithAmount("50000.00");
            Alert alert = alertForTx(tx, 0.8);
            ExplanationResponse response = new ExplanationResponse(
                    "Summary",
                    "The 50000.00 transfer from sender SENDER_001 to receiver RECV_001 " +
                            "is suspicious.",
                    List.of("Pattern"),
                    List.of("Action"),
                    0.85
            );

            var result = validator.validate(response, alert, tx, null);

            assertThat(result.flags()).noneMatch(f -> f.contains("ACCOUNT_FABRICATION"));
        }
    }

    // ================================================================
    // Helpers
    // ================================================================

    private Transaction txWithAmount(String amount) {
        Transaction tx = new Transaction();
        tx.setId(1L);
        tx.setAmount(new BigDecimal(amount));
        tx.setSenderAccount("SENDER_001");
        tx.setReceiverAccount("RECV_001");
        return tx;
    }

    private Alert alertForTx(Transaction tx, Double mlRiskScore) {
        Alert alert = new Alert();
        alert.setId(100L);
        alert.setTransaction(tx);
        alert.setDetectionConfig(DetectionConfig.ML_LLM_RAG);
        alert.setMlRiskScore(mlRiskScore);
        alert.setIsAnomaly(true);
        return alert;
    }

    private ExplanationResponse responseWithConfidence(Transaction tx, double confidence) {
        return new ExplanationResponse(
                "Summary",
                "The transaction of " + tx.getAmount().toPlainString() + " was flagged.",
                List.of("Pattern"),
                List.of("Action"),
                confidence
        );
    }
}
