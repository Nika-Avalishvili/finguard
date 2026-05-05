package dev.finguard.ingestion.service;

import dev.finguard.config.MetricsConfig;
import dev.finguard.detection.service.PipelineStatusTracker;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Finalizes the train/test split after a CSV import.
 *
 * <p>Freshly imported rows default to {@code is_training_set = true} (see
 * {@code IngestionService.COPY_SQL} and {@code Transaction.isTrainingSet}) so that
 * ML training works immediately after import. To enable honest evaluation,
 * {@link #finalizeTemporalSplit(double)} carves off the newest portion of the
 * dataset as a temporal test set — the same logic as Liquibase changelog 016,
 * callable on demand after every re-import.</p>
 *
 * <p>Uses a percentile-based cutoff so the split ratio is exact regardless of
 * the dataset's temporal distribution. To minimize WAL pressure during the
 * UPDATE on multi-million-row tables we drop the composite
 * {@code idx_transactions_training_split} index before the rewrite and recreate
 * it afterwards — a known trick for bulk column updates that touch indexed
 * columns.</p>
 */
@Service
public class TrainTestSplitService {

    private static final Logger log = LoggerFactory.getLogger(TrainTestSplitService.class);

    /** Name of the composite index we temporarily drop to avoid per-row index churn. */
    private static final String SPLIT_INDEX = "idx_transactions_training_split";

    /** Total progress steps reported to the UI — keep in sync with the phases below. */
    private static final int TOTAL_PHASES = 6;

    private final JdbcTemplate jdbcTemplate;
    private final PipelineStatusTracker statusTracker;
    private final MeterRegistry meterRegistry;

    public TrainTestSplitService(JdbcTemplate jdbcTemplate,
                                  PipelineStatusTracker statusTracker,
                                  MeterRegistry meterRegistry) {
        this.jdbcTemplate  = jdbcTemplate;
        this.statusTracker = statusTracker;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Synchronous variant — kept for back-compat with tests and callers that want to
     * block until completion (e.g., integration tests that assert split counts).
     * Publishes <b>no</b> progress (jobId is {@code null}).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SplitResult finalizeTemporalSplit(double trainingRatio) {
        return finalizeTemporalSplit(trainingRatio, null);
    }

    /**
     * Job-aware overload. Publishes progress snapshots to
     * {@link PipelineStatusTracker} between phases so the UI can render a live
     * banner. Long-running phases (UPDATE on 1.27 M rows, index rebuild) are
     * the ones users need visibility into — each is bounded by an explicit
     * {@link PipelineStatusTracker#updateProgress} call.
     *
     * @param trainingRatio fraction of rows (by timestamp) to mark as training; must be in {@code (0, 1)}
     * @param jobId         optional tracker ID; when non-null, publishes progress to {@code GET /api/v1/evaluation/status/{jobId}}
     * @return summary containing cutoff timestamp and resulting training/test counts
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SplitResult finalizeTemporalSplit(double trainingRatio, String jobId) {
        if (trainingRatio <= 0.0 || trainingRatio >= 1.0) {
            throw new IllegalArgumentException(
                    "trainingRatio must be in (0, 1); got " + trainingRatio);
        }

        // Dashboard panel: timing for the "Pipeline Stage Timings → Train/Test Split" row.
        Instant splitStart = Instant.now();
        int step = 0;
        publish(jobId, "Counting transactions", ++step, 0);

        long totalRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions", Long.class);
        if (totalRows == 0) {
            log.warn("finalizeTemporalSplit: no transactions in DB — nothing to do");
            return new SplitResult(null, 0L, 0L, 0L);
        }

        publish(jobId, "Computing percentile cutoff (ratio=" + trainingRatio + ")",
                ++step, totalRows);

        // Percentile-based cutoff: exactly trainingRatio of rows fall below it.
        LocalDateTime cutoff = jdbcTemplate.queryForObject(
                "SELECT percentile_disc(?) WITHIN GROUP (ORDER BY timestamp) FROM transactions",
                LocalDateTime.class, trainingRatio);
        if (cutoff == null) {
            log.warn("finalizeTemporalSplit: cutoff came back null (unexpected for non-empty table)");
            return new SplitResult(null, totalRows, 0L, 0L);
        }
        log.info("finalizeTemporalSplit: ratio={}, cutoff={}, total={} rows",
                trainingRatio, cutoff, totalRows);

        // Async WAL commit shaves a large slice off the fsync cost on single-disk
        // setups; safe for a thesis/dev app — worst case of a crash is re-running
        // this endpoint.
        jdbcTemplate.execute("SET LOCAL synchronous_commit = OFF");

        publish(jobId, "Dropping composite index (faster UPDATE)", ++step, totalRows);
        jdbcTemplate.execute("DROP INDEX IF EXISTS " + SPLIT_INDEX);

        publish(jobId, "Flipping newest rows → test set", ++step, totalRows);

        // Flip newest (1 - ratio) to test; everything older stays training.
        // Two narrow UPDATEs (test→false, train→true) are cheaper than one full-scan
        // UPDATE since each only touches rows whose value actually changes.
        int flippedToTest = jdbcTemplate.update(
                "UPDATE transactions SET is_training_set = FALSE " +
                "WHERE is_training_set = TRUE AND timestamp >= ?", cutoff);
        int flippedToTrain = jdbcTemplate.update(
                "UPDATE transactions SET is_training_set = TRUE " +
                "WHERE is_training_set = FALSE AND timestamp < ?", cutoff);
        log.info("finalizeTemporalSplit: flipped {} → test, {} → training",
                flippedToTest, flippedToTrain);

        publish(jobId,
                "Rebuilding composite index (is_training_set, is_fraud, dataset_source)",
                ++step, totalRows);
        jdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS " + SPLIT_INDEX +
                " ON transactions (is_training_set, is_fraud, dataset_source)");

        publish(jobId, "Counting final totals", ++step, totalRows);
        long trainingCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE is_training_set = TRUE", Long.class);
        long testCount = totalRows - trainingCount;
        log.info("finalizeTemporalSplit: done — training={}, test={}", trainingCount, testCount);

        meterRegistry.timer(MetricsConfig.STAGE_TIMER, "stage", "train_test_split")
                .record(Duration.between(splitStart, Instant.now()));

        return new SplitResult(cutoff, totalRows, trainingCount, testCount);
    }

    /**
     * Fire-and-forget async wrapper. Runs the split on the {@code finguard-async}
     * executor and publishes progress + completion to {@link PipelineStatusTracker}.
     * The caller is responsible for having already registered the {@code jobId}
     * via {@link PipelineStatusTracker#start}.
     */
    @Async
    public void runAsync(String jobId, double trainingRatio) {
        try {
            SplitResult r = finalizeTemporalSplit(trainingRatio, jobId);
            statusTracker.complete(jobId, r.testCount());
        } catch (Exception e) {
            log.error("Async split job {} failed: {}", jobId, e.getMessage(), e);
            statusTracker.fail(jobId, e.getMessage());
        }
    }

    /** Publish a progress snapshot if a jobId was supplied; no-op for sync callers. */
    private void publish(String jobId, String stepLabel, int step, long totalRows) {
        if (jobId == null) return;
        // We report step-based progress (n/TOTAL_PHASES) rather than rows, because
        // the UPDATE and index-rebuild phases don't emit row-level progress from
        // PostgreSQL — their wall-clock is dominated by WAL and index writes, not
        // tuples scanned.
        statusTracker.updateProgress(jobId, stepLabel, step, TOTAL_PHASES, totalRows);
    }

    /**
     * Immutable summary returned by {@link #finalizeTemporalSplit(double)}.
     *
     * @param cutoffTimestamp the percentile cutoff used; rows strictly older are training
     * @param totalRows       total row count in the transactions table
     * @param trainingCount   rows assigned to the training set (older than cutoff)
     * @param testCount       rows assigned to the test set (newer than or equal to cutoff)
     */
    public record SplitResult(LocalDateTime cutoffTimestamp,
                              long totalRows,
                              long trainingCount,
                              long testCount) {

        /** Flat JSON-friendly view for the REST response. */
        public Map<String, Object> toMap() {
            return Map.of(
                    "cutoffTimestamp", cutoffTimestamp == null ? "" : cutoffTimestamp.toString(),
                    "totalRows",      totalRows,
                    "trainingCount",  trainingCount,
                    "testCount",      testCount,
                    "trainingRatio",  totalRows == 0 ? 0.0 : (double) trainingCount / totalRows);
        }
    }
}
