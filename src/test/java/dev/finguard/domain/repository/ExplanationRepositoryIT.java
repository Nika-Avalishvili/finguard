package dev.finguard.domain.repository;

import dev.finguard.config.MockAiConfig;
import dev.finguard.config.TestContainersConfig;
import dev.finguard.domain.enums.DatasetSource;
import dev.finguard.domain.enums.DetectionConfig;
import dev.finguard.domain.enums.ExplanationType;
import dev.finguard.domain.enums.TransactionType;
import dev.finguard.domain.model.Alert;
import dev.finguard.domain.model.Explanation;
import dev.finguard.domain.model.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for ExplanationRepository queries.
 *
 * <p>Tests JPQL queries and jsonb column persistence against a real PostgreSQL.</p>
 */
@SpringBootTest
@Import({TestContainersConfig.class, MockAiConfig.class})
@DisplayName("ExplanationRepository (DB integration)")
class ExplanationRepositoryIT {

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private ExplanationRepository explanationRepository;

    @BeforeEach
    void cleanUp() {
        explanationRepository.deleteAll();
        alertRepository.deleteAll();
        transactionRepository.deleteAll();
    }

    // ==============================================================
    // Basic CRUD and jsonb persistence
    // ==============================================================

    @Nested
    @DisplayName("Persistence")
    class Persistence {

        @Test
        @DisplayName("Should persist explanation with all fields including jsonb")
        void shouldPersistWithJsonbFields() {
            Alert alert = createAlert();

            Explanation explanation = new Explanation();
            explanation.setAlert(alert);
            explanation.setExplanationType(ExplanationType.LLM_RAG);
            explanation.setRiskSummary("High-risk large transfer");
            explanation.setExplanationText("Detailed explanation of the suspicious transaction.");
            explanation.setSuspiciousPatterns("[\"Structuring\", \"Money Mule\"]");
            explanation.setRecommendedActions("[\"File SAR\", \"Freeze account\"]");
            explanation.setConfidenceScore(0.92);
            explanation.setHallucinationFree(true);
            explanation.setHallucinationFlags("[]");
            explanation.setPromptTokens(450);
            explanation.setCompletionTokens(230);
            explanation.setLatencyMs(1200);
            explanation.setRagContextIds("[\"doc-1\", \"doc-2\"]");
            explanation.setFullPrompt("Full prompt text...");
            explanation.setRawResponse("{\"riskSummary\": \"...\"}");

            Explanation saved = explanationRepository.save(explanation);

            assertThat(saved.getId()).isNotNull();
            assertThat(saved.getCreatedAt()).isNotNull();

            // Reload from DB and verify jsonb columns
            Explanation reloaded = explanationRepository.findById(saved.getId()).orElseThrow();
            assertThat(reloaded.getSuspiciousPatterns()).contains("Structuring");
            assertThat(reloaded.getRecommendedActions()).contains("File SAR");
            assertThat(reloaded.getRagContextIds()).contains("doc-1");
            assertThat(reloaded.getHallucinationFlags()).isEqualTo("[]");
            assertThat(reloaded.getConfidenceScore()).isEqualTo(0.92);
        }

        @Test
        @DisplayName("Should persist CAKR evaluation scores")
        void shouldPersistCakrScores() {
            Alert alert = createAlert();
            Explanation explanation = createExplanation(alert, ExplanationType.LLM_RAG);
            explanation.setCakrCompleteness(4.0);
            explanation.setCakrCorrectness(3.5);
            explanation.setCakrActionability(4.5);
            explanation.setCakrRegulatory(3.0);
            explanationRepository.save(explanation);

            Explanation reloaded = explanationRepository.findById(explanation.getId()).orElseThrow();
            assertThat(reloaded.getCakrAverage()).isEqualTo(3.75);
        }
    }

    // ==============================================================
    // Query methods
    // ==============================================================

    @Nested
    @DisplayName("Queries")
    class Queries {

        @Test
        @DisplayName("Should find explanations by alert ID")
        void findByAlertId() {
            Alert alert1 = createAlert();
            Alert alert2 = createAlert();
            createExplanation(alert1, ExplanationType.LLM_DIRECT);
            createExplanation(alert1, ExplanationType.LLM_RAG);
            createExplanation(alert2, ExplanationType.LLM_DIRECT);

            List<Explanation> result = explanationRepository.findByAlertId(alert1.getId());

            assertThat(result).hasSize(2);
            assertThat(result).allMatch(e -> e.getAlert().getId().equals(alert1.getId()));
        }

        @Test
        @DisplayName("Should find explanations by alert ID and type")
        void findByAlertIdAndType() {
            Alert alert = createAlert();
            createExplanation(alert, ExplanationType.LLM_DIRECT);
            createExplanation(alert, ExplanationType.LLM_RAG);

            List<Explanation> directOnly =
                    explanationRepository.findByAlertIdAndExplanationType(alert.getId(), ExplanationType.LLM_DIRECT);
            List<Explanation> ragOnly =
                    explanationRepository.findByAlertIdAndExplanationType(alert.getId(), ExplanationType.LLM_RAG);

            assertThat(directOnly).hasSize(1);
            assertThat(ragOnly).hasSize(1);
        }

        @Test
        @DisplayName("Should find explanations by transaction ID")
        void findByTransactionId() {
            Alert alert = createAlert();
            createExplanation(alert, ExplanationType.LLM_RAG);
            createExplanation(alert, ExplanationType.LLM_DIRECT);

            Long txId = alert.getTransaction().getId();
            List<Explanation> result = explanationRepository.findByTransactionId(txId);

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("Should compute average completeness by type")
        void avgCompletenessByType() {
            Alert alert1 = createAlert();
            Alert alert2 = createAlert();

            Explanation e1 = createExplanation(alert1, ExplanationType.LLM_RAG);
            e1.setCakrCompleteness(4.0);
            explanationRepository.save(e1);

            Explanation e2 = createExplanation(alert2, ExplanationType.LLM_RAG);
            e2.setCakrCompleteness(3.0);
            explanationRepository.save(e2);

            Double avg = explanationRepository.avgCompletenessByType(ExplanationType.LLM_RAG);
            assertThat(avg).isEqualTo(3.5);
        }

        @Test
        @DisplayName("Should count hallucinations by type")
        void countHallucinationsByType() {
            Alert alert1 = createAlert();
            Alert alert2 = createAlert();
            Alert alert3 = createAlert();

            Explanation e1 = createExplanation(alert1, ExplanationType.LLM_DIRECT);
            e1.setHallucinationFree(false);
            explanationRepository.save(e1);

            Explanation e2 = createExplanation(alert2, ExplanationType.LLM_DIRECT);
            e2.setHallucinationFree(true);
            explanationRepository.save(e2);

            Explanation e3 = createExplanation(alert3, ExplanationType.LLM_DIRECT);
            e3.setHallucinationFree(false);
            explanationRepository.save(e3);

            long count = explanationRepository.countHallucinationsByType(ExplanationType.LLM_DIRECT);
            assertThat(count).isEqualTo(2);
        }

        @Test
        @DisplayName("Should compute average latency by type")
        void avgLatencyByType() {
            Alert alert1 = createAlert();
            Alert alert2 = createAlert();

            Explanation e1 = createExplanation(alert1, ExplanationType.LLM_RAG);
            e1.setLatencyMs(1000);
            explanationRepository.save(e1);

            Explanation e2 = createExplanation(alert2, ExplanationType.LLM_RAG);
            e2.setLatencyMs(2000);
            explanationRepository.save(e2);

            Double avg = explanationRepository.avgLatencyByType(ExplanationType.LLM_RAG);
            assertThat(avg).isEqualTo(1500.0);
        }
    }

    // ==============================================================
    // Helpers
    // ==============================================================

    private Alert createAlert() {
        Transaction tx = new Transaction();
        tx.setAmount(new BigDecimal("500000.00"));
        tx.setTransactionType(TransactionType.TRANSFER);
        tx.setSenderAccount("SENDER_IT");
        tx.setReceiverAccount("RECEIVER_IT");
        tx.setTimestamp(LocalDateTime.now());
        tx.setDatasetSource(DatasetSource.PAYSIM);
        tx.setExternalId("IT-" + System.nanoTime());
        tx = transactionRepository.save(tx);

        Alert alert = new Alert();
        alert.setTransaction(tx);
        alert.setDetectionConfig(DetectionConfig.ML_LLM_RAG);
        alert.setMlRiskScore(0.87);
        alert.setMlModelName("RandomForest");
        alert.setIsAnomaly(true);
        return alertRepository.save(alert);
    }

    private Explanation createExplanation(Alert alert, ExplanationType type) {
        Explanation explanation = new Explanation();
        explanation.setAlert(alert);
        explanation.setExplanationType(type);
        explanation.setRiskSummary("Test risk summary");
        explanation.setExplanationText("Test explanation text for " + type.name());
        explanation.setConfidenceScore(0.85);
        explanation.setLatencyMs(500);
        return explanationRepository.save(explanation);
    }
}
