package dev.finguard.domain.repository;

import dev.finguard.domain.enums.DatasetSource;
import dev.finguard.domain.model.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByExternalId(String externalId);

    /**
     * Find all external IDs that already exist in the database from a given set.
     * Used for bulk duplicate checking during CSV import (avoids N+1 per-row queries).
     */
    @Query("SELECT t.externalId FROM Transaction t WHERE t.externalId IN :externalIds")
    Set<String> findExistingExternalIds(@Param("externalIds") java.util.Collection<String> externalIds);

    Page<Transaction> findByDatasetSource(DatasetSource source, Pageable pageable);

    Page<Transaction> findByIsFraudTrue(Pageable pageable);

    @Query("SELECT t FROM Transaction t WHERE t.senderAccount = :account " +
           "AND t.timestamp BETWEEN :from AND :to ORDER BY t.timestamp DESC")
    List<Transaction> findBySenderAccountAndTimestampBetween(
            @Param("account") String account,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(DISTINCT t.receiverAccount) FROM Transaction t " +
           "WHERE t.senderAccount = :account AND t.timestamp BETWEEN :from AND :to")
    int countDistinctReceiversByAccount(
            @Param("account") String account,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Query("SELECT AVG(t.amount) FROM Transaction t " +
           "WHERE t.senderAccount = :account AND t.timestamp BETWEEN :from AND :to")
    BigDecimal avgAmountByAccount(
            @Param("account") String account,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.datasetSource = :source")
    long countByDatasetSource(@Param("source") DatasetSource source);

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.datasetSource = :source AND t.isFraud = true")
    long countFraudByDatasetSource(@Param("source") DatasetSource source);

    /** Count test-set transactions only (is_training_set = false) for a given dataset. */
    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.datasetSource = :source AND t.isTrainingSet = false")
    long countTestSetByDatasetSource(@Param("source") DatasetSource source);

    /** Count test-set fraud transactions only (is_training_set = false) for a given dataset. */
    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.datasetSource = :source AND t.isFraud = true AND t.isTrainingSet = false")
    long countFraudTestSetByDatasetSource(@Param("source") DatasetSource source);

    boolean existsBySenderAccountAndReceiverAccount(String senderAccount, String receiverAccount);

    /**
     * Check if a sender has ever sent to this receiver before a given timestamp.
     * Used for the isNewReceiver feature — avoids loading the sender's entire history.
     */
    @Query("SELECT CASE WHEN COUNT(t) > 0 THEN true ELSE false END FROM Transaction t " +
           "WHERE t.senderAccount = :sender AND t.receiverAccount = :receiver AND t.timestamp < :before")
    boolean existsPriorTransfer(@Param("sender") String senderAccount,
                                @Param("receiver") String receiverAccount,
                                @Param("before") LocalDateTime before);

    @Query(value = "SELECT COALESCE(STDDEV_POP(t.amount), 0) FROM transactions t " +
           "WHERE t.sender_account = :account AND t.timestamp BETWEEN :from AND :to",
           nativeQuery = true)
    BigDecimal stddevAmountByAccount(
            @Param("account") String account,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);


    @Query("SELECT t FROM Transaction t WHERE t.id > :afterId ORDER BY t.id ASC")
    List<Transaction> findAllAfterId(@Param("afterId") Long afterId, Pageable pageable);

    /**
     * Test-set scoped variant of {@link #findAllAfterId}, used by the detection
     * pipeline.
     *
     * <p>Rationale: every metric query in this codebase (precision, recall,
     * F1, FPR, AUC, CAKR, hallucination rate) filters to {@code is_training_set
     * = false}, so any alert produced for a training-set row is silently
     * discarded at metric time. Scoring those rows costs ~80% of the detection
     * wall-clock on PaySim (5 M training of 6.3 M total) for zero observable
     * benefit. This method skips them, giving a ~5× speedup with bit-identical
     * metrics — see thesis §Evaluation, "Held-out test partition" subsection.</p>
     *
     * <p>Standard ML evaluation practice (Hastie/Tibshirani, "Elements of
     * Statistical Learning", §7.10): score the held-out set, not data the model
     * has seen during training.</p>
     */
    @Query("SELECT t FROM Transaction t WHERE t.id > :afterId AND t.isTrainingSet = false ORDER BY t.id ASC")
    List<Transaction> findTestSetAfterId(@Param("afterId") Long afterId, Pageable pageable);

    /**
     * Total number of test-set transactions, regardless of dataset source.
     * Used for the detection-pipeline progress denominator.
     */
    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.isTrainingSet = false")
    long countTestSet();

    @Query("SELECT t FROM Transaction t WHERE t.id > :afterId AND NOT EXISTS (SELECT tf FROM TransactionFeatures tf WHERE tf.transaction = t) ORDER BY t.id ASC")
    List<Transaction> findWithoutFeaturesAfterId(@Param("afterId") Long afterId, Pageable pageable);

    /**
     * Count transactions for a sender in 24h and 1h windows in a single round-trip.
     * Replaces loading full Transaction objects just to derive two counts.
     *
     * Returns Object[2]: [0] Long count_24h, [1] Long count_1h (within oneHourBefore..to).
     * Uses PostgreSQL COUNT(*) FILTER syntax (available since PG 9.4).
     */
    @Query(value = """
            SELECT
                COUNT(*) AS count_24h,
                COUNT(*) FILTER (WHERE timestamp >= :oneHourBefore) AS count_1h
            FROM transactions
            WHERE sender_account = :account
            AND timestamp BETWEEN :from AND :to
            """, nativeQuery = true)
    List<Object[]> findVelocityCounts(@Param("account") String account,
                                @Param("from") LocalDateTime from,
                                @Param("to") LocalDateTime to,
                                @Param("oneHourBefore") LocalDateTime oneHourBefore);

    /**
     * Compute avg amount, stddev, and receiver diversity for a sender in a single round-trip.
     * Replaces three separate queries that all hit the same 7-day window.
     *
     * Returns Object[3]: [0] BigDecimal avg_amount (COALESCE, never null),
     *                    [1] Double stddev_amount (COALESCE, never null),
     *                    [2] Long receiver_diversity.
     */
    @Query(value = """
            SELECT
                COALESCE(AVG(amount), 0)              AS avg_amount,
                COALESCE(STDDEV_POP(amount), 0)       AS stddev_amount,
                COUNT(DISTINCT receiver_account)       AS receiver_diversity
            FROM transactions
            WHERE sender_account = :account
            AND timestamp BETWEEN :from AND :to
            """, nativeQuery = true)
    List<Object[]> findSenderStats7d(@Param("account") String account,
                               @Param("from") LocalDateTime from,
                               @Param("to") LocalDateTime to);

    long countByIsFraudTrue();

    /** Count of rows assigned to the training set (is_training_set = true). Used by the data-quality sanity-check page. */
    long countByIsTrainingSetTrue();

    @Query("SELECT t.transactionType, COUNT(t) FROM Transaction t GROUP BY t.transactionType")
    List<Object[]> countByTransactionType();

    @Query("SELECT AVG(t.amount) FROM Transaction t")
    BigDecimal avgAmount();

    @Query("SELECT AVG(t.amount) FROM Transaction t WHERE t.isFraud = true")
    BigDecimal avgFraudAmount();

    /**
     * Flexible filter query supporting optional source, fraud, type, and account search.
     * All parameters are optional — pass null / false to skip each condition.
     *
     * <p>Note: {@code :search} is matched as "empty string means no filter" (not null)
     * because Hibernate 6.6 can mis-infer the type of a null String parameter inside
     * a {@code CONCAT(...)} expression against PostgreSQL, causing
     * {@code function lower(bytea) does not exist}. Callers must pass {@code ""} when
     * they don't want to filter by search.</p>
     */
    @Query("SELECT t FROM Transaction t WHERE " +
           "(:fraudOnly = false OR t.isFraud = true) AND " +
           "(:source IS NULL OR t.datasetSource = :source) AND " +
           "(:type IS NULL OR t.transactionType = :type) AND " +
           "(:search = '' OR " +
           " LOWER(t.senderAccount) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           " LOWER(t.receiverAccount) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Transaction> findByFilters(
            @Param("source") DatasetSource source,
            @Param("fraudOnly") boolean fraudOnly,
            @Param("type") dev.finguard.domain.enums.TransactionType type,
            @Param("search") String search,
            Pageable pageable);


    /** Single-statement bulk delete — O(1) SQL, no entity loading. */
    @Modifying
    @Transactional
    @Query("DELETE FROM Transaction t")
    int bulkDeleteAll();

    // ========================================================================
    // Audit A-1: real k-fold cross-validation support
    // ========================================================================

    /**
     * Assign every transaction a deterministic fold number in {@code [0, k-1]}.
     *
     * <p>Uses PostgreSQL {@code HASHTEXT(id)} to produce a stable, well-distributed
     * fold assignment that survives re-runs. Called once per k-fold experiment
     * before iterating folds.</p>
     *
     * @param k number of folds (typical: 5 or 10)
     * @return number of rows updated
     */
    /**
     * Assign fold numbers 0..k-1 to <strong>test-set rows only</strong>. Training-set
     * rows are cleared (fold = NULL) so they can never contaminate a fold-scoped
     * metric query.
     *
     * <p>Audit correctness: previously this assigned folds to <em>every</em> row,
     * including the 80% training set. The ML model was trained on those rows, so
     * per-fold F1 reported in-sample performance. Restricting folds to the test
     * set guarantees each fold is a genuine held-out slice of size ~test_size/k.</p>
     *
     * @param k number of folds (typical: 5 or 10)
     * @return number of rows updated
     */
    @Modifying
    @Transactional
    @Query(value = "UPDATE transactions " +
                   "SET fold = CASE WHEN is_training_set = false " +
                   "                THEN MOD(ABS(HASHTEXT(CAST(id AS text))), :k) " +
                   "                ELSE NULL " +
                   "           END",
           nativeQuery = true)
    int assignFolds(@Param("k") int k);

    /**
     * Count test-set transactions in a given fold. Audit correctness: the fold
     * column is only populated on test-set rows (see {@link #assignFolds}), but
     * the explicit {@code is_training_set = false} predicate here guards against
     * future changes that might repopulate the fold column more broadly.
     */
    @Query("SELECT COUNT(t) FROM Transaction t " +
           "WHERE t.datasetSource = :source AND t.fold = :fold AND t.isTrainingSet = false")
    long countByDatasetSourceAndFold(@Param("source") DatasetSource source, @Param("fold") int fold);

    /** Count fraud rows in a given fold (test-set only, same rationale as {@link #countByDatasetSourceAndFold}). */
    @Query("SELECT COUNT(t) FROM Transaction t " +
           "WHERE t.datasetSource = :source AND t.fold = :fold " +
           "AND t.isFraud = true AND t.isTrainingSet = false")
    long countFraudByDatasetSourceAndFold(@Param("source") DatasetSource source, @Param("fold") int fold);

    /**
     * Single-pass per-fold totals: one row per fold with the total row count
     * and the fraud row count. Replaces {@code k × 2 = 10} individual {@code COUNT(*)}
     * calls per config in the k-fold loop.
     *
     * <p>Returns {@code [fold (Integer), total_rows (Long), fraud_rows (Long)]},
     * limited to test-set rows with a non-null fold.</p>
     */
    @Query("SELECT t.fold, COUNT(t), " +
           "       SUM(CASE WHEN t.isFraud = true THEN 1 ELSE 0 END) " +
           "FROM Transaction t " +
           "WHERE t.datasetSource = :source AND t.isTrainingSet = false AND t.fold IS NOT NULL " +
           "GROUP BY t.fold")
    List<Object[]> perFoldCountsByDataset(@Param("source") DatasetSource source);

    // ========================================================================
    // AUC sampling — stratified test-set samples for AUC-ROC / AUC-PR (thesis §3.4.1)
    // ========================================================================

    /**
     * All fraud transactions in the test set for a dataset. Used as the positive
     * stratum of the AUC sample — keeping every positive maximises the statistical
     * power of the rare-event metric (fraud is ~0.13% in PaySim).
     */
    @Query("SELECT t FROM Transaction t " +
           "WHERE t.datasetSource = :source AND t.isTrainingSet = false AND t.isFraud = true")
    List<Transaction> findFraudTestSet(@Param("source") DatasetSource source);

    /** Same as {@link #findFraudTestSet} but scoped to one fold. */
    @Query("SELECT t FROM Transaction t " +
           "WHERE t.datasetSource = :source AND t.isTrainingSet = false " +
           "AND t.isFraud = true AND t.fold = :fold")
    List<Transaction> findFraudTestSetByFold(@Param("source") DatasetSource source,
                                             @Param("fold") int fold);

    /**
     * Random sample of <em>legitimate</em> test-set transactions. Native query because
     * {@code ORDER BY random()} isn't expressible in JPQL. PostgreSQL hashes uniformly
     * so the sample is unbiased. {@code LIMIT :n} caps cost — for 1.26M test rows
     * with n=20000, plan is a sequential scan + sort + limit (~3 s on a thesis laptop).
     */
    @Query(value = "SELECT * FROM transactions " +
                   "WHERE dataset_source = :#{#source.name()} " +
                   "AND is_training_set = false AND is_fraud = false " +
                   "ORDER BY random() LIMIT :n",
           nativeQuery = true)
    List<Transaction> findLegitTestSetRandomSample(@Param("source") DatasetSource source,
                                                    @Param("n") int n);

    /** Same as {@link #findLegitTestSetRandomSample} but scoped to one fold. */
    @Query(value = "SELECT * FROM transactions " +
                   "WHERE dataset_source = :#{#source.name()} " +
                   "AND is_training_set = false AND is_fraud = false AND fold = :fold " +
                   "ORDER BY random() LIMIT :n",
           nativeQuery = true)
    List<Transaction> findLegitTestSetRandomSampleByFold(@Param("source") DatasetSource source,
                                                          @Param("fold") int fold,
                                                          @Param("n") int n);
}
