package dev.finguard.domain.repository;

import dev.finguard.domain.enums.ExplanationType;
import dev.finguard.domain.model.Explanation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;

@Repository
public interface ExplanationRepository extends JpaRepository<Explanation, Long> {

    List<Explanation> findByAlertId(Long alertId);

    List<Explanation> findByAlertIdAndExplanationType(Long alertId, ExplanationType type);

    @Query("SELECT e FROM Explanation e WHERE e.alert.transaction.id = :txId ORDER BY e.explanationType")
    List<Explanation> findByTransactionId(@Param("txId") Long transactionId);

    @Query("SELECT AVG(e.cakrCompleteness) FROM Explanation e " +
           "WHERE e.explanationType = :type AND e.cakrCompleteness IS NOT NULL")
    Double avgCompletenessByType(@Param("type") ExplanationType type);

    @Query("SELECT COUNT(e) FROM Explanation e " +
           "WHERE e.explanationType = :type AND e.hallucinationFree = false")
    long countHallucinationsByType(@Param("type") ExplanationType type);

    @Query("SELECT AVG(e.latencyMs) FROM Explanation e WHERE e.explanationType = :type")
    Double avgLatencyByType(@Param("type") ExplanationType type);

    @Query("SELECT e.explanationType, COUNT(e) FROM Explanation e GROUP BY e.explanationType")
    List<Object[]> countByExplanationTypeGrouped();

    @Query("SELECT AVG(e.cakrCompleteness) FROM Explanation e WHERE e.cakrCompleteness IS NOT NULL")
    Double avgCakrCompleteness();

    @Query("SELECT AVG(e.cakrCorrectness) FROM Explanation e WHERE e.cakrCorrectness IS NOT NULL")
    Double avgCakrCorrectness();

    @Query("SELECT AVG(e.cakrActionability) FROM Explanation e WHERE e.cakrActionability IS NOT NULL")
    Double avgCakrActionability();

    @Query("SELECT AVG(e.cakrRegulatory) FROM Explanation e WHERE e.cakrRegulatory IS NOT NULL")
    Double avgCakrRegulatory();

    long countByHallucinationFreeTrue();

    long countByHallucinationFreeFalse();

    @Query("SELECT AVG(e.latencyMs) FROM Explanation e WHERE e.latencyMs IS NOT NULL")
    Double avgLatency();

    @Query("SELECT AVG(e.confidenceScore) FROM Explanation e WHERE e.confidenceScore IS NOT NULL")
    Double avgConfidenceScore();

    /** Count all explanations of a given type (avoids loading all into memory). */
    @Query("SELECT COUNT(e) FROM Explanation e WHERE e.explanationType = :type")
    long countByType(@Param("type") ExplanationType type);

    /**
     * Average CAKR score for a given explanation type.
     *
     * <p>Post-014: only rows with ALL four dimensions non-null contribute. Mixing
     * partial-null rows with full rows via COALESCE(...,0) was the bug fixed in
     * audit A-3.</p>
     */
    @Query("SELECT AVG((e.cakrCompleteness + e.cakrActionability " +
           "+ e.cakrCorrectness + e.cakrRegulatory) / 4.0) " +
           "FROM Explanation e WHERE e.explanationType = :type " +
           "AND e.cakrCompleteness IS NOT NULL " +
           "AND e.cakrActionability IS NOT NULL " +
           "AND e.cakrCorrectness  IS NOT NULL " +
           "AND e.cakrRegulatory   IS NOT NULL")
    Double avgCakrByType(@Param("type") ExplanationType type);

    /**
     * Fetch explanations that have not yet been CAKR-scored, for a given type.
     * Replaces findAll().stream().filter(cakrAverage==null) with a direct DB query.
     */
    @Query("SELECT e FROM Explanation e " +
           "WHERE e.explanationType = :type AND e.cakrCompleteness IS NULL " +
           "ORDER BY e.id")
    Page<Explanation> findUnscoredByType(@Param("type") ExplanationType type, Pageable pageable);

    /**
     * Find any N explanations of the given type, sorted by id, with the owning
     * alert (and its transaction) eagerly fetched.
     *
     * <p>JOIN FETCH is load-bearing here: the {@code /rescore} endpoint hands
     * these explanations to {@link dev.finguard.evaluation.metrics.CAKRScorer}
     * which dereferences {@code explanation.getAlert().getDetectionConfig()}
     * etc. outside the original query's session — without the fetch, every
     * access throws {@code LazyInitializationException}
     * ("no session" / "could not initialize proxy"). Observed as 0/20 scored
     * during the thesis run re-score attempt.</p>
     */
    @Query("SELECT e FROM Explanation e " +
           "JOIN FETCH e.alert a JOIN FETCH a.transaction " +
           "WHERE e.explanationType = :type ORDER BY e.id")
    Page<Explanation> findAllByExplanationType(@Param("type") ExplanationType type,
                                                 Pageable pageable);

    /**
     * Fetch a page of explanations with the associated alert eagerly loaded.
     * Prevents LazyInitializationException when the Thymeleaf template accesses
     * {@code explanation.alert.id} or {@code explanation.alert.transaction}.
     *
     * <p>Uses a count query to avoid a second JOIN FETCH that would conflict
     * with DISTINCT and pagination.</p>
     */
    @Query(value = "SELECT e FROM Explanation e JOIN FETCH e.alert",
           countQuery = "SELECT COUNT(e) FROM Explanation e")
    Page<Explanation> findAllWithAlertFetched(Pageable pageable);

    /**
     * Filtered listing for the Explanations page: any combination of
     * explanation type and hallucination state (null-tolerant).
     *
     * <p>{@code hallucinationFilter} values: {@code "clean"} → only rows where
     * {@code hallucinationFree = true}, {@code "flagged"} → only
     * {@code hallucinationFree = false}, {@code "unscored"} → only
     * {@code hallucinationFree IS NULL}, anything else → match all.</p>
     */
    @Query(value =
           "SELECT e FROM Explanation e JOIN FETCH e.alert " +
           "WHERE (:type IS NULL OR e.explanationType = :type) " +
           "  AND (:flag = 'all' OR " +
           "       (:flag = 'clean'    AND e.hallucinationFree = true) OR " +
           "       (:flag = 'flagged'  AND e.hallucinationFree = false) OR " +
           "       (:flag = 'unscored' AND e.hallucinationFree IS NULL))",
           countQuery =
           "SELECT COUNT(e) FROM Explanation e " +
           "WHERE (:type IS NULL OR e.explanationType = :type) " +
           "  AND (:flag = 'all' OR " +
           "       (:flag = 'clean'    AND e.hallucinationFree = true) OR " +
           "       (:flag = 'flagged'  AND e.hallucinationFree = false) OR " +
           "       (:flag = 'unscored' AND e.hallucinationFree IS NULL))")
    Page<Explanation> findFiltered(@Param("type") ExplanationType type,
                                   @Param("flag") String hallucinationFilter,
                                   Pageable pageable);

    /** Single-statement bulk delete — O(1) SQL, no entity loading. */
    @Modifying
    @Transactional
    @Query("DELETE FROM Explanation e")
    int bulkDeleteAll();

    // ── Multi-type queries for FULL_SYSTEM (stores both LLM_RAG and LLM_RAG_VALIDATED) ──

    @Query("SELECT COUNT(e) FROM Explanation e WHERE e.explanationType IN :types")
    long countByTypeIn(@Param("types") java.util.List<ExplanationType> types);

    @Query("SELECT COUNT(e) FROM Explanation e " +
           "WHERE e.explanationType IN :types AND e.hallucinationFree = false")
    long countHallucinationsByTypeIn(@Param("types") java.util.List<ExplanationType> types);

    @Query("SELECT AVG(e.latencyMs) FROM Explanation e WHERE e.explanationType IN :types")
    Double avgLatencyByTypeIn(@Param("types") java.util.List<ExplanationType> types);

    /** Post-014: honest average — only rows with all four dims non-null contribute. */
    @Query("SELECT AVG((e.cakrCompleteness + e.cakrActionability " +
           "+ e.cakrCorrectness + e.cakrRegulatory) / 4.0) " +
           "FROM Explanation e WHERE e.explanationType IN :types " +
           "AND e.cakrCompleteness IS NOT NULL " +
           "AND e.cakrActionability IS NOT NULL " +
           "AND e.cakrCorrectness  IS NOT NULL " +
           "AND e.cakrRegulatory   IS NOT NULL")
    Double avgCakrByTypeIn(@Param("types") java.util.List<ExplanationType> types);

    @Query("SELECT e FROM Explanation e " +
           "WHERE e.explanationType IN :types AND e.cakrCompleteness IS NULL " +
           "ORDER BY e.id")
    Page<Explanation> findUnscoredByTypeIn(@Param("types") java.util.List<ExplanationType> types,
                                           Pageable pageable);

    // ── Alert-scoped queries for ExperimentRunner (prevents cross-run metric pollution) ──

    @Query("SELECT COUNT(e) FROM Explanation e WHERE e.explanationType IN :types AND e.alert.id IN :alertIds")
    long countByTypeInAndAlertIdIn(@Param("types") java.util.List<ExplanationType> types,
                                   @Param("alertIds") java.util.List<Long> alertIds);

    @Query("SELECT COUNT(e) FROM Explanation e " +
           "WHERE e.explanationType IN :types AND e.hallucinationFree = false AND e.alert.id IN :alertIds")
    long countHallucinationsByTypeInAndAlertIdIn(@Param("types") java.util.List<ExplanationType> types,
                                                 @Param("alertIds") java.util.List<Long> alertIds);

    /**
     * Audit A-2: denominator for an honest hallucination rate.
     *
     * <p>Counts only explanations that have actually been evaluated for
     * hallucination (i.e., {@code hallucinationFree IS NOT NULL}). Using the
     * unconditional count inflated the denominator with never-evaluated rows
     * and systematically understated the true rate.</p>
     */
    @Query("SELECT COUNT(e) FROM Explanation e " +
           "WHERE e.explanationType IN :types AND e.alert.id IN :alertIds " +
           "AND e.hallucinationFree IS NOT NULL")
    long countEvaluatedForHallucinationByTypeInAndAlertIdIn(
            @Param("types") java.util.List<ExplanationType> types,
            @Param("alertIds") java.util.List<Long> alertIds);

    @Query("SELECT AVG(e.latencyMs) FROM Explanation e WHERE e.explanationType IN :types AND e.alert.id IN :alertIds")
    Double avgLatencyByTypeInAndAlertIdIn(@Param("types") java.util.List<ExplanationType> types,
                                          @Param("alertIds") java.util.List<Long> alertIds);

    /** Post-014: honest average — only rows with all four dims non-null contribute. */
    @Query("SELECT AVG((e.cakrCompleteness + e.cakrActionability " +
           "+ e.cakrCorrectness + e.cakrRegulatory) / 4.0) " +
           "FROM Explanation e WHERE e.explanationType IN :types " +
           "AND e.alert.id IN :alertIds " +
           "AND e.cakrCompleteness IS NOT NULL " +
           "AND e.cakrActionability IS NOT NULL " +
           "AND e.cakrCorrectness  IS NOT NULL " +
           "AND e.cakrRegulatory   IS NOT NULL")
    Double avgCakrByTypeInAndAlertIdIn(@Param("types") java.util.List<ExplanationType> types,
                                       @Param("alertIds") java.util.List<Long> alertIds);

    @Query("SELECT e FROM Explanation e " +
           "WHERE e.explanationType IN :types AND e.cakrCompleteness IS NULL AND e.alert.id IN :alertIds " +
           "ORDER BY e.id")
    Page<Explanation> findUnscoredByTypeInAndAlertIdIn(@Param("types") java.util.List<ExplanationType> types,
                                                       @Param("alertIds") java.util.List<Long> alertIds,
                                                       Pageable pageable);

    // ──────────────────────────────────────────────────────────────────
    // Config-scoped variants (post-audit fix for PostgreSQL parameter cap)
    // ──────────────────────────────────────────────────────────────────
    // The {@code *AndAlertIdIn} methods above bind one parameter per alert id.
    // For configurations that produce > 32k alerts (FULL_SYSTEM with rules-OR-ML
    // aggregation typically hits this on PaySim), Hibernate's
    // {@code in_clause_parameter_padding=true} pads the bind list to the next
    // power of two, exceeding PostgreSQL's hard limit of 65,535 parameters per
    // PreparedStatement and crashing the query mid-benchmark.
    //
    // The methods below replace the IN-clause with a SQL JOIN through the
    // alerts table, moving the config + test-set scoping into the relationship
    // graph rather than into bind parameters. Behaviourally identical, but
    // bullet-proof for any future config size.
    //
    // Usage from ExperimentRunner: prefer these over the *AndAlertIdIn variants
    // unless you specifically need to scope to an arbitrary list of alert ids.
    // ──────────────────────────────────────────────────────────────────

    @Query("SELECT COUNT(e) FROM Explanation e JOIN e.alert a JOIN a.transaction t " +
           "WHERE e.explanationType IN :types " +
           "AND a.detectionConfig = :config " +
           "AND a.isAnomaly = true " +
           "AND t.isTrainingSet = false")
    long countByTypeInAndConfigForTestSet(
            @Param("types") java.util.List<ExplanationType> types,
            @Param("config") dev.finguard.domain.enums.DetectionConfig config);

    @Query("SELECT COUNT(e) FROM Explanation e JOIN e.alert a JOIN a.transaction t " +
           "WHERE e.explanationType IN :types " +
           "AND a.detectionConfig = :config " +
           "AND a.isAnomaly = true " +
           "AND t.isTrainingSet = false " +
           "AND e.hallucinationFree IS NOT NULL")
    long countEvaluatedForHallucinationByTypeInAndConfigForTestSet(
            @Param("types") java.util.List<ExplanationType> types,
            @Param("config") dev.finguard.domain.enums.DetectionConfig config);

    @Query("SELECT COUNT(e) FROM Explanation e JOIN e.alert a JOIN a.transaction t " +
           "WHERE e.explanationType IN :types " +
           "AND a.detectionConfig = :config " +
           "AND a.isAnomaly = true " +
           "AND t.isTrainingSet = false " +
           "AND e.hallucinationFree = false")
    long countHallucinationsByTypeInAndConfigForTestSet(
            @Param("types") java.util.List<ExplanationType> types,
            @Param("config") dev.finguard.domain.enums.DetectionConfig config);

    @Query("SELECT AVG(e.latencyMs) FROM Explanation e JOIN e.alert a JOIN a.transaction t " +
           "WHERE e.explanationType IN :types " +
           "AND a.detectionConfig = :config " +
           "AND a.isAnomaly = true " +
           "AND t.isTrainingSet = false")
    Double avgLatencyByTypeInAndConfigForTestSet(
            @Param("types") java.util.List<ExplanationType> types,
            @Param("config") dev.finguard.domain.enums.DetectionConfig config);

    @Query("SELECT AVG((e.cakrCompleteness + e.cakrActionability " +
           "          + e.cakrCorrectness  + e.cakrRegulatory) / 4.0) " +
           "FROM Explanation e JOIN e.alert a JOIN a.transaction t " +
           "WHERE e.explanationType IN :types " +
           "AND a.detectionConfig = :config " +
           "AND a.isAnomaly = true " +
           "AND t.isTrainingSet = false " +
           "AND e.cakrCompleteness  IS NOT NULL " +
           "AND e.cakrActionability IS NOT NULL " +
           "AND e.cakrCorrectness   IS NOT NULL " +
           "AND e.cakrRegulatory    IS NOT NULL")
    Double avgCakrByTypeInAndConfigForTestSet(
            @Param("types") java.util.List<ExplanationType> types,
            @Param("config") dev.finguard.domain.enums.DetectionConfig config);

    @Query("SELECT e FROM Explanation e JOIN e.alert a JOIN a.transaction t " +
           "WHERE e.explanationType IN :types " +
           "AND a.detectionConfig = :config " +
           "AND a.isAnomaly = true " +
           "AND t.isTrainingSet = false " +
           "AND e.cakrCompleteness IS NULL " +
           "ORDER BY e.id")
    Page<Explanation> findUnscoredByTypeInAndConfigForTestSet(
            @Param("types") java.util.List<ExplanationType> types,
            @Param("config") dev.finguard.domain.enums.DetectionConfig config,
            Pageable pageable);

    // Fold-scoped variants (per-fold metrics in k-fold CV).
    // Same JOIN pattern + extra filter on transaction.fold.
    @Query("SELECT COUNT(e) FROM Explanation e JOIN e.alert a JOIN a.transaction t " +
           "WHERE e.explanationType IN :types " +
           "AND a.detectionConfig = :config " +
           "AND a.isAnomaly = true " +
           "AND t.isTrainingSet = false " +
           "AND t.fold = :fold")
    long countByTypeInAndConfigAndFold(
            @Param("types") java.util.List<ExplanationType> types,
            @Param("config") dev.finguard.domain.enums.DetectionConfig config,
            @Param("fold") int fold);

    @Query("SELECT COUNT(e) FROM Explanation e JOIN e.alert a JOIN a.transaction t " +
           "WHERE e.explanationType IN :types " +
           "AND a.detectionConfig = :config " +
           "AND a.isAnomaly = true " +
           "AND t.isTrainingSet = false " +
           "AND t.fold = :fold " +
           "AND e.hallucinationFree IS NOT NULL")
    long countEvaluatedForHallucinationByTypeInAndConfigAndFold(
            @Param("types") java.util.List<ExplanationType> types,
            @Param("config") dev.finguard.domain.enums.DetectionConfig config,
            @Param("fold") int fold);

    @Query("SELECT COUNT(e) FROM Explanation e JOIN e.alert a JOIN a.transaction t " +
           "WHERE e.explanationType IN :types " +
           "AND a.detectionConfig = :config " +
           "AND a.isAnomaly = true " +
           "AND t.isTrainingSet = false " +
           "AND t.fold = :fold " +
           "AND e.hallucinationFree = false")
    long countHallucinationsByTypeInAndConfigAndFold(
            @Param("types") java.util.List<ExplanationType> types,
            @Param("config") dev.finguard.domain.enums.DetectionConfig config,
            @Param("fold") int fold);

    @Query("SELECT AVG(e.latencyMs) FROM Explanation e JOIN e.alert a JOIN a.transaction t " +
           "WHERE e.explanationType IN :types " +
           "AND a.detectionConfig = :config " +
           "AND a.isAnomaly = true " +
           "AND t.isTrainingSet = false " +
           "AND t.fold = :fold")
    Double avgLatencyByTypeInAndConfigAndFold(
            @Param("types") java.util.List<ExplanationType> types,
            @Param("config") dev.finguard.domain.enums.DetectionConfig config,
            @Param("fold") int fold);

    @Query("SELECT AVG((e.cakrCompleteness + e.cakrActionability " +
           "          + e.cakrCorrectness  + e.cakrRegulatory) / 4.0) " +
           "FROM Explanation e JOIN e.alert a JOIN a.transaction t " +
           "WHERE e.explanationType IN :types " +
           "AND a.detectionConfig = :config " +
           "AND a.isAnomaly = true " +
           "AND t.isTrainingSet = false " +
           "AND t.fold = :fold " +
           "AND e.cakrCompleteness  IS NOT NULL " +
           "AND e.cakrActionability IS NOT NULL " +
           "AND e.cakrCorrectness   IS NOT NULL " +
           "AND e.cakrRegulatory    IS NOT NULL")
    Double avgCakrByTypeInAndConfigAndFold(
            @Param("types") java.util.List<ExplanationType> types,
            @Param("config") dev.finguard.domain.enums.DetectionConfig config,
            @Param("fold") int fold);

    /**
     * Improvement (post-audit): per-dimension CAKR averages, grouped by the
     * detection config of the underlying alert. Used by
     * {@code /api/v1/evaluation/cakr-breakdown} to produce the "where does
     * each config win or lose?" comparison chart for the thesis.
     *
     * <p>Each row is {@code [detectionConfig, avgCompleteness, avgActionability,
     * avgCorrectness, avgRegulatory, scoredCount]}. The AVG aggregations skip
     * NULL by default (standard SQL semantics) — audit A-3 made failed scores
     * NULL for exactly this reason. The count covers explanations with at
     * least one CAKR dimension populated, so the caller can spot sparse data.
     * The caller can compute the overall mean client-side as the mean of the
     * four dim averages.</p>
     */
    @Query("SELECT a.detectionConfig, " +
           "       AVG(e.cakrCompleteness), AVG(e.cakrActionability), " +
           "       AVG(e.cakrCorrectness),  AVG(e.cakrRegulatory), " +
           "       COUNT(e) " +
           "FROM Explanation e JOIN e.alert a " +
           "WHERE (e.cakrCompleteness IS NOT NULL OR e.cakrActionability IS NOT NULL OR " +
           "       e.cakrCorrectness  IS NOT NULL OR e.cakrRegulatory    IS NOT NULL) " +
           "GROUP BY a.detectionConfig")
    java.util.List<Object[]> cakrBreakdownByDetectionConfig();

    /**
     * LLM cost and latency, grouped by alert detection config.
     *
     * <p>Each row: {@code [detectionConfig, explanationCount, sumPromptTokens,
     * sumCompletionTokens, avgPromptTokens, avgCompletionTokens, avgLatencyMs,
     * p95LatencyMs]}. The p95 is computed client-side (no standard JPQL for
     * percentiles) — we return the row-level sum/avg and let the controller
     * do any tail-latency math by re-querying latencies when needed.</p>
     *
     * <p>Used by {@code /api/v1/evaluation/cost-breakdown} for the thesis
     * cost-benefit analysis: is RAG's token/latency overhead justified by
     * the CAKR gain we measured in {@code /cakr-breakdown}?</p>
     */
    @Query("SELECT a.detectionConfig, " +
           "       COUNT(e), " +
           "       COALESCE(SUM(e.promptTokens), 0), " +
           "       COALESCE(SUM(e.completionTokens), 0), " +
           "       AVG(e.promptTokens), AVG(e.completionTokens), " +
           "       AVG(e.latencyMs) " +
           "FROM Explanation e JOIN e.alert a " +
           "GROUP BY a.detectionConfig")
    java.util.List<Object[]> costBreakdownByDetectionConfig();

    /**
     * Latencies for a given config — used to compute the p95 tail metric
     * alongside {@link #costBreakdownByDetectionConfig()}.
     */
    @Query("SELECT e.latencyMs FROM Explanation e JOIN e.alert a " +
           "WHERE a.detectionConfig = :config AND e.latencyMs IS NOT NULL")
    java.util.List<Integer> latenciesByDetectionConfig(
            @org.springframework.data.repository.query.Param("config")
            dev.finguard.domain.enums.DetectionConfig config);

    /**
     * Frequency of each hallucination flag type, grouped by detection config.
     *
     * <p>The {@code hallucinationFlags} column stores a JSON array of human-readable
     * flag strings (e.g., {@code "AMOUNT_MISMATCH: ..."}). This query ignores the
     * JSON structure and returns the raw row text so the controller can parse
     * and count flag types in-memory — pgjdbc does not expose JSONB array
     * unnesting via JPQL cleanly. Row count per config is bounded by the
     * size of the explanation table.</p>
     */
    @Query("SELECT a.detectionConfig, e.hallucinationFlags " +
           "FROM Explanation e JOIN e.alert a " +
           "WHERE e.hallucinationFree = false AND e.hallucinationFlags IS NOT NULL")
    java.util.List<Object[]> hallucinationFlagsByDetectionConfig();
}
