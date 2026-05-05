package dev.finguard.domain.repository;

import dev.finguard.domain.enums.AlertStatus;
import dev.finguard.domain.enums.DetectionConfig;
import dev.finguard.domain.enums.ExplanationType;
import dev.finguard.domain.model.Alert;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Repository
public interface AlertRepository extends JpaRepository<Alert, Long> {

    Page<Alert> findByStatus(AlertStatus status, Pageable pageable);

    Page<Alert> findByDetectionConfig(DetectionConfig config, Pageable pageable);

    /**
     * Combined filter for the alerts listing page.
     *
     * <p>Either parameter can be {@code null} to "match all" on that column.
     * Avoids the pre-refactor "either status OR config, never both" bug in
     * {@code WebController.alerts}.</p>
     */
    @Query("SELECT a FROM Alert a WHERE " +
           "(:status IS NULL OR a.status = :status) AND " +
           "(:config IS NULL OR a.detectionConfig = :config)")
    Page<Alert> findByStatusAndConfig(@Param("status") AlertStatus status,
                                      @Param("config") DetectionConfig config,
                                      Pageable pageable);

    List<Alert> findByTransactionId(Long transactionId);

    @Query("SELECT a FROM Alert a WHERE a.mlRiskScore >= :minScore ORDER BY a.mlRiskScore DESC")
    Page<Alert> findHighRiskAlerts(@Param("minScore") Double minScore, Pageable pageable);

    @Query("SELECT a FROM Alert a WHERE a.detectionConfig = :config AND a.isAnomaly = true")
    List<Alert> findAnomaliesByConfig(@Param("config") DetectionConfig config);

    long countByDetectionConfigAndIsAnomalyTrue(DetectionConfig config);

    long countByStatus(AlertStatus status);

    @Query("SELECT AVG(a.mlRiskScore) FROM Alert a WHERE a.mlRiskScore IS NOT NULL")
    Double avgMlRiskScore();

    @Query("SELECT a.detectionConfig, COUNT(a) FROM Alert a GROUP BY a.detectionConfig")
    List<Object[]> countByDetectionConfigGrouped();

    /**
     * Audit B-5: single GROUP BY query returning anomaly counts per config.
     *
     * <p>Replaces the per-config loop in {@code DashboardService.getDetectionAnalytics}
     * which fired one {@code COUNT(*)} query per {@link DetectionConfig} — 5 round-trips
     * on a hot dashboard path. This version does it in one.</p>
     */
    @Query("SELECT a.detectionConfig, COUNT(a) FROM Alert a WHERE a.isAnomaly = true " +
           "GROUP BY a.detectionConfig")
    List<Object[]> countAnomaliesByDetectionConfigGrouped();

    @Query("SELECT a.status, COUNT(a) FROM Alert a GROUP BY a.status")
    List<Object[]> countByStatusGrouped();

    /**
     * Find anomaly alerts that don't yet have an explanation of the given type,
     * <b>scoped to a specific detection config</b>. Single query with NOT EXISTS
     * subquery — avoids N+1 per-alert lookups.
     *
     * <p><b>Config-scoping is load-bearing for thesis correctness.</b> Without
     * the {@code detectionConfig} filter, this query returns alerts from
     * <em>every</em> config sorted by id. In the benchmark run order
     * (RULES_ONLY first → highest-ID last), it would sweep up low-ID
     * RULES_ONLY alerts and generate LLM explanations for <em>them</em>,
     * effectively evaluating RULES_ONLY under an LLM label. That breaks the
     * whole comparison: CAKR / hallucination / latency metrics for
     * ML_LLM_DIRECT, ML_LLM_RAG, and FULL_SYSTEM end up measuring
     * explanations attached to rules-only alerts.</p>
     */
    @Query("SELECT a FROM Alert a WHERE a.isAnomaly = true " +
           "AND a.detectionConfig = :config " +
           "AND NOT EXISTS (SELECT 1 FROM Explanation e WHERE e.alert = a AND e.explanationType = :type) " +
           "ORDER BY a.id")
    List<Alert> findAnomalyAlertsWithoutExplanation(
            @Param("config") DetectionConfig config,
            @Param("type") ExplanationType type,
            Pageable pageable);

    /**
     * Same as {@link #findAnomalyAlertsWithoutExplanation} but eagerly fetches the transaction.
     * Use this in batch explanation loops where {@code open-in-view=false} would otherwise
     * cause {@code LazyInitializationException} when accessing transaction fields.
     */
    @Query("SELECT a FROM Alert a JOIN FETCH a.transaction " +
           "WHERE a.isAnomaly = true " +
           "AND a.detectionConfig = :config " +
           "AND NOT EXISTS (SELECT 1 FROM Explanation e WHERE e.alert = a AND e.explanationType = :type) " +
           "ORDER BY a.id")
    List<Alert> findAnomalyAlertsWithoutExplanationWithTransaction(
            @Param("config") DetectionConfig config,
            @Param("type") ExplanationType type,
            Pageable pageable);

    /**
     * Pick a deterministic pseudo-random sample of N anomaly-alert IDs eligible for
     * LLM explanation. Used by {@link
     * dev.finguard.explanation.llm.ExplanationBatchAsyncService} when the user
     * has asked for an unbiased sample instead of the lowest-id N alerts.
     *
     * <h3>Why MD5(id::text), not RANDOM()?</h3>
     * <p>{@code RANDOM()} returns a different ordering on every call, so two
     * runs of the same benchmark would CAKR-score different alerts and produce
     * different CAKR averages — devastating for thesis reproducibility. Hashing
     * the primary key gives a uniform-looking but completely deterministic
     * order: the same DB returns the same N alerts every time. This is the
     * standard reproducible-random pattern in evaluation pipelines (cf.
     * scikit-learn's {@code random_state=42} idiom).</p>
     *
     * <p>The hash is computed on {@code id::text} — Postgres needs an explicit
     * cast because {@code MD5} only accepts text/bytea. Costs a CPU cycle per
     * row over the not-yet-explained subset; trivial vs the LLM call cost.</p>
     */
    @Query(value = "SELECT a.id FROM alerts a " +
                   "WHERE a.is_anomaly = true " +
                   "AND a.detection_config = :#{#config.name()} " +
                   "AND NOT EXISTS (SELECT 1 FROM explanations e WHERE e.alert_id = a.id AND e.explanation_type = :#{#type.name()}) " +
                   "ORDER BY MD5(a.id::text) " +
                   "LIMIT :limit",
           nativeQuery = true)
    List<Long> findRandomEligibleAlertIds(
            @Param("config") DetectionConfig config,
            @Param("type") ExplanationType type,
            @Param("limit") int limit);

    /**
     * Hydrate alerts by ID with {@code JOIN FETCH a.transaction}, so the
     * explanation batch's RAG service can read {@code transaction.transactionType}
     * outside the loading session without {@code LazyInitializationException}.
     *
     * <p>Companion to {@link #findRandomEligibleAlertIds}: that one chooses
     * <em>which</em> alerts to explain, this one materialises them with their
     * transaction. Two queries instead of one is a fine trade for the unbiased
     * sample — the second query uses an indexed PK lookup of at most a few
     * hundred IDs.</p>
     *
     * <p>Result order is unspecified — Hibernate returns rows in whatever order
     * the IN-clause optimiser chooses. The explanation batch processes alerts
     * via parallel workers, so order doesn't matter for output.</p>
     */
    @Query("SELECT a FROM Alert a JOIN FETCH a.transaction WHERE a.id IN :ids")
    List<Alert> findByIdsWithTransaction(@Param("ids") List<Long> ids);

    /**
     * Load a single alert with its transaction eagerly fetched.
     * Use this in web controllers to avoid LazyInitializationException when
     * rendering transaction fields after the JPA session has closed.
     */
    @Query("SELECT a FROM Alert a JOIN FETCH a.transaction WHERE a.id = :id")
    Optional<Alert> findByIdWithTransaction(@Param("id") Long id);

    /**
     * Stream all alerts with their transaction eagerly loaded.
     *
     * <p>Caller MUST:</p>
     * <ol>
     *   <li>Execute within a {@code @Transactional(readOnly=true)} boundary so
     *       the JDBC cursor remains open for the duration of the stream.</li>
     *   <li>Close the stream (try-with-resources) to release the cursor.</li>
     * </ol>
     */
    @QueryHints(@QueryHint(name = org.hibernate.jpa.AvailableHints.HINT_FETCH_SIZE, value = "1000"))
    @Query("SELECT a FROM Alert a JOIN FETCH a.transaction ORDER BY a.id")
    Stream<Alert> streamAllWithTransaction();

    /** Streaming variant — see {@link #streamAllWithTransaction()} for caller requirements. */
    @QueryHints(@QueryHint(name = org.hibernate.jpa.AvailableHints.HINT_FETCH_SIZE, value = "1000"))
    @Query("SELECT a FROM Alert a JOIN FETCH a.transaction " +
           "WHERE a.detectionConfig = :config AND a.isAnomaly = true ORDER BY a.id")
    Stream<Alert> streamAnomaliesByConfigWithTransaction(@Param("config") DetectionConfig config);

    /**
     * Fetch anomaly alerts for a given config scoped to the test set only (is_training_set = false).
     * Use this for evaluation to avoid inflating metrics with in-sample (training) data.
     */
    @Query("SELECT a FROM Alert a JOIN FETCH a.transaction t " +
           "WHERE a.detectionConfig = :config AND a.isAnomaly = true AND t.isTrainingSet = false")
    List<Alert> findAnomaliesByConfigForTestSet(@Param("config") DetectionConfig config);

    /**
     * Audit A-1: fetch anomaly alerts restricted to a specific held-out CV fold.
     *
     * <p>Used by {@code ExperimentRunner.runKFold}: for each fold k in [0, K-1],
     * query alerts whose underlying transaction has {@code fold = k} — those
     * are the "test" rows for that fold iteration.</p>
     */
    /**
     * Per-fold alert query — audit correctness: must require {@code t.isTrainingSet = false}.
     * The ML model was trained on training-set rows, so its alerts there are in-sample.
     * Including them in a fold would inflate F1 with training performance.
     */
    @Query("SELECT a FROM Alert a JOIN FETCH a.transaction t " +
           "WHERE a.detectionConfig = :config AND a.isAnomaly = true " +
           "AND t.fold = :fold AND t.isTrainingSet = false")
    List<Alert> findAnomaliesByConfigAndFold(@Param("config") DetectionConfig config,
                                             @Param("fold") int fold);

    // ==============================================================
    // Count-only queries for metric computation — avoid loading millions of
    // Alert entities into heap just to count them. See DetectionMetrics
    // computeFromAggregates. These pair up: "total anomalies" and "true
    // positives" together give TP/FP without materializing a List<Alert>.
    // ==============================================================

    /** Count anomaly alerts for a config scoped to the test set. */
    @Query("SELECT COUNT(a) FROM Alert a JOIN a.transaction t " +
           "WHERE a.detectionConfig = :config AND a.isAnomaly = true AND t.isTrainingSet = false")
    long countAnomaliesByConfigForTestSet(@Param("config") DetectionConfig config);

    /** Count true-positive alerts (anomaly + underlying tx is fraud) scoped to the test set. */
    @Query("SELECT COUNT(a) FROM Alert a JOIN a.transaction t " +
           "WHERE a.detectionConfig = :config AND a.isAnomaly = true " +
           "AND t.isTrainingSet = false AND t.isFraud = true")
    long countTruePositivesByConfigForTestSet(@Param("config") DetectionConfig config);

    /**
     * Count anomaly alerts for a config scoped to a specific CV fold.
     * Audit correctness: test-set rows only (see {@link #findAnomaliesByConfigAndFold}).
     */
    @Query("SELECT COUNT(a) FROM Alert a JOIN a.transaction t " +
           "WHERE a.detectionConfig = :config AND a.isAnomaly = true " +
           "AND t.fold = :fold AND t.isTrainingSet = false")
    long countAnomaliesByConfigAndFold(@Param("config") DetectionConfig config,
                                        @Param("fold") int fold);

    /** Count true-positive alerts scoped to a specific CV fold (test-set only). */
    @Query("SELECT COUNT(a) FROM Alert a JOIN a.transaction t " +
           "WHERE a.detectionConfig = :config AND a.isAnomaly = true " +
           "AND t.fold = :fold AND t.isTrainingSet = false AND t.isFraud = true")
    long countTruePositivesByConfigAndFold(@Param("config") DetectionConfig config,
                                            @Param("fold") int fold);

    /**
     * Fetch only the alert IDs for a config/fold — lightweight alternative to
     * loading the full entities when the caller just needs IDs (e.g., scoping
     * explanation metric queries by alert-id-in). Test-set only for the same
     * correctness reason as the other fold queries.
     */
    @Query("SELECT a.id FROM Alert a JOIN a.transaction t " +
           "WHERE a.detectionConfig = :config AND a.isAnomaly = true " +
           "AND t.fold = :fold AND t.isTrainingSet = false")
    List<Long> findAnomalyAlertIdsByConfigAndFold(@Param("config") DetectionConfig config,
                                                   @Param("fold") int fold);

    /** ID-only variant for the test-set scope. */
    @Query("SELECT a.id FROM Alert a JOIN a.transaction t " +
           "WHERE a.detectionConfig = :config AND a.isAnomaly = true AND t.isTrainingSet = false")
    List<Long> findAnomalyAlertIdsByConfigForTestSet(@Param("config") DetectionConfig config);

    /**
     * Single-pass per-fold counts for a config — returns one row per fold with
     * both the total anomaly count and the true-positive count.
     *
     * <p>Replaces {@code k × 2 = 10} separate {@code COUNT(*)} queries per
     * config. On the thesis dataset with 3.5 M alerts per config this cut
     * metric assembly from ~5 min per config to ~5 s (PostgreSQL gets to
     * scan the alerts table once and fold in the fraud-label filter via a
     * conditional COUNT). Saves ~20 min of wall-clock on a 5-config × 5-fold
     * benchmark.</p>
     *
     * <p>Returned rows: {@code [fold (Integer), total_alerts (Long), true_positives (Long)]}.
     * Training-set rows (fold IS NULL) are excluded — same scoping rule as the
     * per-fold count queries.</p>
     */
    @Query("SELECT t.fold, COUNT(a), " +
           "       SUM(CASE WHEN t.isFraud = true THEN 1 ELSE 0 END) " +
           "FROM Alert a JOIN a.transaction t " +
           "WHERE a.detectionConfig = :config AND a.isAnomaly = true " +
           "AND t.isTrainingSet = false AND t.fold IS NOT NULL " +
           "GROUP BY t.fold")
    List<Object[]> perFoldCountsByConfig(@Param("config") DetectionConfig config);

    /**
     * Return the subset of the given transaction IDs that already have an alert for a config.
     * Scoped to the batch — avoids loading all processed IDs into heap at once.
     * Call once per batch of transactions to determine which to skip.
     */
    @Query("SELECT a.transaction.id FROM Alert a WHERE a.detectionConfig = :config AND a.transaction.id IN :ids")
    java.util.Set<Long> findTransactionIdsForConfigAndIdIn(
            @Param("config") DetectionConfig config,
            @Param("ids") java.util.Collection<Long> ids);

    /** Single-statement bulk delete — O(1) SQL, no entity loading. */
    @Modifying
    @Transactional
    @Query("DELETE FROM Alert a")
    int bulkDeleteAll();
}
