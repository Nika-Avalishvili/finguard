package dev.finguard.presentation.controller;

import dev.finguard.config.MockAiConfig;
import dev.finguard.config.TestContainersConfig;
import dev.finguard.detection.ml.MLPredictionResult;
import dev.finguard.detection.ml.TribuoModelService;
import dev.finguard.domain.enums.*;
import dev.finguard.domain.model.ExperimentResult;
import dev.finguard.domain.model.Transaction;
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
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the EvaluationController REST API.
 *
 * <p>Tests experiment execution, result retrieval, comparison across configs,
 * and summary endpoints against a real PostgreSQL database.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, MockAiConfig.class})
@DisplayName("EvaluationController (REST API integration)")
class EvaluationControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private TransactionFeaturesRepository featuresRepository;
    @Autowired private AlertRepository alertRepository;
    @Autowired private ExplanationRepository explanationRepository;
    @Autowired private ExperimentResultRepository experimentResultRepository;
    @Autowired private dev.finguard.testutil.AsyncITCleaner cleaner;

    @MockitoBean private TribuoModelService mlService;
    @MockitoBean private LLMExplanationService llmService;

    @BeforeEach
    void cleanUp() {
        cleaner.drainAndClean();
    }

    /**
     * Poll a synchronous predicate until it becomes true or timeout (default 15s).
     * Used to wait for async evaluation jobs (POST /run returns 202 immediately;
     * the result is persisted a moment later by the async executor).
     */
    private void await(Callable<Boolean> condition) {
        long deadline = System.currentTimeMillis() + 15_000L;
        try {
            while (System.currentTimeMillis() < deadline) {
                if (Boolean.TRUE.equals(condition.call())) return;
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting", e);
        } catch (Exception e) {
            throw new AssertionError("Predicate threw", e);
        }
        throw new AssertionError("Timed out after 15s waiting for async condition");
    }

    // ==============================================================
    // POST /api/v1/evaluation/run
    // ==============================================================

    @Nested
    @DisplayName("POST /api/v1/evaluation/run")
    class RunExperiment {

        @Test
        @DisplayName("Runs RULES_ONLY experiment (async: 202 then persisted)")
        void rulesOnly_returnsMetrics() throws Exception {
            // 2 fraud (1 detectable), 1 legit
            persistTransaction("500000.00", TransactionType.TRANSFER, true);  // triggers LARGE_TRANSACTION
            persistTransaction("100.00", TransactionType.PAYMENT, true);      // no rule triggers
            persistTransaction("50.00", TransactionType.DEBIT, false);

            // Async contract: endpoint returns 202 immediately with a jobId +
            // statusUrl; the experiment result is persisted by the async runner.
            mockMvc.perform(post("/api/v1/evaluation/run")
                            .param("experimentName", "baseline-rules")
                            .param("config", "RULES_ONLY")
                            .param("dataset", "PAYSIM"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.jobId").isString())
                    .andExpect(jsonPath("$.statusUrl").isString());

            // Wait for the async runner to persist exactly one result row.
            await(() -> experimentResultRepository.count() == 1L);

            ExperimentResult r = experimentResultRepository
                    .findByExperimentName("baseline-rules").get(0);
            assertThat(r.getConfig()).isEqualTo(DetectionConfig.RULES_ONLY);
            assertThat(r.getDataset()).isEqualTo(DatasetSource.PAYSIM);
            assertThat(r.getPrecisionScore()).isNotNull();
            assertThat(r.getRecallScore()).isNotNull();
            assertThat(r.getF1Score()).isNotNull();
            assertThat(r.getFalsePositiveRate()).isNotNull();
            assertThat(r.getTotalTransactions()).isNotNull();
            assertThat(r.getTotalAlerts()).isNotNull();
            assertThat(r.getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("Experiment result is persisted and retrievable (async)")
        void resultIsPersisted() throws Exception {
            persistTransaction("500000.00", TransactionType.TRANSFER, true);

            mockMvc.perform(post("/api/v1/evaluation/run")
                            .param("experimentName", "persist-test")
                            .param("config", "RULES_ONLY"))
                    .andExpect(status().isAccepted());

            await(() -> !experimentResultRepository
                    .findByExperimentName("persist-test").isEmpty());

            ExperimentResult result = experimentResultRepository
                    .findByExperimentName("persist-test").get(0);
            assertThat(result.getConfig()).isEqualTo(DetectionConfig.RULES_ONLY);
            assertThat(result.getRunParameters()).contains("RULES_ONLY");
        }

        @Test
        @DisplayName("Returns 400 when experimentName is missing")
        void missingExperimentName_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/evaluation/run")
                            .param("config", "RULES_ONLY"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Missing Parameter"));
        }

        @Test
        @DisplayName("Returns 400 when experimentName is blank")
        void blankExperimentName_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/evaluation/run")
                            .param("experimentName", "   ")
                            .param("config", "RULES_ONLY"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Returns 400 for invalid config enum value")
        void invalidConfig_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/evaluation/run")
                            .param("experimentName", "test")
                            .param("config", "BOGUS"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Invalid Parameter"))
                    .andExpect(jsonPath("$.allowedValues").value(containsString("RULES_ONLY")));
        }

        @Test
        @DisplayName("Handles empty dataset gracefully (zero metrics, async)")
        void emptyDataset_zeroMetrics() throws Exception {
            mockMvc.perform(post("/api/v1/evaluation/run")
                            .param("experimentName", "empty-test")
                            .param("config", "RULES_ONLY"))
                    .andExpect(status().isAccepted());

            await(() -> !experimentResultRepository
                    .findByExperimentName("empty-test").isEmpty());

            ExperimentResult r = experimentResultRepository
                    .findByExperimentName("empty-test").get(0);
            assertThat(r.getTotalAlerts()).isZero();
        }
    }

    // ==============================================================
    // POST /api/v1/evaluation/compare
    // ==============================================================

    @Nested
    @DisplayName("POST /api/v1/evaluation/compare")
    class CompareConfigs {

        @Test
        @DisplayName("Compares all 5 configs and persists one result per config (async)")
        void comparesAllConfigs() throws Exception {
            when(mlService.isModelAvailable()).thenReturn(true);
            when(mlService.predict(any(), any())).thenReturn(
                    new MLPredictionResult("RF", 0.85, true, Map.of()));

            persistTransaction("500000.00", TransactionType.TRANSFER, true);
            persistTransaction("100.00", TransactionType.PAYMENT, false);

            // /compare is async: returns 202 immediately with a jobId; results
            // (one per config) are persisted by the async runner.
            mockMvc.perform(post("/api/v1/evaluation/compare")
                            .param("experimentName", "compare-all"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.jobId").isString())
                    .andExpect(jsonPath("$.statusUrl").isString());

            await(() -> experimentResultRepository
                    .findByExperimentName("compare-all").size() == 5);
        }

        @Test
        @DisplayName("Returns 400 when experimentName is missing")
        void missingName_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/evaluation/compare"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ==============================================================
    // GET /api/v1/evaluation/results
    // ==============================================================

    @Nested
    @DisplayName("GET /api/v1/evaluation/results")
    class GetResults {

        @Test
        @DisplayName("Returns all experiment results")
        void returnsAll() throws Exception {
            persistExperimentResult("exp-1", DetectionConfig.RULES_ONLY, 0.85, 0.80, 0.82);
            persistExperimentResult("exp-2", DetectionConfig.FULL_SYSTEM, 0.92, 0.88, 0.90);

            mockMvc.perform(get("/api/v1/evaluation/results"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)));
        }

        @Test
        @DisplayName("Returns empty array when no experiments exist")
        void returnsEmpty() throws Exception {
            mockMvc.perform(get("/api/v1/evaluation/results"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }
    }

    // ==============================================================
    // GET /api/v1/evaluation/results/{experimentName}
    // ==============================================================

    @Nested
    @DisplayName("GET /api/v1/evaluation/results/{experimentName}")
    class GetByName {

        @Test
        @DisplayName("Returns results filtered by experiment name")
        void filtersByName() throws Exception {
            persistExperimentResult("baseline", DetectionConfig.RULES_ONLY, 0.85, 0.80, 0.82);
            persistExperimentResult("baseline", DetectionConfig.ML_ONLY, 0.90, 0.85, 0.87);
            persistExperimentResult("other", DetectionConfig.FULL_SYSTEM, 0.92, 0.88, 0.90);

            mockMvc.perform(get("/api/v1/evaluation/results/baseline"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].experimentName").value("baseline"))
                    .andExpect(jsonPath("$[1].experimentName").value("baseline"));
        }

        @Test
        @DisplayName("Returns empty list for unknown experiment name")
        void unknownName_returnsEmpty() throws Exception {
            mockMvc.perform(get("/api/v1/evaluation/results/nonexistent"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }
    }

    // ==============================================================
    // GET /api/v1/evaluation/results/config/{config}
    // ==============================================================

    @Nested
    @DisplayName("GET /api/v1/evaluation/results/config/{config}")
    class GetByConfig {

        @Test
        @DisplayName("Returns results filtered by detection config")
        void filtersByConfig() throws Exception {
            persistExperimentResult("exp-1", DetectionConfig.RULES_ONLY, 0.85, 0.80, 0.82);
            persistExperimentResult("exp-2", DetectionConfig.RULES_ONLY, 0.86, 0.81, 0.83);
            persistExperimentResult("exp-3", DetectionConfig.FULL_SYSTEM, 0.92, 0.88, 0.90);

            mockMvc.perform(get("/api/v1/evaluation/results/config/RULES_ONLY"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].config").value("RULES_ONLY"));
        }

        @Test
        @DisplayName("Returns 400 for invalid config enum")
        void invalidConfig() throws Exception {
            mockMvc.perform(get("/api/v1/evaluation/results/config/INVALID"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Invalid Parameter"));
        }
    }

    // ==============================================================
    // GET /api/v1/evaluation/summary/{experimentName}
    // ==============================================================

    @Nested
    @DisplayName("GET /api/v1/evaluation/summary/{experimentName}")
    class GetSummary {

        @Test
        @DisplayName("Returns summary with key metrics for comparison")
        void returnsSummary() throws Exception {
            persistExperimentResult("thesis-eval", DetectionConfig.RULES_ONLY, 0.85, 0.80, 0.82);
            persistExperimentResult("thesis-eval", DetectionConfig.FULL_SYSTEM, 0.92, 0.88, 0.90);

            mockMvc.perform(get("/api/v1/evaluation/summary/thesis-eval"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.experimentName").value("thesis-eval"))
                    .andExpect(jsonPath("$.configCount").value(2))
                    .andExpect(jsonPath("$.results", hasSize(2)))
                    .andExpect(jsonPath("$.results[0].config").isString())
                    .andExpect(jsonPath("$.results[0].precision").isNumber())
                    .andExpect(jsonPath("$.results[0].recall").isNumber())
                    .andExpect(jsonPath("$.results[0].f1Score").isNumber());
        }

        @Test
        @DisplayName("Returns 404 when experiment name not found")
        void notFound() throws Exception {
            mockMvc.perform(get("/api/v1/evaluation/summary/nonexistent"))
                    .andExpect(status().isNotFound());
        }
    }

    // ==============================================================
    // Helpers
    // ==============================================================

    private Transaction persistTransaction(String amount, TransactionType type, boolean isFraud) {
        Transaction tx = new Transaction();
        tx.setAmount(new BigDecimal(amount));
        tx.setTransactionType(type);
        tx.setSenderAccount("SENDER-EVAL-" + System.nanoTime());
        tx.setReceiverAccount("RECEIVER-EVAL-" + System.nanoTime());
        tx.setTimestamp(LocalDateTime.now());
        tx.setDatasetSource(DatasetSource.PAYSIM);
        tx.setExternalId("EVAL-" + System.nanoTime());
        tx.setIsFraud(isFraud);
        return transactionRepository.save(tx);
    }

    private void persistExperimentResult(String name, DetectionConfig config,
                                          double precision, double recall, double f1) {
        ExperimentResult result = new ExperimentResult();
        result.setExperimentName(name);
        result.setConfig(config);
        result.setDataset(DatasetSource.PAYSIM);
        result.setPrecisionScore(precision);
        result.setRecallScore(recall);
        result.setF1Score(f1);
        result.setFalsePositiveRate(0.05);
        result.setTotalTransactions(100);
        result.setTotalAlerts(10);
        experimentResultRepository.save(result);
    }
}
