package dev.finguard.presentation.controller;

import dev.finguard.ingestion.service.FeatureEngineeringAsyncService;
import dev.finguard.ingestion.service.FeatureEngineeringBulkService;
import dev.finguard.ingestion.service.IngestionAsyncService;
import dev.finguard.ingestion.service.IngestionJobStore;
import dev.finguard.ingestion.service.TrainTestSplitService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(IngestionController.class)
@DisplayName("Ingestion Security")
class IngestionSecurityTest {

    @Autowired
    private MockMvc mockMvc;

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

    private void stubValidJob() {
        when(ingestionJobStore.createJob()).thenReturn("test-job-id");
        doNothing().when(ingestionAsyncService).runImportAsync(anyString(), any(), anyBoolean());
    }

    @Nested
    @DisplayName("Path Traversal Prevention (POST /paysim/path)")
    class PathTraversal {

        @ParameterizedTest(name = "Should reject path traversal attempt: {0}")
        @ValueSource(strings = {
                "../../../etc/passwd",
                "..\\..\\..\\windows\\system32\\config\\sam",
                "/etc/shadow",
                "/proc/self/environ",
                "../../../../etc/hosts",
                "/tmp/../etc/passwd"
        })
        @DisplayName("Should reject dangerous file paths")
        void path_shouldReject_traversalAttempts(String maliciousPath) throws Exception {
            mockMvc.perform(post("/api/v1/ingestion/paysim/path")
                            .param("path", maliciousPath))
                    .andExpect(status().isBadRequest());
            verify(ingestionAsyncService, never()).runImportAsync(any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("Should reject null path parameter")
        void path_shouldReject_nullPath() throws Exception {
            mockMvc.perform(post("/api/v1/ingestion/paysim/path")
                            .param("path", ""))
                    .andExpect(status().isBadRequest());
            verify(ingestionAsyncService, never()).runImportAsync(any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("Should reject path with whitespace only")
        void path_shouldReject_whitespaceOnlyPath() throws Exception {
            mockMvc.perform(post("/api/v1/ingestion/paysim/path")
                            .param("path", "   "))
                    .andExpect(status().isBadRequest());
            verify(ingestionAsyncService, never()).runImportAsync(any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("Should reject nonexistent but absolute path")
        void path_shouldReject_nonexistentAbsolutePath() throws Exception {
            mockMvc.perform(post("/api/v1/ingestion/paysim/path")
                            .param("path", "/nonexistent/deep/path/data.csv"))
                    .andExpect(status().isBadRequest());
            verify(ingestionAsyncService, never()).runImportAsync(any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("Should accept valid existing file path and return 202")
        void path_shouldAccept_validExistingFile() throws Exception {
            Path validFile = tempDir.resolve("valid-data.csv");
            Files.writeString(validFile, "header\nrow\n");
            stubValidJob();

            mockMvc.perform(post("/api/v1/ingestion/paysim/path")
                            .param("path", validFile.toString()))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.jobId").value("test-job-id"));

            verify(ingestionAsyncService).runImportAsync(eq("test-job-id"), eq(validFile), eq(false));
        }
    }

    @Nested
    @DisplayName("File Upload Security (POST /paysim/upload)")
    class FileUploadSecurity {

        @Test
        @DisplayName("Should reject empty file")
        void upload_shouldReject_emptyFile() throws Exception {
            MockMultipartFile emptyFile = new MockMultipartFile(
                    "file", "empty.csv", "text/csv", new byte[0]);
            mockMvc.perform(multipart("/api/v1/ingestion/paysim/upload").file(emptyFile))
                    .andExpect(status().isBadRequest());
            verify(ingestionAsyncService, never()).runImportAsync(any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("Should handle file with path traversal in filename and return 202")
        void upload_shouldHandle_traversalInFilename() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "../../etc/passwd", "text/csv", "header\ndata\n".getBytes());
            stubValidJob();
            mockMvc.perform(multipart("/api/v1/ingestion/paysim/upload").file(file))
                    .andExpect(status().isAccepted());
        }

        @Test
        @DisplayName("Should handle file with non-CSV content type and return 202")
        void upload_shouldHandle_wrongContentType() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "malware.exe", "application/octet-stream", "header\ndata\n".getBytes());
            stubValidJob();
            mockMvc.perform(multipart("/api/v1/ingestion/paysim/upload").file(file))
                    .andExpect(status().isAccepted());
        }

        @Test
        @DisplayName("Should handle file with very long filename and return 202")
        void upload_shouldHandle_veryLongFilename() throws Exception {
            String longName = "a".repeat(500) + ".csv";
            MockMultipartFile file = new MockMultipartFile(
                    "file", longName, "text/csv", "header\ndata\n".getBytes());
            stubValidJob();
            mockMvc.perform(multipart("/api/v1/ingestion/paysim/upload").file(file))
                    .andExpect(status().isAccepted());
        }

        @Test
        @DisplayName("Should handle file with special characters in filename and return 202")
        void upload_shouldHandle_specialCharsInFilename() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "data; rm -rf /.csv", "text/csv", "header\ndata\n".getBytes());
            stubValidJob();
            mockMvc.perform(multipart("/api/v1/ingestion/paysim/upload").file(file))
                    .andExpect(status().isAccepted());
        }
    }

    @Nested
    @DisplayName("File Size Enforcement")
    class FileSizeEnforcement {

        @Test
        @DisplayName("Should accept file within size limit and return 202")
        void upload_shouldAccept_fileWithinLimit() throws Exception {
            byte[] content = new byte[1024];
            java.util.Arrays.fill(content, (byte) 'a');
            MockMultipartFile file = new MockMultipartFile("file", "small.csv", "text/csv", content);
            stubValidJob();
            mockMvc.perform(multipart("/api/v1/ingestion/paysim/upload").file(file))
                    .andExpect(status().isAccepted());
        }

        @Test
        @DisplayName("Should reject file exceeding max upload size with 413")
        void upload_shouldReject_oversizedFile() throws Exception {
            byte[] oversized = new byte[11 * 1024 * 1024];
            java.util.Arrays.fill(oversized, (byte) 'x');
            MockMultipartFile file = new MockMultipartFile("file", "huge.csv", "text/csv", oversized);
            mockMvc.perform(multipart("/api/v1/ingestion/paysim/upload").file(file))
                    .andExpect(status().isPayloadTooLarge());
            verify(ingestionAsyncService, never()).runImportAsync(any(), any(), anyBoolean());
        }
    }

    @Nested
    @DisplayName("Request Parameter Validation")
    class RequestParameterValidation {

        @Test
        @DisplayName("Should return 400 when file parameter is missing")
        void upload_shouldReturn400_whenFileParamMissing() throws Exception {
            mockMvc.perform(post("/api/v1/ingestion/paysim/upload")
                            .contentType("multipart/form-data"))
                    .andExpect(status().isBadRequest());
            verify(ingestionAsyncService, never()).runImportAsync(any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("Should return 400 when path parameter is missing")
        void path_shouldReturn400_whenPathParamMissing() throws Exception {
            mockMvc.perform(post("/api/v1/ingestion/paysim/path"))
                    .andExpect(status().isBadRequest());
            verify(ingestionAsyncService, never()).runImportAsync(any(), any(), anyBoolean());
        }
    }
}
