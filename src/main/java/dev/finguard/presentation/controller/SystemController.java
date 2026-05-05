package dev.finguard.presentation.controller;

import dev.finguard.config.RecentLogBuffer;
import dev.finguard.config.RecentLogBuffer.LogEntry;
import dev.finguard.domain.model.MetricSnapshot;
import dev.finguard.domain.repository.MetricSnapshotRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * System-level endpoints for monitoring and observability.
 */
@RestController
@RequestMapping("/api/v1/system")
@Tag(name = "System", description = "System monitoring — live activity log and metric trends")
public class SystemController {

    private final RecentLogBuffer recentLogBuffer;
    private final MetricSnapshotRepository snapshotRepository;
    private final dev.finguard.dashboard.service.DataQualityService dataQualityService;

    public SystemController(RecentLogBuffer recentLogBuffer,
                             MetricSnapshotRepository snapshotRepository,
                             dev.finguard.dashboard.service.DataQualityService dataQualityService) {
        this.recentLogBuffer = recentLogBuffer;
        this.snapshotRepository = snapshotRepository;
        this.dataQualityService = dataQualityService;
    }

    /**
     * Dataset sanity-check report — pass/warn/fail per check across data,
     * models, LLM, knowledge-base. Useful as a pre-flight before a thesis
     * demo so the examiner doesn't catch a latent issue first.
     */
    @GetMapping("/data-quality")
    @Operation(summary = "Dataset + runtime sanity checks",
               description = "Returns an ordered list of pass/warn/fail checks plus a top-line severity.")
    public ResponseEntity<dev.finguard.dashboard.service.DataQualityService.HealthReport> dataQuality() {
        return ResponseEntity.ok(dataQualityService.run());
    }

    @GetMapping("/logs")
    @Operation(summary = "Recent activity log",
               description = "Returns recent INFO+ log entries. Use 'since' for incremental polling.")
    public ResponseEntity<List<LogEntry>> recentLogs(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) Long since) {

        List<LogEntry> result = since != null
                ? recentLogBuffer.getSince(since)
                : recentLogBuffer.getRecent(Math.min(limit, 100));

        return ResponseEntity.ok(result);
    }

    /**
     * Historical aggregate counts — powers trend charts on the dashboard.
     *
     * <p>Backed by the audit-D-3 {@code metric_snapshots} table: one row per
     * minute (give or take) with totals across transactions / alerts /
     * explanations / experiments. Useful for:</p>
     * <ul>
     *   <li>"Dataset growth over time" charts in the thesis evaluation chapter</li>
     *   <li>"Alerts created by detection config" trend lines</li>
     *   <li>Spotting drift between evaluation runs</li>
     * </ul>
     *
     * @param minutes lookback window in minutes (default 60, max 10 080 = 7 days)
     */
    @GetMapping("/metrics/history")
    @Operation(summary = "Metric snapshots for trend charts",
               description = "Returns aggregate counts sampled once per minute. " +
                             "Default window: last 60 minutes.")
    public ResponseEntity<List<MetricHistoryPoint>> metricsHistory(
            @Parameter(description = "Lookback window in minutes (max 10 080 = 7 days)")
            @RequestParam(defaultValue = "60") int minutes) {

        int clamped = Math.min(Math.max(minutes, 1), 10_080);
        LocalDateTime since = LocalDateTime.now().minus(Duration.ofMinutes(clamped));
        List<MetricSnapshot> rows = snapshotRepository.findSince(since);
        List<MetricHistoryPoint> points = rows.stream()
                .map(MetricHistoryPoint::from)
                .toList();
        return ResponseEntity.ok(points);
    }

    /**
     * Projection of {@link MetricSnapshot} to a stable public DTO.
     * Using a record + explicit mapping keeps the DB schema internal and lets
     * future snapshot columns roll out without breaking API consumers.
     */
    public record MetricHistoryPoint(
            String snapshotAt,
            long totalTransactions,
            long totalFraudTx,
            long totalAlerts,
            long totalExplanations,
            long totalFraudPatterns,
            long totalExperiments
    ) {
        static MetricHistoryPoint from(MetricSnapshot s) {
            return new MetricHistoryPoint(
                    s.getSnapshotAt() != null ? s.getSnapshotAt().toString() : null,
                    s.getTotalTransactions(),
                    s.getTotalFraudTx(),
                    s.getTotalAlerts(),
                    s.getTotalExplanations(),
                    s.getTotalFraudPatterns(),
                    s.getTotalExperiments()
            );
        }
    }
}
