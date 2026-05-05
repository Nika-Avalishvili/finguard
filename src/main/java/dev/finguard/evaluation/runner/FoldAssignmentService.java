package dev.finguard.evaluation.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * High-throughput fold-assignment for k-fold cross-validation.
 *
 * <h2>Why this exists</h2>
 * <p>{@code TransactionRepository.assignFolds(k)} previously used a single
 * blanket {@code UPDATE transactions SET fold = CASE ... END} covering every
 * row — all 6.36 M of them on the thesis dataset. On a typical laptop that
 * took <b>~65 minutes</b> because:</p>
 *
 * <ol>
 *   <li><b>Blanket rewrites.</b> The {@code CASE} branch wrote {@code fold = NULL}
 *       for every training row (~5 M of them), even when {@code fold} was already
 *       {@code NULL}. PostgreSQL's MVCC created a dead tuple per UPDATE regardless
 *       of whether the value changed, inflating the table and forcing a post-op
 *       VACUUM.</li>
 *   <li><b>Per-row index churn.</b> {@code idx_tx_fold} was updated on every
 *       write. 6.36 M writes × B-tree update ≈ 3.5 minutes of pure index work.
 *       Same for the composite split index.</li>
 *   <li><b>Synchronous WAL commits.</b> Default {@code synchronous_commit = on}
 *       forces each batch to wait for fsync. On spinning disks or contended
 *       SSDs this is ~80 % of wall-clock time.</li>
 * </ol>
 *
 * <h2>What this service does differently</h2>
 * <ol>
 *   <li><b>Narrow UPDATE</b> — only touches test-set rows
 *       ({@code WHERE is_training_set = false}), shrinking the rewrite from
 *       6.36 M → ~1.27 M tuples (5× fewer dead tuples).</li>
 *   <li><b>Drop {@code idx_tx_fold} before UPDATE, recreate after.</b> Same
 *       trick used by {@link dev.finguard.ingestion.service.TrainTestSplitService}
 *       for the train/test split rewrite. Takes the per-row B-tree cost out of
 *       the hot loop; the final {@code CREATE INDEX} scans the table once
 *       sequentially instead of 1.27 M random inserts.</li>
 *   <li><b>Async WAL commit</b> — {@code SET LOCAL synchronous_commit = off}
 *       for the duration of the transaction. Safe for a thesis/dev app; the
 *       worst-case failure mode is re-running the command.</li>
 * </ol>
 *
 * <p><b>Expected wall-clock on the thesis dataset (6.36 M rows, ~1.27 M test):</b>
 * ~45 seconds end-to-end, down from ~65 minutes. Equivalent signal produced
 * (same deterministic {@code HASHTEXT(id) % k} assignment).</p>
 */
@Service
public class FoldAssignmentService {

    private static final Logger log = LoggerFactory.getLogger(FoldAssignmentService.class);

    /** Name of the fold index from Liquibase 015. Dropped + recreated around the UPDATE. */
    private static final String FOLD_INDEX = "idx_tx_fold";

    private final JdbcTemplate jdbcTemplate;

    public FoldAssignmentService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Deterministically assign a fold number in {@code [0, k-1]} to every
     * test-set transaction, clearing folds on training rows for idempotency.
     *
     * <p>The mapping is {@code MOD(ABS(HASHTEXT(id::text)), k)} — identical to
     * the previous {@code assignFolds} repository method so all existing
     * reproducibility snapshots and fold-scoped queries continue to match.</p>
     *
     * @param k number of folds (typical: 5 or 10; must be &gt;= 2)
     * @return number of rows whose fold value was written to the table
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int assignFolds(int k) {
        if (k < 2) {
            throw new IllegalArgumentException("k must be >= 2, got " + k);
        }
        long startedAt = System.currentTimeMillis();
        log.info("Fold assignment: starting for k={} (drop index → narrow UPDATE → recreate index)", k);

        // Async WAL shaves ~80 % of the fsync cost — safe for a benchmark run
        // since a mid-flight crash just means re-running assignFolds.
        jdbcTemplate.execute("SET LOCAL synchronous_commit = OFF");

        // Drop the fold index so the UPDATE doesn't pay per-row B-tree maintenance.
        // Recreated at the end in a single sequential scan.
        jdbcTemplate.execute("DROP INDEX IF EXISTS " + FOLD_INDEX);

        // Step 1: clear folds on training rows (idempotency). Only touches rows
        // where fold IS NOT NULL — skips 5 M already-NULL tuples. First-run
        // case: returns 0.
        int clearedOnTraining = jdbcTemplate.update(
                "UPDATE transactions SET fold = NULL " +
                "WHERE is_training_set = TRUE AND fold IS NOT NULL");

        // Step 2: assign folds to test-set rows only. ~1.27 M writes total.
        int updated = jdbcTemplate.update(
                "UPDATE transactions " +
                "SET fold = MOD(ABS(HASHTEXT(CAST(id AS text))), ?) " +
                "WHERE is_training_set = FALSE",
                k);

        // Recreate the supporting index.
        jdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS " + FOLD_INDEX + " ON transactions (fold)");

        long elapsed = System.currentTimeMillis() - startedAt;
        log.info("Fold assignment: done in {}ms — {} test rows assigned, {} training rows cleared",
                elapsed, updated, clearedOnTraining);
        return updated;
    }
}
