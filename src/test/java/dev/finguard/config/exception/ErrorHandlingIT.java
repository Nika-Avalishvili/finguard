package dev.finguard.config.exception;

import dev.finguard.config.MockAiConfig;
import dev.finguard.config.TestContainersConfig;
import dev.finguard.domain.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests verifying that error responses follow RFC 9457 Problem Detail format
 * across all REST controllers.
 *
 * <p>Tests the full HTTP pipeline: request → controller → exception → GlobalExceptionHandler → response.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, MockAiConfig.class})
@DisplayName("Error Handling (integration)")
class ErrorHandlingIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private AlertRepository alertRepository;
    @Autowired private ExplanationRepository explanationRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private TransactionFeaturesRepository featuresRepository;

    @BeforeEach
    void cleanUp() {
        explanationRepository.deleteAll();
        alertRepository.deleteAll();
        featuresRepository.deleteAll();
        transactionRepository.deleteAll();
    }

    // ================================================================
    // Resource Not Found (404)
    // ================================================================

    @Nested
    @DisplayName("404 — Resource Not Found")
    class NotFound {

        @Test
        @DisplayName("GET /api/v1/detection/alerts/{id} with non-existent ID → 404 ProblemDetail")
        void alertNotFound_returnsProblemDetail() throws Exception {
            mockMvc.perform(get("/api/v1/detection/alerts/999999"))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.title").value("Resource Not Found"))
                    .andExpect(jsonPath("$.detail").value(containsString("Alert")))
                    .andExpect(jsonPath("$.detail").value(containsString("999999")))
                    .andExpect(jsonPath("$.type").value(endsWith("resource-not-found")))
                    .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        @DisplayName("GET /api/v1/explanations/{id} with non-existent ID → 404 ProblemDetail")
        void explanationNotFound_returnsProblemDetail() throws Exception {
            mockMvc.perform(get("/api/v1/explanations/999999"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.title").value("Resource Not Found"))
                    .andExpect(jsonPath("$.detail").value(containsString("Explanation")));
        }

        @Test
        @DisplayName("PATCH /api/v1/detection/alerts/{id}/status with non-existent ID → 404")
        void updateAlertStatusNotFound_returnsProblemDetail() throws Exception {
            mockMvc.perform(patch("/api/v1/detection/alerts/999999/status")
                            .param("status", "REVIEWED"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.title").value("Resource Not Found"));
        }
    }

    // ================================================================
    // Bad Request (400)
    // ================================================================

    @Nested
    @DisplayName("400 — Bad Request")
    class BadRequest {

        @Test
        @DisplayName("POST /api/v1/ingestion/paysim/upload with empty file → 400 ProblemDetail")
        void emptyFileUpload_returnsBadRequest() throws Exception {
            MockMultipartFile emptyFile = new MockMultipartFile(
                    "file", "empty.csv", "text/csv", new byte[0]);

            mockMvc.perform(multipart("/api/v1/ingestion/paysim/upload").file(emptyFile))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.title").value("Bad Request"))
                    .andExpect(jsonPath("$.detail").value(containsString("empty")))
                    .andExpect(jsonPath("$.type").value(endsWith("bad-request")));
        }

        @Test
        @DisplayName("POST /api/v1/ingestion/paysim/path with non-existent path → 400 ProblemDetail")
        void fileNotFound_returnsBadRequest() throws Exception {
            mockMvc.perform(post("/api/v1/ingestion/paysim/path")
                            .param("path", "/nonexistent/path/file.csv"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Bad Request"))
                    .andExpect(jsonPath("$.detail").value(containsString("not found")));
        }

        @Test
        @DisplayName("POST /api/v1/evaluation/run with blank experiment name → 400")
        void blankExperimentName_returnsBadRequest() throws Exception {
            mockMvc.perform(post("/api/v1/evaluation/run")
                            .param("experimentName", "  ")
                            .param("config", "RULES_ONLY"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(containsString("name")));
        }
    }

    // ================================================================
    // Invalid Parameter (400 — type mismatch)
    // ================================================================

    @Nested
    @DisplayName("400 — Invalid Parameter (type mismatch)")
    class InvalidParameter {

        @Test
        @DisplayName("POST /api/v1/detection/run with invalid enum → 400 with allowed values")
        void invalidDetectionConfig_returnsBadRequestWithAllowedValues() throws Exception {
            mockMvc.perform(post("/api/v1/detection/run")
                            .param("config", "INVALID_CONFIG"))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.title").value("Invalid Parameter"))
                    .andExpect(jsonPath("$.detail").value(containsString("INVALID_CONFIG")))
                    .andExpect(jsonPath("$.parameter").value("config"))
                    .andExpect(jsonPath("$.rejectedValue").value("INVALID_CONFIG"))
                    .andExpect(jsonPath("$.allowedValues").value(containsString("RULES_ONLY")))
                    .andExpect(jsonPath("$.allowedValues").value(containsString("FULL_SYSTEM")));
        }

        @Test
        @DisplayName("PATCH /api/v1/detection/alerts/{id}/status with invalid status → 400")
        void invalidAlertStatus_returnsBadRequestWithAllowedValues() throws Exception {
            mockMvc.perform(patch("/api/v1/detection/alerts/1/status")
                            .param("status", "BOGUS"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Invalid Parameter"))
                    .andExpect(jsonPath("$.parameter").value("status"))
                    .andExpect(jsonPath("$.rejectedValue").value("BOGUS"));
        }

        @Test
        @DisplayName("GET /api/v1/detection/alerts with invalid page number → 400")
        void invalidPageNumber_returnsBadRequest() throws Exception {
            mockMvc.perform(get("/api/v1/detection/alerts")
                            .param("page", "not_a_number"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Invalid Parameter"));
        }
    }

    // ================================================================
    // Missing Parameter (400)
    // ================================================================

    @Nested
    @DisplayName("400 — Missing Required Parameter")
    class MissingParameter {

        @Test
        @DisplayName("POST /api/v1/evaluation/run without experimentName → 400")
        void missingExperimentName_returnsBadRequest() throws Exception {
            mockMvc.perform(post("/api/v1/evaluation/run")
                            .param("config", "RULES_ONLY"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Missing Parameter"))
                    .andExpect(jsonPath("$.parameter").value("experimentName"));
        }
    }

    // ================================================================
    // Response structure validation
    // ================================================================

    @Nested
    @DisplayName("Problem Detail structure compliance")
    class ProblemDetailStructure {

        @Test
        @DisplayName("Every error response includes type, title, status, detail, and timestamp")
        void errorResponse_hasAllRequiredFields() throws Exception {
            // Use a simple 404 case
            mockMvc.perform(get("/api/v1/detection/alerts/999999"))
                    .andExpect(jsonPath("$.type").isString())
                    .andExpect(jsonPath("$.title").isString())
                    .andExpect(jsonPath("$.status").isNumber())
                    .andExpect(jsonPath("$.detail").isString())
                    .andExpect(jsonPath("$.timestamp").isString());
        }

        @Test
        @DisplayName("Error response content type is application/problem+json")
        void errorResponse_hasProblemJsonContentType() throws Exception {
            mockMvc.perform(get("/api/v1/detection/alerts/999999"))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
    }
}
