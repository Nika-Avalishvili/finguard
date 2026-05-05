package dev.finguard.presentation.controller;

import dev.finguard.domain.enums.DetectionConfig;
import dev.finguard.domain.model.Alert;
import dev.finguard.domain.model.ExperimentResult;
import dev.finguard.domain.repository.AlertRepository;
import dev.finguard.domain.repository.ExperimentResultRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.stream.Stream;

/**
 * REST API for exporting data as CSV files.
 *
 * <p>Audit A-7: both endpoints stream via {@code Stream<T>} + a JDBC cursor
 * rather than materialising the whole table into heap. The caller method is
 * {@code @Transactional(readOnly=true)} so the cursor survives for the length
 * of the export, and the stream is always closed via try-with-resources.</p>
 */
@RestController
@RequestMapping("/api/v1/export")
@Tag(name = "Export", description = "CSV data export for analysis")
public class ExportController {

    private static final Logger log = LoggerFactory.getLogger(ExportController.class);

    /** Progress milestone — emit INFO log every N rows written. */
    private static final int PROGRESS_INTERVAL = 10_000;

    private final ExperimentResultRepository experimentResultRepository;
    private final AlertRepository alertRepository;
    private final dev.finguard.evaluation.export.ThesisExportService thesisExportService;

    public ExportController(ExperimentResultRepository experimentResultRepository,
                             AlertRepository alertRepository,
                             dev.finguard.evaluation.export.ThesisExportService thesisExportService) {
        this.experimentResultRepository = experimentResultRepository;
        this.alertRepository = alertRepository;
        this.thesisExportService = thesisExportService;
    }

    /**
     * Return an experiment's results as a self-contained Markdown report.
     *
     * <p>Pastes straight into the thesis evaluation chapter. Includes the
     * metrics table, winners summary, full reproducibility snapshot (ML
     * hyperparams, LLM config, dataset counts, JVM environment), and the
     * cross-experiment CAKR / cost breakdowns for population reference.</p>
     */
    @GetMapping(value = "/experiments/{experimentName}/markdown",
                produces = "text/markdown; charset=UTF-8")
    @Operation(summary = "Markdown report for a named experiment (thesis-ready)")
    public ResponseEntity<String> exportMarkdown(@PathVariable String experimentName) {
        String md = thesisExportService.buildMarkdownReport(experimentName);
        if (md == null) {
            return ResponseEntity.notFound().build();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(org.springframework.http.ContentDisposition
                .attachment().filename(experimentName + "-RESULTS.md").build());
        headers.set(HttpHeaders.CACHE_CONTROL, "no-store");
        return new ResponseEntity<>(md, headers, HttpStatus.OK);
    }

    /**
     * Stream a thesis-appendix ZIP bundle for a named experiment.
     *
     * <p>Contains the markdown report, all four breakdowns as CSV, and the
     * raw {@code runParameters} JSON per result row. Drops straight into
     * the thesis document's supplementary materials folder.</p>
     */
    @GetMapping(value = "/experiments/{experimentName}/bundle",
                produces = "application/zip")
    @Operation(summary = "Thesis-appendix ZIP bundle for a named experiment")
    public void exportBundle(@PathVariable String experimentName,
                              HttpServletResponse response) throws java.io.IOException {
        try {
            response.setContentType("application/zip");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + experimentName + "-bundle.zip\"");
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            thesisExportService.writeZipBundle(experimentName, response.getOutputStream());
            response.flushBuffer();
        } catch (IllegalArgumentException notFound) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            response.getWriter().write("{\"error\":\"" + notFound.getMessage() + "\"}");
        }
    }

    /**
     * Stream experiment results as CSV.
     */
    @GetMapping("/experiments")
    @Operation(summary = "Export experiment results as CSV (streaming)",
               description = "Streams all experiment results row-by-row via JDBC cursor; " +
                             "safe for millions of rows without OOM.")
    @Transactional(readOnly = true)
    public void exportExperiments(
            @Parameter(description = "Filter by experiment name")
            @RequestParam(required = false) String experimentName,
            HttpServletResponse response) throws IOException {

        response.setContentType("text/csv");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"experiment-results.csv\"");

        long startedAt = System.currentTimeMillis();
        log.info("[export/experiments] Starting stream (filter experimentName={})",
                experimentName != null ? experimentName : "ALL");

        long[] written = {0};
        try (PrintWriter writer = response.getWriter();
             Stream<ExperimentResult> stream = experimentName != null
                     ? experimentResultRepository.streamByExperimentNameOrdered(experimentName)
                     : experimentResultRepository.streamAll()) {

            writer.println("id,experiment_name,config,dataset,fold," +
                    "precision,recall,f1_score,false_positive_rate," +
                    "total_transactions,total_alerts," +
                    "avg_cakr_score,hallucination_rate,avg_latency_ms,created_at");

            stream.forEach(r -> {
                writer.printf("%d,%s,%s,%s,%s,%.6f,%.6f,%.6f,%.6f,%d,%d,%s,%s,%s,%s%n",
                        r.getId(),
                        escapeCsv(r.getExperimentName()),
                        r.getConfig(),
                        r.getDataset() != null ? r.getDataset() : "",
                        r.getFold() != null ? r.getFold() : "",
                        defaultZero(r.getPrecisionScore()),
                        defaultZero(r.getRecallScore()),
                        defaultZero(r.getF1Score()),
                        defaultZero(r.getFalsePositiveRate()),
                        defaultZeroInt(r.getTotalTransactions()),
                        defaultZeroInt(r.getTotalAlerts()),
                        r.getAvgCakrScore() != null ? String.format("%.4f", r.getAvgCakrScore()) : "",
                        r.getHallucinationRate() != null ? String.format("%.4f", r.getHallucinationRate()) : "",
                        r.getAvgLatencyMs() != null ? String.format("%.0f", r.getAvgLatencyMs()) : "",
                        r.getCreatedAt() != null ? r.getCreatedAt().toString() : ""
                );
                written[0]++;
                if (written[0] % PROGRESS_INTERVAL == 0) {
                    log.info("[export/experiments] streamed {} rows in {}ms",
                            written[0], System.currentTimeMillis() - startedAt);
                }
            });
        }
        log.info("[export/experiments] Complete — {} rows in {}ms",
                written[0], System.currentTimeMillis() - startedAt);
    }

    /**
     * Stream alerts as CSV.
     */
    @GetMapping("/alerts")
    @Operation(summary = "Export alerts as CSV (streaming)",
               description = "Streams alerts row-by-row via JDBC cursor; safe for millions of rows.")
    @Transactional(readOnly = true)
    public void exportAlerts(
            @Parameter(description = "Filter by detection config")
            @RequestParam(required = false) DetectionConfig config,
            HttpServletResponse response) throws IOException {

        response.setContentType("text/csv");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"alerts.csv\"");

        long startedAt = System.currentTimeMillis();
        log.info("[export/alerts] Starting stream (filter config={})",
                config != null ? config : "ALL");

        long[] written = {0};
        try (PrintWriter writer = response.getWriter();
             Stream<Alert> stream = config != null
                     ? alertRepository.streamAnomaliesByConfigWithTransaction(config)
                     : alertRepository.streamAllWithTransaction()) {

            writer.println("id,transaction_id,detection_config,status," +
                    "is_anomaly,rule_triggered,ml_risk_score,ml_model_name,created_at");

            stream.forEach(a -> {
                writer.printf("%d,%d,%s,%s,%b,%s,%s,%s,%s%n",
                        a.getId(),
                        a.getTransaction() != null ? a.getTransaction().getId() : 0,
                        a.getDetectionConfig(),
                        a.getStatus(),
                        a.getIsAnomaly(),
                        escapeCsv(a.getRuleTriggered()),
                        a.getMlRiskScore() != null ? String.format("%.6f", a.getMlRiskScore()) : "",
                        a.getMlModelName() != null ? escapeCsv(a.getMlModelName()) : "",
                        a.getCreatedAt() != null ? a.getCreatedAt().toString() : ""
                );
                written[0]++;
                if (written[0] % PROGRESS_INTERVAL == 0) {
                    log.info("[export/alerts] streamed {} rows in {}ms",
                            written[0], System.currentTimeMillis() - startedAt);
                }
            });
        }
        log.info("[export/alerts] Complete — {} rows in {}ms",
                written[0], System.currentTimeMillis() - startedAt);
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private double defaultZero(Double value) {
        return value != null ? value : 0.0;
    }

    private int defaultZeroInt(Integer value) {
        return value != null ? value : 0;
    }
}
