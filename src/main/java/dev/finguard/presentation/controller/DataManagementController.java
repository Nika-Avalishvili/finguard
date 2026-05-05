package dev.finguard.presentation.controller;

import dev.finguard.domain.repository.AlertRepository;
import dev.finguard.domain.repository.ExplanationRepository;
import dev.finguard.domain.repository.MetricSnapshotRepository;
import dev.finguard.domain.repository.TransactionFeaturesRepository;
import dev.finguard.domain.repository.TransactionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Bulk-delete endpoints for clearing data between experiments.
 *
 * <p>Each operation uses a single-statement JPQL {@code DELETE FROM} query per table —
 * no entity loading, no N+1 deletes. FK-safe order:
 * Explanations → Alerts → TransactionFeatures → Transactions.</p>
 */
@RestController
@RequestMapping("/api/v1/data")
@Tag(name = "Data Management", description = "Bulk-clear operations for resetting experiment data")
public class DataManagementController {

    private static final Logger log = LoggerFactory.getLogger(DataManagementController.class);

    private final ExplanationRepository explanationRepository;
    private final AlertRepository alertRepository;
    private final TransactionFeaturesRepository featuresRepository;
    private final TransactionRepository transactionRepository;
    private final MetricSnapshotRepository metricSnapshotRepository;

    public DataManagementController(ExplanationRepository explanationRepository,
                                     AlertRepository alertRepository,
                                     TransactionFeaturesRepository featuresRepository,
                                     TransactionRepository transactionRepository,
                                     MetricSnapshotRepository metricSnapshotRepository) {
        this.explanationRepository = explanationRepository;
        this.alertRepository = alertRepository;
        this.featuresRepository = featuresRepository;
        this.transactionRepository = transactionRepository;
        this.metricSnapshotRepository = metricSnapshotRepository;
    }

    @DeleteMapping("/explanations")
    @Transactional
    @CacheEvict(value = "dashboard-stats", allEntries = true)
    @Operation(summary = "Delete all explanations")
    public ResponseEntity<Map<String, Object>> clearExplanations() {
        log.warn("BULK DELETE initiated: explanations");
        long start = Instant.now().toEpochMilli();

        int deleted = explanationRepository.bulkDeleteAll();
        // Stale snapshot rows would otherwise let the dashboard read pre-delete
        // counts on its next render — wipe them so the next refresher run starts clean.
        metricSnapshotRepository.deleteAll();

        long ms = Instant.now().toEpochMilli() - start;
        log.warn("BULK DELETE complete: {} explanations removed in {}ms", deleted, ms);

        return ResponseEntity.ok(Map.of("deleted", deleted, "entity", "explanations", "durationMs", ms));
    }

    @DeleteMapping("/alerts")
    @Transactional
    @CacheEvict(value = "dashboard-stats", allEntries = true)
    @Operation(summary = "Delete all alerts and their explanations")
    public ResponseEntity<Map<String, Object>> clearAlerts() {
        log.warn("BULK DELETE initiated: alerts + explanations");
        long start = Instant.now().toEpochMilli();

        log.info("BULK DELETE step 1/2: deleting explanations");
        int expDeleted = explanationRepository.bulkDeleteAll();
        log.info("BULK DELETE step 1/2: {} explanations deleted", expDeleted);

        log.info("BULK DELETE step 2/2: deleting alerts");
        int alertDeleted = alertRepository.bulkDeleteAll();
        log.info("BULK DELETE step 2/2: {} alerts deleted", alertDeleted);
        metricSnapshotRepository.deleteAll();

        long ms = Instant.now().toEpochMilli() - start;
        log.warn("BULK DELETE complete: {} explanations + {} alerts removed in {}ms", expDeleted, alertDeleted, ms);

        return ResponseEntity.ok(Map.of(
                "explanations", expDeleted,
                "alerts",       alertDeleted,
                "deleted",      expDeleted + alertDeleted,
                "durationMs",   ms
        ));
    }

    @DeleteMapping("/transactions")
    @Transactional
    @CacheEvict(value = "dashboard-stats", allEntries = true)
    @Operation(summary = "Delete all transactions and all dependent data")
    public ResponseEntity<Map<String, Object>> clearTransactions() {
        log.warn("BULK DELETE initiated: full data reset (explanations → alerts → features → transactions)");
        long start = Instant.now().toEpochMilli();

        log.info("BULK DELETE step 1/4: deleting explanations");
        int expDeleted = explanationRepository.bulkDeleteAll();
        log.info("BULK DELETE step 1/4: {} explanations deleted", expDeleted);

        log.info("BULK DELETE step 2/4: deleting alerts");
        int altDeleted = alertRepository.bulkDeleteAll();
        log.info("BULK DELETE step 2/4: {} alerts deleted", altDeleted);

        log.info("BULK DELETE step 3/4: deleting transaction features");
        int featDeleted = featuresRepository.bulkDeleteAll();
        log.info("BULK DELETE step 3/4: {} transaction features deleted", featDeleted);

        log.info("BULK DELETE step 4/4: deleting transactions");
        int txDeleted = transactionRepository.bulkDeleteAll();
        log.info("BULK DELETE step 4/4: {} transactions deleted", txDeleted);
        metricSnapshotRepository.deleteAll();

        long ms = Instant.now().toEpochMilli() - start;
        log.warn("BULK DELETE complete: {} explanations, {} alerts, {} features, {} transactions removed in {}ms",
                expDeleted, altDeleted, featDeleted, txDeleted, ms);

        return ResponseEntity.ok(Map.of(
                "explanations",        expDeleted,
                "alerts",              altDeleted,
                "transactionFeatures", featDeleted,
                "transactions",        txDeleted,
                "durationMs",          ms
        ));
    }
}
