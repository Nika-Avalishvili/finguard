package dev.finguard.presentation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finguard.config.MockAiConfig;
import dev.finguard.config.TestContainersConfig;
import dev.finguard.domain.enums.DatasetSource;
import dev.finguard.domain.enums.DetectionConfig;
import dev.finguard.domain.enums.ExplanationType;
import dev.finguard.domain.enums.TransactionType;
import dev.finguard.domain.model.Alert;
import dev.finguard.domain.model.Explanation;
import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.repository.AlertRepository;
import dev.finguard.domain.repository.ExplanationRepository;
import dev.finguard.domain.repository.TransactionFeaturesRepository;
import dev.finguard.domain.repository.TransactionRepository;
import dev.finguard.explanation.llm.LLMExplanationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.awaitility.Awaitility;
import java.time.Duration;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.*;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integration tests for the ExplanationController REST API.
 *
 * <p>Tests the full HTTP layer: request → controller → service → DB.
 * LLMExplanationService is mocked since it depends on a running LLM.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, MockAiConfig.class})
@DisplayName("ExplanationController (REST API integration)")
class ExplanationControllerIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private TransactionFeaturesRepository featuresRepository;

    @Autowired
    private AlertRepository alertRepository;

    @Autowired
    private ExplanationRepository explanationRepository;

    @MockitoBean
    private LLMExplanationService llmService;

    @Autowired
    private dev.finguard.testutil.AsyncITCleaner cleaner;

    @BeforeEach
    void cleanUp() {
        cleaner.drainAndClean();
    }

    // ==============================================================
    // Generate endpoints
    // ==============================================================

    @Nested
    @DisplayName("POST /api/v1/explanations/generate/direct/{alertId}")
    class GenerateDirect {

        @Test
        @DisplayName("Should generate direct explanation for existing alert")
        void generateDirect_success() throws Exception {
            Alert alert = persistAlertWithTransaction();

            when(llmService.generateDirectExplanation(any())).thenAnswer(invocation -> {
                Alert a = invocation.getArgument(0);
                return persistExplanation(a, ExplanationType.LLM_DIRECT,
                        "Direct risk summary", "Direct explanation text");
            });

            mockMvc.perform(post("/api/v1/explanations/generate/direct/{alertId}", alert.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.alertId").value(alert.getId()))
                    .andExpect(jsonPath("$.riskSummary").value("Direct risk summary"))
                    .andExpect(jsonPath("$.explanationText").value("Direct explanation text"))
                    .andExpect(jsonPath("$.explanationType").value("LLM_DIRECT"));
        }

        @Test
        @DisplayName("Should return 404 for non-existent alert")
        void generateDirect_notFound() throws Exception {
            mockMvc.perform(post("/api/v1/explanations/generate/direct/999999"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("POST /api/v1/explanations/generate/rag/{alertId}")
    class GenerateRag {

        @Test
        @DisplayName("Should generate RAG explanation for existing alert")
        void generateRag_success() throws Exception {
            Alert alert = persistAlertWithTransaction();

            when(llmService.generateRagExplanation(any())).thenAnswer(invocation -> {
                Alert a = invocation.getArgument(0);
                return persistExplanation(a, ExplanationType.LLM_RAG,
                        "RAG risk summary", "RAG explanation with pattern context");
            });

            mockMvc.perform(post("/api/v1/explanations/generate/rag/{alertId}", alert.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.alertId").value(alert.getId()))
                    .andExpect(jsonPath("$.riskSummary").value("RAG risk summary"))
                    .andExpect(jsonPath("$.explanationType").value("LLM_RAG"));
        }
    }

    // ==============================================================
    // Retrieval endpoints
    // ==============================================================

    @Nested
    @DisplayName("GET /api/v1/explanations/alert/{alertId}")
    class GetByAlert {

        @Test
        @DisplayName("Should return all explanations for an alert")
        void getByAlert_returnsAll() throws Exception {
            Alert alert = persistAlertWithTransaction();
            persistExplanation(alert, ExplanationType.LLM_DIRECT, "Summary 1", "Text 1");
            persistExplanation(alert, ExplanationType.LLM_RAG, "Summary 2", "Text 2");

            mockMvc.perform(get("/api/v1/explanations/alert/{alertId}", alert.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].alertId").value(alert.getId()))
                    .andExpect(jsonPath("$[1].alertId").value(alert.getId()));
        }

        @Test
        @DisplayName("Should return empty list when alert has no explanations")
        void getByAlert_empty() throws Exception {
            Alert alert = persistAlertWithTransaction();

            mockMvc.perform(get("/api/v1/explanations/alert/{alertId}", alert.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/explanations/{id}")
    class GetById {

        @Test
        @DisplayName("Should return explanation by ID")
        void getById_success() throws Exception {
            Alert alert = persistAlertWithTransaction();
            Explanation explanation = persistExplanation(alert, ExplanationType.LLM_RAG,
                    "Test summary", "Test explanation");

            mockMvc.perform(get("/api/v1/explanations/{id}", explanation.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(explanation.getId()))
                    .andExpect(jsonPath("$.riskSummary").value("Test summary"))
                    .andExpect(jsonPath("$.confidenceScore").value(0.85));
        }

        @Test
        @DisplayName("Should return 404 for non-existent explanation")
        void getById_notFound() throws Exception {
            mockMvc.perform(get("/api/v1/explanations/999999"))
                    .andExpect(status().isNotFound());
        }
    }

    // ==============================================================
    // Validation endpoint
    // ==============================================================

    @Nested
    @DisplayName("POST /api/v1/explanations/{id}/validate")
    class Revalidate {

        @Test
        @DisplayName("Should re-validate explanation and return hallucination flags")
        void revalidate_returnsFlags() throws Exception {
            Alert alert = persistAlertWithTransaction();
            Explanation explanation = persistExplanation(alert, ExplanationType.LLM_RAG,
                    "Risk summary for 500000.00",
                    "The transaction of 500000.00 from SENDER_IT to RECEIVER_IT is suspicious.");

            mockMvc.perform(post("/api/v1/explanations/{id}/validate", explanation.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(explanation.getId()))
                    .andExpect(jsonPath("$.hallucinationFree").isBoolean());
        }

        @Test
        @DisplayName("Should return 404 for non-existent explanation")
        void revalidate_notFound() throws Exception {
            mockMvc.perform(post("/api/v1/explanations/999999/validate"))
                    .andExpect(status().isNotFound());
        }
    }

    // ==============================================================
    // Batch generation
    // ==============================================================

    @Nested
    @DisplayName("POST /api/v1/explanations/generate/batch")
    class BatchGenerate {

        /**
         * Submits a batch job and blocks until it reaches COMPLETED, then returns the jobId.
         * The endpoint is now async (202); tests must poll the status endpoint for completion.
         */
        private String submitBatchAndAwait(String type, int limit) throws Exception {
            MvcResult result = mockMvc.perform(post("/api/v1/explanations/generate/batch")
                            .param("type", type)
                            .param("limit", String.valueOf(limit)))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.jobId").isNotEmpty())
                    .andReturn();

            String jobId = objectMapper.readTree(result.getResponse().getContentAsString())
                    .get("jobId").asText();

            // Poll batch-status until COMPLETED or FAILED (max 10 s — async runs fast in tests)
            Awaitility.await()
                    .atMost(Duration.ofSeconds(10))
                    .pollInterval(Duration.ofMillis(100))
                    .until(() -> {
                        String body = mockMvc.perform(get("/api/v1/explanations/batch-status/{jobId}", jobId))
                                .andReturn().getResponse().getContentAsString();
                        String phase = objectMapper.readTree(body).get("phase").asText();
                        return "COMPLETED".equals(phase) || "FAILED".equals(phase);
                    });

            return jobId;
        }

        @Test
        @DisplayName("Should generate explanations for anomaly alerts without existing explanations")
        void batch_generatesForUnexplainedAlerts() throws Exception {
            Alert alert1 = persistAlertWithTransaction();
            Alert alert2 = persistAlertWithTransaction();

            // Batch uses generateFocusedDirectExplanation for LLM_DIRECT (signal-conditioned shorter prompt)
            when(llmService.generateFocusedDirectExplanation(any())).thenAnswer(invocation -> {
                Alert a = invocation.getArgument(0);
                return persistExplanation(a, ExplanationType.LLM_DIRECT, "Batch summary", "Batch text");
            });

            submitBatchAndAwait("LLM_DIRECT", 10);

            // Verify both alerts got explanations in the DB
            org.assertj.core.api.Assertions.assertThat(explanationRepository.count()).isEqualTo(2);
        }

        @Test
        @DisplayName("Should skip alerts that already have explanations")
        void batch_skipsAlreadyExplained() throws Exception {
            Alert alert1 = persistAlertWithTransaction();
            Alert alert2 = persistAlertWithTransaction();

            // Pre-create explanation for alert1 — batch should skip it
            persistExplanation(alert1, ExplanationType.LLM_DIRECT, "Existing", "Already explained");

            // Batch uses generateFocusedDirectExplanation for LLM_DIRECT
            when(llmService.generateFocusedDirectExplanation(any())).thenAnswer(invocation -> {
                Alert a = invocation.getArgument(0);
                return persistExplanation(a, ExplanationType.LLM_DIRECT, "New", "New text");
            });

            submitBatchAndAwait("LLM_DIRECT", 10);

            // Only 1 new explanation should have been added (alert1 already had one)
            org.assertj.core.api.Assertions.assertThat(explanationRepository.count()).isEqualTo(2);
        }
    }

    // ==============================================================
    // Helpers
    // ==============================================================

    private Alert persistAlertWithTransaction() {
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

    private Explanation persistExplanation(Alert alert, ExplanationType type,
                                            String summary, String text) {
        Explanation explanation = new Explanation();
        explanation.setAlert(alert);
        explanation.setExplanationType(type);
        explanation.setRiskSummary(summary);
        explanation.setExplanationText(text);
        explanation.setConfidenceScore(0.85);
        explanation.setLatencyMs(150);
        explanation.setSuspiciousPatterns("[\"Large Transaction\"]");
        explanation.setRecommendedActions("[\"Review account\"]");
        return explanationRepository.save(explanation);
    }
}
