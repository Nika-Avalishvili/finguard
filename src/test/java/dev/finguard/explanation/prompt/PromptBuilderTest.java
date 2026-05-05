package dev.finguard.explanation.prompt;

import dev.finguard.domain.enums.DetectionConfig;
import dev.finguard.domain.model.Alert;
import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.model.TransactionFeatures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PromptBuilder")
class PromptBuilderTest {

    private PromptBuilder promptBuilder;

    @BeforeEach
    void setUp() {
        promptBuilder = new PromptBuilder();
    }

    @Nested
    @DisplayName("System Prompt")
    class SystemPromptTests {

        @Test
        @DisplayName("Should include JSON format instructions")
        void systemPrompt_containsJsonInstructions() {
            String systemPrompt = promptBuilder.getSystemPrompt();

            assertThat(systemPrompt).contains("riskSummary");
            assertThat(systemPrompt).contains("explanationText");
            assertThat(systemPrompt).contains("suspiciousPatterns");
            assertThat(systemPrompt).contains("recommendedActions");
            assertThat(systemPrompt).contains("confidenceScore");
        }

        @Test
        @DisplayName("Should include fraud analyst persona")
        void systemPrompt_containsAnalystPersona() {
            String systemPrompt = promptBuilder.getSystemPrompt();

            assertThat(systemPrompt).contains("financial fraud analyst");
        }

        @Test
        @DisplayName("Should instruct JSON-only response")
        void systemPrompt_instructsJsonOnly() {
            String systemPrompt = promptBuilder.getSystemPrompt();

            assertThat(systemPrompt).contains("valid JSON");
            assertThat(systemPrompt).contains("no markdown");
        }
    }

    @Nested
    @DisplayName("Direct Prompt")
    class DirectPromptTests {

        @Test
        @DisplayName("Should include transaction details")
        void directPrompt_includesTransactionDetails() {
            Transaction tx = createTransaction();
            Alert alert = createAlert(tx);

            String prompt = promptBuilder.buildDirectPrompt(alert, tx, null);

            assertThat(prompt).contains("Transaction Details");
            assertThat(prompt).contains("250000");
            assertThat(prompt).contains("SENDER_001");
            assertThat(prompt).contains("RECV_001");
        }

        @Test
        @DisplayName("Should include detection signals")
        void directPrompt_includesDetectionSignals() {
            Transaction tx = createTransaction();
            Alert alert = createAlert(tx);
            alert.setRuleTriggered("LARGE_TRANSACTION: Amount exceeds threshold");
            alert.setMlRiskScore(0.87);
            alert.setMlModelName("RandomForest");

            String prompt = promptBuilder.buildDirectPrompt(alert, tx, null);

            assertThat(prompt).contains("Detection Signals");
            assertThat(prompt).contains("LARGE_TRANSACTION");
            assertThat(prompt).contains("0.8700");
            assertThat(prompt).contains("RandomForest");
        }

        @Test
        @DisplayName("Should include computed features when available")
        void directPrompt_includesFeatures() {
            Transaction tx = createTransaction();
            Alert alert = createAlert(tx);
            TransactionFeatures features = createFeatures();

            String prompt = promptBuilder.buildDirectPrompt(alert, tx, features);

            assertThat(prompt).contains("Computed Features");
            assertThat(prompt).contains("Amount Z-Score");
            assertThat(prompt).contains("3.50");
            assertThat(prompt).contains("Transaction Velocity (1h)");
        }

        @Test
        @DisplayName("Should handle null features gracefully")
        void directPrompt_handlesNullFeatures() {
            Transaction tx = createTransaction();
            Alert alert = createAlert(tx);

            String prompt = promptBuilder.buildDirectPrompt(alert, tx, null);

            assertThat(prompt).doesNotContain("Computed Features");
            assertThat(prompt).contains("Transaction Details");
        }

        @Test
        @DisplayName("Should include balance information when available")
        void directPrompt_includesBalanceInfo() {
            Transaction tx = createTransaction();
            tx.setSenderBalanceBefore(new BigDecimal("1000000.00"));
            tx.setSenderBalanceAfter(new BigDecimal("750000.00"));
            Alert alert = createAlert(tx);

            String prompt = promptBuilder.buildDirectPrompt(alert, tx, null);

            assertThat(prompt).contains("Sender Balance Before");
            assertThat(prompt).contains("1000000.00");
            assertThat(prompt).contains("Sender Balance After");
            assertThat(prompt).contains("750000.00");
        }
    }

    @Nested
    @DisplayName("RAG Prompt")
    class RagPromptTests {

        @Test
        @DisplayName("Should include RAG context documents")
        void ragPrompt_includesRagContext() {
            Transaction tx = createTransaction();
            Alert alert = createAlert(tx);
            List<Document> ragContext = List.of(
                    new Document("Structuring involves breaking large transactions into smaller amounts below reporting thresholds."),
                    new Document("Money mule accounts receive and redistribute illicit funds through multiple rapid transfers.")
            );

            String prompt = promptBuilder.buildRagPrompt(alert, tx, null, ragContext);

            assertThat(prompt).contains("Relevant Fraud Patterns from Knowledge Base");
            assertThat(prompt).contains("Pattern 1");
            assertThat(prompt).contains("Pattern 2");
            assertThat(prompt).contains("Structuring involves");
            assertThat(prompt).contains("Money mule");
        }

        @Test
        @DisplayName("Should handle empty RAG context")
        void ragPrompt_handlesEmptyContext() {
            Transaction tx = createTransaction();
            Alert alert = createAlert(tx);

            String prompt = promptBuilder.buildRagPrompt(alert, tx, null, List.of());

            assertThat(prompt).doesNotContain("Relevant Fraud Patterns");
            assertThat(prompt).contains("Transaction Details");
        }

        @Test
        @DisplayName("Should handle null RAG context")
        void ragPrompt_handlesNullContext() {
            Transaction tx = createTransaction();
            Alert alert = createAlert(tx);

            String prompt = promptBuilder.buildRagPrompt(alert, tx, null, null);

            assertThat(prompt).doesNotContain("Relevant Fraud Patterns");
        }

        @Test
        @DisplayName("Should instruct LLM to cite relevant patterns")
        void ragPrompt_instructsCitation() {
            Transaction tx = createTransaction();
            Alert alert = createAlert(tx);
            List<Document> ragContext = List.of(
                    new Document("Account takeover involves unauthorized access to victim accounts.")
            );

            String prompt = promptBuilder.buildRagPrompt(alert, tx, null, ragContext);

            assertThat(prompt).contains("Cite specific patterns");
        }
    }

    // ================================================================
    // Helpers
    // ================================================================

    private Transaction createTransaction() {
        Transaction tx = new Transaction();
        tx.setId(1L);
        tx.setAmount(new BigDecimal("250000.00"));
        tx.setSenderAccount("SENDER_001");
        tx.setReceiverAccount("RECV_001");
        tx.setTimestamp(LocalDateTime.of(2024, 3, 15, 2, 30));
        return tx;
    }

    private Alert createAlert(Transaction tx) {
        Alert alert = new Alert();
        alert.setId(100L);
        alert.setTransaction(tx);
        alert.setDetectionConfig(DetectionConfig.ML_LLM_RAG);
        alert.setIsAnomaly(true);
        return alert;
    }

    private TransactionFeatures createFeatures() {
        TransactionFeatures f = new TransactionFeatures();
        f.setAmountZscore(3.5);
        f.setTxVelocity1h(5);
        f.setTxVelocity24h(12);
        f.setAvgAmount7d(new BigDecimal("5000.00"));
        f.setAmountRatioToAvg(50.0);
        f.setIsNewReceiver(true);
        f.setReceiverDiversity7d(8);
        f.setIsRoundAmount(true);
        f.setIsHighRiskType(false);
        return f;
    }
}
