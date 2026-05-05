package dev.finguard.evaluation.runner;

import dev.finguard.detection.service.PipelineStatusTracker;
import dev.finguard.domain.enums.DatasetSource;
import dev.finguard.domain.enums.DetectionConfig;
import dev.finguard.domain.model.ExperimentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Runs evaluation experiments asynchronously on a virtual-thread executor.
 *
 * <p>The {@link ExperimentRunner} is synchronous and can run for minutes on
 * large datasets. This service wraps it so the REST controller can return
 * 202 Accepted immediately and the client polls {@link PipelineStatusTracker}
 * for progress.</p>
 */
@Service
public class EvaluationAsyncService {

    private static final Logger log = LoggerFactory.getLogger(EvaluationAsyncService.class);

    private final ExperimentRunner experimentRunner;
    private final PipelineStatusTracker statusTracker;

    public EvaluationAsyncService(ExperimentRunner experimentRunner,
                                   PipelineStatusTracker statusTracker) {
        this.experimentRunner = experimentRunner;
        this.statusTracker = statusTracker;
    }

    /**
     * Run a single experiment asynchronously.
     *
     * @param jobId   tracking ID returned to the client
     * @param request experiment configuration
     */
    @Async
    public void runAsync(String jobId, ExperimentRunner.ExperimentRequest request) {
        log.info("Async experiment job {} started: config={}", jobId, request.config());
        statusTracker.updateProgress(jobId, "Running detection pipeline", 0, 1, 0);
        try {
            ExperimentResult result = experimentRunner.runExperiment(request);
            statusTracker.complete(jobId, result.getTotalAlerts() != null ? result.getTotalAlerts() : 0);
            log.info("Async experiment job {} completed: id={}, F1={}", jobId, result.getId(),
                    result.getF1Score() != null ? String.format("%.4f", result.getF1Score()) : "N/A");
        } catch (Exception e) {
            log.error("Async experiment job {} failed: {}", jobId, e.getMessage(), e);
            statusTracker.fail(jobId, e.getMessage());
        }
    }

    /**
     * Run a comparative experiment across all configs asynchronously.
     *
     * @param jobId             tracking ID returned to the client
     * @param experimentName    shared experiment name
     * @param configs           configs to run
     * @param dataset           dataset to evaluate on
     * @param scoreExplanations whether to run CAKR scoring
     */
    @Async
    public void runComparisonAsync(String jobId, String experimentName,
                                    List<DetectionConfig> configs,
                                    DatasetSource dataset,
                                    boolean scoreExplanations,
                                    int maxAlertsToExplain) {
        log.info("Async comparison job {} started: {} configs (maxAlertsToExplain={})",
                jobId, configs.size(), maxAlertsToExplain);
        statusTracker.updateProgress(jobId, "Running comparison across " + configs.size() + " configs", 0, configs.size(), 0);
        try {
            List<ExperimentResult> results = experimentRunner.runComparison(
                    experimentName, configs, dataset, scoreExplanations, maxAlertsToExplain);
            long totalAlerts = results.stream()
                    .mapToLong(r -> r.getTotalAlerts() != null ? r.getTotalAlerts() : 0)
                    .sum();
            statusTracker.complete(jobId, totalAlerts);
            log.info("Async comparison job {} completed: {}/{} configs succeeded",
                    jobId, results.size(), configs.size());
        } catch (Exception e) {
            log.error("Async comparison job {} failed: {}", jobId, e.getMessage(), e);
            statusTracker.fail(jobId, e.getMessage());
        }
    }

    /**
     * Run k-fold CV asynchronously. Polled via the job-status endpoint;
     * cancellable by calling {@code POST /api/v1/evaluation/cancel/{jobId}}.
     */
    @Async
    public void runKFoldAsync(String jobId,
                               String experimentName,
                               DetectionConfig config,
                               DatasetSource dataset,
                               int k,
                               boolean scoreExplanations,
                               int maxAlertsToExplain) {
        log.info("Async k-fold job {} started: config={}, k={}", jobId, config, k);
        statusTracker.updateProgress(jobId, "Initialising k-fold", 0, 4 + k, 0);
        try {
            List<ExperimentResult> results = experimentRunner.runKFold(
                    experimentName, config, dataset, k, scoreExplanations, maxAlertsToExplain, jobId);
            long totalAlerts = results.stream()
                    .mapToLong(r -> r.getTotalAlerts() != null ? r.getTotalAlerts() : 0)
                    .max().orElse(0L);
            statusTracker.complete(jobId, totalAlerts);
            log.info("Async k-fold job {} completed: {} rows", jobId, results.size());
        } catch (ExperimentRunner.CancellationException ce) {
            log.warn("Async k-fold job {} cancelled by user", jobId);
            statusTracker.cancelled(jobId, 0);
        } catch (Exception e) {
            log.error("Async k-fold job {} failed: {}", jobId, e.getMessage(), e);
            statusTracker.fail(jobId, e.getMessage());
        }
    }

    /**
     * Run the full benchmark sweep asynchronously. Same cancel + status semantics
     * as {@link #runKFoldAsync}, just with one outer step per config.
     */
    @Async
    public void runBenchmarkAsync(String jobId,
                                   String experimentName,
                                   List<DetectionConfig> configs,
                                   DatasetSource dataset,
                                   int k,
                                   boolean scoreExplanations,
                                   int maxAlertsToExplain) {
        log.info("Async benchmark job {} started: {} configs × k={}", jobId, configs.size(), k);
        statusTracker.updateProgress(jobId, "Initialising benchmark",
                0, configs.size(), 0);
        try {
            List<ExperimentResult> results = experimentRunner.runBenchmark(
                    experimentName, configs, dataset, k, scoreExplanations, maxAlertsToExplain, jobId);
            long totalAlerts = results.stream()
                    .mapToLong(r -> r.getTotalAlerts() != null ? r.getTotalAlerts() : 0)
                    .max().orElse(0L);
            statusTracker.complete(jobId, totalAlerts);
            log.info("Async benchmark job {} completed: {} rows across {} configs",
                    jobId, results.size(), configs.size());
        } catch (ExperimentRunner.CancellationException ce) {
            log.warn("Async benchmark job {} cancelled by user", jobId);
            statusTracker.cancelled(jobId, 0);
        } catch (Exception e) {
            log.error("Async benchmark job {} failed: {}", jobId, e.getMessage(), e);
            statusTracker.fail(jobId, e.getMessage());
        }
    }
}
