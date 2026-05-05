package dev.finguard.evaluation.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finguard.config.MetricsConfig;
import dev.finguard.detection.ml.MLPredictionResult;
import dev.finguard.detection.ml.TribuoModelService;
import dev.finguard.detection.service.DetectionPipelineService;
import dev.finguard.detection.service.PipelineStatusTracker;
import dev.finguard.domain.enums.DatasetSource;
import dev.finguard.domain.enums.DetectionConfig;
import dev.finguard.domain.enums.ExplanationType;
import dev.finguard.domain.model.Explanation;
import dev.finguard.domain.model.ExperimentResult;
import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.model.TransactionFeatures;
import dev.finguard.domain.repository.AlertRepository;
import dev.finguard.domain.repository.ExperimentResultRepository;
import dev.finguard.domain.repository.ExplanationRepository;
import dev.finguard.domain.repository.TransactionFeaturesRepository;
import dev.finguard.domain.repository.TransactionRepository;
import dev.finguard.evaluation.metrics.CAKRScorer;
import dev.finguard.evaluation.metrics.DetectionMetrics;
import dev.finguard.explanation.llm.ExplanationBatchAsyncService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Orchestrates end-to-end evaluation experiments.
 *
 * <p>An experiment runs the detection pipeline with a specified {@link DetectionConfig},
 * computes detection metrics (precision, recall, F1) against ground-truth labels,
 * optionally scores explanations with the CAKR framework, and persists
 * {@link ExperimentResult} for later analysis.</p>
 *
 * <p>Typical usage for the thesis evaluation chapter: run the same dataset through
 * all 5 configs (RULES_ONLY, ML_ONLY, ML_LLM_DIRECT, ML_LLM_RAG, FULL_SYSTEM)
 * and compare the results.</p>
 */
@Service
public class ExperimentRunner {

    private static final Logger log = LoggerFactory.getLogger(ExperimentRunner.class);

    private final DetectionPipelineService detectionPipeline;
    private final DetectionMetrics detectionMetrics;
    private final CAKRScorer cakrScorer;
    private final TransactionRepository transactionRepository;
    private final TransactionFeaturesRepository featuresRepository;
    private final TribuoModelService mlService;
    private final AlertRepository alertRepository;
    private final ExplanationRepository explanationRepository;
    private final ExperimentResultRepository experimentResultRepository;
    private final ExplanationBatchAsyncService explanationBatchService;
    private final PipelineStatusTracker statusTracker;
    private final ObjectMapper objectMapper;
    private final ReproducibilitySnapshot reproSnapshot;
    private final HeapGuardrail heapGuardrail;
    private final FoldAssignmentService foldAssignmentService;
    private final MeterRegistry meterRegistry;

    /**
     * Number of legitimate test-set transactions sampled to compute AUC.
     * All test-set fraud is always included (small population, ~1.6k for PaySim).
     * 10 000 legit + ~1.6k fraud → ~11k pairs → stable AUC estimate (±0.015).
     *
     * <p>Default lowered from 20k → 10k after the OOM during the first thesis
     * benchmark run: 5 folds × 5 configs × 22k features at peak ≈ ~120 MB
     * resident in the L1 cache during AUC computation, on top of DevTools'
     * ~500 MB and the trained ML ensemble. 10k cuts the per-fold cost in half
     * with negligible loss in AUC stability.</p>
     *
     * <p>If you have heap to spare (e.g. {@code -Xmx12g}) raise to 25k for
     * tighter confidence intervals on AUC-PR.</p>
     */
    @Value("${finguard.evaluation.auc.legit-sample-size:10000}")
    private int aucLegitSampleSize;

    public ExperimentRunner(DetectionPipelineService detectionPipeline,
                             DetectionMetrics detectionMetrics,
                             CAKRScorer cakrScorer,
                             TransactionRepository transactionRepository,
                             TransactionFeaturesRepository featuresRepository,
                             TribuoModelService mlService,
                             AlertRepository alertRepository,
                             ExplanationRepository explanationRepository,
                             ExperimentResultRepository experimentResultRepository,
                             ExplanationBatchAsyncService explanationBatchService,
                             PipelineStatusTracker statusTracker,
                             ObjectMapper objectMapper,
                             ReproducibilitySnapshot reproSnapshot,
                             HeapGuardrail heapGuardrail,
                             FoldAssignmentService foldAssignmentService,
                             MeterRegistry meterRegistry) {
        this.detectionPipeline = detectionPipeline;
        this.detectionMetrics = detectionMetrics;
        this.cakrScorer = cakrScorer;
        this.transactionRepository = transactionRepository;
        this.featuresRepository = featuresRepository;
        this.mlService = mlService;
        this.alertRepository = alertRepository;
        this.explanationRepository = explanationRepository;
        this.experimentResultRepository = experimentResultRepository;
        this.explanationBatchService = explanationBatchService;
        this.statusTracker = statusTracker;
        this.objectMapper = objectMapper;
        this.reproSnapshot = reproSnapshot;
        this.heapGuardrail = heapGuardrail;
        this.foldAssignmentService = foldAssignmentService;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Run a single experiment: pipeline → (sync) explanations → metrics → CAKR (optional) → persist.
     *
     * <p>Audit C-4: annotated {@code NOT_SUPPORTED} to suspend any caller-side
     * transaction for the duration of the detection + LLM phases, which can
     * run for minutes. Holding a DB connection for that long would exhaust
     * the HikariCP pool and risks tripping
     * {@code idle_in_transaction_session_timeout} on the PostgreSQL side.
     * Per-query transactions are opened only where needed (repository calls
     * and the final {@code save()}), all short-lived.</p>
     *
     * @param request experiment configuration
     * @return the persisted ExperimentResult
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ExperimentResult runExperiment(ExperimentRequest request) {
        log.info("Starting experiment '{}' [config={}, dataset={}, scoreExplanations={}]",
                request.experimentName(), request.config(), request.dataset(),
                request.scoreExplanations());

        long startTime = System.currentTimeMillis();

        // 1. Run detection pipeline — disable auto-dispatch of async explanation so ExperimentRunner
        //    controls explanation timing (sync) and metric queries happen only after completion.
        log.info("[{}/5] Running detection pipeline for config={}", 1, request.config());
        long alertsCreated = detectionPipeline.analyzeAllTransactions(
                request.config(), null, Long.MAX_VALUE, false);
        log.info("[{}/5] Detection complete: {} alerts created in {}ms",
                1, alertsCreated, System.currentTimeMillis() - startTime);

        // 2. Generate LLM explanations synchronously for configs that require them.
        //    Synchronous execution ensures explanations are fully committed before metrics are queried.
        if (hasExplanations(request.config())) {
            ExplanationType explType = resolveExplanationType(request.config());
            if (explType != null) {
                long explStart = System.currentTimeMillis();
                // Cap explanation generation to the request's maxAlertsToExplain (default 500).
                // Without this, a run that flagged hundreds of thousands of alerts would spawn
                // one LLM call per alert — infeasible on local hardware. The CAKR judge only
                // ever samples up to maxExplanationsToScore anyway, so generating more than
                // that yields no evaluation benefit.
                int cap = request.maxAlertsToExplain();
                int explLimit = (int) Math.min(alertsCreated > 0 ? alertsCreated : cap, cap);
                log.info("[{}/5] Generating up to {} explanations synchronously [type={}, config={}, alerts={}, cap={}]",
                        2, explLimit, explType, request.config(), alertsCreated, cap);
                String explJobId = UUID.randomUUID().toString();
                statusTracker.start(explJobId, "EXPLANATION_BATCH");
                // Pass request.config() so the batch scopes its alert pool to this
                // experiment's config — critical for thesis-correct metric attribution.
                explanationBatchService.runSync(explJobId, request.config(), explType, explLimit);
                log.info("[{}/5] Explanations generated in {}ms", 2,
                        System.currentTimeMillis() - explStart);
            }
        } else {
            log.info("[{}/5] Config={} does not use LLM — skipping explanation phase",
                    2, request.config());
        }

        // 3. Aggregate-only metric computation — OOM-safe on large alert sets.
        // Previously this loaded a List<Alert> with JOIN FETCH transactions, which exploded
        // heap (1M+ alerts × eager transaction ≈ several GB). Two COUNT queries (total alerts
        // and true-positive alerts) give TP/FP without materialising any entities.
        log.info("[{}/5] Aggregating test-set counts for metrics", 3);
        long totalTx     = transactionRepository.countTestSetByDatasetSource(request.dataset());
        long fraudTx     = transactionRepository.countFraudTestSetByDatasetSource(request.dataset());
        long totalAlerts = alertRepository.countAnomaliesByConfigForTestSet(request.config());
        long truePositives = alertRepository.countTruePositivesByConfigForTestSet(request.config());
        log.info("[{}/5] Test-set stats: totalTx={}, fraudTx={}, alerts={}, TP={}",
                3, totalTx, fraudTx, totalAlerts, truePositives);

        // 4. Compute detection metrics purely from aggregate counts.
        log.info("[{}/5] Computing detection metrics (precision, recall, F1, FPR)", 4);
        DetectionMetrics.MetricsResult metrics = detectionMetrics.computeFromAggregates(
                request.config(), totalTx, fraudTx, totalAlerts, truePositives);
        log.info("[{}/5] Metrics: precision={}, recall={}, F1={}, FPR={}",
                4,
                String.format("%.4f", metrics.precision()),
                String.format("%.4f", metrics.recall()),
                String.format("%.4f", metrics.f1Score()),
                String.format("%.4f", metrics.falsePositiveRate()));

        // 5. Compute explanation metrics scoped to THIS experiment's alerts.
        //    Using alert IDs prevents pollution from previous experiment runs.
        //    Lightweight ID-only query — no JOIN FETCH of full Alert entities.
        Double avgCakr = null;
        Double hallucinationRate = null;
        Double avgLatency = null;

        log.info("[{}/5] Collecting explanation metrics (if applicable)", 5);

        if (hasExplanations(request.config()) && totalAlerts > 0) {
            List<ExplanationType> explTypes = getExplanationTypes(request.config());

            // Post-fix: use config-scoped JOIN queries instead of materialising
            // tens of thousands of alert ids into IN-clause bind parameters.
            // The legacy *AndAlertIdIn methods crashed on FULL_SYSTEM benchmarks
            // because Hibernate padded the IN list to >65,535 PostgreSQL params
            // (rules-OR-ML aggregation routinely produces 50k+ alerts on PaySim).
            log.info("Collecting explanation metrics for config={} using types={} (JOIN-scoped, no IN-clause)",
                    request.config(), explTypes);

            long totalExplanations = explanationRepository
                    .countByTypeInAndConfigForTestSet(explTypes, request.config());
            long totalEvaluated    = explanationRepository
                    .countEvaluatedForHallucinationByTypeInAndConfigForTestSet(explTypes, request.config());
            long hallucinations    = explanationRepository
                    .countHallucinationsByTypeInAndConfigForTestSet(explTypes, request.config());
            hallucinationRate = totalEvaluated > 0
                    ? (double) hallucinations / totalEvaluated
                    : null; // no evaluated rows → not reportable, persist NULL
            log.info("Hallucination rate: {}/{} evaluated (of {} total) = {}",
                    hallucinations, totalEvaluated, totalExplanations,
                    hallucinationRate != null ? String.format("%.4f", hallucinationRate) : "N/A");

            avgLatency = explanationRepository
                    .avgLatencyByTypeInAndConfigForTestSet(explTypes, request.config());
            log.info("Average explanation latency: {}ms",
                    avgLatency != null ? String.format("%.0f", avgLatency) : "N/A");

            // CAKR scoring (optional, LLM-based)
            if (request.scoreExplanations()) {
                log.info("Starting CAKR scoring (max={}) for config={}", request.maxExplanationsToScore(), request.config());
                List<Explanation> explanations = explanationRepository
                        .findUnscoredByTypeInAndConfigForTestSet(explTypes, request.config(),
                                PageRequest.of(0, request.maxExplanationsToScore()))
                        .getContent();

                log.info("Found {} unscored explanations to evaluate with CAKR", explanations.size());
                if (!explanations.isEmpty()) {
                    // Dashboard panel: per-config CAKR wall-clock for the
                    // "Pipeline Stage Timings → Evaluation" table.
                    long cakrStartMs = System.currentTimeMillis();
                    int scored = cakrScorer.scoreAll(explanations);
                    meterRegistry.timer(MetricsConfig.CAKR_CONFIG_TIMER, "config", request.config().name())
                            .record(System.currentTimeMillis() - cakrStartMs, TimeUnit.MILLISECONDS);
                    log.info("CAKR scoring complete: {}/{} scored", scored, explanations.size());
                }

                avgCakr = explanationRepository
                        .avgCakrByTypeInAndConfigForTestSet(explTypes, request.config());
                log.info("Average CAKR score for config={}: {}", request.config(),
                        avgCakr != null ? String.format("%.3f", avgCakr) : "N/A");
            }
        }

        // 6. Threshold-independent metrics (AUC-ROC + AUC-PR) on a stratified sample.
        //    Skipped for RULES_ONLY (no continuous score) and when the ML model isn't loaded.
        AucPair auc = computeAucForConfig(request.config(), request.dataset(), request.fold());

        long totalDurationMs = System.currentTimeMillis() - startTime;

        // Persist experiment result
        ExperimentResult result = new ExperimentResult();
        result.setExperimentName(request.experimentName());
        result.setConfig(request.config());
        result.setDataset(request.dataset());
        result.setFold(request.fold());
        result.setPrecisionScore(metrics.precision());
        result.setRecallScore(metrics.recall());
        result.setF1Score(metrics.f1Score());
        result.setAucRoc(auc.aucRoc());
        result.setAucPr(auc.aucPr());
        result.setFalsePositiveRate(metrics.falsePositiveRate());
        result.setTotalTransactions(metrics.totalTransactions());
        result.setTotalAlerts(metrics.totalAlerts());
        result.setAvgCakrScore(avgCakr);
        result.setHallucinationRate(hallucinationRate);
        result.setAvgLatencyMs(avgLatency);

        // Store run parameters as JSON — merged with the reproducibility
        // snapshot so every persisted result row can be used to recreate the
        // exact runtime conditions (rule thresholds, ML hyperparams, LLM
        // provider/model, dataset counts, JVM version, etc.). Useful when a
        // thesis examiner asks "how do I reproduce this number?".
        try {
            Map<String, Object> params = new java.util.LinkedHashMap<>();
            params.put("config", request.config().name());
            params.put("dataset", request.dataset().name());
            params.put("scoreExplanations", request.scoreExplanations());
            params.put("maxExplanationsToScore", request.maxExplanationsToScore());
            params.put("durationMs", totalDurationMs);
            // Attach the full reproducibility snapshot.
            params.putAll(reproSnapshot.capture());
            result.setRunParameters(objectMapper.writeValueAsString(params));
        } catch (Exception e) {
            log.warn("Failed to serialize run parameters: {}", e.getMessage());
        }

        result = experimentResultRepository.save(result);
        log.info("Experiment '{}' complete [id={}]: F1={}, CAKR={}, duration={}ms",
                request.experimentName(), result.getId(),
                String.format("%.4f", metrics.f1Score()),
                avgCakr != null ? String.format("%.2f", avgCakr) : "N/A",
                totalDurationMs);

        return result;
    }

    /**
     * Audit A-1: real k-fold cross-validation.
     *
     * <p>Assigns folds 0..k-1 deterministically (via {@code HASHTEXT(id)}), then
     * iterates each fold as the held-out test partition. For each fold iteration
     * we:</p>
     * <ol>
     *   <li>Query alerts and txCounts scoped to that fold (via
     *       {@link AlertRepository#findAnomaliesByConfigAndFold}).</li>
     *   <li>Compute precision/recall/F1/FPR for that fold only.</li>
     *   <li>Persist one {@link ExperimentResult} per fold (with {@code fold} set).</li>
     * </ol>
     *
     * <p>After the loop, a summary row is persisted with {@code fold = null}
     * whose {@code precision/recall/f1/FPR} are the mean across folds, and
     * whose {@code runParameters} JSON includes per-fold stddev.</p>
     *
     * @param experimentName    base experiment name (fold number appended per row)
     * @param config            detection config to evaluate
     * @param dataset           dataset source
     * @param k                 number of folds (typical: 5 or 10; must be >= 2)
     * @param scoreExplanations whether to run CAKR scoring per fold
     * @param maxAlertsToExplain cap on how many alerts receive an LLM explanation across
     *                           the whole run (applied once, not per fold). Defaults to 500
     *                           if the caller passes {@code <= 0}.
     * @return one row per fold plus one summary row (k + 1 total)
     */
    public List<ExperimentResult> runKFold(String experimentName,
                                            DetectionConfig config,
                                            DatasetSource dataset,
                                            int k,
                                            boolean scoreExplanations,
                                            int maxAlertsToExplain) {
        return runKFold(experimentName, config, dataset, k, scoreExplanations, maxAlertsToExplain, null);
    }

    /**
     * Job-aware overload that publishes progress to {@link PipelineStatusTracker}
     * and checks the cancel flag at fold boundaries. Use this from async paths
     * (kfold-async, benchmark-async) so the user can monitor + stop the run.
     *
     * <p>Phase plan (k=5 example): {@code (1) detection (2) explanation generation
     * (3) CAKR scoring (4..k+3) per-fold metrics (k+4) summary row}. Progress
     * percentage interpolates linearly across these phases, so the banner
     * advances smoothly through the run.</p>
     *
     * @throws CancellationException if {@link PipelineStatusTracker#requestCancel}
     *         was called between two phases. The async service catches this
     *         and marks the job cancelled.
     */
    public List<ExperimentResult> runKFold(String experimentName,
                                            DetectionConfig config,
                                            DatasetSource dataset,
                                            int k,
                                            boolean scoreExplanations,
                                            int maxAlertsToExplain,
                                            String jobId) {
        return runKFold(experimentName, config, dataset, k,
                scoreExplanations, maxAlertsToExplain, jobId, false);
    }

    /**
     * Overload that lets a caller (notably {@link #runBenchmark}) opt out of
     * the internal {@code assignFolds} step. Fold assignment is deterministic
     * and config-independent, so the benchmark loop only needs it once;
     * repeating it per-config was wasting ~10 min × 5 configs ≈ <b>~50 min</b>
     * of wall-clock on the thesis dataset.
     */
    public List<ExperimentResult> runKFold(String experimentName,
                                            DetectionConfig config,
                                            DatasetSource dataset,
                                            int k,
                                            boolean scoreExplanations,
                                            int maxAlertsToExplain,
                                            String jobId,
                                            boolean skipFoldAssignment) {
        if (k < 2) {
            throw new IllegalArgumentException("k-fold CV requires k >= 2, got k=" + k);
        }
        log.info("Starting {}-fold CV for '{}' [config={}, dataset={}, jobId={}, skipFoldAssign={}]",
                k, experimentName, config, dataset, jobId, skipFoldAssignment);

        // Total "steps" = 3 setup phases + k folds + 1 summary row.
        final int totalSteps = 4 + k;
        int doneSteps = 0;
        progress(jobId, skipFoldAssignment ? "Reusing fold assignment" : "Assigning folds",
                doneSteps, totalSteps, 0);
        checkCancel(jobId);

        // Deterministic assignment — same HASHTEXT(id) % k regardless of config.
        // The benchmark loop sets skipFoldAssignment=true for configs 2..N so we
        // don't drop + rebuild the idx_tx_fold index 5 times in a row.
        if (skipFoldAssignment) {
            log.info("Skipping fold assignment (already done for this benchmark run)");
        } else {
            int updated = foldAssignmentService.assignFolds(k);
            log.info("Assigned folds 0..{} to {} transactions", k - 1, updated);
        }
        doneSteps++;
        progress(jobId, "Running detection pipeline", doneSteps, totalSteps, 0);
        checkCancel(jobId);

        // Run detection pipeline once — alerts cover all folds; per-fold queries
        // slice by transaction.fold at metric time.
        long overallStart = System.currentTimeMillis();
        long alertsCreated = detectionPipeline.analyzeAllTransactions(
                config, null, Long.MAX_VALUE, false);
        log.info("K-fold detection produced {} alerts in {}ms",
                alertsCreated, System.currentTimeMillis() - overallStart);
        doneSteps++;
        progress(jobId, "Generating explanations", doneSteps, totalSteps, alertsCreated);
        checkCancel(jobId);

        // Generate LLM explanations once if needed — they're keyed to alerts,
        // so each fold slices its own subset for CAKR.
        List<ExplanationType> explTypes = hasExplanations(config)
                ? getExplanationTypes(config)
                : List.of();
        if (hasExplanations(config)) {
            ExplanationType explType = resolveExplanationType(config);
            if (explType != null) {
                String explJobId = UUID.randomUUID().toString();
                statusTracker.start(explJobId, "EXPLANATION_BATCH");
                int cap = maxAlertsToExplain > 0 ? maxAlertsToExplain : 500;
                int explLimit = (int) Math.min(alertsCreated > 0 ? alertsCreated : cap, cap);
                log.info("K-fold: generating up to {} explanations for config={} (alerts={}, cap={})",
                        explLimit, config, alertsCreated, cap);
                explanationBatchService.runSync(explJobId, config, explType, explLimit);
            }
        }
        doneSteps++;
        progress(jobId, "Scoring CAKR (if enabled)", doneSteps, totalSteps, alertsCreated);
        checkCancel(jobId);

        // CAKR scoring happens ONCE per config (not per fold) — scores a sample
        // of the generated explanations, which are then queried per-fold via
        // scoped averages (avgCakrByTypeInAndAlertIdIn). Without this step, CAKR
        // columns are NULL on every fold row.
        if (scoreExplanations && hasExplanations(config) && !explTypes.isEmpty()) {
            // CAKR sample size aligned with ExperimentRequest default — see compact
            // ctor javadoc. Bumped 100 → 300 to halve the CAKR confidence interval.
            int cakrCap = 300;
            // Config-scoped JOIN query — the previous (alertIds IN ...) approach
            // crashed FULL_SYSTEM benchmarks at the PostgreSQL 65,535-parameter
            // cap. The JOIN approach scopes through the alert/transaction
            // relationship rather than the bind list, so it's bullet-proof at
            // any config size. It also preserves the no-cross-config-bleed
            // semantics (each config scores only its own alerts' explanations).
            List<dev.finguard.domain.model.Explanation> unscored =
                    explanationRepository.findUnscoredByTypeInAndConfigForTestSet(
                            explTypes, config, PageRequest.of(0, cakrCap)).getContent();
            log.info("K-fold CAKR: scoring up to {} unscored explanations for config={}",
                    unscored.size(), config);
            if (!unscored.isEmpty()) {
                int scored = cakrScorer.scoreAll(unscored);
                log.info("K-fold CAKR: {}/{} explanations scored", scored, unscored.size());
            }
        }
        doneSteps++;

        List<ExperimentResult> perFold = new ArrayList<>(k);
        double[] p = new double[k], r = new double[k], f1 = new double[k], fpr = new double[k];
        Double[] cakrs   = new Double[k];
        Double[] hallucs = new Double[k];
        Double[] lats    = new Double[k];
        Double[] aucRocs = new Double[k];
        Double[] aucPrs  = new Double[k];

        // Grouped per-fold counts — TWO queries instead of 4×k individual COUNTs.
        // On a 3.5 M-alert config this cuts metric assembly from ~5 min per config
        // to ~5 s (PostgreSQL scans each table once and folds the per-fold buckets).
        // Shape: Map<foldId, long[]{total, fraudOrTp}>.
        java.util.Map<Integer, long[]> txCountsByFold =
                extractFoldCounts(transactionRepository.perFoldCountsByDataset(dataset));
        java.util.Map<Integer, long[]> alertCountsByFold =
                extractFoldCounts(alertRepository.perFoldCountsByConfig(config));

        for (int fold = 0; fold < k; fold++) {
            // Cancel between folds — graceful exit, the partial perFold list
            // is discarded by the async service which calls statusTracker.cancelled.
            checkCancel(jobId);
            progress(jobId, "Fold " + (fold + 1) + " / " + k + " — computing metrics",
                    doneSteps + fold, totalSteps, alertsCreated);
            log.info("[fold {}/{}] Computing metrics for '{}'", fold + 1, k, experimentName);

            long[] txRow    = txCountsByFold.getOrDefault(fold, new long[]{0L, 0L});
            long[] alertRow = alertCountsByFold.getOrDefault(fold, new long[]{0L, 0L});
            long foldTotalTx  = txRow[0];
            long foldFraudTx  = txRow[1];
            long foldAlertCnt = alertRow[0];
            long foldTp       = alertRow[1];

            DetectionMetrics.MetricsResult m = detectionMetrics.computeFromAggregates(
                    config, foldTotalTx, foldFraudTx, foldAlertCnt, foldTp);
            p[fold]   = m.precision();
            r[fold]   = m.recall();
            f1[fold]  = m.f1Score();
            fpr[fold] = m.falsePositiveRate();

            // Per-fold explanation metrics — scoped to the alerts whose underlying
            // transaction falls in this fold. IDs-only query keeps the metric step
            // OOM-safe even when millions of alerts exist.
            Double foldCakr = null;
            Double foldHalluc = null;
            Double foldLat = null;
            if (hasExplanations(config) && !explTypes.isEmpty()) {
                // Config + fold + test-set scoping moved into the SQL JOIN to
                // avoid the PostgreSQL 65,535-parameter cap on FULL_SYSTEM
                // (the previous foldAlertIds-IN approach crashed mid-benchmark).
                foldCakr = explanationRepository
                        .avgCakrByTypeInAndConfigAndFold(explTypes, config, fold);
                long hc = explanationRepository
                        .countHallucinationsByTypeInAndConfigAndFold(explTypes, config, fold);
                long eval = explanationRepository
                        .countEvaluatedForHallucinationByTypeInAndConfigAndFold(explTypes, config, fold);
                foldHalluc = eval > 0 ? (double) hc / eval : null;
                foldLat = explanationRepository
                        .avgLatencyByTypeInAndConfigAndFold(explTypes, config, fold);
            }
            cakrs[fold]   = foldCakr;
            hallucs[fold] = foldHalluc;
            lats[fold]    = foldLat;

            // Per-fold AUC-ROC/AUC-PR over a stratified sample of the held-out fold.
            AucPair foldAuc = computeAucForConfig(config, dataset, fold);
            aucRocs[fold] = foldAuc.aucRoc();
            aucPrs[fold]  = foldAuc.aucPr();

            log.info("[fold {}/{}] precision={} recall={} f1={} fpr={} aucRoc={} aucPr={} cakr={} halluc={} lat={}",
                    fold + 1, k,
                    String.format("%.4f", p[fold]),
                    String.format("%.4f", r[fold]),
                    String.format("%.4f", f1[fold]),
                    String.format("%.4f", fpr[fold]),
                    foldAuc.aucRoc() != null ? String.format("%.4f", foldAuc.aucRoc()) : "N/A",
                    foldAuc.aucPr()  != null ? String.format("%.4f", foldAuc.aucPr())  : "N/A",
                    foldCakr != null ? String.format("%.3f", foldCakr) : "N/A",
                    foldHalluc != null ? String.format("%.3f", foldHalluc) : "N/A",
                    foldLat != null ? String.format("%.0f", foldLat) + "ms" : "N/A");

            ExperimentResult row = new ExperimentResult();
            row.setExperimentName(experimentName);
            row.setConfig(config);
            row.setDataset(dataset);
            row.setFold(fold);
            row.setPrecisionScore(m.precision());
            row.setRecallScore(m.recall());
            row.setF1Score(m.f1Score());
            row.setAucRoc(foldAuc.aucRoc());
            row.setAucPr(foldAuc.aucPr());
            row.setFalsePositiveRate(m.falsePositiveRate());
            row.setTotalTransactions(m.totalTransactions());
            row.setTotalAlerts(m.totalAlerts());
            row.setAvgCakrScore(foldCakr);
            row.setHallucinationRate(foldHalluc);
            row.setAvgLatencyMs(foldLat);
            perFold.add(experimentResultRepository.save(row));
        }
        progress(jobId, "Computing summary row", doneSteps + k, totalSteps, alertsCreated);
        checkCancel(jobId);

        // Summary row — mean across folds, fold = null. Carries the explanation
        // metrics too so the thesis-headline row is directly comparable across
        // configs on CAKR / hallucination / latency, not just precision/recall.
        ExperimentResult summary = new ExperimentResult();
        summary.setExperimentName(experimentName);
        summary.setConfig(config);
        summary.setDataset(dataset);
        summary.setFold(null);
        summary.setPrecisionScore(mean(p));
        summary.setRecallScore(mean(r));
        summary.setF1Score(mean(f1));
        summary.setFalsePositiveRate(mean(fpr));
        summary.setAucRoc(meanNullable(aucRocs));
        summary.setAucPr(meanNullable(aucPrs));
        summary.setAvgCakrScore(meanNullable(cakrs));
        summary.setHallucinationRate(meanNullable(hallucs));
        summary.setAvgLatencyMs(meanNullable(lats));
        try {
            Map<String, Object> params = new java.util.LinkedHashMap<>();
            params.put("k", k);
            params.put("precisionStd", stddev(p));
            params.put("recallStd",    stddev(r));
            params.put("f1Std",        stddev(f1));
            params.put("fprStd",       stddev(fpr));
            params.put("aucRocStd",    stddevNullable(aucRocs));
            params.put("aucPrStd",     stddevNullable(aucPrs));
            params.put("cakrStd",      stddevNullable(cakrs));
            params.put("hallucStd",    stddevNullable(hallucs));
            params.put("latencyStd",   stddevNullable(lats));
            params.put("scoreExplanations", scoreExplanations);
            params.put("durationMs",   System.currentTimeMillis() - overallStart);
            summary.setRunParameters(objectMapper.writeValueAsString(params));
        } catch (Exception e) {
            log.warn("Failed to serialize k-fold summary params: {}", e.getMessage());
        }
        perFold.add(experimentResultRepository.save(summary));
        progress(jobId, "Done", totalSteps, totalSteps, alertsCreated);

        log.info("K-fold CV '{}' complete: meanF1={} (±{}), folds persisted={}",
                experimentName,
                String.format("%.4f", mean(f1)),
                String.format("%.4f", stddev(f1)),
                perFold.size());
        return perFold;
    }

    // ====================================================================
    // AUC-ROC / AUC-PR computation (thesis §3.4.1)
    // ====================================================================

    /**
     * Compute AUC-ROC and AUC-PR for a config over a stratified test-set sample.
     *
     * <p>Keeps <em>all</em> test-set fraud rows and random-samples up to
     * {@code aucLegitSampleSize} legit rows. Each sampled transaction is scored by
     * the current ML model; the (score, isFraud) vector feeds
     * {@link DetectionMetrics#computeAuc(double[], boolean[])}.</p>
     *
     * <p>Returns {@code (null, null)} for {@code RULES_ONLY} (no continuous score)
     * or when the model isn't loaded. For ML-based configs, the same ML ensemble
     * produces the score used by the detection pipeline — so AUC here is directly
     * comparable to the threshold-based precision/recall reported alongside.</p>
     *
     * @param config  detection config to evaluate (AUC is defined for ML-based configs only)
     * @param dataset dataset to sample from
     * @param fold    if non-null, restrict the sample to that fold (for k-fold CV)
     * @return (aucRoc, aucPr) — each {@code null} when not defined for the config
     */
    AucPair computeAucForConfig(DetectionConfig config, DatasetSource dataset, Integer fold) {
        if (config == DetectionConfig.RULES_ONLY) {
            return new AucPair(null, null);
        }
        if (!mlService.isModelAvailable()) {
            log.warn("AUC skipped: ML model not available for config {}", config);
            return new AucPair(null, null);
        }

        long t0 = System.currentTimeMillis();
        List<Transaction> fraud = fold == null
                ? transactionRepository.findFraudTestSet(dataset)
                : transactionRepository.findFraudTestSetByFold(dataset, fold);
        List<Transaction> legit = fold == null
                ? transactionRepository.findLegitTestSetRandomSample(dataset, aucLegitSampleSize)
                : transactionRepository.findLegitTestSetRandomSampleByFold(dataset, fold, aucLegitSampleSize);

        if (fraud.isEmpty() || legit.isEmpty()) {
            log.warn("AUC skipped: insufficient sample (fraud={}, legit={}) for config={} fold={}",
                    fraud.size(), legit.size(), config, fold);
            return new AucPair(null, null);
        }

        int total = fraud.size() + legit.size();
        List<Long> ids = new ArrayList<>(total);
        for (Transaction t : fraud) ids.add(t.getId());
        for (Transaction t : legit) ids.add(t.getId());

        // Pre-load features for the whole sample in one query to avoid N+1.
        Map<Long, TransactionFeatures> featuresById = new HashMap<>(total);
        featuresRepository.findAllByTransactionIdIn(ids)
                .forEach(tf -> featuresById.put(tf.getTransaction().getId(), tf));

        double[] scores = new double[total];
        boolean[] labels = new boolean[total];
        int idx = 0;
        int missing = 0;
        for (Transaction tx : fraud) {
            TransactionFeatures f = featuresById.get(tx.getId());
            if (f == null) { missing++; continue; }
            MLPredictionResult pred = mlService.predict(tx, f);
            scores[idx] = pred.riskScore();
            labels[idx] = true;
            idx++;
        }
        for (Transaction tx : legit) {
            TransactionFeatures f = featuresById.get(tx.getId());
            if (f == null) { missing++; continue; }
            MLPredictionResult pred = mlService.predict(tx, f);
            scores[idx] = pred.riskScore();
            labels[idx] = false;
            idx++;
        }
        if (missing > 0) {
            log.warn("AUC sample: {} of {} rows had no features — skipped", missing, total);
        }
        if (idx < 2) {
            log.warn("AUC sample collapsed to {} rows — skipping", idx);
            return new AucPair(null, null);
        }

        double[] trimmedScores = new double[idx];
        boolean[] trimmedLabels = new boolean[idx];
        System.arraycopy(scores, 0, trimmedScores, 0, idx);
        System.arraycopy(labels, 0, trimmedLabels, 0, idx);

        DetectionMetrics.AucResult auc = detectionMetrics.computeAuc(trimmedScores, trimmedLabels);
        log.info("AUC [config={}, fold={}]: AUC-ROC={}, AUC-PR={} (sample={} fraud + {} legit, {}ms)",
                config, fold,
                Double.isNaN(auc.aucRoc()) ? "N/A" : String.format("%.4f", auc.aucRoc()),
                Double.isNaN(auc.aucPr()) ? "N/A" : String.format("%.4f", auc.aucPr()),
                fraud.size(), legit.size(), System.currentTimeMillis() - t0);
        return new AucPair(
                Double.isNaN(auc.aucRoc()) ? null : auc.aucRoc(),
                Double.isNaN(auc.aucPr())  ? null : auc.aucPr());
    }

    /** Pair of AUC values, either of which may be {@code null} when the metric is undefined. */
    public record AucPair(Double aucRoc, Double aucPr) { }

    /**
     * Milestone-logging helper so status polls and logs stay in sync.
     * Safe to call with a {@code null} jobId — falls back to a plain log line.
     */
    private void milestone(String jobId, String message) {
        if (jobId != null) {
            statusTracker.logMilestone(jobId, message);
        } else {
            log.info("[pipeline] {}", message);
        }
    }

    // ── Progress + cancel helpers (no-op when jobId is null) ──────────────

    /** Publish a progress snapshot to the status tracker if a jobId is supplied. */
    private void progress(String jobId, String step, int done, int total, long alerts) {
        if (jobId == null) return;
        statusTracker.updateProgress(jobId, step, done, total, alerts);
    }

    /**
     * Throw {@link CancellationException} if the operator requested cancellation
     * for this jobId. Long-running runners call this between phases so the run
     * exits at the next safe boundary.
     */
    private void checkCancel(String jobId) {
        if (jobId != null && statusTracker.isCancelled(jobId)) {
            throw new CancellationException(
                    "Run was cancelled by the user at job " + jobId);
        }
    }

    /**
     * Marker exception thrown when a run is asked to stop. Async services
     * catch this and call {@link PipelineStatusTracker#cancelled} — distinct
     * from a real {@code fail} since no error occurred.
     */
    public static class CancellationException extends RuntimeException {
        public CancellationException(String message) { super(message); }
    }

    /**
     * Normalise the {@code [fold, total, hits]} rows returned by the grouped
     * per-fold count queries into a {@code Map<fold, long[]{total, hits}>}.
     *
     * <p>"hits" means fraud count for the transaction-side query and true-positive
     * count for the alert-side query. Both map directly onto the MetricsResult
     * inputs.</p>
     */
    private static java.util.Map<Integer, long[]> extractFoldCounts(List<Object[]> rows) {
        java.util.Map<Integer, long[]> out = new java.util.HashMap<>();
        for (Object[] row : rows) {
            if (row[0] == null) continue;
            Integer fold = ((Number) row[0]).intValue();
            long total = ((Number) row[1]).longValue();
            long hits  = row[2] == null ? 0L : ((Number) row[2]).longValue();
            out.put(fold, new long[]{total, hits});
        }
        return out;
    }

    /** Arithmetic mean; returns 0 for an empty array (package-visible for tests). */
    static double mean(double[] xs) {
        double s = 0;
        for (double x : xs) s += x;
        return xs.length == 0 ? 0 : s / xs.length;
    }

    /** Sample standard deviation (Bessel-corrected, n-1); 0 for <= 1 observations. */
    static double stddev(double[] xs) {
        if (xs.length <= 1) return 0;
        double m = mean(xs);
        double sq = 0;
        for (double x : xs) sq += (x - m) * (x - m);
        return Math.sqrt(sq / (xs.length - 1));
    }

    /**
     * Null-safe mean — ignores null fold values so folds without explanations
     * (e.g. RULES_ONLY, or an LLM config where no alerts fell in that fold)
     * don't drag the summary toward zero. Returns {@code null} when every fold
     * is null, so the persisted column stays honestly empty.
     */
    static Double meanNullable(Double[] xs) {
        double s = 0;
        int n = 0;
        for (Double x : xs) {
            if (x != null) { s += x; n++; }
        }
        return n == 0 ? null : s / n;
    }

    /** Null-safe sample stddev — same rules as {@link #meanNullable}. */
    static Double stddevNullable(Double[] xs) {
        Double m = meanNullable(xs);
        if (m == null) return null;
        double sq = 0;
        int n = 0;
        for (Double x : xs) {
            if (x != null) { sq += (x - m) * (x - m); n++; }
        }
        return n <= 1 ? 0.0 : Math.sqrt(sq / (n - 1));
    }

    /**
     * Audit follow-up: full benchmark sweep — k-fold CV across every detection
     * config in one call. Produces the exact shape the thesis evaluation
     * chapter needs for its comparison table:
     *
     * <pre>
     * configs × (k per-fold rows + 1 summary row)
     * </pre>
     *
     * <p>The detection pipeline runs once per config (not once per fold × config),
     * so total wall-clock is approximately {@code |configs| × pipeline_time + k × metric_time},
     * not {@code |configs| × k × pipeline_time}. LLM explanations are generated
     * once per LLM-using config.</p>
     *
     * @param experimentName    shared experiment name; fold rows have the same name,
     *                          summary rows have name "{experimentName}:summary"
     * @param configs           configs to benchmark (typically all 5)
     * @param dataset           dataset source
     * @param k                 folds per config (typical: 5 or 10)
     * @param scoreExplanations whether to run CAKR scoring for LLM-using configs
     * @param maxAlertsToExplain per-config cap on explanation generation (defaults to
     *                           500 if {@code <= 0}). The same cap is applied in each
     *                           fold within a config.
     * @return every result row produced: {@code |configs| × (k + 1)}
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<ExperimentResult> runBenchmark(String experimentName,
                                                List<DetectionConfig> configs,
                                                DatasetSource dataset,
                                                int k,
                                                boolean scoreExplanations,
                                                int maxAlertsToExplain) {
        return runBenchmark(experimentName, configs, dataset, k,
                scoreExplanations, maxAlertsToExplain, null);
    }

    /**
     * Job-aware overload — publishes per-config progress + checks cancel between
     * configs. {@link CancellationException} from runKFold bubbles up to the
     * async caller which marks the job cancelled.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<ExperimentResult> runBenchmark(String experimentName,
                                                List<DetectionConfig> configs,
                                                DatasetSource dataset,
                                                int k,
                                                boolean scoreExplanations,
                                                int maxAlertsToExplain,
                                                String jobId) {
        if (configs == null || configs.isEmpty()) {
            throw new IllegalArgumentException("runBenchmark: at least one config required");
        }
        long wallStart = System.currentTimeMillis();
        log.info("=== BENCHMARK START '{}' — configs={}, k={}, dataset={}, jobId={} ===",
                experimentName, configs, k, dataset, jobId);

        // Assign folds ONCE for the whole benchmark. HASHTEXT-based fold
        // assignment is deterministic and config-independent, so doing it
        // per-config wastes the drop-index / bulk-update / rebuild-index
        // cycle (~10 min on the thesis dataset) × (configs.size() - 1) times.
        // On 5 configs that's ~50 min saved.
        progress(jobId, "Assigning folds (once for the whole benchmark)",
                0, configs.size(), 0);
        long foldStart = System.currentTimeMillis();
        int foldRowsUpdated = foldAssignmentService.assignFolds(k);
        log.info("Benchmark fold assignment: {} rows updated in {}ms — reused across all {} configs",
                foldRowsUpdated, System.currentTimeMillis() - foldStart, configs.size());

        // Outer progress: configs.size() steps, one per config.
        int totalConfigs = configs.size();
        int doneConfigs = 0;
        progress(jobId, "Benchmark — config 1 / " + totalConfigs,
                doneConfigs, totalConfigs, 0);

        List<ExperimentResult> all = new ArrayList<>(configs.size() * (k + 1));
        for (DetectionConfig config : configs) {
            checkCancel(jobId);

            // Heap circuit breaker — if we're already critical BEFORE this
            // config starts, abort the whole benchmark cleanly. Prevents the
            // cascade that killed the original thesis run (config 2 started
            // at heap 4091/4096 MB and OOM'd into Tomcat's async thread pool).
            try {
                heapGuardrail.check("before-config-" + config);
            } catch (HeapGuardrail.HeapCriticalException hce) {
                log.error("Benchmark aborted before config {}: {}", config, hce.getMessage());
                if (jobId != null) statusTracker.fail(jobId, hce.getMessage());
                return all;  // return whatever we've got — partial results are still useful
            }

            try {
                long cfgStart = System.currentTimeMillis();
                log.info("=== BENCHMARK config={} ({} of {}) ===",
                        config, doneConfigs + 1, totalConfigs);
                progress(jobId,
                        "Benchmark config " + (doneConfigs + 1) + " / " + totalConfigs
                                + " (" + config.name() + ")",
                        doneConfigs, totalConfigs, all.size());
                // Each per-config k-fold gets the same jobId so its inner phase
                // updates show up in the same banner — but the per-config status
                // text overrides the outer message momentarily, which is fine.
                // skipFoldAssignment=true because runBenchmark assigned folds
                // once before the loop (saves 10+ min per config).
                all.addAll(runKFold(experimentName, config, dataset, k,
                        scoreExplanations, maxAlertsToExplain, jobId,
                        /* skipFoldAssignment = */ true));
                log.info("=== BENCHMARK config={} complete in {}ms ===",
                        config, System.currentTimeMillis() - cfgStart);
            } catch (CancellationException ce) {
                // Operator cancelled mid-config — propagate so the outer async
                // service can mark the whole benchmark cancelled (don't swallow).
                throw ce;
            } catch (HeapGuardrail.HeapCriticalException hce) {
                // Heap gave way inside runKFold — don't try more configs. Abort.
                log.error("Benchmark aborted during config {}: {}", config, hce.getMessage());
                if (jobId != null) statusTracker.fail(jobId, hce.getMessage());
                return all;
            } catch (Exception e) {
                // Log-and-continue so a single misbehaving config doesn't void
                // the whole benchmark run — partial tables are still useful.
                log.error("Benchmark failed for config {}: {}", config, e.getMessage(), e);
            }
            doneConfigs++;
            progress(jobId,
                    "Benchmark — finished " + doneConfigs + " / " + totalConfigs + " configs",
                    doneConfigs, totalConfigs, all.size());

            // Inter-config GC hint + heap reading. Each config's per-fold AUC
            // sampling, explanation generation, and CAKR scoring leave ~100-200
            // MB of short-lived state behind. Without this hint the heap can
            // drift toward OOM before G1 decides to do a major GC. System.gc()
            // is a *suggestion* — G1 honours it as a non-blocking concurrent
            // cycle, never forces stop-the-world.
            HeapGuardrail.HeapStatus before = heapGuardrail.peek();
            System.gc();
            try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            HeapGuardrail.HeapStatus after = heapGuardrail.peek();
            log.info("Inter-config GC: {} → {} ({} MB freed)",
                    before, after,
                    Math.max(0L, (before.usedBytes() - after.usedBytes()) >> 20));
        }

        log.info("=== BENCHMARK COMPLETE '{}': {} rows across {} configs in {}ms ===",
                experimentName, all.size(), configs.size(),
                System.currentTimeMillis() - wallStart);
        return all;
    }

    /**
     * Run a comparative experiment across multiple detection configs.
     *
     * <p>Runs the same experiment for each config and returns all results
     * for side-by-side comparison in the thesis evaluation chapter.</p>
     *
     * @param experimentName   shared experiment name
     * @param configs          configs to compare
     * @param dataset          dataset to evaluate on
     * @param scoreExplanations whether to run CAKR scoring
     * @param maxAlertsToExplain per-config cap on LLM explanation generation (defaults to 500 if ≤ 0)
     * @return list of experiment results, one per config
     */
    public List<ExperimentResult> runComparison(String experimentName,
                                                 List<DetectionConfig> configs,
                                                 DatasetSource dataset,
                                                 boolean scoreExplanations,
                                                 int maxAlertsToExplain) {
        log.info("Starting comparative experiment '{}' across {} configs (maxAlertsToExplain={})",
                experimentName, configs.size(), maxAlertsToExplain);

        int cap = maxAlertsToExplain > 0 ? maxAlertsToExplain : 500;
        List<ExperimentResult> results = new ArrayList<>();

        for (DetectionConfig config : configs) {
            // maxExplanationsToScore=0 triggers the compact-ctor default (300),
            // keeping this path aligned with the ±0.15 CAKR CI claim in the
            // thesis methodology. Previously hardcoded 100, which silently
            // halved the sample and produced a ±0.3 CI — inconsistent with
            // the single-run path.
            ExperimentRequest request = new ExperimentRequest(
                    experimentName, config, dataset, null, scoreExplanations, 0, cap
            );
            try {
                results.add(runExperiment(request));
            } catch (Exception e) {
                log.error("Experiment failed for config {}: {}", config, e.getMessage());
            }
        }

        log.info("Comparative experiment '{}' complete: {}/{} configs succeeded",
                experimentName, results.size(), configs.size());

        return results;
    }

    /**
     * Check whether a detection config produces LLM explanations.
     */
    private boolean hasExplanations(DetectionConfig config) {
        return config == DetectionConfig.ML_LLM_DIRECT
                || config == DetectionConfig.ML_LLM_RAG
                || config == DetectionConfig.FULL_SYSTEM;
    }

    /**
     * Map a detection config to the explanation type used for sync generation.
     */
    private ExplanationType resolveExplanationType(DetectionConfig config) {
        return switch (config) {
            case ML_LLM_DIRECT -> ExplanationType.LLM_DIRECT;
            case ML_LLM_RAG, FULL_SYSTEM -> ExplanationType.LLM_RAG;
            default -> null;
        };
    }

    /**
     * Map a detection config to the expected explanation types for metric queries.
     *
     * <p>FULL_SYSTEM generates LLM_RAG explanations that are then upgraded to
     * LLM_RAG_VALIDATED by the hallucination validator. Both types must be queried
     * to avoid missing explanations when computing metrics.</p>
     */
    private List<ExplanationType> getExplanationTypes(DetectionConfig config) {
        return switch (config) {
            case ML_LLM_DIRECT -> List.of(ExplanationType.LLM_DIRECT);
            // BOTH ML_LLM_RAG AND FULL_SYSTEM run the HallucinationValidator, which
            // upgrades any hallucination-free LLM_RAG explanation to LLM_RAG_VALIDATED
            // (see ExplanationBatchAsyncService.validateInPlace). Failed-validation
            // explanations stay as LLM_RAG. Both types must be queried, otherwise
            // every successful explanation is silently excluded from the metrics —
            // the previous ML_LLM_RAG-only-LLM_RAG asymmetry caused that config's
            // CAKR, hallucination rate, and avg latency to render as "—" on the
            // dashboard despite explanations existing in the database.
            case ML_LLM_RAG,
                 FULL_SYSTEM   -> List.of(ExplanationType.LLM_RAG, ExplanationType.LLM_RAG_VALIDATED);
            default            -> List.of(ExplanationType.TEMPLATE);
        };
    }

    /**
     * Request to run an evaluation experiment.
     *
     * @param experimentName         unique name for this experiment run
     * @param config                 which detection config to evaluate
     * @param dataset                which dataset to evaluate on
     * @param fold                   optional fold number (for cross-validation)
     * @param scoreExplanations      whether to run CAKR scoring (requires LLM)
     * @param maxExplanationsToScore max explanations to CAKR-score (limits LLM cost)
     * @param maxAlertsToExplain     cap on how many alerts receive an LLM explanation.
     *                               Critical safety knob — without it a FULL_SYSTEM run
     *                               that flagged 450 000 alerts would spawn 450 000 LLM calls.
     *                               Defaults to 500.
     */
    public record ExperimentRequest(
            String experimentName,
            DetectionConfig config,
            DatasetSource dataset,
            Integer fold,
            boolean scoreExplanations,
            int maxExplanationsToScore,
            int maxAlertsToExplain
    ) {
        public ExperimentRequest {
            // CAKR sample size: 300 explanations gives ~±0.15 95 % CI on the
            // 1-5 score (was 50 → ±0.30). The bigger sample lets the thesis
            // distinguish closer-spaced configs in the comparative analysis.
            // Cost: ~3-4× more LLM calls during CAKR scoring per config.
            if (maxExplanationsToScore <= 0) maxExplanationsToScore = 300;
            if (maxAlertsToExplain <= 0) maxAlertsToExplain = 500;
        }

        /** Back-compat ctor for callers that only cap CAKR scoring (defaults alerts cap to 500). */
        public ExperimentRequest(String experimentName,
                                  DetectionConfig config,
                                  DatasetSource dataset,
                                  Integer fold,
                                  boolean scoreExplanations,
                                  int maxExplanationsToScore) {
            this(experimentName, config, dataset, fold, scoreExplanations, maxExplanationsToScore, 500);
        }
    }
}
