package dev.finguard.presentation.controller;

import dev.finguard.config.exception.BadRequestException;
import dev.finguard.detection.service.PipelineStatusTracker;
import dev.finguard.domain.enums.DatasetSource;
import dev.finguard.domain.enums.DetectionConfig;
import dev.finguard.domain.model.ExperimentResult;
import dev.finguard.domain.repository.ExperimentResultRepository;
import dev.finguard.domain.repository.ExplanationRepository;
import dev.finguard.evaluation.metrics.CAKRScorer;
import dev.finguard.evaluation.metrics.StatisticalSignificanceService;
import dev.finguard.evaluation.runner.EvaluationApplicationService;
import dev.finguard.evaluation.runner.EvaluationAsyncService;
import dev.finguard.evaluation.runner.ExperimentRunner;
import dev.finguard.llm.DynamicChatClientService;
import dev.finguard.presentation.dto.RunComparisonRequest;
import dev.finguard.presentation.dto.RunExperimentRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST API for running evaluation experiments and viewing results.
 *
 * <p>Provides endpoints to trigger experiments, run comparative evaluations
 * across detection configs, and retrieve stored experiment results.</p>
 */
@RestController
@RequestMapping("/api/v1/evaluation")
@Tag(name = "Evaluation", description = "Experiment runner and metrics")
public class EvaluationController {

    private static final Logger log = LoggerFactory.getLogger(EvaluationController.class);

    private final ExperimentRunner experimentRunner;
    private final EvaluationAsyncService asyncService;
    private final EvaluationApplicationService evaluationService;
    private final ExperimentResultRepository resultRepository;
    private final ExplanationRepository explanationRepository;
    private final PipelineStatusTracker statusTracker;
    private final DynamicChatClientService chatClientService;
    private final CAKRScorer cakrScorer;
    private final StatisticalSignificanceService significanceService;

    public EvaluationController(ExperimentRunner experimentRunner,
                                 EvaluationAsyncService asyncService,
                                 EvaluationApplicationService evaluationService,
                                 ExperimentResultRepository resultRepository,
                                 ExplanationRepository explanationRepository,
                                 PipelineStatusTracker statusTracker,
                                 DynamicChatClientService chatClientService,
                                 CAKRScorer cakrScorer,
                                 StatisticalSignificanceService significanceService) {
        this.experimentRunner = experimentRunner;
        this.asyncService = asyncService;
        this.evaluationService = evaluationService;
        this.resultRepository = resultRepository;
        this.explanationRepository = explanationRepository;
        this.statusTracker = statusTracker;
        this.chatClientService = chatClientService;
        this.cakrScorer = cakrScorer;
        this.significanceService = significanceService;
    }

    /**
     * Run a single experiment for one detection config.
     */
    @PostMapping("/run")
    @Operation(summary = "Run a single experiment (async)",
               description = "Dispatches experiment to background thread. Poll /status/{jobId} for progress.")
    public ResponseEntity<Map<String, String>> runExperiment(
            @RequestParam String experimentName,
            @RequestParam DetectionConfig config,
            @RequestParam(defaultValue = "PAYSIM") DatasetSource dataset,
            @RequestParam(required = false) Integer fold,
            @RequestParam(defaultValue = "false") boolean scoreExplanations,
            @RequestParam(defaultValue = "50") int maxExplanationsToScore,
            @RequestParam(defaultValue = "500") int maxAlertsToExplain) {

        if (experimentName == null || experimentName.isBlank()) {
            throw new BadRequestException("Experiment name is required");
        }
        if (experimentName.length() > 100) {
            throw new BadRequestException("Experiment name must be at most 100 characters");
        }
        if (maxAlertsToExplain < 1 || maxAlertsToExplain > 10_000) {
            throw new BadRequestException(
                    "maxAlertsToExplain must be between 1 and 10 000 inclusive, got "
                            + maxAlertsToExplain);
        }

        ExperimentRunner.ExperimentRequest request = new ExperimentRunner.ExperimentRequest(
                experimentName, config, dataset, fold, scoreExplanations,
                maxExplanationsToScore, maxAlertsToExplain
        );

        String jobId = UUID.randomUUID().toString();
        statusTracker.start(jobId, "EXPERIMENT");
        asyncService.runAsync(jobId, request);

        return ResponseEntity.accepted().body(Map.of(
                "jobId", jobId,
                "statusUrl", "/api/v1/evaluation/status/" + jobId
        ));
    }

    /**
     * Audit A-1: run real k-fold cross-validation for a single detection config.
     *
     * <p>Assigns folds 0..k-1 (deterministic by row id), runs the detection
     * pipeline once, then computes per-fold metrics. Persists one
     * {@link ExperimentResult} per fold plus a summary row with mean metrics
     * across folds and stddev in {@code runParameters}. Synchronous: returns
     * all (k+1) rows in the response.</p>
     */
    @PostMapping("/kfold")
    @Operation(summary = "Run k-fold cross-validation for a single config (async)",
               description = "Returns 202 Accepted with a jobId. Poll /status/{jobId} for live progress, " +
                             "POST /cancel/{jobId} to stop. Persists k fold rows + 1 summary row when complete.")
    public ResponseEntity<Map<String, String>> runKFold(
            @RequestParam String experimentName,
            @RequestParam DetectionConfig config,
            @RequestParam(defaultValue = "PAYSIM") DatasetSource dataset,
            @RequestParam(defaultValue = "5") int k,
            @RequestParam(defaultValue = "false") boolean scoreExplanations,
            @RequestParam(defaultValue = "500") int maxAlertsToExplain) {

        if (experimentName == null || experimentName.isBlank()) {
            throw new BadRequestException("Experiment name is required");
        }
        if (k < 2 || k > 20) {
            throw new BadRequestException("k must be between 2 and 20 inclusive, got k=" + k);
        }
        if (maxAlertsToExplain < 1 || maxAlertsToExplain > 10_000) {
            throw new BadRequestException(
                    "maxAlertsToExplain must be between 1 and 10 000 inclusive, got "
                            + maxAlertsToExplain);
        }

        String jobId = UUID.randomUUID().toString();
        statusTracker.start(jobId, "KFOLD");
        log.info("[kfold] Submitting k={} CV job {} for experiment='{}' config={} dataset={}",
                k, jobId, experimentName, config, dataset);
        asyncService.runKFoldAsync(jobId, experimentName, config, dataset, k,
                scoreExplanations, maxAlertsToExplain);
        return ResponseEntity.accepted().body(Map.of(
                "jobId", jobId,
                "statusUrl", "/api/v1/evaluation/status/" + jobId,
                "cancelUrl", "/api/v1/evaluation/cancel/" + jobId
        ));
    }

    /**
     * End-to-end benchmark: k-fold CV × every detection config.
     *
     * <p>This is the thesis evaluation chapter's main table in one call. For
     * each of the 5 configs it runs real k-fold cross-validation, persists
     * one {@link ExperimentResult} per (config, fold) plus one summary row
     * per config with mean ± stddev.</p>
     *
     * <p>Synchronous: returns every result row in the response body. Expected
     * wall-clock on a 6M-row dataset: {@code |configs| × pipeline_time +
     * k × metric_time} (≈ 15-30 min for all 5 configs at k=5). Use this
     * endpoint overnight and pull the resulting CSV via
     * {@code /api/v1/export/experiments?experimentName=...}.</p>
     */
    @PostMapping("/benchmark")
    @Operation(summary = "Run full k-fold × all-configs benchmark (async)",
               description = "Returns 202 Accepted with a jobId. Poll /status/{jobId} for progress, " +
                             "POST /cancel/{jobId} to stop. The thesis evaluation table is persisted as " +
                             "rows complete — partial results survive a cancel.")
    public ResponseEntity<Map<String, String>> runBenchmark(
            @RequestParam String experimentName,
            @RequestParam(defaultValue = "PAYSIM") DatasetSource dataset,
            @RequestParam(defaultValue = "5") int k,
            @RequestParam(defaultValue = "false") boolean scoreExplanations,
            @RequestParam(required = false) List<DetectionConfig> configs,
            @RequestParam(defaultValue = "500") int maxAlertsToExplain) {

        if (experimentName == null || experimentName.isBlank()) {
            throw new BadRequestException("Experiment name is required");
        }
        if (k < 2 || k > 20) {
            throw new BadRequestException("k must be between 2 and 20 inclusive, got k=" + k);
        }
        if (maxAlertsToExplain < 1 || maxAlertsToExplain > 10_000) {
            throw new BadRequestException(
                    "maxAlertsToExplain must be between 1 and 10 000 inclusive, got "
                            + maxAlertsToExplain);
        }

        List<DetectionConfig> targets = (configs == null || configs.isEmpty())
                ? List.of(DetectionConfig.RULES_ONLY,
                          DetectionConfig.ML_ONLY,
                          DetectionConfig.ML_LLM_DIRECT,
                          DetectionConfig.ML_LLM_RAG,
                          DetectionConfig.FULL_SYSTEM)
                : configs;

        String jobId = UUID.randomUUID().toString();
        statusTracker.start(jobId, "BENCHMARK");
        log.info("[benchmark] Submitting job {} experiment='{}' k={} configs={} dataset={}",
                jobId, experimentName, k, targets, dataset);
        asyncService.runBenchmarkAsync(jobId, experimentName, targets, dataset, k,
                scoreExplanations, maxAlertsToExplain);
        return ResponseEntity.accepted().body(Map.of(
                "jobId", jobId,
                "statusUrl", "/api/v1/evaluation/status/" + jobId,
                "cancelUrl", "/api/v1/evaluation/cancel/" + jobId
        ));
    }

    /**
     * Cancel a running async job (single / compare / kfold / benchmark).
     *
     * <p>Cancellation is cooperative — the runner sets a flag, and the
     * background thread checks it between phases (folds / configs / explanation
     * batches). Detection itself is a single monolithic operation so cancel
     * doesn't interrupt mid-detection; expect a delay of up to a minute on
     * large datasets before the run actually stops.</p>
     */
    @PostMapping("/cancel/{jobId}")
    @Operation(summary = "Request cooperative cancellation of a running job",
               description = "Sets a cancel flag the running job polls between phases. The job exits cleanly at the next safe point and reports CANCELLED status.")
    public ResponseEntity<Map<String, Object>> cancelJob(@PathVariable String jobId) {
        boolean accepted = statusTracker.requestCancel(jobId);
        return ResponseEntity.ok(Map.of(
                "jobId", jobId,
                "cancelRequested", accepted,
                "message", accepted
                        ? "Cancel requested — the job will stop at the next phase boundary."
                        : "No active job with that id (already finished or never started)."
        ));
    }

    /**
     * Run a comparative experiment across all detection configs.
     */
    @PostMapping("/compare")
    @Operation(summary = "Run comparative experiment across all configs (async)",
               description = "Dispatches comparison to background thread. Poll /status/{jobId} for progress.")
    public ResponseEntity<Map<String, String>> runComparison(
            @RequestParam String experimentName,
            @RequestParam(defaultValue = "PAYSIM") DatasetSource dataset,
            @RequestParam(defaultValue = "false") boolean scoreExplanations,
            @RequestParam(defaultValue = "500") int maxAlertsToExplain) {

        if (experimentName == null || experimentName.isBlank()) {
            throw new BadRequestException("Experiment name is required");
        }
        if (maxAlertsToExplain < 1 || maxAlertsToExplain > 10_000) {
            throw new BadRequestException(
                    "maxAlertsToExplain must be between 1 and 10 000 inclusive, got "
                            + maxAlertsToExplain);
        }

        List<DetectionConfig> configs = List.of(
                DetectionConfig.RULES_ONLY,
                DetectionConfig.ML_ONLY,
                DetectionConfig.ML_LLM_DIRECT,
                DetectionConfig.ML_LLM_RAG,
                DetectionConfig.FULL_SYSTEM
        );

        String jobId = UUID.randomUUID().toString();
        statusTracker.start(jobId, "COMPARISON");
        asyncService.runComparisonAsync(jobId, experimentName, configs, dataset,
                scoreExplanations, maxAlertsToExplain);

        return ResponseEntity.accepted().body(Map.of(
                "jobId", jobId,
                "statusUrl", "/api/v1/evaluation/status/" + jobId
        ));
    }

    /**
     * Get all results for a named experiment.
     */
    @GetMapping("/results/{experimentName}")
    @Operation(summary = "Get experiment results by name")
    public ResponseEntity<List<ExperimentResultDto>> getByName(@PathVariable String experimentName) {
        List<ExperimentResult> results =
                resultRepository.findByExperimentNameOrdered(experimentName);
        return ResponseEntity.ok(results.stream().map(this::toDto).toList());
    }

    /**
     * Get all results for a specific detection config.
     */
    @GetMapping("/results/config/{config}")
    @Operation(summary = "Get experiment results by config")
    public ResponseEntity<List<ExperimentResultDto>> getByConfig(@PathVariable DetectionConfig config) {
        List<ExperimentResult> results = resultRepository.findByConfig(config);
        return ResponseEntity.ok(results.stream().map(this::toDto).toList());
    }

    /**
     * Get all experiment results (most recent first).
     */
    @GetMapping("/results")
    @Operation(summary = "Get all experiment results")
    public ResponseEntity<List<ExperimentResultDto>> getAll() {
        return ResponseEntity.ok(
                resultRepository.findAllOrderedByCreatedAtDesc().stream().map(this::toDto).toList());
    }

    /**
     * Poll the status of an async experiment job.
     */
    @GetMapping("/status/{jobId}")
    @Operation(summary = "Poll async experiment job status")
    public ResponseEntity<PipelineStatusTracker.PipelineStatus> getStatus(@PathVariable String jobId) {
        PipelineStatusTracker.PipelineStatus status = statusTracker.getStatus(jobId);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }

    /**
     * Generate a plain-language LLM interpretation of a single experiment result.
     *
     * <p>Sends the numeric metrics to the active LLM and asks it to explain
     * what the numbers mean in the context of fraud detection research.</p>
     */
    @PostMapping("/results/{id}/interpret")
    @Operation(summary = "Interpret experiment result metrics with LLM",
               description = "Returns a plain-language explanation of the result metrics")
    public ResponseEntity<Map<String, String>> interpret(@PathVariable Long id) {
        ExperimentResult r = resultRepository.findById(id)
                .orElseThrow(() -> new dev.finguard.config.exception.ResourceNotFoundException(
                        "ExperimentResult", "id", id));

        String prompt = buildInterpretationPrompt(r);
        log.info("Requesting LLM interpretation for experiment result id={}", id);

        try {
            String interpretation = chatClientService.getCurrentClient()
                    .prompt()
                    .user(prompt)
                    .call()
                    .content();
            log.info("LLM interpretation generated for result id={}", id);
            return ResponseEntity.ok(Map.of("interpretation", interpretation));
        } catch (Exception e) {
            log.warn("LLM interpretation failed for result id={}: {}", id, e.getMessage());
            return ResponseEntity.ok(Map.of("interpretation",
                    "LLM interpretation unavailable: " + e.getMessage()));
        }
    }

    private String buildInterpretationPrompt(ExperimentResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a fraud detection research assistant helping a compliance analyst understand machine learning experiment results. ");
        sb.append("Explain the following results in plain, non-technical language. ");
        sb.append("Be concise (3-5 sentences). Focus on what the numbers mean for real-world fraud detection — ");
        sb.append("what the system is good at, what it misses, and what the tradeoffs are.\n\n");
        sb.append("Experiment: ").append(r.getExperimentName()).append("\n");
        sb.append("Detection Config: ").append(r.getConfig()).append("\n");
        sb.append("Dataset: ").append(r.getDataset()).append("\n");
        sb.append("Total Transactions: ").append(r.getTotalTransactions()).append("\n");
        sb.append("Total Alerts Raised: ").append(r.getTotalAlerts()).append("\n");
        if (r.getPrecisionScore() != null)
            sb.append(String.format("Precision: %.3f (of all alerts raised, this fraction were truly fraud)\n", r.getPrecisionScore()));
        if (r.getRecallScore() != null)
            sb.append(String.format("Recall: %.3f (of all actual fraud, this fraction was detected)\n", r.getRecallScore()));
        if (r.getF1Score() != null)
            sb.append(String.format("F1 Score: %.3f (harmonic mean of precision and recall)\n", r.getF1Score()));
        if (r.getFalsePositiveRate() != null)
            sb.append(String.format("False Positive Rate: %.3f (fraction of legitimate transactions incorrectly flagged)\n", r.getFalsePositiveRate()));
        if (r.getAvgCakrScore() != null)
            sb.append(String.format("Average CAKR Score: %.2f/5.0 (explanation quality: completeness, actionability, knowledge, regulatory compliance)\n", r.getAvgCakrScore()));
        if (r.getHallucinationRate() != null)
            sb.append(String.format("Hallucination Rate: %.1f%% (fraction of explanations containing factual errors)\n", r.getHallucinationRate() * 100));
        if (r.getAvgLatencyMs() != null)
            sb.append(String.format("Average Explanation Latency: %.0fms\n", r.getAvgLatencyMs()));
        sb.append("\nProvide your interpretation:");
        return sb.toString();
    }

    /**
     * Delete a single experiment result by ID. Delegates to
     * {@link EvaluationApplicationService} so persistence stays in the
     * application layer, not the controller.
     */
    @DeleteMapping("/results/{id}")
    @Operation(summary = "Delete an experiment result by ID")
    public ResponseEntity<Void> deleteById(@PathVariable Long id) {
        boolean deleted = evaluationService.deleteById(id);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /** Delete all experiment results. */
    @DeleteMapping("/results")
    @Operation(summary = "Delete all experiment results")
    public ResponseEntity<Map<String, Object>> deleteAll() {
        return ResponseEntity.ok(Map.of("deleted", evaluationService.deleteAll()));
    }

    /**
     * Bulk-delete experiment results by a list of IDs.
     *
     * <p>Powers the "Delete selected" checkbox workflow on the Evaluation page.
     * Accepts up to 1 000 IDs in a single request; the caller is responsible for
     * paging larger selections.</p>
     */
    @DeleteMapping("/results/bulk")
    @Operation(summary = "Delete multiple experiment results by ID list",
               description = "Send ids as a comma-separated list or repeated query param: ids=1,2,3 or ids=1&ids=2&ids=3.")
    public ResponseEntity<Map<String, Object>> deleteByIds(@RequestParam("ids") List<Long> ids) {
        int deleted = evaluationService.deleteByIds(ids);
        return ResponseEntity.ok(Map.of(
                "requested", ids == null ? 0 : ids.size(),
                "deleted", deleted));
    }

    /**
     * Delete every row (fold + summary) belonging to a single experiment name.
     *
     * <p>Cheapest way to wipe one experiment without affecting others — useful
     * when iterating on a specific configuration.</p>
     */
    @DeleteMapping("/results/by-name/{experimentName}")
    @Operation(summary = "Delete all rows for one experiment (fold + summary)")
    public ResponseEntity<Map<String, Object>> deleteByExperimentName(@PathVariable String experimentName) {
        int deleted = evaluationService.deleteByExperimentName(experimentName);
        return ResponseEntity.ok(Map.of(
                "experimentName", experimentName,
                "deleted", deleted));
    }

    /** List distinct experiment names — feeds the "delete experiment" dropdown. */
    @GetMapping("/experiments")
    @Operation(summary = "List distinct experiment names")
    public ResponseEntity<List<String>> listExperimentNames() {
        return ResponseEntity.ok(evaluationService.listExperimentNames());
    }

    /**
     * Get a summary comparison of the latest experiment.
     */
    @GetMapping("/summary/{experimentName}")
    @Operation(summary = "Get experiment summary with key metrics for comparison")
    public ResponseEntity<Map<String, Object>> getSummary(@PathVariable String experimentName) {
        List<ExperimentResult> results =
                resultRepository.findByExperimentNameOrdered(experimentName);

        if (results.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        List<Map<String, Object>> configs = results.stream()
                .map(r -> Map.<String, Object>of(
                        "config", r.getConfig().name(),
                        "precision", format(r.getPrecisionScore()),
                        "recall", format(r.getRecallScore()),
                        "f1Score", format(r.getF1Score()),
                        "falsePositiveRate", format(r.getFalsePositiveRate()),
                        "avgCakrScore", format(r.getAvgCakrScore()),
                        "hallucinationRate", format(r.getHallucinationRate()),
                        "avgLatencyMs", format(r.getAvgLatencyMs()),
                        "totalAlerts", r.getTotalAlerts() != null ? r.getTotalAlerts() : 0
                ))
                .toList();

        return ResponseEntity.ok(Map.of(
                "experimentName", experimentName,
                "configCount", results.size(),
                "results", configs
        ));
    }

    /**
     * Per-dimension CAKR breakdown across every detection config.
     *
     * <p>Answers the thesis question "where does each config win or lose on
     * explanation quality?" — not just the collapsed average. For example,
     * FULL_SYSTEM might edge ML_LLM_RAG on Knowledge and Regulatory (RAG is
     * doing its job) while tying on Actionability (both use the same prompt
     * template).</p>
     */
    @GetMapping("/cakr-breakdown")
    @Operation(summary = "Per-config, per-dimension CAKR averages",
               description = "Grouped query: one row per detection config, with averages " +
                             "for each of the four CAKR dimensions plus a scored-count. " +
                             "Used to render the CAKR dimension comparison chart.")
    public ResponseEntity<List<Map<String, Object>>> cakrBreakdown() {
        List<Object[]> rows = explanationRepository.cakrBreakdownByDetectionConfig();
        List<Map<String, Object>> result = new java.util.ArrayList<>(rows.size());
        for (Object[] row : rows) {
            DetectionConfig cfg = (DetectionConfig) row[0];
            Double completeness  = (Double) row[1];
            Double actionability = (Double) row[2];
            Double correctness   = (Double) row[3];
            Double regulatory    = (Double) row[4];
            long scored = ((Number) row[5]).longValue();

            // Overall = mean of available dim averages (mirrors Explanation.getCakrAverage).
            double sum = 0; int n = 0;
            if (completeness != null)  { sum += completeness;  n++; }
            if (actionability != null) { sum += actionability; n++; }
            if (correctness != null)   { sum += correctness;   n++; }
            if (regulatory != null)    { sum += regulatory;    n++; }
            Double overall = n > 0 ? sum / n : null;

            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("config", cfg.name());
            entry.put("completeness", format(completeness));
            entry.put("actionability", format(actionability));
            entry.put("correctness", format(correctness));
            entry.put("regulatory", format(regulatory));
            entry.put("overall", format(overall));
            entry.put("scoredExplanations", scored);
            result.add(entry);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Re-score previously generated explanations with the current (possibly
     * different) judge LLM.
     *
     * <p>Thesis motivation: compare two judges on the same explanations to
     * quantify inter-judge agreement. Workflow:</p>
     * <ol>
     *   <li>Run initial CAKR scoring (Claude): {@code /run?scoreExplanations=true}</li>
     *   <li>Export CAKR scores to CSV</li>
     *   <li>Switch judge to Ollama via {@code /api/v1/llm/config}</li>
     *   <li>POST {@code /rescore} — clears existing CAKR and re-scores with Ollama</li>
     *   <li>Export again; diff the two CSVs</li>
     * </ol>
     *
     * <p>Operates on a sampled subset (max {@code limit}) to control LLM
     * cost. Explanations are selected in deterministic ID order.</p>
     *
     * @param explanationType explanation type to target (e.g., LLM_RAG_VALIDATED)
     * @param limit           max explanations to re-score (default 20, max 500)
     */
    @PostMapping("/rescore")
    @Operation(summary = "Re-score existing explanations with the current judge LLM",
               description = "Clears existing CAKR dims on a sample and re-scores them. " +
                             "Useful for comparing judge models.")
    public ResponseEntity<Map<String, Object>> rescoreExplanations(
            @RequestParam(defaultValue = "LLM_RAG_VALIDATED")
            dev.finguard.domain.enums.ExplanationType explanationType,
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(evaluationService.rescoreExplanations(explanationType, limit));
    }

    /**
     * LLM cost + latency breakdown per detection config.
     *
     * <p>Pairs with {@code /cakr-breakdown} to answer the thesis cost-benefit
     * question: "is the extra token spend from RAG/FULL_SYSTEM worth the
     * CAKR quality gain?" Totals come from the already-persisted
     * {@code prompt_tokens}/{@code completion_tokens}/{@code latency_ms}
     * columns on {@code Explanation}.</p>
     *
     * <p>Response per config: {@code count, totalPromptTokens, totalCompletionTokens,
     * avgPromptTokens, avgCompletionTokens, avgLatencyMs, p95LatencyMs}.</p>
     */
    @GetMapping("/cost-breakdown")
    @Operation(summary = "Per-config LLM cost and latency breakdown",
               description = "Totals and p95 latency per detection config, sourced from " +
                             "per-explanation token counts and latency_ms.")
    public ResponseEntity<List<Map<String, Object>>> costBreakdown() {
        List<Object[]> rows = explanationRepository.costBreakdownByDetectionConfig();
        List<Map<String, Object>> result = new java.util.ArrayList<>(rows.size());
        for (Object[] row : rows) {
            DetectionConfig cfg = (DetectionConfig) row[0];
            long count             = ((Number) row[1]).longValue();
            long sumPromptTokens   = ((Number) row[2]).longValue();
            long sumCompletion     = ((Number) row[3]).longValue();
            Double avgPromptTokens = (Double) row[4];
            Double avgCompletion   = (Double) row[5];
            Double avgLatency      = (Double) row[6];

            // p95 computed client-side — JPQL has no PERCENTILE_CONT and we
            // don't want to bake PostgreSQL-native SQL into the repository.
            List<Integer> latencies = explanationRepository.latenciesByDetectionConfig(cfg);
            Integer p95 = percentile(latencies, 0.95);

            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("config", cfg.name());
            entry.put("count", count);
            entry.put("totalPromptTokens", sumPromptTokens);
            entry.put("totalCompletionTokens", sumCompletion);
            entry.put("totalTokens", sumPromptTokens + sumCompletion);
            entry.put("avgPromptTokens", format(avgPromptTokens));
            entry.put("avgCompletionTokens", format(avgCompletion));
            entry.put("avgLatencyMs", format(avgLatency));
            entry.put("p95LatencyMs", p95 != null ? p95 : "N/A");
            result.add(entry);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Compute the p-th percentile of a sorted copy of {@code values}.
     * Returns {@code null} for an empty input.
     */
    private static Integer percentile(List<Integer> values, double p) {
        if (values == null || values.isEmpty()) return null;
        List<Integer> sorted = new java.util.ArrayList<>(values);
        java.util.Collections.sort(sorted);
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx);
    }

    /**
     * Per-config hallucination flag frequency — answers "which failure modes
     * does each config exhibit?"
     *
     * <p>Parses the {@code hallucinationFlags} JSON array on each failed
     * explanation, strips the descriptive suffix (everything after the first
     * {@code ':'}), and returns a frequency count per flag type per config.
     * Typical flag types include {@code AMOUNT_MISMATCH}, {@code ACCOUNT_INVENTED},
     * {@code RISK_OVERCLAIM}, etc. — see {@code HallucinationValidator}.</p>
     */
    @GetMapping("/hallucination-breakdown")
    @Operation(summary = "Per-config hallucination flag frequency",
               description = "Counts each hallucination flag type per detection config.")
    public ResponseEntity<Map<String, Map<String, Integer>>> hallucinationBreakdown() {
        List<Object[]> rows = explanationRepository.hallucinationFlagsByDetectionConfig();

        // config -> flagType -> count
        Map<String, Map<String, Integer>> byConfig = new java.util.LinkedHashMap<>();
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();

        for (Object[] row : rows) {
            String config = ((DetectionConfig) row[0]).name();
            String flagsJson = (String) row[1];
            if (flagsJson == null || flagsJson.isBlank()) continue;

            List<String> flags;
            try {
                flags = om.readValue(flagsJson,
                        om.getTypeFactory().constructCollectionType(List.class, String.class));
            } catch (Exception e) {
                log.debug("Failed to parse hallucination flags: {}", flagsJson);
                continue;
            }

            Map<String, Integer> perFlag = byConfig.computeIfAbsent(config,
                    k -> new java.util.LinkedHashMap<>());
            for (String flag : flags) {
                // Flag strings look like "AMOUNT_MISMATCH: amount 500 not found".
                // Keep only the type prefix so counts aggregate across variants.
                int sep = flag.indexOf(':');
                String type = sep > 0 ? flag.substring(0, sep).trim() : flag.trim();
                perFlag.merge(type, 1, Integer::sum);
            }
        }
        return ResponseEntity.ok(byConfig);
    }

    // ================================================================
    // Statistical significance (thesis §3.5 — paired t-tests + Bonferroni)
    // ================================================================

    /**
     * Pairwise paired t-tests across configs in a k-fold experiment, with
     * Bonferroni correction for multiple comparisons.
     *
     * <p>Reads per-fold rows for the named experiment and produces a report
     * for each metric (F1, precision, recall, AUC-ROC, AUC-PR) listing every
     * config-pair, the mean diff, the t-statistic, the raw two-sided p-value,
     * and a {@code significant} flag set when {@code p < α / num_pairs}.</p>
     *
     * <p>Default α is 0.05; override via {@code ?alpha=0.01} for stricter
     * comparisons (only justified when the family-wise error rate matters).</p>
     */
    @GetMapping("/significance/{experimentName}")
    @Operation(summary = "Pairwise paired t-tests (Bonferroni-corrected) for a k-fold experiment",
               description = "For each metric, every config pair gets a paired t-test; significance " +
                             "uses Bonferroni-corrected α = α / numPairs.")
    public ResponseEntity<StatisticalSignificanceService.ExperimentSignificanceReport> significance(
            @PathVariable String experimentName,
            @RequestParam(defaultValue = "0.05") double alpha) {
        if (alpha <= 0 || alpha >= 1) {
            throw new BadRequestException("alpha must be in (0, 1), got " + alpha);
        }
        return ResponseEntity.ok(significanceService.compare(experimentName, alpha));
    }

    // ================================================================
    // Cohen's κ inter-rater sample (thesis §3.4.2 — CAKR validity check)
    // ================================================================

    /**
     * Export a CSV sample of CAKR-scored explanations for manual human re-rating.
     *
     * <p>Workflow for inter-rater reliability:</p>
     * <ol>
     *   <li>Hit this endpoint to download {@code n} random explanations and their
     *       LLM CAKR scores (1–5 per dimension).</li>
     *   <li>A human annotator rates each explanation on the same 1–5 scale per dim
     *       <em>without</em> looking at the LLM scores.</li>
     *   <li>Compute Cohen's κ (or Fleiss' κ for &gt;2 raters) between the human
     *       and LLM columns in a notebook (e.g., scikit-learn
     *       {@code sklearn.metrics.cohen_kappa_score}).</li>
     *   <li>Report κ in the thesis as evidence the LLM-as-judge is calibrated.</li>
     * </ol>
     *
     * @param n              number of explanations to sample (default 20, max 200)
     * @param explanationType filter to a specific type (default LLM_RAG_VALIDATED)
     */
    @GetMapping(value = "/cakr/sample", produces = "text/csv")
    @Operation(summary = "Export CAKR-scored explanation sample as CSV for inter-rater study",
               description = "Returns explanation_id, alert_id, transaction_id, explanation_text, " +
                             "and LLM-assigned CAKR scores for n explanations. Empty human columns " +
                             "are appended for manual rating.")
    public ResponseEntity<String> cakrSample(
            @RequestParam(defaultValue = "20") int n,
            @RequestParam(defaultValue = "LLM_RAG_VALIDATED")
            dev.finguard.domain.enums.ExplanationType explanationType) {
        var sample = evaluationService.sampleExplanationsForRating(explanationType, n);
        int clamped = sample.size();

        StringBuilder csv = new StringBuilder(1024);
        csv.append("explanation_id,alert_id,transaction_id,explanation_type,")
           .append("llm_completeness,llm_actionability,llm_correctness,llm_regulatory,llm_avg,")
           .append("human_completeness,human_actionability,human_correctness,human_regulatory,")
           .append("explanation_text\n");
        for (var e : sample) {
            Long alertId = e.getAlert() != null ? e.getAlert().getId() : null;
            Long txId = (e.getAlert() != null && e.getAlert().getTransaction() != null)
                    ? e.getAlert().getTransaction().getId() : null;
            csv.append(e.getId()).append(',')
               .append(alertId != null ? alertId : "").append(',')
               .append(txId != null ? txId : "").append(',')
               .append(e.getExplanationType()).append(',')
               .append(csvCell(e.getCakrCompleteness())).append(',')
               .append(csvCell(e.getCakrActionability())).append(',')
               .append(csvCell(e.getCakrCorrectness())).append(',')
               .append(csvCell(e.getCakrRegulatory())).append(',')
               .append(csvCell(e.getCakrAverage())).append(',')
               .append(",,,,")  // four empty human columns
               .append(csvEscape(e.getExplanationText())).append('\n');
        }
        return ResponseEntity.ok()
                .header("Content-Disposition",
                        "attachment; filename=\"cakr-sample-" + explanationType + "-n" + clamped + ".csv\"")
                .body(csv.toString());
    }

    private static String csvCell(Double v) {
        if (v == null) return "";
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private static String csvEscape(String s) {
        if (s == null || s.isEmpty()) return "";
        boolean needsQuote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        String escaped = s.replace("\"", "\"\"")
                          .replace("\r", " ").replace("\n", " ");
        return needsQuote ? "\"" + escaped + "\"" : escaped;
    }

    // ================================================================
    // Helpers
    // ================================================================

    private Object format(Double value) {
        return value != null ? Math.round(value * 10000.0) / 10000.0 : "N/A";
    }

    private ExperimentResultDto toDto(ExperimentResult r) {
        return new ExperimentResultDto(
                r.getId(), r.getExperimentName(), r.getConfig(), r.getDataset(),
                r.getFold(), r.getPrecisionScore(), r.getRecallScore(), r.getF1Score(),
                r.getAucRoc(), r.getAucPr(), r.getFalsePositiveRate(),
                r.getAvgCakrScore(), r.getHallucinationRate(), r.getAvgLatencyMs(),
                r.getTotalTransactions(), r.getTotalAlerts(), r.getRunParameters(),
                r.getCreatedAt() != null ? r.getCreatedAt().toString() : null
        );
    }

    public record ExperimentResultDto(
            Long id,
            String experimentName,
            DetectionConfig config,
            DatasetSource dataset,
            Integer fold,
            Double precisionScore,
            Double recallScore,
            Double f1Score,
            Double aucRoc,
            Double aucPr,
            Double falsePositiveRate,
            Double avgCakrScore,
            Double hallucinationRate,
            Double avgLatencyMs,
            Integer totalTransactions,
            Integer totalAlerts,
            String runParameters,
            String createdAt
    ) {}
}
