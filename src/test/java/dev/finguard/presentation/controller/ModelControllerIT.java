package dev.finguard.presentation.controller;

import dev.finguard.config.MockAiConfig;
import dev.finguard.config.TestContainersConfig;
import dev.finguard.detection.ml.TribuoModelService;
import dev.finguard.detection.ml.TribuoModelService.TrainingSummary;
import dev.finguard.domain.enums.DatasetSource;
import dev.finguard.domain.enums.TransactionType;
import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.model.TransactionFeatures;
import dev.finguard.domain.repository.*;
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

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the ModelController REST API.
 *
 * <p>Tests ML model training, status check, and loading endpoints.
 * TribuoModelService is mocked since we cannot train real models
 * in integration tests (requires sufficient training data and is slow).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, MockAiConfig.class})
@DisplayName("ModelController (REST API integration)")
class ModelControllerIT {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private TribuoModelService modelService;

    // ==============================================================
    // POST /api/v1/models/train
    // ==============================================================

    @Nested
    @DisplayName("POST /api/v1/models/train")
    class TrainModels {

        @Test
        @DisplayName("Returns training summary with model details on success")
        void trainSuccess_returnsSummary() throws Exception {
            when(modelService.trainModels()).thenReturn(new TrainingSummary(
                    1000, 150, 850, 4500,
                    List.of("RandomForest", "XGBoost")
            ));

            mockMvc.perform(post("/api/v1/models/train"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalExamples").value(1000))
                    .andExpect(jsonPath("$.fraudCount").value(150))
                    .andExpect(jsonPath("$.legitCount").value(850))
                    .andExpect(jsonPath("$.trainingTimeMs").value(4500))
                    .andExpect(jsonPath("$.trainedModels", hasSize(2)))
                    .andExpect(jsonPath("$.trainedModels[0]").value("RandomForest"))
                    .andExpect(jsonPath("$.trainedModels[1]").value("XGBoost"));
        }

        @Test
        @DisplayName("Returns 422 when insufficient training data")
        void insufficientData_returns422() throws Exception {
            when(modelService.trainModels()).thenReturn(new TrainingSummary(
                    0, 0, 0, 0, List.of()
            ));

            mockMvc.perform(post("/api/v1/models/train"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.trainedModels", hasSize(0)));
        }

        @Test
        @DisplayName("Successful training triggers auto-save to disk")
        void trainSuccess_autoSaves() throws Exception {
            when(modelService.trainModels()).thenReturn(new TrainingSummary(
                    500, 50, 450, 2000,
                    List.of("RandomForest")
            ));

            mockMvc.perform(post("/api/v1/models/train"))
                    .andExpect(status().isOk());

            verify(modelService).saveModels(any(Path.class));
        }

        @Test
        @DisplayName("Training succeeds even when save to disk fails")
        void trainSuccess_saveFails_stillReturnsOk() throws Exception {
            when(modelService.trainModels()).thenReturn(new TrainingSummary(
                    500, 50, 450, 2000,
                    List.of("RandomForest")
            ));
            doThrow(new IOException("Disk full")).when(modelService).saveModels(any(Path.class));

            // Should still return 200 with training summary
            mockMvc.perform(post("/api/v1/models/train"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.trainedModels", hasSize(1)));
        }
    }

    // ==============================================================
    // GET /api/v1/models/status
    // ==============================================================

    @Nested
    @DisplayName("GET /api/v1/models/status")
    class ModelStatus {

        @Test
        @DisplayName("Returns modelsAvailable=true when models are loaded")
        void modelsLoaded_returnsTrue() throws Exception {
            when(modelService.isModelAvailable()).thenReturn(true);

            mockMvc.perform(get("/api/v1/models/status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.modelsAvailable").value(true));
        }

        @Test
        @DisplayName("Returns modelsAvailable=false when no models loaded")
        void noModels_returnsFalse() throws Exception {
            when(modelService.isModelAvailable()).thenReturn(false);

            mockMvc.perform(get("/api/v1/models/status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.modelsAvailable").value(false));
        }
    }

    // ==============================================================
    // POST /api/v1/models/load
    // ==============================================================

    @Nested
    @DisplayName("POST /api/v1/models/load")
    class LoadModels {

        @Test
        @DisplayName("Returns success message when models load from disk")
        void loadSuccess_returnsMessage() throws Exception {
            doNothing().when(modelService).loadModels(any(Path.class));

            mockMvc.perform(post("/api/v1/models/load"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Models loaded successfully")));
        }

        @Test
        @DisplayName("Returns 503 when model files are not found on disk")
        void loadFails_returns503() throws Exception {
            doThrow(new IOException("No model file found"))
                    .when(modelService).loadModels(any(Path.class));

            mockMvc.perform(post("/api/v1/models/load"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.title").value("Service Unavailable"))
                    .andExpect(jsonPath("$.detail").value(containsString("ML Models")));
        }
    }
}
