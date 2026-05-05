package dev.finguard.detection.ml;

import com.oracle.labs.mlrg.olcut.util.Pair;
import dev.finguard.config.MetricsConfig;
import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.model.TransactionFeatures;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.tribuo.*;
import org.tribuo.classification.Label;
import org.tribuo.classification.LabelFactory;
import org.tribuo.classification.dtree.CARTClassificationTrainer;
import org.tribuo.classification.ensemble.VotingCombiner;
import org.tribuo.classification.xgboost.XGBoostClassificationTrainer;
import org.tribuo.common.tree.RandomForestTrainer;
import org.tribuo.dataset.MinimumCardinalityDataset;
import org.tribuo.provenance.SimpleDataSourceProvenance;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Manages Tribuo ML models for fraud detection.
 *
 * <p>Supports two model types:</p>
 * <ul>
 *   <li><b>Random Forest</b> — built as {@code rfParallelism} parallel mini-forests of
 *       {@code rfNumTrees / rfParallelism} trees each.  Predictions average the fraud
 *       probability across all mini-forests.  This gives a ~{@code rfParallelism}×
 *       wall-clock speedup over a single sequential forest while maintaining equivalent
 *       (or slightly better, due to diversity) accuracy.</li>
 *   <li><b>XGBoost</b> — gradient-boosted trees via {@link XGBoostClassificationTrainer},
 *       using all available CPU cores ({@code nthread}).  Requires native JNI library;
 *       gracefully skipped when unavailable (e.g. macOS aarch64).</li>
 * </ul>
 *
 * <p>Training data is loaded via JDBC with cursor-based pagination and reservoir sampling:
 * ALL fraud rows are kept, legitimate rows are randomly subsampled to
 * {@code maxLegitTrainingSamples}, reducing a 6.5M-row dataset to ~208K examples.</p>
 *
 * <p>A heartbeat thread logs progress every {@value HEARTBEAT_INTERVAL_SEC} seconds while
 * Tribuo's internal training loop runs (which is a black box with no callbacks).</p>
 */
@Service
public class TribuoModelService {

    private static final Logger log = LoggerFactory.getLogger(TribuoModelService.class);

    /** How often the heartbeat thread logs during Tribuo's black-box training loop. */
    private static final int HEARTBEAT_INTERVAL_SEC = 15;

    private static final String TRAINING_SQL = """
            SELECT tf.transaction_id,
                   t.is_fraud,
                   t.amount,
                   tf.amount_zscore,
                   tf.tx_velocity_1h,
                   tf.tx_velocity_24h,
                   COALESCE(tf.avg_amount_7d, 0) AS avg_amount_7d,
                   tf.amount_ratio_to_avg,
                   tf.balance_change_ratio,
                   tf.is_new_receiver,
                   tf.receiver_diversity_7d,
                   tf.hour_of_day,
                   tf.day_of_week,
                   tf.is_round_amount,
                   tf.is_high_risk_type
            FROM transaction_features tf
            JOIN transactions t ON t.id = tf.transaction_id
            WHERE tf.transaction_id > ?
              AND t.is_training_set = true
            ORDER BY tf.transaction_id
            LIMIT ?
            """;

    private static final String ESTIMATE_COUNT_SQL =
            "SELECT reltuples::bigint FROM pg_class WHERE relname = 'transaction_features'";

    private final JdbcTemplate jdbcTemplate;
    private final FeatureTransformer featureTransformer;

    /**
     * Dedicated platform-thread pool for CPU-bound training.
     *
     * <p>Sized to {@code max(2, rfParallelism)} so RF mini-forests and XGBoost can all
     * run concurrently.  Initialized in {@link #initExecutor()} after {@code @Value}
     * injection completes, because the pool size depends on {@code rfParallelism}.</p>
     *
     * <p>Virtual threads (enabled globally) are for I/O-bound work; CPU-bound tree
     * building benefits from platform threads only.</p>
     */
    private ExecutorService mlTrainingExecutor;

    // ── Tunable via application.yml ───────────────────────────────────

    @Value("${finguard.detection.ml.risk-score-threshold:0.5}")
    private double riskScoreThreshold;

    @Value("${finguard.detection.ml.ensemble-enabled:true}")
    private boolean ensembleEnabled;

    @Value("${finguard.detection.ml.training-batch-size:100000}")
    private int trainingBatchSize;

    @Value("${finguard.detection.ml.max-legit-training-samples:200000}")
    private int maxLegitTrainingSamples;

    /** Total number of trees in the Random Forest (split across mini-forests). */
    @Value("${finguard.detection.ml.rf.num-trees:100}")
    private int rfNumTrees;

    /** Max tree depth per CART tree. Deeper = more expressive, higher overfitting risk. */
    @Value("${finguard.detection.ml.rf.max-depth:6}")
    private int rfMaxDepth;

    /**
     * Number of parallel mini-forests to build simultaneously.
     *
     * <p>The 100 trees are split evenly: with parallelism=4 each mini-forest gets 25 trees.
     * All 4 mini-forests train on separate platform threads at the same time, giving
     * roughly a 4× wall-clock speedup.  Set to 1 to disable parallelism.</p>
     */
    @Value("${finguard.detection.ml.rf.parallelism:4}")
    private int rfParallelism;

    /** Number of XGBoost boosting rounds (= number of trees in XGBoost terminology). */
    @Value("${finguard.detection.ml.xgb.num-rounds:100}")
    private int xgbNumRounds;

    /** XGBoost nthread. 0 = auto-detect availableProcessors(). */
    @Value("${finguard.detection.ml.xgb.num-threads:0}")
    private int xgbNumThreads;

    // ── Training progress gauges ──────────────────────────────────────

    /** Counts completed RF mini-forests during active training. Reset at training start. */
    private final AtomicInteger trainingRfForestsComplete = new AtomicInteger(0);

    /** 1 when XGBoost training is complete, 0 otherwise. Reset at training start. */
    private final AtomicInteger trainingXgbComplete = new AtomicInteger(0);

    // ── Model state ───────────────────────────────────────────────────

    /**
     * Working list used by training threads to stage new mini-forests. Training
     * clears this at the start of a pass and each parallel task adds its
     * finished forest. Serialised model I/O and the Micrometer gauge also read
     * it. Callers who serve predictions must NOT read it directly — use
     * {@link #publishedForests} instead.
     */
    private final List<Model<Label>> rfMiniForests = new CopyOnWriteArrayList<>();

    /**
     * Atomically-published snapshot of the current RF ensemble that prediction
     * traffic reads. Updated once per training/load pass after all mini-forests
     * have been built, so {@link #predict} always sees a coherent ensemble —
     * never a partially-cleared list in the middle of a rebuild.
     *
     * <p>Improvement on audit C-3: the previous implementation short-circuited
     * {@code predict()} to "unavailable" during training; this version keeps
     * serving the <em>previous</em> ensemble until the new one is ready, so
     * concurrent experiment runs never observe a gap.</p>
     */
    private final AtomicReference<List<Model<Label>>> publishedForests =
            new AtomicReference<>(List.of());

    private final AtomicReference<Model<Label>> xgboostModel = new AtomicReference<>();
    private final AtomicReference<Map<String, Double>> rfFeatureImportances  = new AtomicReference<>(Map.of());
    private final AtomicReference<Map<String, Double>> xgbFeatureImportances = new AtomicReference<>(Map.of());

    /**
     * Audit C-3 (retained as a finer-grained signal): flipped to {@code true}
     * only during the clear-and-repopulate window of {@link #loadModels}.
     * Predictions read from {@link #publishedForests} either way, but this
     * flag lets callers observe "retrain in progress" via a gauge if they care.
     */
    private final java.util.concurrent.atomic.AtomicBoolean trainingInProgress =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private final MeterRegistry meterRegistry;

    public TribuoModelService(JdbcTemplate jdbcTemplate, FeatureTransformer featureTransformer,
                               MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.featureTransformer = featureTransformer;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void initExecutor() {
        // Need rfParallelism + 1 threads: N for mini-forests and 1 for XGBoost in parallel.
        int threads = Math.max(2, Math.min(rfParallelism + 1,
                Runtime.getRuntime().availableProcessors()));
        mlTrainingExecutor = Executors.newFixedThreadPool(threads,
                Thread.ofPlatform().name("ml-trainer-", 0).factory());
        log.info("ML training executor: {} platform threads (rfParallelism={}, availableProcessors={})",
                threads, rfParallelism, Runtime.getRuntime().availableProcessors());

        // Gauges: readable by Micrometer/Prometheus without polling the service.
        meterRegistry.gauge("ml.training.rf.forests.complete", trainingRfForestsComplete, AtomicInteger::get);
        meterRegistry.gauge("ml.training.xgb.complete", trainingXgbComplete, AtomicInteger::get);
        meterRegistry.gauge("ml.models.rf.count", rfMiniForests, List::size);
        meterRegistry.gauge("ml.models.xgb.loaded", xgboostModel,
                ref -> ref.get() != null ? 1.0 : 0.0);
    }

    @PreDestroy
    public void shutdown() {
        if (mlTrainingExecutor != null) {
            mlTrainingExecutor.shutdownNow();
        }
    }

    // ================================================================
    // Public API
    // ================================================================

    public TrainingSummary trainModels() {
        return trainModels(stage -> {}, true, true);
    }

    /**
     * Train models while reporting stage progress to {@code stageReporter}.
     *
     * <p><b>Stages reported:</b>
     * <ol>
     *   <li>"Loading training data"</li>
     *   <li>"Training models (parallel)"</li>
     * </ol>
     *
     * <p><b>Parallelism strategy:</b>
     * <ul>
     *   <li>RF is split into {@code rfParallelism} mini-forests built simultaneously.
     *   <li>XGBoost runs on a separate thread concurrent with the mini-forests.
     *   <li>A heartbeat thread logs every {@value HEARTBEAT_INTERVAL_SEC}s so the user
     *       can see training is progressing inside Tribuo's black-box training loop.
     * </ul>
     */
    public TrainingSummary trainModels(Consumer<String> stageReporter) {
        return trainModels(stageReporter, true, true);
    }

    /**
     * Train models with optional per-model selection.
     *
     * @param includeRf  train Random Forest mini-forests
     * @param includeXgb train XGBoost (skipped if JNI unavailable regardless)
     */
    public TrainingSummary trainModels(Consumer<String> stageReporter, boolean includeRf, boolean includeXgb) {
        int cores = Runtime.getRuntime().availableProcessors();
        int effectiveXgbThreads = xgbNumThreads > 0 ? xgbNumThreads : cores;
        int treesPerForest = Math.max(1, rfNumTrees / rfParallelism);

        printBanner("ML Model Training Started");
        log.info("  System     : {} CPU cores  |  heap: {}", cores, heapInfo());
        log.info("  RF config  : {} trees  |  {} parallel mini-forests × {} trees each  |  depth {}",
                rfNumTrees, rfParallelism, treesPerForest, rfMaxDepth);
        log.info("  XGB config : {} rounds  |  {} threads (nthread={})",
                xgbNumRounds, effectiveXgbThreads, effectiveXgbThreads);
        log.info("  Ensemble   : {}  |  risk threshold: {}",
                ensembleEnabled ? "ENABLED" : "DISABLED", riskScoreThreshold);
        log.info("  Sampling   : ALL fraud + reservoir({}) legit  |  batch: {}",
                fmt(maxLegitTrainingSamples), fmt(trainingBatchSize));

        long wallStart = System.currentTimeMillis();

        // ── STAGE 1: Load data ───────────────────────────────────────
        stageReporter.accept("Loading training data");
        log.info("");
        log.info("  ┌── STAGE 1/3 : Loading training data ─────────────────────────────");
        long loadStart = System.currentTimeMillis();
        MutableDataset<Label> rawDataset = loadTrainingData();
        long loadMs = System.currentTimeMillis() - loadStart;
        log.info("  └── STAGE 1/3 complete  │  {}ms  │  heap: {}", fmt(loadMs), heapInfo());

        if (rawDataset.size() < 10) {
            log.warn("  ✗ Not enough training data ({} examples, need ≥10). " +
                     "Run 'Compute Features' first.", fmt(rawDataset.size()));
            return new TrainingSummary(0, 0, 0, 0, List.of());
        }

        // ── STAGE 2: Feature cardinality filter ──────────────────────
        log.info("");
        log.info("  ┌── STAGE 2/3 : Feature cardinality filter (min=2) ────────────────");
        long filterStart = System.currentTimeMillis();
        MinimumCardinalityDataset<Label> dataset = new MinimumCardinalityDataset<>(rawDataset, 2);

        long fraudCount = 0;
        for (var example : dataset) {
            if (example.getOutput().getLabel().equals("FRAUD")) fraudCount++;
        }
        long legitCount = dataset.size() - fraudCount;
        double fraudRate = dataset.size() > 0 ? (100.0 * fraudCount / dataset.size()) : 0.0;
        int removed = rawDataset.size() - dataset.size();

        log.info("  │  Total examples : {}  (removed {} low-cardinality)", fmt(dataset.size()), fmt(removed));
        log.info("  │  Fraud  : {}  ({})  ← ALL retained",
                fmt(fraudCount), pct(fraudRate));
        log.info("  │  Legit  : {}  ({})  ← reservoir sampled",
                fmt(legitCount), pct(100.0 - fraudRate));
        log.info("  └── STAGE 2/3 complete  │  {}ms", System.currentTimeMillis() - filterStart);

        List<String> trainedModels = Collections.synchronizedList(new ArrayList<>());
        trainingInProgress.set(true);
        rfMiniForests.clear();

        // ── STAGE 3: Parallel training ───────────────────────────────
        stageReporter.accept("Training models (parallel)");
        log.info("");
        log.info("  ┌── STAGE 3/3 : Training RF mini-forests + XGBoost in PARALLEL ────");
        log.info("  │");
        log.info("  │  WHY PARALLEL MINI-FORESTS?");
        log.info("  │  Tribuo's RandomForestTrainer builds trees one-by-one (no internal");
        log.info("  │  parallelism). By splitting {} trees into {} independent mini-forests",
                rfNumTrees, rfParallelism);
        log.info("  │  of {} trees each, all run simultaneously on platform threads.", treesPerForest);
        log.info("  │  Wall-clock time ≈ 1/{} × sequential time.  Prediction averages", rfParallelism);
        log.info("  │  fraud probability across all mini-forests.");
        log.info("  │");
        log.info("  │  NOTE: Tribuo has NO training-progress callbacks. A heartbeat");
        log.info("  │  thread logs every {}s so you can see training is alive.", HEARTBEAT_INTERVAL_SEC);
        log.info("  │");

        long trainStart = System.currentTimeMillis();
        final MinimumCardinalityDataset<Label> finalDataset = dataset;
        trainingRfForestsComplete.set(0);
        trainingXgbComplete.set(0);
        AtomicInteger completedForests = trainingRfForestsComplete;

        // Heartbeat — fires every N seconds during Tribuo's black-box training.
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("ml-heartbeat").factory());
        heartbeat.scheduleAtFixedRate(() -> {
            int done = completedForests.get();
            long elapsed = System.currentTimeMillis() - trainStart;
            String rfStatus = done >= rfParallelism ? "✓ done" :
                    done + "/" + rfParallelism + " mini-forests complete";
            String xgbStatus = xgboostModel.get() != null ? "✓ done" : "running...";
            log.info("  │  ♥ heartbeat  │  elapsed: {}ms  │  RF: {}  │  XGB: {}  │  heap: {}",
                    fmt(elapsed), rfStatus, xgbStatus, heapInfo());
        }, HEARTBEAT_INTERVAL_SEC, HEARTBEAT_INTERVAL_SEC, TimeUnit.SECONDS);

        // ── Dashboard timers: track wall-clock for RF and XGB separately ─────
        // Since both run in parallel, we attach `whenComplete` callbacks that
        // fire as soon as each phase finishes. This captures each model's true
        // training time, not the combined max(rf, xgb).
        final long rfTimerStart  = System.currentTimeMillis();
        final long xgbTimerStart = System.currentTimeMillis();

        // ── RF: N parallel mini-forests ──────────────────────────────
        List<CompletableFuture<Void>> rfFutures = new ArrayList<>();
        if (includeRf) for (int i = 0; i < rfParallelism; i++) {
            final int forestIdx = i;
            // Distribute remainder to the last forest
            final int treesInForest = (i == rfParallelism - 1)
                    ? rfNumTrees - treesPerForest * (rfParallelism - 1)
                    : treesPerForest;

            rfFutures.add(CompletableFuture.runAsync(() -> {
                long t = System.currentTimeMillis();
                log.info("  │  [RF-{}] ▶ Started  │  {} trees  │  depth {}  │  thread: '{}'",
                        forestIdx, treesInForest, rfMaxDepth, Thread.currentThread().getName());
                try {
                    // Use forestIdx as seed offset so each mini-forest has different tree structure.
                    Model<Label> mini = trainSingleForest(finalDataset, treesInForest, forestIdx);
                    rfMiniForests.add(mini);
                    int done = completedForests.incrementAndGet();
                    log.info("  │  [RF-{}] ✓ Complete  │  {}ms  │  ({}/{} mini-forests done)  │  heap: {}",
                            forestIdx, fmt(System.currentTimeMillis() - t), done, rfParallelism, heapInfo());
                } catch (Exception | NoClassDefFoundError e) {
                    log.error("  │  [RF-{}] ✗ FAILED  │  {}ms  │  {}",
                            forestIdx, fmt(System.currentTimeMillis() - t), e.getMessage(), e);
                }
            }, mlTrainingExecutor));
        }

        // Combine all RF futures so we can record the moment ALL mini-forests
        // are done. Empty rfFutures (includeRf=false) → completed-immediately
        // future, so the timer below records ~0ms — harmless and accurate.
        if (includeRf) {
            CompletableFuture<Void> rfAll = rfFutures.isEmpty()
                    ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.allOf(rfFutures.toArray(new CompletableFuture[0]));
            rfAll.whenComplete((v, t) -> meterRegistry
                    .timer(MetricsConfig.TRAINING_TIMER, "model", "rf")
                    .record(System.currentTimeMillis() - rfTimerStart, TimeUnit.MILLISECONDS));
        }

        // ── XGBoost: one task in parallel with all RF mini-forests ───
        CompletableFuture<Void> xgbFuture = includeXgb ? CompletableFuture.runAsync(() -> {
            long t = System.currentTimeMillis();
            log.info("  │  [XGB] ▶ Started  │  {} rounds  │  {} threads  │  thread: '{}'",
                    xgbNumRounds, effectiveXgbThreads, Thread.currentThread().getName());
            try {
                Model<Label> xgb = trainXGBoost(finalDataset, effectiveXgbThreads);
                xgboostModel.set(xgb);
                Map<String, Double> importances = extractFeatureImportances(xgb);
                xgbFeatureImportances.set(importances);
                trainedModels.add("XGBoost");
                log.info("  │  [XGB] ✓ Complete  │  {}ms  │  top feature: '{}'  │  heap: {}",
                        fmt(System.currentTimeMillis() - t), topFeature(importances), heapInfo());
                logTopFeatures("[XGB]   ", importances);
            } catch (Exception | NoClassDefFoundError | ExceptionInInitializerError e) {
                log.warn("  │  [XGB] ✗ Unavailable  │  {}ms  │  {} (native JNI not found — RF only)",
                        fmt(System.currentTimeMillis() - t), e.getMessage());
            }
        }, mlTrainingExecutor) : CompletableFuture.completedFuture(null);

        // Dashboard timer: record XGB wall-clock as soon as the XGB task settles.
        // Runs whether the model trained successfully or fell back due to JNI errors —
        // both outcomes consume time that should appear in the dashboard.
        if (includeXgb) {
            xgbFuture.whenComplete((v, t) -> meterRegistry
                    .timer(MetricsConfig.TRAINING_TIMER, "model", "xgb")
                    .record(System.currentTimeMillis() - xgbTimerStart, TimeUnit.MILLISECONDS));
        }

        // Wait for all RF mini-forests + XGBoost
        List<CompletableFuture<Void>> allFutures = new ArrayList<>(rfFutures);
        allFutures.add(xgbFuture);
        try {
            CompletableFuture.allOf(allFutures.toArray(new CompletableFuture[0])).join();
        } finally {
            heartbeat.shutdownNow();
            // Publish the completed ensemble atomically. Up to this point
            // predict() has been serving the previous (pre-training) snapshot.
            publishedForests.set(List.copyOf(rfMiniForests));
            trainingInProgress.set(false);
        }

        long trainMs = System.currentTimeMillis() - trainStart;

        // Aggregate RF results
        if (!rfMiniForests.isEmpty()) {
            trainedModels.add("RandomForest");
            Map<String, Double> avgImportances = averageImportances(rfMiniForests);
            rfFeatureImportances.set(avgImportances);
            log.info("  │");
            log.info("  │  [RF] ✓ All {} mini-forests complete  │  {} total trees  │  top feature: '{}'",
                    rfMiniForests.size(), rfMiniForests.size() * treesPerForest, topFeature(avgImportances));
            logTopFeatures("[RF]    ", avgImportances);
        }

        log.info("  │");
        log.info("  └── STAGE 3/3 complete  │  {}ms wall clock  │  {} model(s) trained",
                fmt(trainMs), trainedModels.size());

        long totalMs = System.currentTimeMillis() - wallStart;

        log.info("");
        printBanner("ML Training Complete");
        log.info("  Models trained  : {}", trainedModels);
        log.info("  Dataset         : {} examples  ({} fraud + {} legit)",
                fmt(dataset.size()), fmt(fraudCount), fmt(legitCount));
        log.info("  Fraud rate      : {}", pct(fraudRate));
        log.info("  Stage 1 (load)  : {}ms", fmt(loadMs));
        log.info("  Stage 3 (train) : {}ms  ← {} RF mini-forests + XGBoost in parallel",
                fmt(trainMs), rfParallelism);
        log.info("  Total elapsed   : {}ms  (~{}min)", fmt(totalMs), totalMs / 60000);
        log.info("  JVM heap used   : {}", heapInfo());
        printBannerBottom();

        return new TrainingSummary(
                dataset.size(),
                fraudCount,
                legitCount,
                totalMs,
                List.copyOf(trainedModels)
        );
    }

    public MLPredictionResult predict(Transaction transaction, TransactionFeatures features) {
        // Read from the atomically-published ensemble — always a complete
        // snapshot, never a partial rebuild. Concurrent training populates
        // rfMiniForests (working buffer) and only publishes once all
        // mini-forests are present.
        List<Model<Label>> forests = publishedForests.get();
        Model<Label> xgb = xgboostModel.get();

        if (forests.isEmpty() && xgb == null) {
            log.warn("No ML models available — returning unavailable result");
            return MLPredictionResult.unavailable();
        }

        Example<Label> example = featureTransformer.toPredictionExample(transaction, features);

        boolean haveRf  = !forests.isEmpty();
        boolean haveXgb = xgb != null;

        if (ensembleEnabled && haveRf && haveXgb) {
            return predictEnsemble(example, forests, xgb);
        }
        if (haveRf) {
            return predictRF(example, forests);
        }
        return predictSingle("XGBoost", example, xgb, xgbFeatureImportances.get());
    }

    public boolean isModelAvailable() {
        // Read from the published snapshot so a training-in-flight doesn't
        // briefly flip this to true when only the first mini-forest has
        // finished. Mirrors the predict() read path.
        return !publishedForests.get().isEmpty() || xgboostModel.get() != null;
    }

    public void saveModels(Path directory) throws IOException {
        Files.createDirectories(directory);

        // Save each RF mini-forest as random-forest-N.model
        List<Model<Label>> forests = new ArrayList<>(rfMiniForests);
        for (int i = 0; i < forests.size(); i++) {
            Path rfPath = directory.resolve("random-forest-" + i + ".model");
            try (ObjectOutputStream oos = new ObjectOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(rfPath)))) {
                oos.writeObject(forests.get(i));
            }
            log.info("RF mini-forest {} saved to {}", i, rfPath);
        }

        Model<Label> xgb = xgboostModel.get();
        if (xgb != null) {
            Path xgbPath = directory.resolve("xgboost.model");
            try (ObjectOutputStream oos = new ObjectOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(xgbPath)))) {
                oos.writeObject(xgb);
            }
            log.info("XGBoost model saved to {}", xgbPath);
        }
    }

    @Retryable(
            maxAttempts = 3,
            backoff = @Backoff(delay = 500, multiplier = 2.0),
            retryFor = IOException.class
    )
    @SuppressWarnings("unchecked")
    public void loadModels(Path directory) throws IOException {
        // Audit C-3: gate predictions during the clear+repopulate window.
        trainingInProgress.set(true);
        try {
            rfMiniForests.clear();

            // Load mini-forests: random-forest-0.model, random-forest-1.model, ...
            // Also supports old single-model format: random-forest.model
            for (int i = 0; ; i++) {
                Path rfPath = directory.resolve("random-forest-" + i + ".model");
                if (!Files.exists(rfPath)) {
                    if (i == 0) {
                        // Try legacy single-forest format
                        rfPath = directory.resolve("random-forest.model");
                        if (!Files.exists(rfPath)) break;
                    } else {
                        break;
                    }
                }
                try (ObjectInputStream ois = new ObjectInputStream(
                        new BufferedInputStream(Files.newInputStream(rfPath)))) {
                    Model<Label> rf = (Model<Label>) ois.readObject();
                    rfMiniForests.add(rf);
                    log.info("RF mini-forest {} loaded from {}", i, rfPath);
                } catch (ClassNotFoundException e) {
                    throw new IOException("Failed to deserialize RF model " + i, e);
                }
            }

            if (!rfMiniForests.isEmpty()) {
                rfFeatureImportances.set(averageImportances(rfMiniForests));
            }

            Path xgbPath = directory.resolve("xgboost.model");
            if (Files.exists(xgbPath)) {
                try (ObjectInputStream ois = new ObjectInputStream(
                        new BufferedInputStream(Files.newInputStream(xgbPath)))) {
                    Model<Label> xgb = (Model<Label>) ois.readObject();
                    xgboostModel.set(xgb);
                    xgbFeatureImportances.set(extractFeatureImportances(xgb));
                    log.info("XGBoost model loaded from {}", xgbPath);
                } catch (ClassNotFoundException e) {
                    throw new IOException("Failed to deserialize XGBoost model", e);
                }
            }
            // Publish after both RF and XGB are fully loaded — predict() served
            // the prior snapshot (or an empty one on first boot) during this
            // window and never saw a partial model set.
            publishedForests.set(List.copyOf(rfMiniForests));
        } finally {
            trainingInProgress.set(false);
        }
    }

    // ================================================================
    // Internal: data loading
    // ================================================================

    private MutableDataset<Label> loadTrainingData() {
        LabelFactory labelFactory = new LabelFactory();
        MutableDataset<Label> dataset = new MutableDataset<>(
                new SimpleDataSourceProvenance("finguard-transactions", labelFactory),
                labelFactory
        );

        List<Example<Label>> legitReservoir = new ArrayList<>(
                Math.min(maxLegitTrainingSamples, 1 << 18));
        long legitSeen  = 0;
        long fraudAdded = 0;
        long lastId     = 0;
        int  batchNum   = 0;

        long estimatedTotal  = estimateTrainingRowCount();
        long estimatedBatches = estimatedTotal > 0
                ? Math.max(1, estimatedTotal / trainingBatchSize) : -1;

        log.info("  │  Sampling   : ALL fraud + reservoir({}) legit",
                fmt(maxLegitTrainingSamples));
        if (estimatedTotal > 0) {
            log.info("  │  Estimated  : ~{} rows  →  ~{} batches of {}",
                    fmt(estimatedTotal), estimatedBatches, fmt(trainingBatchSize));
        } else {
            log.info("  │  Estimated  : unknown (pg_class estimate unavailable)");
        }
        log.info("  │");

        while (true) {
            long batchStart = System.currentTimeMillis();

            List<TrainingRow> batch = jdbcTemplate.query(
                    TRAINING_SQL,
                    (rs, rowNum) -> new TrainingRow(
                            rs.getLong("transaction_id"),
                            rs.getBoolean("is_fraud"),
                            rs.getDouble("amount"),
                            rs.getDouble("amount_zscore"),
                            rs.getInt("tx_velocity_1h"),
                            rs.getInt("tx_velocity_24h"),
                            rs.getDouble("avg_amount_7d"),
                            rs.getDouble("amount_ratio_to_avg"),
                            rs.getDouble("balance_change_ratio"),
                            rs.getBoolean("is_new_receiver"),
                            rs.getInt("receiver_diversity_7d"),
                            rs.getInt("hour_of_day"),
                            rs.getInt("day_of_week"),
                            rs.getBoolean("is_round_amount"),
                            rs.getBoolean("is_high_risk_type")
                    ),
                    lastId, trainingBatchSize
            );

            if (batch.isEmpty()) break;

            for (TrainingRow row : batch) {
                Example<Label> example = featureTransformer.toTrainingExampleFromValues(
                        row.isFraud(), row.amount(),
                        row.amountZscore(), row.txVelocity1h(), row.txVelocity24h(),
                        row.avgAmount7d(), row.amountRatioToAvg(), row.balanceChangeRatio(),
                        row.isNewReceiver(), row.receiverDiversity7d(),
                        row.hourOfDay(), row.dayOfWeek(),
                        row.isRoundAmount(), row.isHighRiskType()
                );

                if (row.isFraud()) {
                    dataset.add(example);
                    fraudAdded++;
                } else {
                    if (legitSeen < maxLegitTrainingSamples) {
                        legitReservoir.add(example);
                    } else {
                        long slot = ThreadLocalRandom.current().nextLong(legitSeen + 1);
                        if (slot < maxLegitTrainingSamples) {
                            legitReservoir.set((int) slot, example);
                        }
                    }
                    legitSeen++;
                }
            }

            lastId    = batch.get(batch.size() - 1).transactionId();
            batchNum++;
            long batchMs   = System.currentTimeMillis() - batchStart;
            long rowsPerSec = batchMs > 0 ? batch.size() * 1000L / batchMs : 0;

            String progressStr;
            if (estimatedBatches > 0) {
                double pctDone = 100.0 * batchNum / estimatedBatches;
                long etaSec = batchMs > 0 ? ((estimatedBatches - batchNum) * batchMs) / 1000 : 0;
                progressStr = String.format("%.1f%%  ETA ~%ds", pctDone, etaSec);
            } else {
                progressStr = "?%";
            }

            log.info("  │  Batch {}/{}  │  {} rows  │  {}ms  │  {}/s  │  {}",
                    batchNum,
                    estimatedBatches > 0 ? estimatedBatches : "?",
                    fmt(batch.size()), batchMs, fmt(rowsPerSec), progressStr);
            log.info("  │    fraud: {}  │  legit seen: {}  │  reservoir: {}/{}",
                    fmt(fraudAdded), fmt(legitSeen),
                    fmt(Math.min(legitSeen, maxLegitTrainingSamples)),
                    fmt(maxLegitTrainingSamples));
        }

        for (Example<Label> ex : legitReservoir) dataset.add(ex);

        log.info("  │");
        log.info("  │  ── Summary ────────────────────────────────────────────────────");
        log.info("  │  Batches          : {}", batchNum);
        log.info("  │  DB rows scanned  : ~{}", fmt(legitSeen + fraudAdded));
        log.info("  │  Fraud examples   : {}  (100% kept)", fmt(fraudAdded));
        log.info("  │  Legit examples   : {}  (sampled from {}  keep rate: {})",
                fmt(legitReservoir.size()), fmt(legitSeen),
                legitSeen > 0 ? pct(100.0 * legitReservoir.size() / legitSeen) : "n/a");
        log.info("  │  Total in dataset : {}", fmt(fraudAdded + legitReservoir.size()));
        log.info("  │  Heap after load  : {}", heapInfo());
        return dataset;
    }

    private long estimateTrainingRowCount() {
        try {
            Long estimate = jdbcTemplate.queryForObject(ESTIMATE_COUNT_SQL, Long.class);
            return estimate != null && estimate > 0 ? estimate : -1L;
        } catch (Exception e) {
            log.debug("pg_class estimate unavailable: {}", e.getMessage());
            return -1L;
        }
    }

    record TrainingRow(
            long transactionId, boolean isFraud, double amount,
            double amountZscore, int txVelocity1h, int txVelocity24h,
            double avgAmount7d, double amountRatioToAvg, double balanceChangeRatio,
            boolean isNewReceiver, int receiverDiversity7d,
            int hourOfDay, int dayOfWeek,
            boolean isRoundAmount, boolean isHighRiskType
    ) {}

    // ================================================================
    // Internal: model training
    // ================================================================

    /**
     * Train a single RF mini-forest.
     *
     * @param dataset     the training dataset
     * @param numTrees    number of trees in this mini-forest
     * @param seedOffset  added to base seed so each mini-forest uses different random state
     */
    private Model<Label> trainSingleForest(Dataset<Label> dataset, int numTrees, int seedOffset) {
        int numFeatures = FeatureTransformer.FEATURE_NAMES.size();
        float fraction  = (float) Math.sqrt(numFeatures) / numFeatures;

        CARTClassificationTrainer baseTrainer = new CARTClassificationTrainer(
                rfMaxDepth,
                fraction,
                1L + seedOffset  // Different seed per mini-forest = more diverse trees
        );
        RandomForestTrainer<Label> rfTrainer = new RandomForestTrainer<>(
                baseTrainer, new VotingCombiner(), numTrees);
        return rfTrainer.train(dataset);
    }

    private Model<Label> trainXGBoost(Dataset<Label> dataset, int numThreads) {
        XGBoostClassificationTrainer xgbTrainer =
                new XGBoostClassificationTrainer(xgbNumRounds, numThreads, false);
        return xgbTrainer.train(dataset);
    }

    // ================================================================
    // Internal: prediction
    // ================================================================

    private MLPredictionResult predictRF(Example<Label> example, List<Model<Label>> forests) {
        double avgScore = forests.stream()
                .mapToDouble(m -> getFraudProbability(m.predict(example)))
                .average()
                .orElse(0.0);
        return new MLPredictionResult(
                "RandomForest",
                avgScore,
                avgScore >= riskScoreThreshold,
                rfFeatureImportances.get()
        );
    }

    private MLPredictionResult predictSingle(String modelName, Example<Label> example,
                                              Model<Label> model, Map<String, Double> importances) {
        Prediction<Label> prediction = model.predict(example);
        double fraudScore = getFraudProbability(prediction);
        return new MLPredictionResult(
                modelName, fraudScore, fraudScore >= riskScoreThreshold, importances);
    }

    private MLPredictionResult predictEnsemble(Example<Label> example,
                                                List<Model<Label>> forests, Model<Label> xgb) {
        double rfScore = forests.stream()
                .mapToDouble(m -> getFraudProbability(m.predict(example)))
                .average().orElse(0.0);
        double xgbScore = getFraudProbability(xgb.predict(example));

        if (rfScore >= xgbScore) {
            return new MLPredictionResult("Ensemble(RandomForest)", rfScore,
                    rfScore >= riskScoreThreshold, rfFeatureImportances.get());
        }
        return new MLPredictionResult("Ensemble(XGBoost)", xgbScore,
                xgbScore >= riskScoreThreshold, xgbFeatureImportances.get());
    }

    private double getFraudProbability(Prediction<Label> prediction) {
        Map<String, Label> outputScores = prediction.getOutputScores();
        Label fraudLabel = outputScores.get("FRAUD");
        if (fraudLabel != null) return fraudLabel.getScore();
        return prediction.getOutput().getLabel().equals("FRAUD") ? 1.0 : 0.0;
    }

    // ================================================================
    // Internal: feature importance
    // ================================================================

    private Map<String, Double> averageImportances(List<Model<Label>> models) {
        if (models.isEmpty()) return Map.of();
        Map<String, Double> summed = new LinkedHashMap<>();
        for (Model<Label> m : models) {
            extractFeatureImportances(m).forEach(
                    (k, v) -> summed.merge(k, v, Double::sum));
        }
        int n = models.size();
        summed.replaceAll((k, v) -> v / n);
        return Collections.unmodifiableMap(summed);
    }

    private Map<String, Double> extractFeatureImportances(Model<Label> model) {
        try {
            Map<String, List<Pair<String, Double>>> topFeatures =
                    model.getTopFeatures(FeatureTransformer.FEATURE_NAMES.size());
            if (topFeatures != null && !topFeatures.isEmpty()) {
                Map<String, Double> importances = new LinkedHashMap<>();
                for (List<Pair<String, Double>> pairList : topFeatures.values()) {
                    for (Pair<String, Double> pair : pairList) {
                        importances.merge(pair.getA(), pair.getB(), Double::sum);
                    }
                }
                double total = importances.values().stream().mapToDouble(Double::doubleValue).sum();
                if (total > 0) importances.replaceAll((k, v) -> v / total);
                return Collections.unmodifiableMap(importances);
            }
        } catch (Exception e) {
            log.debug("Could not extract feature importances: {}", e.getMessage());
        }
        return Map.of();
    }

    private static String topFeature(Map<String, Double> importances) {
        return importances.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("unknown");
    }

    private static void logTopFeatures(String prefix, Map<String, Double> importances) {
        if (importances.isEmpty()) return;
        importances.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(5)
                .forEach(e -> log.info("  │  {}  {}: {}",
                        prefix, e.getKey(), String.format("%.4f", e.getValue())));
    }

    // ================================================================
    // Formatting helpers
    // ================================================================

    private static String fmt(long n)    { return String.format("%,d", n); }
    private static String pct(double v)  { return String.format("%.2f%%", v); }

    private static String heapInfo() {
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long max  = rt.maxMemory() / (1024 * 1024);
        return used + " MB / " + max + " MB";
    }

    private static void printBanner(String title) {
        String line = "─".repeat(55);
        log.info("  ┌{}┐", line);
        int pad = Math.max(0, (55 - title.length()) / 2);
        log.info("  │{}{}{}│", " ".repeat(pad), title, " ".repeat(55 - pad - title.length()));
        log.info("  └{}┘", line);
    }

    private static void printBannerBottom() {
        log.info("  {}", "─".repeat(57));
    }

    // ================================================================
    // Training summary record
    // ================================================================

    public record TrainingSummary(
            long totalExamples,
            long fraudCount,
            long legitCount,
            long trainingTimeMs,
            List<String> trainedModels
    ) {}
}
