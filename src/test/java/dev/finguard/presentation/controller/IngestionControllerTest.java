package dev.finguard.presentation.controller;

import dev.finguard.ingestion.dto.IngestionJob;
import dev.finguard.ingestion.service.FeatureEngineeringAsyncService;
import dev.finguard.ingestion.service.FeatureEngineeringBulkService;
import dev.finguard.ingestion.service.IngestionAsyncService;
import dev.finguard.ingestion.service.IngestionJobStore;
import dev.finguard.ingestion.service.TrainTestSplitService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit test for the IngestionController REST layer.
 *
 * <p>Uses @WebMvcTest to load only the web slice (no DB, no AI).
 * Service dependencies are replaced with Mockito mocks via @MockitoBean.</p>
 */
@WebMvcTest(IngestionController.class)
@DisplayName("IngestionController")
class IngestionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IngestionController controller;

    @MockitoBean
    private IngestionAsyncService ingestionAsyncService;

    @MockitoBean
    private IngestionJobStore ingestionJobStore;

    @MockitoBean
    private FeatureEngineeringBulkService featureEngineeringBulkService;

    @MockitoBean
    private FeatureEngineeringAsyncService featureEngineeringAsyncService;

    @MockitoBean
    private TrainTestSplitService trainTestSplitService;

    @MockitoBean
    private dev.finguard.detection.service.PipelineStatusTracker pipelineStatusTracker;

    @TempDir
    Path tempDir;

    // ==============================================================
    // POST /api/v1/ingestion/paysim/upload
    // ==============================================================

    @Nested
    @DisplayName("POST /paysim/upload")
    class UploadEndpoint {

        @Test
        @DisplayName("Should return 202 Accepted with jobId for valid CSV upload")
        void upload_shouldReturn202_withValidFile() throws Exception {
            String csvContent = "step,type,amount,nameOrig,oldbalanceOrg,newbalanceOrig,nameDest,oldbalanceDest,newbalanceDest,isFraud,isFlaggedFraud\n" +
                    "1,TRANSFER,50000.00,C100,100000.00,50000.00,C200,0.00,50000.00,0,0\n";

            MockMultipartFile file = new MockMultipartFile(
                    "file", "paysim.csv", "text/csv", csvContent.getBytes());

            when(ingestionJobStore.createJob()).thenReturn("test-job-id");
            doNothing().when(ingestionAsyncService).runImportAsync(anyString(), any(), anyBoolean());

            mockMvc.perform(multipart("/api/v1/ingestion/paysim/upload").file(file))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.jobId").value("test-job-id"))
                    .andExpect(jsonPath("$.statusUrl").value("/api/v1/ingestion/status/test-job-id"));

            verify(ingestionAsyncService).runImportAsync(eq("test-job-id"), any(Path.class), eq(true));
        }

        @Test
        @DisplayName("Should return 400 for empty file upload")
        void upload_shouldReturn400_whenFileIsEmpty() throws Exception {
            MockMultipartFile emptyFile = new MockMultipartFile(
                    "file", "empty.csv", "text/csv", new byte[0]);

            mockMvc.perform(multipart("/api/v1/ingestion/paysim/upload").file(emptyFile))
                    .andExpect(status().isBadRequest());

            verify(ingestionAsyncService, never()).runImportAsync(any(), any(), anyBoolean());
        }
    }

    // ==============================================================
    // POST /api/v1/ingestion/paysim/path
    // ==============================================================

    @Nested
    @DisplayName("POST /paysim/path")
    class PathEndpoint {

        @Test
        @DisplayName("Should return 202 Accepted with jobId for valid server-side file path")
        void path_shouldReturn202_whenFileExists() throws Exception {
            Path csvFile = tempDir.resolve("test.csv");
            Files.writeString(csvFile, "header\nrow1\n");

            when(ingestionJobStore.createJob()).thenReturn("path-job-id");
            doNothing().when(ingestionAsyncService).runImportAsync(anyString(), any(), anyBoolean());

            mockMvc.perform(post("/api/v1/ingestion/paysim/path")
                            .param("path", csvFile.toString()))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.jobId").value("path-job-id"))
                    .andExpect(jsonPath("$.statusUrl").value("/api/v1/ingestion/status/path-job-id"));

            verify(ingestionAsyncService).runImportAsync(eq("path-job-id"), eq(csvFile), eq(false));
        }

        @Test
        @DisplayName("Should return 400 when file does not exist")
        void path_shouldReturn400_whenFileNotFound() throws Exception {
            mockMvc.perform(post("/api/v1/ingestion/paysim/path")
                            .param("path", "/nonexistent/file.csv"))
                    .andExpect(status().isBadRequest());

            verify(ingestionAsyncService, never()).runImportAsync(any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("Should return 400 for path traversal attempt")
        void path_shouldReturn400_forPathTraversal() throws Exception {
            mockMvc.perform(post("/api/v1/ingestion/paysim/path")
                            .param("path", "/data/../etc/passwd"))
                    .andExpect(status().isBadRequest());

            verify(ingestionAsyncService, never()).runImportAsync(any(), any(), anyBoolean());
        }
    }

    // ==============================================================
    // GET /api/v1/ingestion/status/{jobId}
    // ==============================================================

    @Nested
    @DisplayName("GET /status/{jobId}")
    class StatusEndpoint {

        @Test
        @DisplayName("Should return live progress for a running job")
        void status_shouldReturnProgress_whenJobIsRunning() throws Exception {
            IngestionJob job = new IngestionJob("running-job");
            job.markRunning();
            job.updateTotal(1_000_000);
            job.updateProgress(50_000, 49_800, 200);

            when(ingestionJobStore.find("running-job")).thenReturn(Optional.of(job));

            mockMvc.perform(get("/api/v1/ingestion/status/running-job"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.jobId").value("running-job"))
                    .andExpect(jsonPath("$.status").value("RUNNING"))
                    .andExpect(jsonPath("$.totalRows").value(1_000_000))
                    .andExpect(jsonPath("$.processedRows").value(50_000))
                    .andExpect(jsonPath("$.importedRows").value(49_800))
                    .andExpect(jsonPath("$.skippedRows").value(200));
        }

        @Test
        @DisplayName("Should return final summary for a completed job")
        void status_shouldReturnSummary_whenJobIsDone() throws Exception {
            IngestionJob job = new IngestionJob("done-job");
            job.markRunning();
            job.markDone(5_000, 100, 5_100, 5_000);

            when(ingestionJobStore.find("done-job")).thenReturn(Optional.of(job));

            mockMvc.perform(get("/api/v1/ingestion/status/done-job"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DONE"))
                    .andExpect(jsonPath("$.importedRows").value(5_000))
                    .andExpect(jsonPath("$.featuresComputed").value(5_000));
        }

        @Test
        @DisplayName("Should return 404 for unknown job ID")
        void status_shouldReturn404_whenJobNotFound() throws Exception {
            when(ingestionJobStore.find("unknown")).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/v1/ingestion/status/unknown"))
                    .andExpect(status().isNotFound());
        }
    }

    // ==============================================================
    // POST /api/v1/ingestion/features/compute
    // ==============================================================

    @Nested
    @DisplayName("POST /features/compute")
    class FeaturesEndpoint {

        @AfterEach
        void resetFlag() {
            ReflectionTestUtils.setField(controller, "featureComputationRunning",
                    new java.util.concurrent.atomic.AtomicBoolean(false));
        }

        @Test
        @DisplayName("Should return 202 with jobId")
        void compute_shouldReturn202WithJobId() throws Exception {
            when(ingestionJobStore.createJob()).thenReturn("test-job-id");

            mockMvc.perform(post("/api/v1/ingestion/features/compute"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.jobId").value("test-job-id"))
                    .andExpect(jsonPath("$.statusUrl").value("/api/v1/ingestion/status/test-job-id"));

            verify(featureEngineeringAsyncService).runAsync(eq("test-job-id"), any(Runnable.class));
        }

        @Test
        @DisplayName("Should start async computation")
        void compute_shouldDelegateToAsyncService() throws Exception {
            when(ingestionJobStore.createJob()).thenReturn("async-job-123");

            mockMvc.perform(post("/api/v1/ingestion/features/compute"))
                    .andExpect(status().isAccepted());

            verify(featureEngineeringAsyncService).runAsync(eq("async-job-123"), any(Runnable.class));
        }

        @Test
        @DisplayName("Should return 400 when feature computation is already in progress")
        void compute_shouldReturn400_whenAlreadyRunning() throws Exception {
            when(ingestionJobStore.createJob()).thenReturn("job-1");
            // Mock does NOT call the Runnable, so the AtomicBoolean stays true after first call
            doAnswer(invocation -> null)
                    .when(featureEngineeringAsyncService).runAsync(anyString(), any(Runnable.class));

            // First call — flag set to true, not released because Runnable is not called
            mockMvc.perform(post("/api/v1/ingestion/features/compute"))
                    .andExpect(status().isAccepted());

            // Second call while first is still "running" — must be rejected
            mockMvc.perform(post("/api/v1/ingestion/features/compute"))
                    .andExpect(status().isBadRequest());
        }

        /**
         * Regression for A-6: if the async-submission itself throws (e.g., the
         * Spring proxy rejects the task, or the executor cannot queue it), the
         * completion callback never fires — so the lock must be released in the
         * catch block. Without this, the second call would be permanently
         * blocked until a process restart.
         */
        @Test
        @DisplayName("Should release lock when async submission throws")
        void compute_shouldReleaseLock_whenSubmissionThrows() throws Exception {
            when(ingestionJobStore.createJob()).thenReturn("fail-job");
            doThrow(new IllegalStateException("executor rejected task"))
                    .when(featureEngineeringAsyncService).runAsync(anyString(), any(Runnable.class));

            // First call — submission throws, lock must be released.
            mockMvc.perform(post("/api/v1/ingestion/features/compute"))
                    .andExpect(status().is5xxServerError());

            // Second call — lock is free, so make the mock succeed silently this time.
            when(ingestionJobStore.createJob()).thenReturn("ok-job");
            doNothing().when(featureEngineeringAsyncService)
                    .runAsync(anyString(), any(Runnable.class));

            mockMvc.perform(post("/api/v1/ingestion/features/compute"))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.jobId").value("ok-job"));
        }
    }

    // ==============================================================
    // POST /api/v1/ingestion/jobs/{jobId}/cancel
    // ==============================================================

    @Nested
    @DisplayName("POST /jobs/{jobId}/cancel")
    class CancelEndpoint {

        @Test
        @DisplayName("Should return 200 and set cancel flag when job exists")
        void cancel_shouldReturn200_andSetCancelFlag_whenJobExists() throws Exception {
            IngestionJob job = new IngestionJob("cancel-job");
            job.markRunning();
            when(ingestionJobStore.find("cancel-job")).thenReturn(Optional.of(job));

            mockMvc.perform(post("/api/v1/ingestion/jobs/cancel-job/cancel"))
                    .andExpect(status().isOk());

            assertThat(job.isCancelRequested()).isTrue();
        }

        @Test
        @DisplayName("Should return 404 when job not found")
        void cancel_shouldReturn404_whenJobNotFound() throws Exception {
            when(ingestionJobStore.find("unknown")).thenReturn(Optional.empty());

            mockMvc.perform(post("/api/v1/ingestion/jobs/unknown/cancel"))
                    .andExpect(status().isNotFound());
        }
    }

    // ==============================================================
    // POST /api/v1/ingestion/finalize-train-test-split
    // ==============================================================

    @Nested
    @DisplayName("POST /finalize-train-test-split")
    class FinalizeTrainTestSplitEndpoint {

        @Test
        @DisplayName("Should return 200 with counts when service succeeds")
        void finalizeSplit_returnsCounts() throws Exception {
            TrainTestSplitService.SplitResult stub = new TrainTestSplitService.SplitResult(
                    java.time.LocalDateTime.of(2025, 6, 15, 0, 0), 100L, 80L, 20L);
            when(trainTestSplitService.finalizeTemporalSplit(0.80)).thenReturn(stub);

            mockMvc.perform(post("/api/v1/ingestion/finalize-train-test-split")
                            .param("ratio", "0.80"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalRows").value(100))
                    .andExpect(jsonPath("$.trainingCount").value(80))
                    .andExpect(jsonPath("$.testCount").value(20));
        }

        @Test
        @DisplayName("Should default to ratio 0.80 when not specified")
        void finalizeSplit_defaultsRatio() throws Exception {
            TrainTestSplitService.SplitResult stub = new TrainTestSplitService.SplitResult(
                    null, 0L, 0L, 0L);
            when(trainTestSplitService.finalizeTemporalSplit(0.80)).thenReturn(stub);

            mockMvc.perform(post("/api/v1/ingestion/finalize-train-test-split"))
                    .andExpect(status().isOk());

            verify(trainTestSplitService).finalizeTemporalSplit(0.80);
        }

        @Test
        @DisplayName("Should return 400 for out-of-range ratio")
        void finalizeSplit_rejectsInvalidRatio() throws Exception {
            mockMvc.perform(post("/api/v1/ingestion/finalize-train-test-split")
                            .param("ratio", "1.5"))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(post("/api/v1/ingestion/finalize-train-test-split")
                            .param("ratio", "0"))
                    .andExpect(status().isBadRequest());

            verifyNoInteractions(trainTestSplitService);
        }
    }

}