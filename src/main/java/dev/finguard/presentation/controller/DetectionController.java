package dev.finguard.presentation.controller;

import dev.finguard.config.exception.ResourceNotFoundException;
import dev.finguard.detection.dto.AlertResponse;
import dev.finguard.detection.dto.DetectionResponse;
import dev.finguard.detection.service.DetectionPipelineService;
import dev.finguard.detection.service.PipelineStatusTracker;
import dev.finguard.detection.service.PipelineStatusTracker.PipelineStatus;
import dev.finguard.domain.enums.AlertStatus;
import dev.finguard.domain.enums.DetectionConfig;
import dev.finguard.domain.model.Alert;
import dev.finguard.domain.repository.AlertRepository;
import dev.finguard.domain.repository.TransactionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * REST API for the detection pipeline and alert management.
 *
 * <p>Provides endpoints to:
 * <ul>
 *   <li>Run the detection pipeline on all unprocessed transactions</li>
 *   <li>Browse and filter alerts</li>
 *   <li>Update alert status (for analyst review workflow)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/detection")
@Tag(name = "Detection", description = "Anomaly detection pipeline and alert management")
public class DetectionController {

    private static final Logger log = LoggerFactory.getLogger(DetectionController.class);

    private final DetectionPipelineService detectionPipelineService;
    private final PipelineStatusTracker statusTracker;
    private final AlertRepository alertRepository;
    private final TransactionRepository transactionRepository;

    public DetectionController(DetectionPipelineService detectionPipelineService,
                                PipelineStatusTracker statusTracker,
                                AlertRepository alertRepository,
                                TransactionRepository transactionRepository) {
        this.detectionPipelineService = detectionPipelineService;
        this.statusTracker = statusTracker;
        this.alertRepository = alertRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Run the detection pipeline on all transactions without alerts.
     */
    @PostMapping("/run")
    @Operation(summary = "Run detection pipeline",
               description = "Analyzes all transactions that don't have alerts yet using the specified " +
                             "detection configuration. Creates alerts for flagged transactions.")
    public ResponseEntity<DetectionResponse> runDetection(
            @Parameter(description = "Detection configuration to use")
            @RequestParam(defaultValue = "RULES_ONLY") DetectionConfig config) {

        Instant start = Instant.now();
        long totalTransactions = transactionRepository.count();
        long alertsCreated = detectionPipelineService.analyzeAllTransactions(config);
        long durationSeconds = Duration.between(start, Instant.now()).toSeconds();

        return ResponseEntity.ok(new DetectionResponse(
                config, alertsCreated, totalTransactions, durationSeconds));
    }

    /**
     * Browse alerts with pagination and optional filtering.
     */
    @GetMapping("/alerts")
    @Operation(summary = "List alerts",
               description = "Returns paginated alerts. Filter by status or detection config.")
    public ResponseEntity<Page<AlertResponse>> listAlerts(
            @Parameter(description = "Filter by alert status")
            @RequestParam(required = false) AlertStatus status,
            @Parameter(description = "Filter by detection config")
            @RequestParam(required = false) DetectionConfig config,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {

        Page<Alert> alerts;
        if (status != null) {
            alerts = alertRepository.findByStatus(status, pageable);
        } else if (config != null) {
            alerts = alertRepository.findByDetectionConfig(config, pageable);
        } else {
            alerts = alertRepository.findAll(pageable);
        }

        return ResponseEntity.ok(alerts.map(AlertResponse::from));
    }

    /**
     * Get a single alert by ID.
     */
    @GetMapping("/alerts/{id}")
    @Operation(summary = "Get alert details", description = "Returns a single alert by its ID.")
    public ResponseEntity<AlertResponse> getAlert(@PathVariable Long id) {
        Alert alert = alertRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Alert", "id", id));
        return ResponseEntity.ok(AlertResponse.from(alert));
    }

    /**
     * Update the status of an alert (analyst review workflow).
     */
    @PatchMapping("/alerts/{id}/status")
    @Operation(summary = "Update alert status",
               description = "Updates the review status of an alert (e.g., NEW → REVIEWED → CONFIRMED_FRAUD).")
    public ResponseEntity<AlertResponse> updateAlertStatus(
            @PathVariable Long id,
            @Parameter(description = "New status for the alert")
            @RequestParam AlertStatus status) {

        Alert alert = alertRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Alert", "id", id));

        alert.setStatus(status);
        Alert saved = alertRepository.save(alert);
        log.info("Alert {} status updated to {}", id, status);
        return ResponseEntity.ok(AlertResponse.from(saved));
    }

    /**
     * Run the detection pipeline asynchronously (non-blocking).
     *
     * <p>Returns immediately with a job ID. Poll {@code GET /api/v1/detection/status/{jobId}}
     * for progress.</p>
     *
     * @param limit optional max transactions to process (omit for all)
     */
    @PostMapping("/run/async")
    @Operation(summary = "Run detection pipeline asynchronously",
               description = "Starts the pipeline in the background and returns a job ID for progress tracking. " +
                             "Use 'limit' to cap the number of transactions analyzed (useful for demos).")
    public ResponseEntity<Map<String, String>> runDetectionAsync(
            @Parameter(description = "Detection configuration to use")
            @RequestParam(defaultValue = "RULES_ONLY") DetectionConfig config,
            @Parameter(description = "Max transactions to analyze. Omit for all.")
            @RequestParam(required = false) Long limit) {

        String jobId = java.util.UUID.randomUUID().toString().substring(0, 8);
        statusTracker.start(jobId, config.name());

        if (limit != null && limit > 0) {
            detectionPipelineService.runAsync(config, jobId, limit);
        } else {
            detectionPipelineService.runAsync(config, jobId);
        }

        return ResponseEntity.accepted().body(Map.of(
                "jobId", jobId,
                "statusUrl", "/api/v1/detection/status/" + jobId,
                "config", config.name()
        ));
    }

    /**
     * Get the status of an async pipeline job.
     */
    @GetMapping("/status/{jobId}")
    @Operation(summary = "Get pipeline job status",
               description = "Returns progress information for an async detection pipeline job.")
    public ResponseEntity<PipelineStatus> getJobStatus(@PathVariable String jobId) {
        PipelineStatus status = statusTracker.getStatus(jobId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }
}
