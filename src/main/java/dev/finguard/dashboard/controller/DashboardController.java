package dev.finguard.dashboard.controller;

import dev.finguard.dashboard.service.DashboardService;
import dev.finguard.dashboard.service.DashboardService.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Dashboard REST API for the FinGuard system.
 *
 * <p>Provides aggregated statistics for the frontend dashboard, covering
 * system overview, detection analytics, explanation quality, dataset
 * breakdowns, and experiment summaries.</p>
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Dashboard", description = "Aggregated system statistics and analytics")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /**
     * System overview: total counts of transactions, alerts, explanations,
     * fraud patterns, experiments, and the overall fraud rate.
     */
    @GetMapping("/overview")
    @Operation(summary = "System overview with key counts and fraud rate")
    public ResponseEntity<SystemOverview> getOverview() {
        return ResponseEntity.ok(dashboardService.getSystemOverview());
    }

    /**
     * Detection analytics: alert distribution by status and config,
     * total anomalies, and average ML risk score.
     */
    @GetMapping("/detection")
    @Operation(summary = "Detection analytics with alert distributions")
    public ResponseEntity<DetectionAnalytics> getDetectionAnalytics() {
        return ResponseEntity.ok(dashboardService.getDetectionAnalytics());
    }

    /**
     * Explanation quality: CAKR scores, hallucination stats, latency,
     * and explanation type distribution.
     */
    @GetMapping("/explanation-quality")
    @Operation(summary = "Explanation quality metrics and CAKR averages")
    public ResponseEntity<ExplanationQuality> getExplanationQuality() {
        return ResponseEntity.ok(dashboardService.getExplanationQuality());
    }

    /**
     * Dataset breakdown: transaction and fraud counts per data source.
     */
    @GetMapping("/datasets")
    @Operation(summary = "Transaction and fraud counts by dataset source")
    public ResponseEntity<List<DatasetBreakdown>> getDatasetBreakdown() {
        return ResponseEntity.ok(dashboardService.getDatasetBreakdown());
    }

    /**
     * Transaction type distribution.
     */
    @GetMapping("/transaction-types")
    @Operation(summary = "Transaction count by type")
    public ResponseEntity<Map<String, Long>> getTransactionTypeDistribution() {
        return ResponseEntity.ok(dashboardService.getTransactionTypeDistribution());
    }

    /**
     * Experiment summaries with detection and explanation metrics.
     *
     * @param experimentName optional filter by experiment name
     */
    @GetMapping("/experiments")
    @Operation(summary = "Experiment results with detection and CAKR metrics")
    public ResponseEntity<List<ExperimentSummary>> getExperimentSummaries(
            @RequestParam(required = false) String experimentName) {
        return ResponseEntity.ok(dashboardService.getExperimentSummaries(experimentName));
    }

    /**
     * Pipeline-stage timing breakdowns aggregated from the in-memory
     * Micrometer registry. Powers the dashboard "Pipeline Stage Timings" panel.
     *
     * <p>Counts and totals reset when the JVM restarts — this endpoint reflects
     * activity since the last app start.</p>
     */
    @GetMapping("/timings")
    @Operation(summary = "Pipeline stage timing breakdowns (ingestion, training, evaluation)")
    public ResponseEntity<TimingsSummary> getTimings() {
        return ResponseEntity.ok(dashboardService.getTimings());
    }

    /**
     * Combined dashboard payload — all sections in a single response.
     *
     * <p>Useful for initial dashboard load to avoid multiple round trips.</p>
     */
    @GetMapping("/full")
    @Operation(summary = "Complete dashboard data in a single response")
    public ResponseEntity<FullDashboard> getFullDashboard() {
        return ResponseEntity.ok(new FullDashboard(
                dashboardService.getSystemOverview(),
                dashboardService.getDetectionAnalytics(),
                dashboardService.getExplanationQuality(),
                dashboardService.getDatasetBreakdown(),
                dashboardService.getTransactionTypeDistribution(),
                dashboardService.getExperimentSummaries(null)
        ));
    }

    record FullDashboard(
            SystemOverview overview,
            DetectionAnalytics detection,
            ExplanationQuality explanationQuality,
            List<DatasetBreakdown> datasets,
            Map<String, Long> transactionTypes,
            List<ExperimentSummary> experiments
    ) {}
}
