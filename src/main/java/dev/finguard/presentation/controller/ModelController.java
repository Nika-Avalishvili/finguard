package dev.finguard.presentation.controller;

import dev.finguard.config.exception.ServiceUnavailableException;
import dev.finguard.detection.ml.TribuoModelService;
import dev.finguard.detection.ml.TribuoModelService.TrainingSummary;
import dev.finguard.detection.service.ModelTrainingAsyncService;
import dev.finguard.detection.service.PipelineStatusTracker;
import dev.finguard.detection.service.PipelineStatusTracker.PipelineStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * REST API for ML model training and management.
 */
@RestController
@RequestMapping("/api/v1/models")
@Tag(name = "ML Models", description = "Model training, persistence, and status")
public class ModelController {

    private static final Logger log = LoggerFactory.getLogger(ModelController.class);
    private static final Path MODEL_DIR = Path.of("data", "models");

    private final TribuoModelService modelService;
    private final ModelTrainingAsyncService modelTrainingAsyncService;
    private final PipelineStatusTracker statusTracker;

    public ModelController(TribuoModelService modelService,
                            ModelTrainingAsyncService modelTrainingAsyncService,
                            PipelineStatusTracker statusTracker) {
        this.modelService = modelService;
        this.modelTrainingAsyncService = modelTrainingAsyncService;
        this.statusTracker = statusTracker;
    }

    /**
     * Train models asynchronously. Returns a jobId immediately; poll
     * {@code GET /api/v1/models/training-status/{jobId}} for progress.
     *
     * @param models optional comma-separated list of models to train: RF, XGBoost, or both (default: both)
     */
    @PostMapping("/train/async")
    @Operation(summary = "Train ML models asynchronously",
               description = "Starts training in the background. Returns a jobId for progress polling. " +
                             "Optional ?models=RF or ?models=XGBoost to train a single model.")
    public ResponseEntity<Map<String, String>> trainModelsAsync(
            @RequestParam(required = false) String models) {
        boolean includeRf  = models == null || models.contains("RF");
        boolean includeXgb = models == null || models.contains("XGBoost");

        String jobId = UUID.randomUUID().toString().substring(0, 8);
        String label = models == null ? "Initialising (RF + XGBoost)" :
                       "Initialising (" + models + " only)";
        statusTracker.start(jobId, label);
        modelTrainingAsyncService.runAsync(jobId, includeRf, includeXgb);
        return ResponseEntity.accepted().body(Map.of(
                "jobId", jobId,
                "model", models != null ? models : "RF,XGBoost",
                "statusUrl", "/api/v1/models/training-status/" + jobId
        ));
    }

    /**
     * Poll the status of an async training job.
     */
    @GetMapping("/training-status/{jobId}")
    @Operation(summary = "Training job status",
               description = "Returns progress information for an async model training job.")
    public ResponseEntity<PipelineStatus> getTrainingStatus(@PathVariable String jobId) {
        PipelineStatus status = statusTracker.getStatus(jobId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }

    /**
     * Retrieve the {@link TrainingSummary} for a completed training job.
     */
    @GetMapping("/training-result/{jobId}")
    @Operation(summary = "Training result",
               description = "Returns the TrainingSummary for a completed async training job.")
    public ResponseEntity<TrainingSummary> getTrainingResult(@PathVariable String jobId) {
        return modelTrainingAsyncService.getResult(jobId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Train models synchronously (blocking). Kept for API and test compatibility.
     */
    @PostMapping("/train")
    @Operation(summary = "Train ML models (blocking)",
               description = "Trains models synchronously. For the UI, prefer /train/async.")
    public ResponseEntity<TrainingSummary> trainModels() {
        TrainingSummary summary = modelService.trainModels();

        if (summary.trainedModels().isEmpty()) {
            return ResponseEntity.unprocessableEntity().body(summary);
        }

        try {
            modelService.saveModels(MODEL_DIR);
        } catch (IOException e) {
            log.warn("Models trained but failed to save to disk: {}", e.getMessage());
        }

        return ResponseEntity.ok(summary);
    }

    /**
     * Check if ML models are loaded and ready for prediction.
     */
    @GetMapping("/status")
    @Operation(summary = "Model readiness", description = "Returns whether ML models are loaded.")
    public ResponseEntity<ModelStatus> modelStatus() {
        return ResponseEntity.ok(new ModelStatus(modelService.isModelAvailable()));
    }

    /**
     * Load previously saved models from disk.
     */
    @PostMapping("/load")
    @Operation(summary = "Load saved models",
               description = "Loads previously serialized Random Forest and XGBoost models from disk.")
    public ResponseEntity<String> loadModels() {
        try {
            modelService.loadModels(MODEL_DIR);
            return ResponseEntity.ok("Models loaded successfully");
        } catch (IOException e) {
            log.error("Failed to load models", e);
            throw new ServiceUnavailableException("ML Models",
                    "Failed to load models from disk. Ensure models were trained and saved previously.");
        }
    }

    public record ModelStatus(boolean modelsAvailable) {}
}