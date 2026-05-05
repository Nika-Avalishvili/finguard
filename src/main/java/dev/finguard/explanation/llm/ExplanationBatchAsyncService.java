package dev.finguard.explanation.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finguard.config.MetricsConfig;
import dev.finguard.detection.service.PipelineStatusTracker;
import dev.finguard.domain.enums.ExplanationType;
import dev.finguard.domain.model.Alert;
import dev.finguard.domain.model.Explanation;
import dev.finguard.domain.model.TransactionFeatures;
import dev.finguard.domain.repository.AlertRepository;
import dev.finguard.domain.repository.ExplanationRepository;
import dev.finguard.domain.repository.TransactionFeaturesRepository;
import dev.finguard.explanation.rag.RAGContextService;
import dev.finguard.explanation.validation.HallucinationValidator;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs batch LLM explanation generation in the background.
 *
 * <p>Exists as a separate bean so that {@code @Async} is honoured through the Spring proxy.
 * The controller returns 202 immediately; clients poll
 * {@code GET /api/v1/explanations/batch-status/{jobId}} for progress.</p>
 *
 * <h3>Optimisations</h3>
 * <ul>
 *   <li><b>JOIN FETCH</b> — loads alert + transaction in a single query, preventing
 *       {@code LazyInitializationException} when the RAG service accesses
 *       {@code tx.getTransactionType()} outside the loading transaction.</li>
 *   <li><b>RAG pre-warm</b> — before the main loop, collects distinct
 *       {@code (ruleTriggered, transactionType)} pairs and warms the Caffeine cache
 *       with one pgvector search per unique combination. Subsequent per-alert calls
 *       hit the cache in O(1), reducing total pgvector round-trips from N to
 *       {@code distinctPairs ≪ N}.</li>
 *   <li><b>Confidence-gated routing</b> — uses {@link ExplanationRouter} to downgrade
 *       LLM_RAG calls for medium-risk alerts to focused LLM_DIRECT prompts, saving
 *       pgvector + token cost where full context is not needed.</li>
 * </ul>
 */
@Service
public class ExplanationBatchAsyncService {

    private static final Logger log = LoggerFactory.getLogger(ExplanationBatchAsyncService.class);

    private final LLMExplanationService llmService;
    private final ExplanationRouter explanationRouter;
    private final HallucinationValidator hallucinationValidator;
    private final AlertRepository alertRepository;
    private final ExplanationRepository explanationRepository;
    private final TransactionFeaturesRepository featuresRepository;
    private final RAGContextService ragContextService;
    private final PipelineStatusTracker statusTracker;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    /**
     * How many LLM calls to run concurrently. LLM calls are I/O-bound (HTTP to Ollama or Claude),
     * so 8 parallel calls saturates Ollama's `OLLAMA_NUM_PARALLEL` default without thrashing it.
     * Raise for Claude (which scales further), lower if you see rate-limit errors.
     */
    @Value("${finguard.explanation.batch-concurrency:8}")
    private int batchConcurrency;

    public ExplanationBatchAsyncService(LLMExplanationService llmService,
                                         ExplanationRouter explanationRouter,
                                         HallucinationValidator hallucinationValidator,
                                         AlertRepository alertRepository,
                                         ExplanationRepository explanationRepository,
                                         TransactionFeaturesRepository featuresRepository,
                                         RAGContextService ragContextService,
                                         PipelineStatusTracker statusTracker,
                                         ObjectMapper objectMapper,
                                         MeterRegistry meterRegistry) {
        this.llmService = llmService;
        this.explanationRouter = explanationRouter;
        this.hallucinationValidator = hallucinationValidator;
        this.alertRepository = alertRepository;
        this.explanationRepository = explanationRepository;
        this.featuresRepository = featuresRepository;
        this.ragContextService = ragContextService;
        this.statusTracker = statusTracker;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Generate explanations asynchronously (fire-and-forget for UI-triggered jobs).
     *
     * @param jobId  job identifier (must be pre-registered with {@link PipelineStatusTracker#start})
     * @param type   explanation type (drives RAG vs. direct prompting)
     * @param limit  max alerts to process in this batch
     */
    @Async
    public void runAsync(String jobId,
                         dev.finguard.domain.enums.DetectionConfig config,
                         ExplanationType type,
                         int limit) {
        runCore(jobId, config, type, limit);
    }

    /**
     * Generate explanations synchronously — blocks until all explanations are generated.
     *
     * <p>Used by {@link dev.finguard.evaluation.runner.ExperimentRunner} to ensure
     * explanations are fully committed before evaluation metrics are queried.</p>
     *
     * @param jobId  job identifier (must be pre-registered with {@link PipelineStatusTracker#start})
     * @param config <b>Load-bearing for thesis correctness.</b> Scopes which alerts are
     *               eligible for explanation. Without this, LLM explanations from later
     *               benchmark configs would attach to low-id RULES_ONLY alerts (see
     *               {@code AlertRepository.findAnomalyAlertsWithoutExplanation} Javadoc).
     * @param type   explanation type (drives RAG vs. direct prompting)
     * @param limit  max alerts to process in this batch
     */
    public void runSync(String jobId,
                        dev.finguard.domain.enums.DetectionConfig config,
                        ExplanationType type,
                        int limit) {
        runCore(jobId, config, type, limit);
    }

    /**
     * Core explanation generation logic — invoked by both {@link #runAsync} and {@link #runSync}.
     */
    private void runCore(String jobId,
                         dev.finguard.domain.enums.DetectionConfig config,
                         ExplanationType type,
                         int limit) {
        // Dashboard panel: per-config wall-clock for the explanation phase.
        // Recorded in the finally block so failures still register (you want
        // to see "the run took 60min, then died" not a missing row).
        long explanationStartMs = System.currentTimeMillis();
        try {
        log.info("[job={}] Batch explanation started — config={} type={}, limit={}",
                jobId, config, type, limit);

        // Two-step candidate selection:
        //   (a) deterministic-random sampling of N eligible alert IDs via MD5(id::text)
        //   (b) JOIN FETCH hydration of those N alerts with their transaction
        //
        // Why this is better than a single ORDER-BY-id query: the natural physical
        // order of the alerts table puts the lowest-id (= earliest test-set
        // transactions) first, so a single LIMIT N query systematically biases the
        // CAKR / hallucination sample toward early-temporal alerts. Random sampling
        // by MD5(id) is reproducible (same DB → same N alerts every run) and
        // unbiased over the eligible population. The two-query cost is negligible:
        // step (a) reads only IDs, step (b) is a PK-lookup IN clause of ≤ N ids.
        List<Long> sampleIds = alertRepository.findRandomEligibleAlertIds(config, type, limit);
        List<Alert> alerts = sampleIds.isEmpty()
                ? java.util.Collections.emptyList()
                : alertRepository.findByIdsWithTransaction(sampleIds);

        int total = alerts.size();
        log.info("[job={}] Found {} alerts to explain", jobId, total);

        // Pre-warm the RAG Caffeine cache before the main loop.
        // Collect distinct (ruleTriggered, txType) pairs — these are the cache key components.
        // Each unique pair triggers exactly one pgvector search; all subsequent per-alert
        // calls for the same pair hit the in-memory cache in O(1).
        if (type == ExplanationType.LLM_RAG || type == ExplanationType.LLM_RAG_VALIDATED) {
            prewarmRagCache(alerts);
        }

        // Audit B-1: pre-load features for every transaction in the batch in a
        // single query. Prior implementation hit featuresRepository once per
        // alert inside validateAndUpdate(), causing an N+1 pattern.
        java.util.Map<Long, TransactionFeatures> featuresByTxId = new java.util.HashMap<>();
        if (!alerts.isEmpty()) {
            List<Long> txIds = alerts.stream()
                    .map(a -> a.getTransaction().getId())
                    .distinct()
                    .toList();
            featuresRepository.findAllByTransactionIdIn(txIds)
                    .forEach(tf -> featuresByTxId.put(tf.getTransaction().getId(), tf));
            log.debug("[job={}] Pre-loaded {} features for {} alerts", jobId, featuresByTxId.size(), total);
        }

        // Counters — atomic because workers mutate them concurrently.
        AtomicInteger generated = new AtomicInteger();
        AtomicInteger skipped   = new AtomicInteger();
        AtomicInteger failed    = new AtomicInteger();
        AtomicInteger processed = new AtomicInteger();

        long loopStart = System.currentTimeMillis();

        // Progress-log cadence: log an aggregate line every PROGRESS_LOG_EVERY alerts,
        // not per-alert (which would spam the file with hundreds of thousands of lines
        // on a large explanation run). Users poll /evaluation/status/{jobId} for live
        // progress; this log is for post-hoc forensics only.
        final int PROGRESS_LOG_EVERY = Math.max(50, Math.min(500, Math.max(1, total / 20)));

        // Audit B-2 + concurrency: workers offer into a lock-free queue; the main
        // thread flushes in sub-batches of PERSIST_BATCH_SIZE so we never hold more
        // than that many explanations in memory, and JPA sees a single transaction
        // per flush (saveAll).
        Queue<Explanation> toSave = new ConcurrentLinkedQueue<>();

        // Bounded platform-thread pool sized to batchConcurrency. Platform threads
        // (not virtual) deliberately — the LLM HTTP clients internally pool connections,
        // and a bounded pool gives us natural backpressure against the inference server.
        int concurrency = Math.max(1, batchConcurrency);
        int parallelism = Math.min(concurrency, Math.max(1, total));
        ExecutorService workers = Executors.newFixedThreadPool(parallelism, r -> {
            Thread t = new Thread(r, "explain-worker");
            t.setDaemon(true);
            return t;
        });
        log.info("[job={}] Starting parallel explanation generation — {} workers for {} alerts",
                jobId, parallelism, total);

        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>(total);
            for (Alert alert : alerts) {
                futures.add(CompletableFuture.runAsync(
                        () -> processOne(jobId, type, alert, featuresByTxId,
                                toSave, generated, skipped, failed),
                        workers));
            }

            // Progress/flush loop — wait for futures incrementally so we can (a) flush
            // to DB as the queue fills and (b) emit periodic progress logs without
            // blocking on `CompletableFuture.allOf`.
            for (int i = 0; i < futures.size(); i++) {
                futures.get(i).join();
                int done = processed.incrementAndGet();

                statusTracker.updateProgress(jobId, "Generating explanations",
                        done, total, generated.get());

                // Opportunistic flush. The queue size check is approximate (non-blocking),
                // which is fine — we only need to bound memory, not hit an exact boundary.
                if (toSave.size() >= PERSIST_BATCH_SIZE) {
                    drainAndSave(toSave, PERSIST_BATCH_SIZE);
                }

                if (done % PROGRESS_LOG_EVERY == 0 || done == total) {
                    long elapsedMs = System.currentTimeMillis() - loopStart;
                    double rate = done / Math.max(1.0, elapsedMs / 1000.0);
                    double pct = (100.0 * done) / Math.max(1, total);
                    long remainingMs = (long) ((total - done) / Math.max(0.001, rate) * 1000);
                    log.info("[job={}] Explanation progress: {}/{} ({}%) — generated={}, skipped={}, failed={}, {} alerts/sec, ~{}s remaining",
                            jobId, done, total, String.format("%.1f", pct),
                            generated.get(), skipped.get(), failed.get(),
                            String.format("%.1f", rate),
                            remainingMs / 1000);
                }
            }

            // Final flush — drain whatever is left in the queue.
            drainAndSave(toSave, Integer.MAX_VALUE);

        } finally {
            workers.shutdown();
            try {
                if (!workers.awaitTermination(30, TimeUnit.SECONDS)) {
                    log.warn("[job={}] Worker pool did not terminate cleanly within 30s", jobId);
                    workers.shutdownNow();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                workers.shutdownNow();
            }
        }

        statusTracker.complete(jobId, generated.get());

        if (failed.get() == 0) {
            log.info("[job={}] Batch explanation complete — generated={}, skipped={}, total={}",
                    jobId, generated.get(), skipped.get(), total);
        } else {
            log.warn("[job={}] Batch explanation finished with {} failure(s) — generated={}, skipped={}, total={}",
                    jobId, failed.get(), generated.get(), skipped.get(), total);
        }
        } finally {
            meterRegistry.timer(MetricsConfig.EXPLANATION_CONFIG_TIMER, "config", config.name())
                    .record(System.currentTimeMillis() - explanationStartMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Per-alert work unit — executed on a worker thread. Never throws; on error
     * the failure counter is incremented and the batch continues. Ordering of
     * side effects (queue offer, counter increment) is not externally observed:
     * all counters are read only at flush time / job completion.
     */
    private void processOne(String jobId,
                             ExplanationType type,
                             Alert alert,
                             java.util.Map<Long, TransactionFeatures> featuresByTxId,
                             Queue<Explanation> toSave,
                             AtomicInteger generated,
                             AtomicInteger skipped,
                             AtomicInteger failed) {
        try {
            ExplanationType effective = explanationRouter.effectiveType(alert, type);
            if (effective == null) {
                skipped.incrementAndGet();
                return;
            }
            Explanation explanation = switch (effective) {
                case LLM_RAG, LLM_RAG_VALIDATED -> llmService.generateRagExplanation(alert);
                case LLM_DIRECT -> llmService.generateFocusedDirectExplanation(alert);
                default -> llmService.generateDirectExplanation(alert);
            };
            TransactionFeatures features = featuresByTxId.get(alert.getTransaction().getId());
            if (validateInPlace(explanation, alert, features)) {
                toSave.offer(explanation);
            }
            generated.incrementAndGet();
        } catch (Exception e) {
            // Concurrent-delete race: an operator wiped alerts (or transactions
            // cascaded) between this batch's load and the per-alert insert.
            // Foreign-key-on-alert_id is then violated. This is an expected
            // outcome of running an evaluation while the user clicks Delete in
            // another tab — log as a single-line WARN, count as skipped (not
            // failed), and keep the batch moving.
            if (isAlertNoLongerPresent(e)) {
                log.warn("[job={}] Skipped alert {} — alert was deleted before its explanation could be persisted (concurrent reset).",
                        jobId, alert.getId());
                skipped.incrementAndGet();
                return;
            }
            log.error("[job={}] Failed to generate explanation for alert {}: {}",
                    jobId, alert.getId(), e.getMessage());
            failed.incrementAndGet();
        }
    }

    /**
     * True if the given exception is the FK-violation that bubbles up when a
     * row in {@code alerts} was removed between batch load and insert. We unwrap
     * the cause chain because Spring/Hibernate wraps PSQL errors in
     * {@code DataIntegrityViolationException} → {@code ConstraintViolationException}
     * → {@code PSQLException}.
     */
    private static boolean isAlertNoLongerPresent(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String msg = c.getMessage();
            if (msg != null && (msg.contains("explanations_alert_id_fkey")
                             || msg.contains("not present in table \"alerts\""))) {
                return true;
            }
            if (c.getCause() == c) break;  // defensive against self-referential cycles
        }
        return false;
    }

    /**
     * Drain up to {@code max} explanations from the concurrent queue and persist them.
     * Called from the orchestrator thread only — workers just offer into the queue.
     */
    private void drainAndSave(Queue<Explanation> queue, int max) {
        List<Explanation> batch = new ArrayList<>(Math.min(max, PERSIST_BATCH_SIZE));
        for (int i = 0; i < max; i++) {
            Explanation e = queue.poll();
            if (e == null) break;
            batch.add(e);
        }
        if (!batch.isEmpty()) {
            explanationRepository.saveAll(batch);
        }
    }

    /** Batch-persistence threshold for explanations (B-2). */
    private static final int PERSIST_BATCH_SIZE = 500;

    /**
     * Pre-warm the RAG Caffeine cache for all distinct (ruleTriggered, txType) pairs
     * in the batch. This converts O(N) pgvector searches into O(distinctPairs) searches
     * before the main loop starts. Subsequent calls for the same pair hit the cache.
     */
    private void prewarmRagCache(List<Alert> alerts) {
        Set<String> seenKeys = new HashSet<>();
        int warmed = 0;

        for (Alert alert : alerts) {
            String ruleKey = alert.getRuleTriggered() != null ? alert.getRuleTriggered() : "";
            String txTypeKey = alert.getTransaction().getTransactionType() != null
                    ? alert.getTransaction().getTransactionType().name() : "";
            String cacheKey = ruleKey + "_" + txTypeKey;

            if (seenKeys.add(cacheKey)) {
                try {
                    ragContextService.retrieveContext(alert, alert.getTransaction());
                    warmed++;
                } catch (Exception e) {
                    log.debug("RAG pre-warm failed for key '{}': {}", cacheKey, e.getMessage());
                }
            }
        }

        log.info("RAG cache pre-warmed: {} distinct query combinations for {} alerts", warmed, alerts.size());
    }

    /**
     * Validate an explanation in-place using pre-loaded features.
     *
     * <p>Audit B-1 + B-2: the caller pre-loads features for the entire batch
     * and streams them in here, so this method doesn't hit the DB, and the
     * explanation is NOT saved here — the caller flushes in sub-batches via
     * {@code saveAll()}.</p>
     *
     * @return {@code true} if the explanation is ready to persist (i.e., the
     *         caller should add it to the save batch), {@code false} if
     *         validation encountered a recoverable error and the explanation
     *         should be skipped.
     */
    private boolean validateInPlace(Explanation explanation, Alert alert, TransactionFeatures features) {
        try {
            ExplanationResponse parsed = new ExplanationResponse(
                    explanation.getRiskSummary(),
                    explanation.getExplanationText(),
                    explanation.getSuspiciousPatterns() != null
                            ? objectMapper.readValue(explanation.getSuspiciousPatterns(),
                                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class))
                            : List.of(),
                    explanation.getRecommendedActions() != null
                            ? objectMapper.readValue(explanation.getRecommendedActions(),
                                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class))
                            : List.of(),
                    explanation.getConfidenceScore() != null ? explanation.getConfidenceScore() : 0.0
            );

            HallucinationValidator.ValidationResult validation =
                    hallucinationValidator.validate(parsed, alert, alert.getTransaction(), features);

            explanation.setHallucinationFree(validation.hallucinationFree());
            explanation.setHallucinationFlags(objectMapper.writeValueAsString(validation.flags()));

            if (explanation.getExplanationType() == ExplanationType.LLM_RAG && validation.hallucinationFree()) {
                explanation.setExplanationType(ExplanationType.LLM_RAG_VALIDATED);
            }
            return true;
        } catch (Exception e) {
            log.warn("Hallucination validation failed for explanation {}: {}",
                    explanation.getId(), e.getMessage());
            return false;
        }
    }
}
