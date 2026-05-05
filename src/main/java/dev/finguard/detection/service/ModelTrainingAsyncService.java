package dev.finguard.detection.service;

import dev.finguard.detection.ml.TribuoModelService;
import dev.finguard.detection.ml.TribuoModelService.TrainingSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs ML model training in the background via Spring's async executor.
 *
 * <p>Exists as a separate bean (not inside {@code ModelController}) so that
 * {@code @Async} is honoured through the Spring proxy — calling it from a
 * controller method goes through the proxy and dispatches to a thread pool.</p>
 *
 * <p>Training stages reported to {@link PipelineStatusTracker}:
 * <ol>
 *   <li>Loading training data — 5 %</li>
 *   <li>Training Random Forest — 20 %</li>
 *   <li>Training XGBoost — 60 %</li>
 *   <li>Saving models to disk — 90 %</li>
 *   <li>Complete — 100 %</li>
 * </ol>
 * Callers poll {@code GET /api/v1/models/training-status/{jobId}} for progress.</p>
 */
@Service
public class ModelTrainingAsyncService {

    private static final Logger log = LoggerFactory.getLogger(ModelTrainingAsyncService.class);
    private static final Path MODEL_DIR = Path.of("data", "models");

    private final TribuoModelService modelService;
    private final PipelineStatusTracker statusTracker;
    private final Map<String, TrainingSummary> results = new ConcurrentHashMap<>();

    public ModelTrainingAsyncService(TribuoModelService modelService,
                                      PipelineStatusTracker statusTracker) {
        this.modelService = modelService;
        this.statusTracker = statusTracker;
    }

    /**
     * Run model training asynchronously, reporting stage progress to the tracker.
     *
     * @param jobId pre-registered job ID (caller must call {@link PipelineStatusTracker#start}
     *              before invoking this method)
     */
    @Async
    public void runAsync(String jobId) {
        runAsync(jobId, true, true);
    }

    /**
     * Run model training for selected models only.
     *
     * @param includeRf  train Random Forest
     * @param includeXgb train XGBoost
     */
    @Async
    public void runAsync(String jobId, boolean includeRf, boolean includeXgb) {
        log.info("[job={}] Async model training started", jobId);

        try {
            TrainingSummary summary = modelService.trainModels(stage -> {
                int pct = switch (stage) {
                    case "Loading training data"  -> 5;
                    case "Training Random Forest" -> 20;
                    case "Training XGBoost"       -> 60;
                    default -> 0;
                };
                log.info("[job={}] Training stage: {} ({}%)", jobId, stage, pct);
                statusTracker.updateProgress(jobId, stage, pct, 100, 0);
            }, includeRf, includeXgb);

            if (summary.trainedModels().isEmpty()) {
                statusTracker.fail(jobId, "No models were trained. " +
                        "Ensure transactions with computed features are available.");
                return;
            }

            // Save to disk
            statusTracker.updateProgress(jobId, "Saving models to disk", 90, 100, 0);
            try {
                Files.createDirectories(MODEL_DIR);
                modelService.saveModels(MODEL_DIR);
                log.info("[job={}] Models saved to {}", jobId, MODEL_DIR);
            } catch (IOException e) {
                log.warn("[job={}] Models trained but could not be saved to disk: {}",
                        jobId, e.getMessage());
            }

            results.put(jobId, summary);
            statusTracker.complete(jobId, summary.trainedModels().size());
            log.info("[job={}] Training complete — {} model(s) in {}ms",
                    jobId, summary.trainedModels().size(), summary.trainingTimeMs());

        } catch (Exception e) {
            log.error("[job={}] Model training failed: {}", jobId, e.getMessage(), e);
            statusTracker.fail(jobId, e.getMessage());
        }
    }

    /**
     * Retrieve the {@link TrainingSummary} for a completed training job.
     *
     * @param jobId the job identifier
     * @return the summary, or empty if the job is still running or not found
     */
    public Optional<TrainingSummary> getResult(String jobId) {
        return Optional.ofNullable(results.get(jobId));
    }
}