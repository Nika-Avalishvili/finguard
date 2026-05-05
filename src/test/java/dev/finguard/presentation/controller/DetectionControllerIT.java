package dev.finguard.presentation.controller;

import dev.finguard.config.MockAiConfig;
import dev.finguard.config.TestContainersConfig;
import dev.finguard.detection.ml.MLPredictionResult;
import dev.finguard.detection.ml.TribuoModelService;
import dev.finguard.detection.rule.RuleMetadata;
import dev.finguard.detection.rule.RuleThresholdService;
import dev.finguard.domain.enums.*;
import dev.finguard.domain.model.Alert;
import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.model.TransactionFeatures;
import dev.finguard.domain.repository.*;
import dev.finguard.explanation.llm.LLMExplanationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the DetectionController REST API.
 *
 * <p>Tests the full HTTP layer: request → controller → DetectionPipelineService → DB.
 * ML and LLM services are mocked to isolate detection logic.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, MockAiConfig.class})
@DisplayName("DetectionController (REST API integration)")
class DetectionControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private TransactionFeaturesRepository featuresRepository;
    @Autowired private AlertRepository alertRepository;
    @Autowired private ExplanationRepository explanationRepository;

    @MockitoBean private TribuoModelService mlService;
    @MockitoBean private LLMExplanationService llmService;

    @Autowired private RuleThresholdService ruleThresholdService;

    @BeforeEach
    void cleanUp() {
        explanationRepository.deleteAll();
        alertRepository.deleteAll();
        featuresRepository.deleteAll();
        transactionRepository.deleteAll();
        // Pin thresholds to paper defaults so fixture amounts continue to
        // trigger the rules they were written against (see DetectionPipelineIT).
        ruleThresholdService.updateThreshold(RuleMetadata.LARGE_TRANSACTION.name(),
                RuleMetadata.LARGE_TRANSACTION.getPaperDefault());
        ruleThresholdService.updateThreshold(RuleMetadata.NEW_RECEIVER_HIGH_VALUE.name(),
                RuleMetadata.NEW_RECEIVER_HIGH_VALUE.getPaperDefault());
        ruleThresholdService.updateThreshold(RuleMetadata.STRUCTURING.name(),
                RuleMetadata.STRUCTURING.getPaperDefault());
        ruleThresholdService.updateThreshold(RuleMetadata.RAPID_VELOCITY.name(),
                RuleMetadata.RAPID_VELOCITY.getPaperDefault());
    }

    // ==============================================================
    // POST /api/v1/detection/run
    // ==============================================================

    @Nested
    @DisplayName("POST /api/v1/detection/run")
    class RunDetection {

        @Test
        @DisplayName("RULES_ONLY creates alerts for large transactions")
        void rulesOnly_createsAlertsForLargeTransactions() throws Exception {
            persistTransaction("500000.00", TransactionType.TRANSFER, false);
            persistTransaction("100.00", TransactionType.PAYMENT, false);

            mockMvc.perform(post("/api/v1/detection/run")
                            .param("config", "RULES_ONLY"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.config").value("RULES_ONLY"))
                    .andExpect(jsonPath("$.alertsCreated").value(1))
                    .andExpect(jsonPath("$.transactionsProcessed").value(2))
                    .andExpect(jsonPath("$.durationSeconds").isNumber());

            assertThat(alertRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("RULES_ONLY creates zero alerts when all transactions are clean")
        void rulesOnly_zeroAlertsWhenClean() throws Exception {
            persistTransaction("100.00", TransactionType.PAYMENT, false);
            persistTransaction("200.00", TransactionType.CASH_IN, false);

            mockMvc.perform(post("/api/v1/detection/run")
                            .param("config", "RULES_ONLY"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.alertsCreated").value(0));
        }

        @Test
        @DisplayName("RULES_ONLY detects structuring pattern")
        void rulesOnly_detectsStructuring() throws Exception {
            Transaction tx = persistTransaction("9000.00", TransactionType.TRANSFER, true);
            TransactionFeatures features = new TransactionFeatures();
            features.setTransaction(tx);
            features.setIsRoundAmount(true);
            features.setTxVelocity24h(5);
            featuresRepository.save(features);

            mockMvc.perform(post("/api/v1/detection/run")
                            .param("config", "RULES_ONLY"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.alertsCreated").value(1));

            Alert alert = alertRepository.findAll().get(0);
            assertThat(alert.getRuleTriggered()).contains("STRUCTURING");
        }

        @Test
        @DisplayName("Running detection twice does not duplicate alerts")
        void rulesOnly_idempotent() throws Exception {
            persistTransaction("500000.00", TransactionType.TRANSFER, true);

            mockMvc.perform(post("/api/v1/detection/run").param("config", "RULES_ONLY"))
                    .andExpect(jsonPath("$.alertsCreated").value(1));

            mockMvc.perform(post("/api/v1/detection/run").param("config", "RULES_ONLY"))
                    .andExpect(jsonPath("$.alertsCreated").value(0));

            assertThat(alertRepository.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("Default config is RULES_ONLY when omitted")
        void defaultConfig_isRulesOnly() throws Exception {
            persistTransaction("500000.00", TransactionType.TRANSFER, true);

            mockMvc.perform(post("/api/v1/detection/run"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.config").value("RULES_ONLY"))
                    .andExpect(jsonPath("$.alertsCreated").value(1));
        }

        @Test
        @DisplayName("Invalid config value returns 400 with allowed values")
        void invalidConfig_returnsBadRequest() throws Exception {
            mockMvc.perform(post("/api/v1/detection/run")
                            .param("config", "NONSENSE"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Invalid Parameter"))
                    .andExpect(jsonPath("$.allowedValues").value(containsString("RULES_ONLY")));
        }

        @Test
        @DisplayName("FULL_SYSTEM with mocked ML creates alerts combining rules and ML")
        void fullSystem_combinesRulesAndMl() throws Exception {
            when(mlService.isModelAvailable()).thenReturn(true);
            when(mlService.predict(any(), any())).thenReturn(
                    new MLPredictionResult("RandomForest", 0.92, true,
                            Map.of("amount_zscore", 0.4)));
            when(llmService.generateRagExplanation(any())).thenReturn(null);

            persistTransaction("500000.00", TransactionType.TRANSFER, true);

            mockMvc.perform(post("/api/v1/detection/run")
                            .param("config", "FULL_SYSTEM"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.config").value("FULL_SYSTEM"))
                    .andExpect(jsonPath("$.alertsCreated").value(1));

            Alert alert = alertRepository.findAll().get(0);
            assertThat(alert.getRuleTriggered()).contains("LARGE_TRANSACTION");
            assertThat(alert.getMlRiskScore()).isEqualTo(0.92);
        }
    }

    // ==============================================================
    // GET /api/v1/detection/alerts
    // ==============================================================

    @Nested
    @DisplayName("GET /api/v1/detection/alerts")
    class ListAlerts {

        @Test
        @DisplayName("Returns paginated alerts")
        void returnsPaginatedAlerts() throws Exception {
            Transaction tx1 = persistTransaction("500000.00", TransactionType.TRANSFER, true);
            Transaction tx2 = persistTransaction("300000.00", TransactionType.TRANSFER, true);
            persistAlert(tx1, DetectionConfig.RULES_ONLY, null);
            persistAlert(tx2, DetectionConfig.FULL_SYSTEM, 0.9);

            mockMvc.perform(get("/api/v1/detection/alerts"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(2)))
                    .andExpect(jsonPath("$.totalElements").value(2))
                    .andExpect(jsonPath("$.content[0].id").isNumber())
                    .andExpect(jsonPath("$.content[0].transactionId").isNumber())
                    .andExpect(jsonPath("$.content[0].status").value("NEW"));
        }

        @Test
        @DisplayName("Filters by alert status")
        void filtersByStatus() throws Exception {
            Transaction tx1 = persistTransaction("500000.00", TransactionType.TRANSFER, true);
            Transaction tx2 = persistTransaction("300000.00", TransactionType.TRANSFER, true);
            Alert a1 = persistAlert(tx1, DetectionConfig.RULES_ONLY, null);
            persistAlert(tx2, DetectionConfig.RULES_ONLY, null);

            a1.setStatus(AlertStatus.CONFIRMED_FRAUD);
            alertRepository.save(a1);

            mockMvc.perform(get("/api/v1/detection/alerts")
                            .param("status", "CONFIRMED_FRAUD"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(1)))
                    .andExpect(jsonPath("$.content[0].status").value("CONFIRMED_FRAUD"));
        }

        @Test
        @DisplayName("Filters by detection config")
        void filtersByConfig() throws Exception {
            Transaction tx1 = persistTransaction("500000.00", TransactionType.TRANSFER, true);
            Transaction tx2 = persistTransaction("300000.00", TransactionType.TRANSFER, true);
            persistAlert(tx1, DetectionConfig.RULES_ONLY, null);
            persistAlert(tx2, DetectionConfig.FULL_SYSTEM, 0.88);

            mockMvc.perform(get("/api/v1/detection/alerts")
                            .param("config", "FULL_SYSTEM"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(1)))
                    .andExpect(jsonPath("$.content[0].detectionConfig").value("FULL_SYSTEM"));
        }

        @Test
        @DisplayName("Returns empty page when no alerts exist")
        void emptyPage() throws Exception {
            mockMvc.perform(get("/api/v1/detection/alerts"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(0)))
                    .andExpect(jsonPath("$.totalElements").value(0));
        }

        @Test
        @DisplayName("Pagination with custom size works")
        void paginationWithCustomSize() throws Exception {
            for (int i = 0; i < 5; i++) {
                Transaction tx = persistTransaction("500000.00", TransactionType.TRANSFER, true);
                persistAlert(tx, DetectionConfig.RULES_ONLY, null);
            }

            mockMvc.perform(get("/api/v1/detection/alerts")
                            .param("page", "0")
                            .param("size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(2)))
                    .andExpect(jsonPath("$.totalElements").value(5))
                    .andExpect(jsonPath("$.totalPages").value(3));
        }
    }

    // ==============================================================
    // GET /api/v1/detection/alerts/{id}
    // ==============================================================

    @Nested
    @DisplayName("GET /api/v1/detection/alerts/{id}")
    class GetAlertById {

        @Test
        @DisplayName("Returns alert with all fields populated")
        void returnsAlertWithAllFields() throws Exception {
            Transaction tx = persistTransaction("500000.00", TransactionType.TRANSFER, true);
            Alert alert = persistAlert(tx, DetectionConfig.FULL_SYSTEM, 0.92);
            alert.setRuleTriggered("LARGE_TRANSACTION: amount exceeds threshold");
            alert.setMlModelName("RandomForest");
            alertRepository.save(alert);

            mockMvc.perform(get("/api/v1/detection/alerts/{id}", alert.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(alert.getId()))
                    .andExpect(jsonPath("$.transactionId").value(tx.getId()))
                    .andExpect(jsonPath("$.detectionConfig").value("FULL_SYSTEM"))
                    .andExpect(jsonPath("$.ruleTriggered").value(containsString("LARGE_TRANSACTION")))
                    .andExpect(jsonPath("$.mlRiskScore").value(0.92))
                    .andExpect(jsonPath("$.mlModelName").value("RandomForest"))
                    .andExpect(jsonPath("$.isAnomaly").value(true))
                    .andExpect(jsonPath("$.status").value("NEW"))
                    .andExpect(jsonPath("$.createdAt").isString());
        }

        @Test
        @DisplayName("Returns 404 ProblemDetail for non-existent alert")
        void notFound_returnsProblemDetail() throws Exception {
            mockMvc.perform(get("/api/v1/detection/alerts/999999"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.title").value("Resource Not Found"))
                    .andExpect(jsonPath("$.detail").value(containsString("Alert")));
        }
    }

    // ==============================================================
    // PATCH /api/v1/detection/alerts/{id}/status
    // ==============================================================

    @Nested
    @DisplayName("PATCH /api/v1/detection/alerts/{id}/status")
    class UpdateAlertStatus {

        @Test
        @DisplayName("Updates alert status from NEW to REVIEWED")
        void updatesStatus_newToReviewed() throws Exception {
            Transaction tx = persistTransaction("500000.00", TransactionType.TRANSFER, true);
            Alert alert = persistAlert(tx, DetectionConfig.RULES_ONLY, null);

            mockMvc.perform(patch("/api/v1/detection/alerts/{id}/status", alert.getId())
                            .param("status", "REVIEWED"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(alert.getId()))
                    .andExpect(jsonPath("$.status").value("REVIEWED"));

            // Verify persisted in DB
            Alert updated = alertRepository.findById(alert.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(AlertStatus.REVIEWED);
        }

        @Test
        @DisplayName("Updates alert status to CONFIRMED_FRAUD")
        void updatesStatus_confirmedFraud() throws Exception {
            Transaction tx = persistTransaction("500000.00", TransactionType.TRANSFER, true);
            Alert alert = persistAlert(tx, DetectionConfig.RULES_ONLY, null);

            mockMvc.perform(patch("/api/v1/detection/alerts/{id}/status", alert.getId())
                            .param("status", "CONFIRMED_FRAUD"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CONFIRMED_FRAUD"));
        }

        @Test
        @DisplayName("Updates alert status to FALSE_POSITIVE")
        void updatesStatus_falsePositive() throws Exception {
            Transaction tx = persistTransaction("500000.00", TransactionType.TRANSFER, false);
            Alert alert = persistAlert(tx, DetectionConfig.RULES_ONLY, null);

            mockMvc.perform(patch("/api/v1/detection/alerts/{id}/status", alert.getId())
                            .param("status", "FALSE_POSITIVE"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("FALSE_POSITIVE"));
        }

        @Test
        @DisplayName("Returns 404 for non-existent alert")
        void notFound() throws Exception {
            mockMvc.perform(patch("/api/v1/detection/alerts/999999/status")
                            .param("status", "REVIEWED"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.title").value("Resource Not Found"));
        }

        @Test
        @DisplayName("Returns 400 for invalid status value")
        void invalidStatus() throws Exception {
            Transaction tx = persistTransaction("500000.00", TransactionType.TRANSFER, true);
            Alert alert = persistAlert(tx, DetectionConfig.RULES_ONLY, null);

            mockMvc.perform(patch("/api/v1/detection/alerts/{id}/status", alert.getId())
                            .param("status", "INVALID_STATUS"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Invalid Parameter"))
                    .andExpect(jsonPath("$.rejectedValue").value("INVALID_STATUS"));
        }
    }

    // ==============================================================
    // Helpers
    // ==============================================================

    private Transaction persistTransaction(String amount, TransactionType type, boolean isFraud) {
        Transaction tx = new Transaction();
        tx.setAmount(new BigDecimal(amount));
        tx.setTransactionType(type);
        tx.setSenderAccount("SENDER-DET-" + System.nanoTime());
        tx.setReceiverAccount("RECEIVER-DET-" + System.nanoTime());
        tx.setTimestamp(LocalDateTime.now());
        tx.setDatasetSource(DatasetSource.PAYSIM);
        tx.setExternalId("DET-" + System.nanoTime());
        tx.setIsFraud(isFraud);
        return transactionRepository.save(tx);
    }

    private Alert persistAlert(Transaction tx, DetectionConfig config, Double mlRiskScore) {
        Alert alert = new Alert();
        alert.setTransaction(tx);
        alert.setDetectionConfig(config);
        alert.setMlRiskScore(mlRiskScore);
        alert.setIsAnomaly(true);
        alert.setStatus(AlertStatus.NEW);
        return alertRepository.save(alert);
    }
}
