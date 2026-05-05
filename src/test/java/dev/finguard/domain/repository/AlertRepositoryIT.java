package dev.finguard.domain.repository;

import dev.finguard.config.MockAiConfig;
import dev.finguard.config.TestContainersConfig;
import dev.finguard.domain.enums.*;
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
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for custom AlertRepository query methods.
 *
 * <p>Validates that the N+1-fixing queries (NOT EXISTS subquery,
 * JOIN FETCH) produce correct results with real PostgreSQL.</p>
 */
@SpringBootTest
@Import({TestContainersConfig.class, MockAiConfig.class})
@DisplayName("AlertRepository (custom query integration)")
class AlertRepositoryIT {

    @Autowired private AlertRepository alertRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private ExplanationRepository explanationRepository;
    @Autowired private TransactionFeaturesRepository featuresRepository;

    @BeforeEach
    void cleanUp() {
        explanationRepository.deleteAll();
        alertRepository.deleteAll();
        featuresRepository.deleteAll();
        transactionRepository.deleteAll();
    }

    // ================================================================
    // findAnomalyAlertsWithoutExplanation
    // ================================================================

    @Nested
    @DisplayName("findAnomalyAlertsWithoutExplanation")
    class FindAnomalyAlertsWithoutExplanation {

        @Test
        @DisplayName("Returns anomaly alerts that have no explanation of the given type")
        void returnsAlertsWithoutExplanation() {
            Alert alert1 = persistAnomalyAlert();
            Alert alert2 = persistAnomalyAlert();

            // Give alert1 an LLM_DIRECT explanation
            persistExplanation(alert1, ExplanationType.LLM_DIRECT);

            // Both lack LLM_RAG explanation → both returned
            List<Alert> result = alertRepository.findAnomalyAlertsWithoutExplanation(
                    DetectionConfig.ML_LLM_RAG, ExplanationType.LLM_RAG, PageRequest.of(0, 50));

            assertThat(result).hasSize(2)
                    .extracting(Alert::getId)
                    .containsExactlyInAnyOrder(alert1.getId(), alert2.getId());
        }

        @Test
        @DisplayName("Excludes alerts that already have an explanation of the given type")
        void excludesAlreadyExplained() {
            Alert alert1 = persistAnomalyAlert();
            Alert alert2 = persistAnomalyAlert();

            // Give alert1 an LLM_DIRECT explanation
            persistExplanation(alert1, ExplanationType.LLM_DIRECT);

            // Query for LLM_DIRECT → only alert2 missing
            List<Alert> result = alertRepository.findAnomalyAlertsWithoutExplanation(
                    DetectionConfig.ML_LLM_RAG, ExplanationType.LLM_DIRECT, PageRequest.of(0, 50));

            assertThat(result).hasSize(1)
                    .extracting(Alert::getId)
                    .containsExactly(alert2.getId());
        }

        @Test
        @DisplayName("Excludes non-anomaly alerts")
        void excludesNonAnomaly() {
            Alert anomaly = persistAnomalyAlert();
            Alert nonAnomaly = persistNonAnomalyAlert();

            List<Alert> result = alertRepository.findAnomalyAlertsWithoutExplanation(
                    DetectionConfig.ML_LLM_RAG, ExplanationType.LLM_RAG, PageRequest.of(0, 50));

            assertThat(result).hasSize(1)
                    .extracting(Alert::getId)
                    .containsExactly(anomaly.getId());
        }

        @Test
        @DisplayName("Respects the page limit")
        void respectsLimit() {
            persistAnomalyAlert();
            persistAnomalyAlert();
            persistAnomalyAlert();

            List<Alert> result = alertRepository.findAnomalyAlertsWithoutExplanation(
                    DetectionConfig.ML_LLM_RAG, ExplanationType.LLM_RAG, PageRequest.of(0, 2));

            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("Returns empty list when all anomaly alerts have explanations")
        void returnsEmptyWhenAllExplained() {
            Alert alert = persistAnomalyAlert();
            persistExplanation(alert, ExplanationType.LLM_RAG);

            List<Alert> result = alertRepository.findAnomalyAlertsWithoutExplanation(
                    DetectionConfig.ML_LLM_RAG, ExplanationType.LLM_RAG, PageRequest.of(0, 50));

            assertThat(result).isEmpty();
        }
    }

    // ================================================================
    // findByIdWithTransaction (single alert JOIN FETCH)
    // ================================================================

    @Nested
    @DisplayName("findByIdWithTransaction")
    class FindByIdWithTransaction {

        @Test
        @DisplayName("Returns alert with transaction eagerly loaded")
        void returnsAlertWithTransactionLoaded() {
            Alert saved = persistAnomalyAlert();

            java.util.Optional<Alert> result = alertRepository.findByIdWithTransaction(saved.getId());

            assertThat(result).isPresent();
            Alert alert = result.get();
            // Accessing non-ID field must not throw LazyInitializationException
            assertThat(alert.getTransaction()).isNotNull();
            assertThat(alert.getTransaction().getAmount()).isNotNull();
            assertThat(alert.getTransaction().getSenderAccount()).isNotNull();
        }

        @Test
        @DisplayName("Returns empty Optional for unknown id")
        void returnsEmptyForUnknownId() {
            assertThat(alertRepository.findByIdWithTransaction(-1L)).isEmpty();
        }
    }

    // ================================================================
    // findTransactionIdsForConfigAndIdIn (per-batch check)
    // ================================================================

    @Nested
    @DisplayName("findTransactionIdsForConfigAndIdIn")
    class FindTransactionIdsForConfigAndIdIn {

        @Test
        @DisplayName("Returns only IDs that have alerts for the given config within the batch")
        void returnsMatchingIdsOnly() {
            Transaction tx1 = persistTransaction();
            Transaction tx2 = persistTransaction();
            Transaction tx3 = persistTransaction();

            persistAlert(tx1, DetectionConfig.RULES_ONLY, true);
            persistAlert(tx2, DetectionConfig.RULES_ONLY, true);
            // tx3 has no alert

            Set<Long> result = alertRepository.findTransactionIdsForConfigAndIdIn(
                    DetectionConfig.RULES_ONLY, List.of(tx1.getId(), tx2.getId(), tx3.getId()));

            assertThat(result).containsExactlyInAnyOrder(tx1.getId(), tx2.getId());
        }

        @Test
        @DisplayName("Does not return IDs with alerts for a different config")
        void excludesDifferentConfig() {
            Transaction tx = persistTransaction();
            persistAlert(tx, DetectionConfig.ML_ONLY, true);

            Set<Long> result = alertRepository.findTransactionIdsForConfigAndIdIn(
                    DetectionConfig.RULES_ONLY, List.of(tx.getId()));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Returns empty set when no IDs in batch have alerts")
        void returnsEmptyWhenNoMatch() {
            Transaction tx = persistTransaction();
            // No alert for this tx

            Set<Long> result = alertRepository.findTransactionIdsForConfigAndIdIn(
                    DetectionConfig.RULES_ONLY, List.of(tx.getId()));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Returns empty set for empty input batch")
        void returnsEmptyForEmptyInput() {
            Set<Long> result = alertRepository.findTransactionIdsForConfigAndIdIn(
                    DetectionConfig.RULES_ONLY, List.of());

            assertThat(result).isEmpty();
        }
    }

    // ================================================================
    // Helpers
    // ================================================================

    private Transaction persistTransaction() {
        Transaction tx = new Transaction();
        tx.setAmount(new BigDecimal("500000.00"));
        tx.setTransactionType(TransactionType.TRANSFER);
        tx.setSenderAccount("SENDER-REPO-" + System.nanoTime());
        tx.setReceiverAccount("RECEIVER-REPO-" + System.nanoTime());
        tx.setTimestamp(LocalDateTime.now());
        tx.setDatasetSource(DatasetSource.PAYSIM);
        tx.setExternalId("REPO-" + System.nanoTime());
        tx.setIsFraud(true);
        return transactionRepository.save(tx);
    }

    private Alert persistAnomalyAlert() {
        Transaction tx = persistTransaction();
        return persistAlert(tx, DetectionConfig.ML_LLM_RAG, true);
    }

    private Alert persistNonAnomalyAlert() {
        Transaction tx = persistTransaction();
        return persistAlert(tx, DetectionConfig.RULES_ONLY, false);
    }

    private Alert persistAlert(Transaction tx, DetectionConfig config, boolean isAnomaly) {
        Alert alert = new Alert();
        alert.setTransaction(tx);
        alert.setDetectionConfig(config);
        alert.setIsAnomaly(isAnomaly);
        alert.setStatus(AlertStatus.NEW);
        return alertRepository.save(alert);
    }

    private Explanation persistExplanation(Alert alert, ExplanationType type) {
        Explanation explanation = new Explanation();
        explanation.setAlert(alert);
        explanation.setExplanationType(type);
        explanation.setRiskSummary("Test summary");
        explanation.setExplanationText("Test explanation text");
        explanation.setConfidenceScore(0.85);
        explanation.setLatencyMs(150);
        return explanationRepository.save(explanation);
    }
}
