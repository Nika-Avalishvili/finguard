package dev.finguard.presentation.controller;

import dev.finguard.detection.service.PipelineStatusTracker;
import dev.finguard.domain.enums.ExplanationType;
import dev.finguard.domain.repository.AlertRepository;
import dev.finguard.domain.repository.ExplanationRepository;
import dev.finguard.domain.repository.TransactionFeaturesRepository;
import dev.finguard.explanation.llm.ExplanationBatchAsyncService;
import dev.finguard.explanation.llm.LLMExplanationService;
import dev.finguard.explanation.validation.HallucinationValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for the ExplanationController REST layer.
 * Uses @WebMvcTest to test only the web slice; service dependencies are mocked.
 */
@WebMvcTest(ExplanationController.class)
@DisplayName("ExplanationController")
class ExplanationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LLMExplanationService llmService;

    @MockitoBean
    private HallucinationValidator hallucinationValidator;

    @MockitoBean
    private AlertRepository alertRepository;

    @MockitoBean
    private ExplanationRepository explanationRepository;

    @MockitoBean
    private TransactionFeaturesRepository featuresRepository;

    @MockitoBean
    private ExplanationBatchAsyncService batchAsyncService;

    @MockitoBean
    private PipelineStatusTracker statusTracker;

    // ================================================================
    // POST /api/v1/explanations/generate/batch
    // ================================================================

    @Nested
    @DisplayName("POST /generate/batch")
    class GenerateBatch {

        @Test
        @DisplayName("Should return 202 Accepted with jobId and statusUrl")
        void generateBatch_shouldReturn202_withJobId() throws Exception {
            doNothing().when(batchAsyncService).runAsync(
                    anyString(),
                    any(dev.finguard.domain.enums.DetectionConfig.class),
                    any(ExplanationType.class),
                    anyInt());

            mockMvc.perform(post("/api/v1/explanations/generate/batch")
                            .param("type", "LLM_RAG")
                            .param("limit", "10"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.jobId").isNotEmpty())
                    .andExpect(jsonPath("$.statusUrl").value(org.hamcrest.Matchers.startsWith("/api/v1/explanations/batch-status/")));
        }

        @Test
        @DisplayName("Should register job with PipelineStatusTracker before dispatching")
        void generateBatch_shouldStartJobBeforeDispatch() throws Exception {
            doNothing().when(batchAsyncService).runAsync(
                    anyString(),
                    any(dev.finguard.domain.enums.DetectionConfig.class),
                    any(ExplanationType.class),
                    anyInt());

            mockMvc.perform(post("/api/v1/explanations/generate/batch"))
                    .andExpect(status().isAccepted());

            verify(statusTracker).start(anyString(), eq("EXPLANATION_BATCH"));
            verify(batchAsyncService).runAsync(anyString(),
                    eq(dev.finguard.domain.enums.DetectionConfig.ML_LLM_RAG),
                    eq(ExplanationType.LLM_RAG), eq(50));
        }

        @Test
        @DisplayName("Should pass LLM_DIRECT type to async service when specified")
        void generateBatch_shouldPassTypeToAsyncService() throws Exception {
            doNothing().when(batchAsyncService).runAsync(
                    anyString(),
                    any(dev.finguard.domain.enums.DetectionConfig.class),
                    any(ExplanationType.class),
                    anyInt());

            mockMvc.perform(post("/api/v1/explanations/generate/batch")
                            .param("config", "ML_LLM_DIRECT")
                            .param("type", "LLM_DIRECT")
                            .param("limit", "5"))
                    .andExpect(status().isAccepted());

            verify(batchAsyncService).runAsync(anyString(),
                    eq(dev.finguard.domain.enums.DetectionConfig.ML_LLM_DIRECT),
                    eq(ExplanationType.LLM_DIRECT), eq(5));
        }
    }

    // ================================================================
    // GET /api/v1/explanations/batch-status/{jobId}
    // ================================================================

    @Nested
    @DisplayName("GET /batch-status/{jobId}")
    class BatchStatus {

        @Test
        @DisplayName("Should return 200 with job status when job exists")
        void batchStatus_shouldReturn200_whenJobExists() throws Exception {
            PipelineStatusTracker.PipelineStatus status = new PipelineStatusTracker.PipelineStatus(
                    "job-1", PipelineStatusTracker.Phase.RUNNING, "Generating explanations",
                    40, 20, 50, 20, Instant.now(), null, null);

            when(statusTracker.getStatus("job-1")).thenReturn(status);

            mockMvc.perform(get("/api/v1/explanations/batch-status/job-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.jobId").value("job-1"))
                    .andExpect(jsonPath("$.phase").value("RUNNING"))
                    .andExpect(jsonPath("$.progressPercent").value(40));
        }

        @Test
        @DisplayName("Should return 404 when job does not exist")
        void batchStatus_shouldReturn404_whenJobNotFound() throws Exception {
            when(statusTracker.getStatus("unknown")).thenReturn(null);

            mockMvc.perform(get("/api/v1/explanations/batch-status/unknown"))
                    .andExpect(status().isNotFound());
        }
    }
}
