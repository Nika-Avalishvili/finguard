package dev.finguard.dashboard.service;

import dev.finguard.domain.enums.AlertStatus;
import dev.finguard.domain.enums.DatasetSource;
import dev.finguard.domain.enums.DetectionConfig;
import dev.finguard.domain.enums.ExplanationType;
import dev.finguard.domain.enums.TransactionType;
import dev.finguard.domain.model.Alert;
import dev.finguard.domain.model.Explanation;
import dev.finguard.domain.model.ExperimentResult;
import dev.finguard.domain.model.FraudPattern;
import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.repository.AlertRepository;
import dev.finguard.domain.repository.ExperimentResultRepository;
import dev.finguard.domain.repository.ExplanationRepository;
import dev.finguard.domain.repository.FraudPatternRepository;
import dev.finguard.domain.repository.TransactionFeaturesRepository;
import dev.finguard.domain.repository.TransactionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * View-model facade for the Thymeleaf web controller.
 *
 * <p>Consolidates all repository access the dashboard/web pages need so the
 * controller layer stays thin and infrastructure-free (architecture audit
 * criterion: no presentation → domain-repository shortcuts).</p>
 *
 * <p>Each method returns either a typed record or a Spring {@link Page} — no
 * raw repository method leaks back up to the controller.</p>
 */
@Service
public class DashboardFacadeService {

    private final TransactionRepository transactionRepository;
    private final TransactionFeaturesRepository featuresRepository;
    private final AlertRepository alertRepository;
    private final ExplanationRepository explanationRepository;
    private final ExperimentResultRepository experimentResultRepository;
    private final FraudPatternRepository fraudPatternRepository;

    public DashboardFacadeService(TransactionRepository transactionRepository,
                                   TransactionFeaturesRepository featuresRepository,
                                   AlertRepository alertRepository,
                                   ExplanationRepository explanationRepository,
                                   ExperimentResultRepository experimentResultRepository,
                                   FraudPatternRepository fraudPatternRepository) {
        this.transactionRepository      = transactionRepository;
        this.featuresRepository         = featuresRepository;
        this.alertRepository            = alertRepository;
        this.explanationRepository      = explanationRepository;
        this.experimentResultRepository = experimentResultRepository;
        this.fraudPatternRepository     = fraudPatternRepository;
    }

    // ── Transactions page ──────────────────────────────────────────────────

    public Page<Transaction> transactionsPage(DatasetSource source, boolean fraudOnly,
                                               TransactionType type, String search,
                                               Pageable pageable) {
        boolean noFilters = !fraudOnly && source == null && type == null
                && (search == null || search.isBlank());
        if (noFilters) {
            return transactionRepository.findAll(pageable);
        }
        // Empty string avoids the Hibernate 6.6 bytea inference bug — see comment in
        // TransactionRepository.findByFilters.
        String s = (search != null) ? search.trim() : "";
        return transactionRepository.findByFilters(source, fraudOnly, type, s, pageable);
    }

    public TransactionStats transactionStats() {
        long total = transactionRepository.count();
        long fraud = transactionRepository.countByIsFraudTrue();
        double fraudRatePercent = total > 0
                ? (fraud * 10_000L / total) / 100.0
                : 0.0;
        return new TransactionStats(total, fraud, fraudRatePercent);
    }

    public record TransactionStats(long total, long fraud, double fraudRatePercent) { }

    // ── Alerts page ────────────────────────────────────────────────────────

    public Page<Alert> alertsPage(AlertStatus status, DetectionConfig config, Pageable pageable) {
        return alertRepository.findByStatusAndConfig(status, config, pageable);
    }

    public AlertStats alertStats() {
        Map<String, Long> alertsByStatus = new LinkedHashMap<>();
        alertRepository.countByStatusGrouped().forEach(row -> {
            Object key = row[0];
            long count = ((Number) row[1]).longValue();
            alertsByStatus.put(key instanceof AlertStatus s ? s.name() : String.valueOf(key), count);
        });
        long totalAnomalies = alertRepository.countAnomaliesByDetectionConfigGrouped().stream()
                .mapToLong(row -> ((Number) row[1]).longValue()).sum();
        Double avgRiskScore = alertRepository.avgMlRiskScore();
        return new AlertStats(alertsByStatus, totalAnomalies, avgRiskScore);
    }

    public record AlertStats(Map<String, Long> alertsByStatus, long totalAnomalies, Double avgRiskScore) { }

    public Alert loadAlertWithTransaction(Long id) {
        return alertRepository.findByIdWithTransaction(id).orElse(null);
    }

    public List<Explanation> explanationsForAlert(Long alertId) {
        return explanationRepository.findByAlertId(alertId);
    }

    /**
     * Persist a status change on an alert. Returns {@code true} when the alert
     * existed and the save completed. No-ops silently (returns {@code false})
     * when the id isn't found — matches the legacy behaviour of the old
     * {@code ifPresent} block in the controller.
     */
    public boolean updateAlertStatus(Long id, AlertStatus status) {
        return alertRepository.findById(id).map(alert -> {
            alert.setStatus(status);
            alertRepository.save(alert);
            return true;
        }).orElse(false);
    }

    // ── Explanations page ──────────────────────────────────────────────────

    public Page<Explanation> explanationsPage(ExplanationType type, String flag, Pageable pageable) {
        return explanationRepository.findFiltered(type, flag, pageable);
    }

    public ExplanationStats explanationStats() {
        Set<ExplanationType> presentTypes = new LinkedHashSet<>();
        for (Object[] row : explanationRepository.countByExplanationTypeGrouped()) {
            if (row[0] instanceof ExplanationType et) presentTypes.add(et);
        }
        return new ExplanationStats(
                explanationRepository.count(),
                explanationRepository.avgConfidenceScore(),
                explanationRepository.avgLatency(),
                explanationRepository.countByHallucinationFreeTrue(),
                explanationRepository.countByHallucinationFreeFalse(),
                presentTypes);
    }

    public record ExplanationStats(long total,
                                    Double avgConfidence,
                                    Double avgLatencyMs,
                                    long clean,
                                    long flagged,
                                    Set<ExplanationType> presentTypes) { }

    // ── Ingestion + detection + models pages ───────────────────────────────

    public IngestionStats ingestionStats() {
        long total = transactionRepository.count();
        long features = featuresRepository.count();
        return new IngestionStats(total, features, Math.max(0L, total - features));
    }

    public record IngestionStats(long totalTransactions, long features, long pending) { }

    public DetectionPageStats detectionPageStats() {
        return new DetectionPageStats(
                transactionRepository.count(),
                alertRepository.count(),
                featuresRepository.count());
    }

    public record DetectionPageStats(long totalTransactions, long totalAlerts, long featuresComputed) { }

    // ── Knowledge base ─────────────────────────────────────────────────────

    public Page<FraudPattern> fraudPatternsPage(Pageable pageable) {
        return fraudPatternRepository.findAll(pageable);
    }

    /** Distinct pattern-types in the current page, preserving encounter order. */
    public Set<String> uniquePatternTypes(Page<FraudPattern> page) {
        Set<String> types = new LinkedHashSet<>();
        for (FraudPattern p : page.getContent()) {
            if (p.getPatternType() != null) types.add(p.getPatternType());
        }
        return types;
    }

    // ── Evaluation page ────────────────────────────────────────────────────

    public Page<ExperimentResult> experimentResultsPage(Pageable pageable) {
        return experimentResultRepository.findAll(pageable);
    }

    public List<ExperimentResult> experimentResultsByName(String experimentName) {
        return experimentResultRepository.findByExperimentNameOrdered(experimentName);
    }

    public EvaluationPreflight evaluationPreflight() {
        long totalTx = transactionRepository.count();
        long testSetSize = transactionRepository.countTestSetByDatasetSource(DatasetSource.PAYSIM);
        long featuresCount = featuresRepository.count();
        return new EvaluationPreflight(totalTx, testSetSize, featuresCount);
    }

    public record EvaluationPreflight(long totalTransactions, long testSetSize, long featuresComputed) { }

    /** Distinct experiment names present in a page of results — powers the "Compare runs" dropdown. */
    public Set<String> experimentNamesFromPage(Page<ExperimentResult> page) {
        return page.getContent().stream()
                .map(ExperimentResult::getExperimentName)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
    }
}
