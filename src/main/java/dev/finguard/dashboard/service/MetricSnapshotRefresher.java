package dev.finguard.dashboard.service;

import dev.finguard.detection.service.PipelineStatusTracker;
import dev.finguard.domain.model.MetricSnapshot;
import dev.finguard.domain.repository.AlertRepository;
import dev.finguard.domain.repository.ExperimentResultRepository;
import dev.finguard.domain.repository.ExplanationRepository;
import dev.finguard.domain.repository.FraudPatternRepository;
import dev.finguard.domain.repository.MetricSnapshotRepository;
import dev.finguard.domain.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Background {@code @Scheduled} job that refreshes the aggregate count snapshot
 * every minute (audit D-3).
 *
 * <p>The dashboard reads the latest row via {@code MetricSnapshotRepository};
 * this job keeps that row current. Five {@code COUNT(*)} queries over the hot
 * tables execute once per minute regardless of dashboard traffic, instead of
 * one cluster of them per page-load.</p>
 *
 * <p>First run is delayed 30 s after application startup so Liquibase/JPA
 * initialisation doesn't compete with the snapshot query for DB connections.
 * Subsequent runs fire on a fixed 60 s delay from the end of the previous run,
 * guaranteeing they never overlap even if a snapshot takes longer than a
 * minute to compute.</p>
 */
/**
 * <b>Refresh gating:</b> this job is suppressed during any active detection
 * / benchmark / evaluation run, regardless of Spring profile. Running a
 * {@code COUNT(*)} on the alerts table while the detection pipeline is
 * streaming millions of rows competes for HikariCP connections and adds
 * heap pressure — observed at ~3 second snapshot latencies during the
 * failing thesis runs. As soon as every active job finishes, the next
 * scheduled tick resumes normal snapshotting.
 *
 * <p>This replaces the previous {@code @Profile("!benchmark")} gate, which
 * only worked if the user remembered to activate the benchmark profile
 * (most didn't). Self-gating makes the safety automatic.</p>
 */
@Service
public class MetricSnapshotRefresher {

    private static final Logger log = LoggerFactory.getLogger(MetricSnapshotRefresher.class);

    private static final long INITIAL_DELAY_MS = 30_000L;
    private static final long REFRESH_INTERVAL_MS = 60_000L;

    private final TransactionRepository transactionRepository;
    private final AlertRepository alertRepository;
    private final ExplanationRepository explanationRepository;
    private final FraudPatternRepository fraudPatternRepository;
    private final ExperimentResultRepository experimentResultRepository;
    private final MetricSnapshotRepository snapshotRepository;
    private final PipelineStatusTracker pipelineStatusTracker;

    public MetricSnapshotRefresher(TransactionRepository transactionRepository,
                                    AlertRepository alertRepository,
                                    ExplanationRepository explanationRepository,
                                    FraudPatternRepository fraudPatternRepository,
                                    ExperimentResultRepository experimentResultRepository,
                                    MetricSnapshotRepository snapshotRepository,
                                    PipelineStatusTracker pipelineStatusTracker) {
        this.transactionRepository = transactionRepository;
        this.alertRepository = alertRepository;
        this.explanationRepository = explanationRepository;
        this.fraudPatternRepository = fraudPatternRepository;
        this.experimentResultRepository = experimentResultRepository;
        this.snapshotRepository = snapshotRepository;
        this.pipelineStatusTracker = pipelineStatusTracker;
    }

    /**
     * Compute counts across all aggregate tables and persist a new snapshot row.
     * Exposed as package-visible so integration tests can invoke synchronously.
     */
    @Scheduled(initialDelay = INITIAL_DELAY_MS, fixedDelay = REFRESH_INTERVAL_MS)
    @Transactional
    public void refresh() {
        // Self-gate: if any pipeline job is running (detection, benchmark,
        // evaluation, feature compute, train/test split) we skip this tick.
        // Running a 3-second COUNT(*) on the alerts table while the benchmark
        // loop is flushing bulk inserts adds GC pressure exactly when we need
        // the heap cleanest.
        if (pipelineStatusTracker.isAnyRunning()) {
            log.debug("[metric-snapshot] skipped — active pipeline job(s) in progress");
            return;
        }
        long startedAt = System.currentTimeMillis();
        MetricSnapshot snap = new MetricSnapshot();
        snap.setSnapshotAt(LocalDateTime.now());
        try {
            snap.setTotalTransactions(transactionRepository.count());
            snap.setTotalFraudTx(transactionRepository.countByIsFraudTrue());
            snap.setTotalAlerts(alertRepository.count());
            snap.setTotalExplanations(explanationRepository.count());
            snap.setTotalFraudPatterns(fraudPatternRepository.count());
            snap.setTotalExperiments(experimentResultRepository.count());
            snapshotRepository.save(snap);
            long elapsed = System.currentTimeMillis() - startedAt;
            log.info("[metric-snapshot] tx={} fraud={} alerts={} expl={} patterns={} experiments={} ({}ms)",
                    snap.getTotalTransactions(), snap.getTotalFraudTx(),
                    snap.getTotalAlerts(), snap.getTotalExplanations(),
                    snap.getTotalFraudPatterns(), snap.getTotalExperiments(), elapsed);
        } catch (Exception e) {
            // Log-and-continue — the dashboard falls back to live queries if no
            // snapshot is available, so a transient failure (e.g., DB blip
            // during maintenance) doesn't break the UI.
            log.warn("[metric-snapshot] refresh failed: {}", e.getMessage());
        }
    }
}
