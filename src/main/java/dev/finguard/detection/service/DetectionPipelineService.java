package dev.finguard.detection.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finguard.config.MetricsConfig;
import dev.finguard.detection.ml.MLPredictionResult;
import dev.finguard.detection.ml.TribuoModelService;
import dev.finguard.detection.rule.RuleEngine;
import dev.finguard.detection.rule.RuleResult;
import dev.finguard.domain.enums.DatasetSource;
import dev.finguard.domain.enums.DetectionConfig;
import dev.finguard.domain.enums.ExplanationType;
import dev.finguard.domain.enums.TransactionType;
import dev.finguard.domain.model.Alert;
import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.model.TransactionFeatures;
import dev.finguard.domain.repository.AlertRepository;
import dev.finguard.domain.repository.TransactionFeaturesRepository;
import dev.finguard.domain.repository.TransactionRepository;
import dev.finguard.explanation.llm.ExplanationBatchAsyncService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;

import org.springframework.beans.factory.annotation.Qualifier;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * Orchestrates the detection pipeline: rules → ML → alert persistence.
 *
 * <h3>Two-Phase Decoupled Pipeline</h3>
 * <p>Detection and explanation are now separated into two independent phases:</p>
 * <ol>
 *   <li><b>Detection phase</b> (this service) — evaluates rules and ML, creates and persists
 *       {@link Alert} entities. Fast: no LLM calls in the hot path. Runs synchronously
 *       within {@link #analyzeAllTransactions}.</li>
 *   <li><b>Explanation phase</b> ({@link ExplanationBatchAsyncService}) — generates LLM
 *       explanations for anomaly alerts. Automatically dispatched as a background job
 *       after detection completes for configs that require LLM.</li>
 * </ol>
 *
 * <p>This separation eliminates the main bottleneck: previously, each alert in
 * {@code ML_LLM_RAG} / {@code FULL_SYSTEM} blocked the detection loop waiting for an
 * Ollama HTTP round-trip (~3-15 s per alert). Decoupling yields roughly
 * {@code N × llmLatency} throughput improvement for large batches.</p>
 *
 * <h3>Parallel Rule + ML Evaluation</h3>
 * <p>In {@code FULL_SYSTEM} mode, rule evaluation and ML prediction are run concurrently
 * using virtual threads. Since both are independent computations, this hides the
 * latency of whichever is slower.</p>
 */
@Service
public class DetectionPipelineService {

    private static final Logger log = LoggerFactory.getLogger(DetectionPipelineService.class);

    /**
     * Default JDBC bulk-insert batch size. Configurable via
     * {@code finguard.detection.persist-batch-size} (see application.yml).
     * The legacy hard-coded value was 500; raising it to 2 000 amortises the
     * PreparedStatement round-trip + parser overhead across more rows and
     * gives ~3-4× faster JDBC throughput on million-row alert sets.
     */
    private static final int DEFAULT_persistBatchSize = 2000;

    private final TransactionRepository transactionRepository;
    private final TransactionFeaturesRepository featuresRepository;
    private final AlertRepository alertRepository;
    private final RuleEngine ruleEngine;
    private final TribuoModelService mlService;
    private final ExplanationBatchAsyncService explanationBatchService;
    private final ObjectMapper objectMapper;
    private final PipelineStatusTracker statusTracker;
    private final AlertPersistenceService alertPersistenceService;
    private final JdbcBulkAlertWriter bulkAlertWriter;
    private final Counter alertsCreatedCounter;
    private final Timer detectionPipelineTimer;
    private final MeterRegistry meterRegistry;
    /**
     * Used by the streaming detection path to acquire a long-lived JDBC
     * connection with autoCommit=false — the only configuration in which
     * PostgreSQL respects {@code setFetchSize(...)} and uses a server-side
     * cursor instead of buffering the entire result set in the driver.
     */
    private final DataSource dataSource;
    /**
     * Bounded virtual-thread-backed executor for intra-request parallelism
     * (rule + ML overlap). Wired from {@code AsyncConfig#intraRequestExecutor}
     * so a hot-loop fan-out can never spawn unbounded virtual threads.
     */
    private final Executor intraRequestExecutor;

    /**
     * Safety cap — when the rules/ML flag more alerts than this for a single
     * config run, we stop processing and log WARN. Prevents pathological
     * over-triggering (e.g. the original PaySim {@code NEW_RECEIVER_HIGH_VALUE}
     * rule firing on ~57 % of transactions) from exhausting heap or generating
     * tens of millions of rows.
     *
     * <p>Default 1,000,000: comfortably above any realistic signal, well below
     * the 6.3 M full-dataset ceiling. Tune via
     * {@code finguard.detection.max-alerts-per-config}.</p>
     */
    @Value("${finguard.detection.max-alerts-per-config:1000000}")
    private long maxAlertsPerConfig;

    /** Prefer the JDBC bulk path over JPA for detection alerts ({@code true} by default). */
    @Value("${finguard.detection.persist-via-jdbc:true}")
    private boolean persistViaJdbc;

    /**
     * How many alerts to accumulate before flushing to the DB. Larger values
     * trade memory for throughput; 2 000 is the throughput sweet spot for
     * pgjdbc (see DEFAULT_persistBatchSize javadoc).
     */
    @Value("${finguard.detection.persist-batch-size:" + DEFAULT_persistBatchSize + "}")
    private int persistBatchSize;

    /**
     * Heap-trajectory log cadence: emit a snapshot every N progress windows. Helps
     * catch drift toward OOM on long runs so users see the problem in logs
     * before the JVM crashes.
     */
    private static final int HEAP_LOG_INTERVAL_BATCHES = 50;

    // ──────────────────────────────────────────────────────────────────
    // NOTE: the legacy paginated path (Hibernate-managed entity batches +
    // per-batch IN-clause dedup chunking + L1 cache clears) was removed when
    // the streaming JDBC path went live. Helpers that used to support it
    // (chunked list splitting, IN_CLAUSE_CHUNK_SIZE, EntityManager.clear,
    // the @Value batchSize) are no longer needed: dedup is now an SQL
    // anti-join, rows stream individually instead of materialising in
    // 50 000-row chunks, and detached row-mapped objects skip the L1 cache
    // entirely. See git history for the previous implementation if needed.
    // ──────────────────────────────────────────────────────────────────

    public DetectionPipelineService(TransactionRepository transactionRepository,
                                     TransactionFeaturesRepository featuresRepository,
                                     AlertRepository alertRepository,
                                     RuleEngine ruleEngine,
                                     TribuoModelService mlService,
                                     ExplanationBatchAsyncService explanationBatchService,
                                     ObjectMapper objectMapper,
                                     PipelineStatusTracker statusTracker,
                                     AlertPersistenceService alertPersistenceService,
                                     JdbcBulkAlertWriter bulkAlertWriter,
                                     Counter alertsCreatedCounter,
                                     Timer detectionPipelineTimer,
                                     MeterRegistry meterRegistry,
                                     DataSource dataSource,
                                     @Qualifier("intraRequestExecutor") Executor intraRequestExecutor) {
        this.transactionRepository = transactionRepository;
        this.featuresRepository = featuresRepository;
        this.alertRepository = alertRepository;
        this.ruleEngine = ruleEngine;
        this.mlService = mlService;
        this.explanationBatchService = explanationBatchService;
        this.objectMapper = objectMapper;
        this.statusTracker = statusTracker;
        this.alertPersistenceService = alertPersistenceService;
        this.bulkAlertWriter = bulkAlertWriter;
        this.alertsCreatedCounter = alertsCreatedCounter;
        this.detectionPipelineTimer = detectionPipelineTimer;
        this.meterRegistry = meterRegistry;
        this.dataSource = dataSource;
        this.intraRequestExecutor = intraRequestExecutor;
    }

    /** Delegate alert persistence either to the JPA path or the JDBC bulk path. */
    private int persistAlertBatch(List<Alert> batch) {
        if (batch.isEmpty()) return 0;
        if (persistViaJdbc) {
            return bulkAlertWriter.insertAlerts(batch);
        }
        return alertPersistenceService.saveAlerts(batch);
    }

    /**
     * Log current heap usage every {@link #HEAP_LOG_INTERVAL_BATCHES} batches
     * so slow memory leaks are visible in normal operator logs instead of
     * only in the crash dump.
     */
    private void logHeapIfNeeded(int batchIndex) {
        if (batchIndex > 0 && batchIndex % HEAP_LOG_INTERVAL_BATCHES == 0) {
            Runtime rt = Runtime.getRuntime();
            long usedMb = (rt.totalMemory() - rt.freeMemory()) >> 20;
            long maxMb  = rt.maxMemory() >> 20;
            double pct  = maxMb > 0 ? (double) usedMb / maxMb * 100.0 : 0.0;
            log.info("Detection heap: {} MB / {} MB ({}% of ceiling)",
                    usedMb, maxMb, String.format("%.1f", pct));
        }
    }

    /**
     * Analyze a transaction with a pre-loaded features object (single-tx API entry point).
     *
     * <p><b>Persistence semantics:</b> methods reached through this entry point
     * <b>persist immediately</b> — callers (REST APIs, tests) receive a fully
     * materialised {@link Alert} with a populated id. Use
     * {@link #analyzeForBatch} from the detection loop where bulk flushing
     * is handled by the outer accumulator.</p>
     */
    public Optional<Alert> analyzeTransactionWithFeatures(Transaction transaction,
                                                           TransactionFeatures features,
                                                           DetectionConfig config) {
        MDC.put("txId", String.valueOf(transaction.getId()));
        MDC.put("detectionConfig", config.name());
        try {
            return switch (config) {
                case RULES_ONLY -> analyzeWithRules(transaction, features, config);
                case ML_ONLY -> analyzeWithML(transaction, features, config);
                case ML_LLM_DIRECT -> analyzeWithMLAndDetect(transaction, features, config);
                case ML_LLM_RAG -> analyzeWithMLAndDetect(transaction, features, config);
                case FULL_SYSTEM -> analyzeFullSystem(transaction, features, config);
            };
        } finally {
            MDC.remove("txId");
            MDC.remove("detectionConfig");
        }
    }

    /**
     * Batch-scope variant of {@link #analyzeTransactionWithFeatures} — returns
     * <b>un-persisted</b> alerts so the outer loop can accumulate and flush
     * via {@link #persistAlertBatch} (either {@link JdbcBulkAlertWriter} or
     * {@link AlertPersistenceService}, whichever is active).
     *
     * <p>Keeping this distinct from the single-tx path means ML and FULL_SYSTEM
     * configs no longer pay per-row {@code alertRepository.save} overhead
     * inside the hot loop — the key throughput + memory fix for the benchmark
     * run that previously wedged at ~6 000 alerts/sec.</p>
     */
    private Optional<Alert> analyzeForBatch(Transaction transaction,
                                             TransactionFeatures features,
                                             DetectionConfig config) {
        MDC.put("txId", String.valueOf(transaction.getId()));
        MDC.put("detectionConfig", config.name());
        try {
            return switch (config) {
                case RULES_ONLY        -> analyzeWithRules(transaction, features, config);
                case ML_ONLY,
                     ML_LLM_DIRECT,
                     ML_LLM_RAG        -> analyzeMlNoSave(transaction, features, config);
                case FULL_SYSTEM       -> analyzeFullSystemNoSave(transaction, features, config);
            };
        } finally {
            MDC.remove("txId");
            MDC.remove("detectionConfig");
        }
    }

    /** ML path without the per-row {@code alertRepository.save} — for batch loops. */
    private Optional<Alert> analyzeMlNoSave(Transaction transaction,
                                             TransactionFeatures features,
                                             DetectionConfig config) {
        MLPredictionResult prediction = mlService.predict(transaction, features);
        if (!prediction.predictedFraud()) {
            return Optional.empty();
        }
        return Optional.of(buildMlAlert(transaction, features, config, prediction));
    }

    /**
     * FULL_SYSTEM path without the per-row save — for batch loops.
     * Mirrors {@link #analyzeFullSystem} minus the trailing
     * {@code alertRepository.save(alert)}.
     */
    private Optional<Alert> analyzeFullSystemNoSave(Transaction transaction,
                                                     TransactionFeatures features,
                                                     DetectionConfig config) {
        CompletableFuture<List<RuleResult>> rulesFuture = CompletableFuture
                .supplyAsync(() -> ruleEngine.evaluateTriggered(transaction, features), intraRequestExecutor);
        CompletableFuture<MLPredictionResult> mlFuture = CompletableFuture
                .supplyAsync(() -> mlService.predict(transaction, features), intraRequestExecutor);

        List<RuleResult> triggered;
        MLPredictionResult prediction;
        try {
            triggered = rulesFuture.get();
            prediction = mlFuture.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            triggered = ruleEngine.evaluateTriggered(transaction, features);
            prediction = mlService.predict(transaction, features);
        } catch (ExecutionException e) {
            log.warn("Parallel detection failed for tx {}, falling back to sequential: {}",
                    transaction.getId(), e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            triggered = ruleEngine.evaluateTriggered(transaction, features);
            prediction = mlService.predict(transaction, features);
        }

        boolean rulesTriggered = !triggered.isEmpty();
        boolean mlFlagged = prediction.predictedFraud();
        if (!rulesTriggered && !mlFlagged) {
            return Optional.empty();
        }

        Alert alert = new Alert();
        alert.setTransaction(transaction);
        alert.setDetectionConfig(config);
        alert.setIsAnomaly(true);
        if (rulesTriggered) {
            alert.setRuleTriggered(triggered.stream()
                    .map(r -> r.ruleName() + ": " + r.reason())
                    .collect(Collectors.joining("; ")));
        }
        if (mlFlagged) {
            alert.setMlRiskScore(prediction.riskScore());
            alert.setMlModelName(prediction.modelName());
            serializeFeatureImportances(alert, prediction, transaction.getId());
        }
        return Optional.of(alert);
    }

    public Optional<Alert> analyzeTransaction(Transaction transaction, DetectionConfig config) {
        TransactionFeatures features = featuresRepository
                .findByTransactionId(transaction.getId())
                .orElse(null);
        return analyzeTransactionWithFeatures(transaction, features, config);
    }

    /**
     * Run the detection pipeline on all transactions that don't have alerts yet.
     *
     * <p>Processes in batches using cursor-based pagination. Skips transactions
     * that already have an alert for the given config. After the detection loop,
     * automatically dispatches an async explanation job for configs that use LLM.</p>
     *
     * @param config which detection stages to run
     * @return number of new alerts created
     */
    public long analyzeAllTransactions(DetectionConfig config) {
        return analyzeAllTransactions(config, null, Long.MAX_VALUE, true);
    }

    public long analyzeAllTransactions(DetectionConfig config, String jobId) {
        return analyzeAllTransactions(config, jobId, Long.MAX_VALUE, true);
    }

    public long analyzeAllTransactions(DetectionConfig config, String jobId, long limit) {
        return analyzeAllTransactions(config, jobId, limit, true);
    }

    /**
     * Core overload — also controls whether the async explanation batch is auto-dispatched.
     *
     * <p><b>Streaming JDBC architecture (Option-2 redesign):</b> instead of paginating
     * through Hibernate-managed entities one batch at a time, this method now opens a
     * single PostgreSQL server-side cursor that joins {@code transactions ⨝
     * transaction_features} and anti-joins {@code alerts} (built-in dedup), filters
     * to the test partition, and streams rows one at a time at {@code fetchSize=5000}.
     * Each row is materialised into a detached {@link Transaction} + {@link
     * TransactionFeatures} pair (no JPA proxy, no L1 cache, no proxy-managed object
     * graph) and passed straight to the existing {@link #analyzeForBatch} evaluator.</p>
     *
     * <p>Why this is faster — even on a single thread:</p>
     * <ul>
     *   <li>Single SQL query per detection run instead of {@code 3 × ⌈rows/batchSize⌉}.</li>
     *   <li>No Hibernate hydration cost (~50–100 µs/row → ~2 µs/row via JDBC).</li>
     *   <li>No L1 cache pollution → no periodic {@code entityManager.clear()} stalls.</li>
     *   <li>Constant memory: rows stream as they arrive; max one row in flight at a time.</li>
     *   <li>Dedup happens in SQL via the alerts anti-join — zero round-trips.</li>
     * </ul>
     *
     * <p>Behavioural contract preserved: same alerts generated, same persistence path
     * ({@link JdbcBulkAlertWriter}), same metric timer + counter, same async-explanation
     * dispatch hook, same alert cap, same status-tracker progress events. The
     * single-row {@link #analyzeTransaction} / {@link #analyzeTransactionWithFeatures}
     * APIs are unchanged.</p>
     *
     * @param dispatchExplanations pass {@code false} when the caller (e.g. ExperimentRunner)
     *                             will manage explanation generation itself synchronously.
     */
    public long analyzeAllTransactions(DetectionConfig config, String jobId, long limit,
                                       boolean dispatchExplanations) {
        log.info("Starting detection pipeline [config={}, jobId={}, limit={}] on test-partition transactions (streaming)",
                config, jobId, limit == Long.MAX_VALUE ? "∞" : String.format("%,d", limit));

        if (config != DetectionConfig.RULES_ONLY && !mlService.isModelAvailable()) {
            log.error("{} config requested but no ML models are trained. " +
                       "Call POST /api/v1/models/train first.", config);
            return 0;
        }

        Timer.Sample timerSample = Timer.start();
        long detectionStartMs = System.currentTimeMillis();
        long totalTransactions = transactionRepository.countTestSet();

        // Mutable counters captured by the streaming row callback. Single-thread
        // access today (one reader thread = one connection cursor) so no
        // synchronisation needed; if/when we add a parallel inner loop these
        // become atomics.
        long[] processedHolder = { 0L };
        long[] alertsCreatedHolder = { 0L };
        boolean[] capReachedHolder = { false };
        List<Alert> toPersist = new ArrayList<>(persistBatchSize);

        try (Connection conn = dataSource.getConnection()) {
            // PostgreSQL only honours setFetchSize when autoCommit=false. Without
            // this, the entire ResultSet is buffered into the JDBC driver up-front
            // (the bug we're trying to fix). Restore at the end so the connection
            // returns to the pool in its original state.
            boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(STREAMING_DETECTION_SQL)) {
                ps.setFetchSize(STREAMING_FETCH_SIZE);
                ps.setString(1, config.name());

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Transaction tx = mapTransaction(rs);
                        TransactionFeatures features = mapFeaturesIfPresent(rs, tx);

                        Optional<Alert> alert = analyzeForBatch(tx, features, config);
                        alert.ifPresent(toPersist::add);

                        long processed = ++processedHolder[0];

                        if (toPersist.size() >= persistBatchSize) {
                            int saved = persistAlertBatch(toPersist);
                            alertsCreatedHolder[0] += saved;
                            alertsCreatedCounter.increment(saved);
                            toPersist.clear();
                        }

                        if (processed >= limit) break;

                        // Safety cap — same behaviour as the legacy batched path.
                        if (alertsCreatedHolder[0] + toPersist.size() >= maxAlertsPerConfig) {
                            log.warn("Alert cap hit for config={}: {} alerts already generated (cap={}). "
                                            + "Stopping detection to prevent runaway. "
                                            + "Consider tuning rule thresholds or raising "
                                            + "finguard.detection.max-alerts-per-config.",
                                    config, alertsCreatedHolder[0] + toPersist.size(), maxAlertsPerConfig);
                            capReachedHolder[0] = true;
                            break;
                        }

                        // Status + progress logs every PROGRESS_UPDATE_EVERY rows
                        // (cheap modulo, no per-row I/O).
                        //
                        // Reporting nuance: alertsCreatedHolder[0] only increments
                        // when toPersist gets flushed (every persistBatchSize alerts,
                        // i.e. 5 000 in benchmark profile). Without adding the
                        // in-flight queue size, the displayed count steps in chunks
                        // of 5 000 and gives the misleading impression that detection
                        // finds "exactly 5 000 alerts per N rows". The true running
                        // count is committed + in-flight.
                        if (processed % PROGRESS_UPDATE_EVERY == 0) {
                            long liveAlerts = alertsCreatedHolder[0] + toPersist.size();
                            if (jobId != null) {
                                statusTracker.updateProgress(jobId, "Analyzing transactions",
                                        processed, totalTransactions, liveAlerts);
                            }
                            log.info("Detection pipeline progress: {}/{} processed, {} alerts ({} committed, {} pending flush)",
                                    String.format("%,d", processed),
                                    String.format("%,d", totalTransactions),
                                    String.format("%,d", liveAlerts),
                                    String.format("%,d", alertsCreatedHolder[0]),
                                    String.format("%,d", toPersist.size()));
                            // Cheap heap snapshot at the same cadence as legacy.
                            logHeapIfNeeded((int) (processed / PROGRESS_UPDATE_EVERY));
                        }
                    }
                }
            }
            conn.commit();
            conn.setAutoCommit(originalAutoCommit);
        } catch (SQLException e) {
            throw new DetectionPipelineException(
                    "Streaming detection failed for config=" + config + ": " + e.getMessage(), e);
        }

        // Final flush — drain anything left under persistBatchSize.
        if (!toPersist.isEmpty()) {
            int saved = persistAlertBatch(toPersist);
            alertsCreatedHolder[0] += saved;
            alertsCreatedCounter.increment(saved);
            toPersist.clear();
        }

        long alertsCreated = alertsCreatedHolder[0];

        timerSample.stop(detectionPipelineTimer);
        meterRegistry.timer(MetricsConfig.DETECTION_CONFIG_TIMER, "config", config.name())
                .record(System.currentTimeMillis() - detectionStartMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        log.info("Detection pipeline complete [config={}]: {} alerts created from {} test rows{}",
                config, String.format("%,d", alertsCreated),
                String.format("%,d", processedHolder[0]),
                capReachedHolder[0] ? " (alert cap hit)" : "");

        // Phase 2: dispatch async LLM explanation for configs that use it.
        if (alertsCreated > 0 && dispatchExplanations) {
            ExplanationType explType = resolveExplanationType(config);
            if (explType != null) {
                int explLimit = alertsCreated > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) alertsCreated;
                String explJobId = UUID.randomUUID().toString();
                statusTracker.start(explJobId, "EXPLANATION_BATCH");
                explanationBatchService.runAsync(explJobId, config, explType, explLimit);
                log.info("Auto-triggered explanation batch [jobId={}, type={}, limit={}]",
                        explJobId, explType, explLimit);
            }
        }

        return alertsCreated;
    }

    /**
     * Streaming detection query — see {@link #analyzeAllTransactions} javadoc.
     *
     * <p>Anti-join on {@code alerts} replaces the legacy per-batch dedup query.
     * Test-partition filter ({@code is_training_set = false}) replaces the legacy
     * cursor scoping. {@code ORDER BY t.id} keeps row ordering deterministic so
     * results match the legacy path byte-for-byte.</p>
     */
    private static final String STREAMING_DETECTION_SQL = """
            SELECT
                t.id, t.external_id, t.dataset_source, t.timestamp,
                t.sender_account, t.receiver_account, t.transaction_type, t.amount,
                t.sender_balance_before, t.sender_balance_after,
                t.receiver_balance_before, t.receiver_balance_after,
                t.is_fraud, t.is_flagged_fraud, t.is_training_set, t.fold,
                tf.transaction_id AS tf_transaction_id,
                tf.amount_zscore, tf.tx_velocity_1h, tf.tx_velocity_24h,
                tf.avg_amount_7d, tf.amount_ratio_to_avg, tf.balance_change_ratio,
                tf.is_new_receiver, tf.receiver_diversity_7d,
                tf.hour_of_day, tf.day_of_week, tf.is_round_amount, tf.is_high_risk_type
            FROM transactions t
            LEFT JOIN transaction_features tf ON tf.transaction_id = t.id
            LEFT JOIN alerts a ON a.transaction_id = t.id AND a.detection_config = ?
            WHERE t.is_training_set = false
              AND a.id IS NULL
            ORDER BY t.id
            """;

    /**
     * JDBC fetch size for the server-side cursor. 5 000 is the same value tuned
     * for {@code spring.jpa.properties.hibernate.jdbc.fetch_size} so the
     * streaming path inherits the same I/O batch behaviour as the rest of the
     * codebase.
     */
    private static final int STREAMING_FETCH_SIZE = 5_000;

    /** Emit a progress log + status-tracker update every N rows. */
    private static final int PROGRESS_UPDATE_EVERY = 10_000;

    /**
     * Map a JDBC row to a detached {@link Transaction} entity. No JPA proxy,
     * no L1 cache attachment — just a plain object with the fields the rule
     * engine, ML predictor, and alert builder need.
     */
    private Transaction mapTransaction(ResultSet rs) throws SQLException {
        Transaction tx = new Transaction();
        tx.setId(rs.getLong("id"));
        tx.setExternalId(rs.getString("external_id"));
        String datasetSource = rs.getString("dataset_source");
        if (datasetSource != null) tx.setDatasetSource(DatasetSource.valueOf(datasetSource));
        Timestamp ts = rs.getTimestamp("timestamp");
        if (ts != null) tx.setTimestamp(ts.toLocalDateTime());
        tx.setSenderAccount(rs.getString("sender_account"));
        tx.setReceiverAccount(rs.getString("receiver_account"));
        String txType = rs.getString("transaction_type");
        if (txType != null) tx.setTransactionType(TransactionType.valueOf(txType));
        tx.setAmount(rs.getBigDecimal("amount"));
        tx.setSenderBalanceBefore(rs.getBigDecimal("sender_balance_before"));
        tx.setSenderBalanceAfter(rs.getBigDecimal("sender_balance_after"));
        tx.setReceiverBalanceBefore(rs.getBigDecimal("receiver_balance_before"));
        tx.setReceiverBalanceAfter(rs.getBigDecimal("receiver_balance_after"));
        // Boxed booleans so a SQL NULL becomes Java null (matching JPA behaviour).
        tx.setIsFraud(rs.getObject("is_fraud", Boolean.class));
        tx.setIsFlaggedFraud(rs.getObject("is_flagged_fraud", Boolean.class));
        tx.setIsTrainingSet(rs.getObject("is_training_set", Boolean.class));
        tx.setFold(rs.getObject("fold", Integer.class));
        // createdAt is @PrePersist-managed and read-only; rule + ML evaluators
        // never touch it, so we don't need a copy on the detached object.
        return tx;
    }

    /**
     * Map a JDBC row to a detached {@link TransactionFeatures} when the LEFT
     * JOIN found a matching row. Returns {@code null} when features have not
     * been computed for this transaction (rare in practice — feature compute
     * runs after every import — but the legacy path also handled it).
     */
    private TransactionFeatures mapFeaturesIfPresent(ResultSet rs, Transaction tx) throws SQLException {
        rs.getLong("tf_transaction_id");
        if (rs.wasNull()) {
            return null;
        }
        TransactionFeatures features = new TransactionFeatures();
        features.setTransaction(tx);
        features.setAmountZscore(rs.getObject("amount_zscore", Double.class));
        features.setTxVelocity1h(rs.getObject("tx_velocity_1h", Integer.class));
        features.setTxVelocity24h(rs.getObject("tx_velocity_24h", Integer.class));
        features.setAvgAmount7d(rs.getBigDecimal("avg_amount_7d"));
        features.setAmountRatioToAvg(rs.getObject("amount_ratio_to_avg", Double.class));
        features.setBalanceChangeRatio(rs.getObject("balance_change_ratio", Double.class));
        features.setIsNewReceiver(rs.getObject("is_new_receiver", Boolean.class));
        features.setReceiverDiversity7d(rs.getObject("receiver_diversity_7d", Integer.class));
        // SMALLINT in PG → Short in Java; ResultSet.getShort returns 0 for NULL,
        // so use getObject for the boxed value.
        features.setHourOfDay(rs.getObject("hour_of_day", Short.class));
        features.setDayOfWeek(rs.getObject("day_of_week", Short.class));
        features.setIsRoundAmount(rs.getObject("is_round_amount", Boolean.class));
        features.setIsHighRiskType(rs.getObject("is_high_risk_type", Boolean.class));
        return features;
    }

    /** Internal exception so callers can distinguish JDBC-streaming failures from generic runtime errors. */
    public static class DetectionPipelineException extends RuntimeException {
        public DetectionPipelineException(String message, Throwable cause) { super(message, cause); }
    }

    /**
     * Run the pipeline asynchronously. The caller is responsible for generating
     * the jobId and registering it with {@link PipelineStatusTracker} before
     * calling this method.
     */
    @Async
    public void runAsync(DetectionConfig config, String jobId) {
        runAsync(config, jobId, Long.MAX_VALUE);
    }

    @Async
    public void runAsync(DetectionConfig config, String jobId, long limit) {
        try {
            long alerts = analyzeAllTransactions(config, jobId, limit);
            statusTracker.complete(jobId, alerts);
        } catch (Exception e) {
            log.error("Async detection pipeline failed [jobId={}]: {}", jobId, e.getMessage(), e);
            statusTracker.fail(jobId, e.getMessage());
        }
    }

    // ================================================================
    // Rules-only detection
    // ================================================================

    private Optional<Alert> analyzeWithRules(Transaction transaction,
                                              TransactionFeatures features,
                                              DetectionConfig config) {
        List<RuleResult> triggered = ruleEngine.evaluateTriggered(transaction, features);

        if (triggered.isEmpty()) {
            return Optional.empty();
        }

        String rulesSummary = triggered.stream()
                .map(r -> r.ruleName() + ": " + r.reason())
                .collect(Collectors.joining("; "));

        Alert alert = new Alert();
        alert.setTransaction(transaction);
        alert.setDetectionConfig(config);
        alert.setRuleTriggered(rulesSummary);
        alert.setIsAnomaly(true);

        return Optional.of(alert);
    }

    // ================================================================
    // ML-only detection
    // ================================================================

    private Optional<Alert> analyzeWithML(Transaction transaction,
                                           TransactionFeatures features,
                                           DetectionConfig config) {
        MLPredictionResult prediction = mlService.predict(transaction, features);

        if (!prediction.predictedFraud()) {
            return Optional.empty();
        }

        Alert alert = buildMlAlert(transaction, features, config, prediction);
        return Optional.of(alert);
    }

    // ================================================================
    // ML + LLM (direct or RAG): detection only — explanation is async
    // ================================================================

    /**
     * Run ML detection and, if fraud is predicted, persist the alert.
     *
     * <p>LLM explanation is NOT generated here — it is dispatched as a background job
     * by {@link #analyzeAllTransactions} after the detection loop completes.
     * This decoupling is the primary throughput improvement for LLM-enabled configs.</p>
     */
    private Optional<Alert> analyzeWithMLAndDetect(Transaction transaction,
                                                    TransactionFeatures features,
                                                    DetectionConfig config) {
        MLPredictionResult prediction = mlService.predict(transaction, features);

        if (!prediction.predictedFraud()) {
            return Optional.empty();
        }

        // Single-transaction path (API calls, tests): persist immediately so
        // callers get a fully-materialised Alert (with id). The batch loop
        // calls buildMlAlert + accumulator directly — see analyzeAllTransactions.
        Alert alert = buildMlAlert(transaction, features, config, prediction);
        alert = alertRepository.save(alert);
        return Optional.of(alert);
    }

    // ================================================================
    // Full system: rules + ML (parallel) — explanation is async
    // ================================================================

    /**
     * Run the full detection pipeline: rules + ML evaluated in parallel, alert persisted.
     *
     * <p>Rule evaluation and ML prediction are independent computations — this method
     * runs them concurrently on virtual threads and merges the results. An alert is
     * created if either rules trigger or ML predicts fraud.</p>
     *
     * <p>LLM explanation is dispatched asynchronously after the detection loop;
     * it is not part of this per-transaction method.</p>
     */
    private Optional<Alert> analyzeFullSystem(Transaction transaction,
                                               TransactionFeatures features,
                                               DetectionConfig config) {
        // Parallel evaluation: rule engine + ML model run concurrently on the
        // bounded intra-request executor (CallerRunsPolicy back-pressure under load).
        CompletableFuture<List<RuleResult>> rulesFuture = CompletableFuture
                .supplyAsync(() -> ruleEngine.evaluateTriggered(transaction, features), intraRequestExecutor);
        CompletableFuture<MLPredictionResult> mlFuture = CompletableFuture
                .supplyAsync(() -> mlService.predict(transaction, features), intraRequestExecutor);

        List<RuleResult> triggered;
        MLPredictionResult prediction;
        try {
            triggered = rulesFuture.get();
            prediction = mlFuture.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // Fallback to sequential on interrupt
            triggered = ruleEngine.evaluateTriggered(transaction, features);
            prediction = mlService.predict(transaction, features);
        } catch (ExecutionException e) {
            log.warn("Parallel detection failed for tx {}, falling back to sequential: {}",
                    transaction.getId(), e.getCause().getMessage());
            triggered = ruleEngine.evaluateTriggered(transaction, features);
            prediction = mlService.predict(transaction, features);
        }

        boolean rulesTriggered = !triggered.isEmpty();
        boolean mlFlagged = prediction.predictedFraud();

        if (!rulesTriggered && !mlFlagged) {
            return Optional.empty();
        }

        Alert alert = new Alert();
        alert.setTransaction(transaction);
        alert.setDetectionConfig(config);
        alert.setIsAnomaly(true);

        if (rulesTriggered) {
            String rulesSummary = triggered.stream()
                    .map(r -> r.ruleName() + ": " + r.reason())
                    .collect(Collectors.joining("; "));
            alert.setRuleTriggered(rulesSummary);
        }

        if (mlFlagged) {
            alert.setMlRiskScore(prediction.riskScore());
            alert.setMlModelName(prediction.modelName());
            serializeFeatureImportances(alert, prediction, transaction.getId());
        }

        // Single-transaction path persists immediately; see analyzeWithMLAndDetect.
        alert = alertRepository.save(alert);
        return Optional.of(alert);
    }

    // ================================================================
    // Shared helpers
    // ================================================================

    private Alert buildMlAlert(Transaction transaction, TransactionFeatures features,
                                DetectionConfig config, MLPredictionResult prediction) {
        Alert alert = new Alert();
        alert.setTransaction(transaction);
        alert.setDetectionConfig(config);
        alert.setMlRiskScore(prediction.riskScore());
        alert.setMlModelName(prediction.modelName());
        alert.setIsAnomaly(true);

        serializeFeatureImportances(alert, prediction, transaction.getId());

        return alert;
    }

    private void serializeFeatureImportances(Alert alert, MLPredictionResult prediction,
                                              Long transactionId) {
        if (!prediction.featureImportances().isEmpty()) {
            try {
                alert.setFeatureImportances(
                        objectMapper.writeValueAsString(prediction.featureImportances()));
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize feature importances for transaction {}",
                        transactionId, e);
            }
        }
    }

    /**
     * Map a detection config to the explanation type used by the async explanation batch.
     * Returns {@code null} for configs that do not involve LLM explanation.
     */
    private static ExplanationType resolveExplanationType(DetectionConfig config) {
        return switch (config) {
            case ML_LLM_DIRECT -> ExplanationType.LLM_DIRECT;
            case ML_LLM_RAG, FULL_SYSTEM -> ExplanationType.LLM_RAG;
            default -> null;
        };
    }
}