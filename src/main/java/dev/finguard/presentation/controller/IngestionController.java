package dev.finguard.presentation.controller;

import dev.finguard.config.exception.BadRequestException;
import dev.finguard.ingestion.service.FeatureEngineeringAsyncService;
import dev.finguard.ingestion.service.FeatureEngineeringBulkService;
import dev.finguard.ingestion.service.IngestionAsyncService;
import dev.finguard.ingestion.service.IngestionJobStore;
import dev.finguard.ingestion.service.TrainTestSplitService;
import dev.finguard.presentation.dto.IngestionStatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * REST API for dataset ingestion and feature engineering.
 *
 * <p>Import endpoints accept a CSV file (upload or server-side path) and return
 * {@code 202 Accepted} immediately with a job ID. The actual import runs in the
 * background. Poll {@code GET /status/{jobId}} for live progress.</p>
 *
 * <p>For large files (500MB+, 6M+ rows), always use the server-side path endpoint
 * to avoid the overhead of multipart upload.</p>
 */
@RestController
@RequestMapping("/api/v1/ingestion")
@Tag(name = "Ingestion", description = "Dataset import and feature engineering")
public class IngestionController {

    private static final Logger log = LoggerFactory.getLogger(IngestionController.class);

    /** Guards against concurrent feature computation runs that would corrupt the feature table. */
    private final AtomicBoolean featureComputationRunning = new AtomicBoolean(false);

    @Value("${spring.servlet.multipart.max-file-size:500MB}")
    private DataSize maxFileSize;

    private final IngestionAsyncService ingestionAsyncService;
    private final IngestionJobStore ingestionJobStore;
    private final FeatureEngineeringBulkService featureEngineeringBulkService;
    private final FeatureEngineeringAsyncService featureEngineeringAsyncService;
    private final TrainTestSplitService trainTestSplitService;
    private final dev.finguard.detection.service.PipelineStatusTracker pipelineStatusTracker;

    public IngestionController(IngestionAsyncService ingestionAsyncService,
                                IngestionJobStore ingestionJobStore,
                                FeatureEngineeringAsyncService featureEngineeringAsyncService,
                                FeatureEngineeringBulkService featureEngineeringBulkService,
                                TrainTestSplitService trainTestSplitService,
                                dev.finguard.detection.service.PipelineStatusTracker pipelineStatusTracker) {
        this.ingestionAsyncService = ingestionAsyncService;
        this.ingestionJobStore = ingestionJobStore;
        this.featureEngineeringAsyncService = featureEngineeringAsyncService;
        this.featureEngineeringBulkService = featureEngineeringBulkService;
        this.trainTestSplitService = trainTestSplitService;
        this.pipelineStatusTracker = pipelineStatusTracker;
    }

    /**
     * Import a PaySim CSV file via multipart upload.
     *
     * <p>The file is saved to a temp location, then imported in the background.
     * For files larger than a few hundred MB, prefer {@code POST /paysim/path}.</p>
     *
     * @return 202 Accepted with a job ID for polling
     */
    @PostMapping("/paysim/upload")
    @Operation(summary = "Import PaySim CSV via file upload (async)",
               description = "Upload a PaySim CSV file. Returns 202 immediately with a jobId. " +
                             "Poll GET /status/{jobId} for progress.")
    public ResponseEntity<Map<String, String>> importPaySimUpload(
            @Parameter(description = "PaySim CSV file")
            @RequestParam("file") MultipartFile file) throws IOException {

        if (file.isEmpty()) {
            throw new BadRequestException("Uploaded CSV file is empty");
        }

        if (file.getSize() > maxFileSize.toBytes()) {
            throw new MaxUploadSizeExceededException(maxFileSize.toBytes());
        }

        // Stream the uploaded file to a temp location while counting lines in a single pass.
        // This avoids the separate NIO scan that IngestionService would otherwise do after
        // the file is written — saving one full read of a potentially 500MB file.
        Path tempFile = Files.createTempFile("paysim-upload-", ".csv");
        long dataRows = transferAndCountLines(file.getInputStream(), tempFile);
        log.info("Uploaded file saved to temp: {} ({} bytes, ~{} data rows)", tempFile, file.getSize(), dataRows);

        return startJobWithKnownCount(tempFile, true, dataRows);
    }

    /**
     * Import a PaySim CSV file from a server-side path.
     *
     * <p>Preferred for large files (500MB+) already on the server filesystem.
     * Skips the overhead of multipart upload entirely.</p>
     *
     * @return 202 Accepted with a job ID for polling
     */
    @PostMapping("/paysim/path")
    @Operation(summary = "Import PaySim CSV from server path (async)",
               description = "Provide the absolute path to a PaySim CSV file already on the server. " +
                             "Returns 202 immediately with a jobId. " +
                             "Poll GET /status/{jobId} for progress.")
    public ResponseEntity<Map<String, String>> importPaySimFromPath(
            @Parameter(description = "Absolute path to the PaySim CSV file on the server")
            @RequestParam("path") String filePath) {

        if (filePath == null || filePath.isBlank()) {
            throw new BadRequestException("File path is required");
        }

        // Reject path traversal attempts
        Path candidate = Path.of(filePath);
        for (Path component : candidate) {
            if ("..".equals(component.toString())) {
                throw new BadRequestException("Path traversal is not allowed");
            }
        }

        if (!Files.exists(candidate)) {
            throw new BadRequestException("File not found at path: " + filePath);
        }

        if (!Files.isReadable(candidate)) {
            throw new BadRequestException("File is not readable: " + filePath);
        }

        return startJob(candidate, false);
    }

    /**
     * Poll the status of an active or completed import job.
     *
     * <p>Returns live progress: rows processed, rows imported, % complete, elapsed time.
     * Returns 404 if the job ID is unknown (jobs are in-memory; restarting the server clears them).</p>
     */
    @GetMapping("/status/{jobId}")
    @Operation(summary = "Get import job status",
               description = "Returns live progress for a running job, or the final result for a completed one. " +
                             "Statuses: PENDING, RUNNING, DONE, FAILED.")
    public ResponseEntity<IngestionStatusResponse> getStatus(
            @PathVariable String jobId) {
        return ingestionJobStore.find(jobId)
                .map(IngestionStatusResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Cancel a running import job.
     *
     * <p>Sets a cancel flag that the import loop checks after each batch.
     * The job transitions to {@code CANCELLED} once the current batch finishes.
     * Already-committed batches are not rolled back — the import is simply stopped early.</p>
     *
     * @return 200 OK if the job exists, 404 if not found
     */
    @PostMapping("/jobs/{jobId}/cancel")
    @Operation(summary = "Cancel a running import job",
               description = "Signals the import loop to stop after the current batch. " +
                             "Does not roll back already-committed rows.")
    public ResponseEntity<Void> cancelJob(@PathVariable String jobId) {
        return ingestionJobStore.find(jobId)
                .map(job -> {
                    job.cancel();
                    log.info("Cancel requested for job {}", jobId);
                    return ResponseEntity.ok().<Void>build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Trigger feature computation for transactions that don't have features yet.
     *
     * <p>This is automatically called as part of the async import job, but can also
     * be triggered manually if features need to be recomputed.</p>
     */
    @PostMapping("/features/compute")
    @Operation(summary = "Compute features for unprocessed transactions (async)",
               description = "Starts background ML feature computation for transactions without features. " +
                             "Returns 202 immediately with a jobId. Poll GET /status/{jobId} for progress. " +
                             "Use 'limit' to process only a subset (recommended: 100000 for demo/thesis use).")
    public ResponseEntity<Map<String, String>> computeFeatures(
            @Parameter(description = "Max transactions to process. Omit for all (very slow on 6M rows).")
            @RequestParam(required = false) Long limit) {

        if (!featureComputationRunning.compareAndSet(false, true)) {
            throw new BadRequestException("Feature computation is already in progress. " +
                    "Poll GET /api/v1/ingestion/status/{jobId} for the running job.");
        }

        String jobId;
        Runnable onComplete = () -> featureComputationRunning.set(false);
        try {
            jobId = ingestionJobStore.createJob();
            log.info("[features/compute] Submitting feature-computation job {} (limit={})",
                    jobId, limit != null ? limit : "ALL");
            if (limit != null && limit > 0) {
                featureEngineeringAsyncService.runAsync(jobId, limit, onComplete);
            } else {
                featureEngineeringAsyncService.runAsync(jobId, onComplete);
            }
        } catch (RuntimeException submissionFailure) {
            // Release the lock immediately — async submission never completed,
            // so onComplete will never run.
            featureComputationRunning.set(false);
            log.error("[features/compute] Failed to submit feature-computation job: {}",
                    submissionFailure.getMessage(), submissionFailure);
            throw submissionFailure;
        }
        return ResponseEntity.accepted().body(Map.of(
                "jobId", jobId,
                "statusUrl", "/api/v1/ingestion/status/" + jobId
        ));
    }

    /**
     * Delete all rows from {@code transaction_features}, allowing feature computation to restart from zero.
     *
     * @return 200 OK with count of deleted rows
     */
    @DeleteMapping("/features")
    @org.springframework.cache.annotation.CacheEvict(value = "dashboard-stats", allEntries = true)
    @Operation(summary = "Clear all computed features",
               description = "Deletes every row in transaction_features. " +
                             "Use before re-running feature computation from scratch.")
    public ResponseEntity<Map<String, Object>> clearFeatures() {
        long deleted = featureEngineeringBulkService.truncateAll();
        log.info("Feature table cleared — {} rows deleted", String.format("%,d", deleted));
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    /**
     * Carve off the newest portion of the dataset as a temporal test set.
     *
     * <p>After a fresh CSV import every row is flagged {@code is_training_set = true}
     * so ML training works immediately. Evaluation, however, requires a held-out test
     * set. This endpoint runs the same 80-th-percentile split used by Liquibase
     * changelog 016 but on demand, so you can re-run it after every import.</p>
     *
     * @param ratio training fraction in (0, 1); default 0.80 (= 80% training / 20% test)
     * @return JSON with cutoff timestamp and resulting training/test counts
     */
    @PostMapping("/finalize-train-test-split")
    @Operation(summary = "Finalize train/test split (temporal 80/20 by default) — SYNC",
               description = "Blocking variant. Marks the newest (1 − ratio) fraction of rows as test set " +
                             "by timestamp. Idempotent: safe to run after every import. " +
                             "For a UI-friendly non-blocking call with live progress, use " +
                             "POST /finalize-train-test-split-async.")
    public ResponseEntity<Map<String, Object>> finalizeTrainTestSplit(
            @RequestParam(name = "ratio", defaultValue = "0.80") double ratio) {
        if (ratio <= 0.0 || ratio >= 1.0) {
            throw new BadRequestException(
                    "ratio must be strictly between 0 and 1; got " + ratio);
        }
        log.info("Finalizing train/test split: ratio={}", ratio);
        TrainTestSplitService.SplitResult result = trainTestSplitService.finalizeTemporalSplit(ratio);
        log.info("Train/test split finalized: training={}, test={}, cutoff={}",
                result.trainingCount(), result.testCount(), result.cutoffTimestamp());
        return ResponseEntity.ok(result.toMap());
    }

    /**
     * Async variant of {@link #finalizeTrainTestSplit(double)}. Returns 202 immediately
     * with a jobId; UI polls {@code GET /api/v1/evaluation/status/{jobId}} for live
     * progress (phase labels: "Counting transactions", "Computing percentile cutoff",
     * "Dropping composite index", "Flipping newest rows → test set", "Rebuilding
     * composite index", "Counting final totals").
     *
     * <p>Fixes audit item #1: the sync endpoint gave users no visibility — the whole
     * 1-3 minute UPDATE + REINDEX was silent, leaving users wondering if it had hung.</p>
     */
    @PostMapping("/finalize-train-test-split-async")
    @Operation(summary = "Finalize train/test split (ASYNC — live progress)",
               description = "Returns 202 immediately with a jobId. " +
                             "Poll GET /api/v1/evaluation/status/{jobId} for live progress. " +
                             "Reports 6 phases: count → cutoff → drop index → UPDATE → rebuild index → final count.")
    public ResponseEntity<Map<String, String>> finalizeTrainTestSplitAsync(
            @RequestParam(name = "ratio", defaultValue = "0.80") double ratio) {
        if (ratio <= 0.0 || ratio >= 1.0) {
            throw new BadRequestException(
                    "ratio must be strictly between 0 and 1; got " + ratio);
        }
        String jobId = java.util.UUID.randomUUID().toString();
        pipelineStatusTracker.start(jobId, "TRAIN_TEST_SPLIT");
        log.info("Finalizing train/test split async: jobId={} ratio={}", jobId, ratio);
        trainTestSplitService.runAsync(jobId, ratio);
        return ResponseEntity.accepted().body(Map.of(
                "jobId", jobId,
                "statusUrl", "/api/v1/evaluation/status/" + jobId
        ));
    }

    private ResponseEntity<Map<String, String>> startJob(Path csvPath, boolean deleteTempAfter) {
        return startJobWithKnownCount(csvPath, deleteTempAfter, 0);
    }

    /**
     * Create a job and submit it for async execution, optionally pre-seeding the total
     * row count so {@link dev.finguard.ingestion.service.IngestionService} can skip its
     * own NIO line-count scan (saves one full read of large upload files).
     */
    private ResponseEntity<Map<String, String>> startJobWithKnownCount(Path csvPath,
                                                                        boolean deleteTempAfter,
                                                                        long knownDataRows) {
        String jobId = ingestionJobStore.createJob();
        if (knownDataRows > 0) {
            ingestionJobStore.find(jobId).ifPresent(job -> job.updateTotal(knownDataRows));
        }
        ingestionAsyncService.runImportAsync(jobId, csvPath, deleteTempAfter);
        log.info("Import job {} submitted for: {} (pre-counted rows: {})", jobId, csvPath, knownDataRows);
        return ResponseEntity.accepted().body(Map.of(
                "jobId", jobId,
                "statusUrl", "/api/v1/ingestion/status/" + jobId
        ));
    }

    /**
     * Write an {@link java.io.InputStream} to {@code target} while counting newline bytes.
     * Returns the number of \n characters seen (header + data rows).
     * Caller subtracts 1 for the header to get the data row count.
     */
    private long transferAndCountLines(java.io.InputStream in, Path target) throws IOException {
        long count = 0;
        try (java.io.OutputStream out = Files.newOutputStream(target)) {
            byte[] buf = new byte[65_536];
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                for (int i = 0; i < n; i++) {
                    if (buf[i] == (byte) '\n') count++;
                }
            }
        }
        // Subtract 1 for the header line; guard against files with no trailing newline
        return Math.max(0, count - 1);
    }
}