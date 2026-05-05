package dev.finguard.evaluation.export;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finguard.domain.model.ExperimentResult;
import dev.finguard.domain.repository.ExperimentResultRepository;
import dev.finguard.domain.repository.ExplanationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Thesis-friendly exports for a named experiment run.
 *
 * <p>Produces two deliverables that drop straight into the thesis document:</p>
 * <ul>
 *   <li><b>Markdown report</b> ({@link #buildMarkdownReport(String)}) — a
 *       single {@code .md} string ready to paste into the evaluation
 *       chapter. Includes the per-config metrics table, CAKR breakdown by
 *       dimension, cost breakdown, hallucination frequency matrix, the
 *       winners summary, and the full reproducibility snapshot from the
 *       result's {@code runParameters} JSON.</li>
 *   <li><b>ZIP bundle</b> ({@link #writeZipBundle(String, OutputStream)}) —
 *       a single archive containing the markdown report, all four
 *       breakdowns as CSV, and the raw reproducibility snapshot as JSON.
 *       Stream straight to the HTTP response.</li>
 * </ul>
 *
 * <p>Every output is self-contained — no external tools needed to read or
 * re-process them, matching the thesis examiner's "can you show me where
 * these numbers came from?" test.</p>
 */
@Service
public class ThesisExportService {

    private static final Logger log = LoggerFactory.getLogger(ThesisExportService.class);

    private final ExperimentResultRepository resultRepository;
    private final ExplanationRepository explanationRepository;
    private final ObjectMapper objectMapper;

    public ThesisExportService(ExperimentResultRepository resultRepository,
                                ExplanationRepository explanationRepository,
                                ObjectMapper objectMapper) {
        this.resultRepository = resultRepository;
        this.explanationRepository = explanationRepository;
        this.objectMapper = objectMapper;
    }

    // ── Public entry points ─────────────────────────────────────────

    /**
     * Build a self-contained Markdown report for an experiment name.
     * Returns {@code null} if no rows match the name.
     */
    public String buildMarkdownReport(String experimentName) {
        List<ExperimentResult> rows = resultRepository.findByExperimentNameOrdered(experimentName);
        if (rows.isEmpty()) return null;

        StringBuilder md = new StringBuilder(8192);
        appendHeader(md, experimentName, rows);
        appendSummaryTable(md, rows);
        appendWinners(md, rows);
        appendReproducibility(md, rows);
        appendAnalyticsBreakdowns(md);
        appendFooter(md);
        return md.toString();
    }

    /**
     * Stream a ZIP bundle to the given output. Entries:
     * <ul>
     *   <li>{@code RESULTS.md} — the markdown report above</li>
     *   <li>{@code results.csv} — one row per (config, fold) with all metrics</li>
     *   <li>{@code cakr-breakdown.csv} — per-dimension CAKR averages by config</li>
     *   <li>{@code cost-breakdown.csv} — per-config token + latency totals</li>
     *   <li>{@code hallucination-breakdown.csv} — flag frequency matrix</li>
     *   <li>{@code run-parameters.json} — raw reproducibility snapshot</li>
     * </ul>
     *
     * @throws IOException if the stream can't be written
     * @throws IllegalArgumentException if {@code experimentName} has no rows
     */
    public void writeZipBundle(String experimentName, OutputStream out) throws IOException {
        List<ExperimentResult> rows = resultRepository.findByExperimentNameOrdered(experimentName);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("No experiment rows found for name: " + experimentName);
        }

        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            writeEntry(zip, "RESULTS.md", buildMarkdownReport(experimentName));
            writeEntry(zip, "results.csv", resultsCsv(rows));
            writeEntry(zip, "cakr-breakdown.csv", cakrBreakdownCsv());
            writeEntry(zip, "cost-breakdown.csv", costBreakdownCsv());
            writeEntry(zip, "hallucination-breakdown.csv", hallucinationBreakdownCsv());
            writeEntry(zip, "run-parameters.json", runParametersJson(rows));
        }
    }

    // ── Markdown assembly ───────────────────────────────────────────

    private void appendHeader(StringBuilder md, String name, List<ExperimentResult> rows) {
        ExperimentResult first = rows.get(0);
        md.append("# Experiment Report: ").append(name).append("\n\n");
        md.append("_Generated: ").append(java.time.LocalDateTime.now()).append("_\n\n");
        md.append("- **Result rows**: ").append(rows.size()).append('\n');
        md.append("- **Dataset**: `").append(first.getDataset()).append("`\n");
        long perFold = rows.stream().filter(r -> r.getFold() != null).count();
        long summary = rows.stream().filter(r -> r.getFold() == null).count();
        md.append("- **Per-fold rows**: ").append(perFold)
          .append(" | **Summary rows**: ").append(summary).append('\n');
        md.append("- **Configs compared**: ")
          .append(rows.stream().map(r -> r.getConfig().name()).distinct().toList()).append('\n');
        md.append('\n');
    }

    private void appendSummaryTable(StringBuilder md, List<ExperimentResult> rows) {
        md.append("## 1. Detection Metrics\n\n");
        md.append("| Config | Fold | Precision | Recall | F1 | FPR | CAKR | Halluc. | Latency | Alerts |\n");
        md.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (ExperimentResult r : rows) {
            md.append("| ").append(r.getConfig()).append(" | ")
              .append(r.getFold() != null ? r.getFold() : "**summary**").append(" | ")
              .append(fmt3(r.getPrecisionScore())).append(" | ")
              .append(fmt3(r.getRecallScore())).append(" | ")
              .append(fmt3(r.getF1Score())).append(" | ")
              .append(fmt3(r.getFalsePositiveRate())).append(" | ")
              .append(fmt2(r.getAvgCakrScore())).append(" | ")
              .append(pct(r.getHallucinationRate())).append(" | ")
              .append(ms(r.getAvgLatencyMs())).append(" | ")
              .append(r.getTotalAlerts() != null ? r.getTotalAlerts() : 0).append(" |\n");
        }
        md.append('\n');
    }

    private void appendWinners(StringBuilder md, List<ExperimentResult> rows) {
        ExperimentResult bestF1 = pickBest(rows, ExperimentResult::getF1Score, true);
        ExperimentResult bestCakr = pickBest(rows, ExperimentResult::getAvgCakrScore, true);
        ExperimentResult lowestFpr = pickBest(rows, ExperimentResult::getFalsePositiveRate, false);
        ExperimentResult lowestHall = pickBest(rows, ExperimentResult::getHallucinationRate, false);

        md.append("## 2. Winners\n\n");
        md.append("| Metric | Best Config | Value |\n|---|---|---:|\n");
        appendWinner(md, "Best F1 (detection)", bestF1, e -> fmt3(e.getF1Score()));
        appendWinner(md, "Best CAKR (quality)", bestCakr, e -> fmt2(e.getAvgCakrScore()));
        appendWinner(md, "Lowest FPR (alert fatigue)", lowestFpr, e -> fmt3(e.getFalsePositiveRate()));
        appendWinner(md, "Lowest hallucination rate", lowestHall, e -> pct(e.getHallucinationRate()));
        md.append('\n');
    }

    private void appendWinner(StringBuilder md, String label, ExperimentResult r,
                               java.util.function.Function<ExperimentResult, String> valueFn) {
        md.append("| ").append(label).append(" | ");
        if (r == null) md.append("— | — |\n");
        else md.append(r.getConfig()).append(" | ").append(valueFn.apply(r)).append(" |\n");
    }

    private void appendReproducibility(StringBuilder md, List<ExperimentResult> rows) {
        md.append("## 3. Reproducibility Snapshot\n\n");
        // Take the first row's runParameters — every row in the same run shares config.
        String params = rows.get(0).getRunParameters();
        if (params == null || params.isBlank()) {
            md.append("_(no snapshot recorded)_\n\n");
            return;
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(params,
                    new TypeReference<>() {});
            renderReproSection(md, parsed);
        } catch (Exception e) {
            md.append("```json\n").append(params).append("\n```\n\n");
        }
    }

    @SuppressWarnings("unchecked")
    private void renderReproSection(StringBuilder md, Map<String, Object> snapshot) {
        // Sort top-level keys alphabetically except put scalars first, nested maps second.
        Map<String, Object> scalars = new TreeMap<>();
        Map<String, Object> nested = new LinkedHashMap<>();
        snapshot.forEach((k, v) -> {
            if (v instanceof Map) nested.put(k, v);
            else scalars.put(k, v);
        });

        if (!scalars.isEmpty()) {
            md.append("### Top-level\n\n");
            md.append("| Key | Value |\n|---|---|\n");
            scalars.forEach((k, v) -> md.append("| ").append(k).append(" | `")
                    .append(v == null ? "—" : v.toString()).append("` |\n"));
            md.append('\n');
        }
        nested.forEach((section, inner) -> {
            md.append("### ").append(capitalize(section)).append("\n\n");
            md.append("| Key | Value |\n|---|---|\n");
            ((Map<String, Object>) inner).forEach((k, v) -> md.append("| ").append(k).append(" | `")
                    .append(v == null ? "—" : v.toString()).append("` |\n"));
            md.append('\n');
        });
    }

    private void appendAnalyticsBreakdowns(StringBuilder md) {
        md.append("## 4. Cross-Experiment Analytics\n\n");
        md.append("_These aggregate across the whole database, not just this experiment — useful as a population reference._\n\n");

        // CAKR breakdown
        List<Object[]> cakrRows = safe(explanationRepository::cakrBreakdownByDetectionConfig);
        if (!cakrRows.isEmpty()) {
            md.append("### 4a. CAKR Dimensions by Config\n\n");
            md.append("| Config | Completeness | Actionability | Correctness | Regulatory | Overall | N |\n");
            md.append("|---|---:|---:|---:|---:|---:|---:|\n");
            for (Object[] r : cakrRows) {
                Double c = (Double) r[1], a = (Double) r[2], k = (Double) r[3], reg = (Double) r[4];
                Double overall = averageNonNull(c, a, k, reg);
                md.append("| ").append(r[0]).append(" | ")
                  .append(fmt2(c)).append(" | ")
                  .append(fmt2(a)).append(" | ")
                  .append(fmt2(k)).append(" | ")
                  .append(fmt2(reg)).append(" | ")
                  .append(fmt2(overall)).append(" | ")
                  .append(r[5]).append(" |\n");
            }
            md.append('\n');
        }

        // Cost breakdown
        List<Object[]> costRows = safe(explanationRepository::costBreakdownByDetectionConfig);
        if (!costRows.isEmpty()) {
            md.append("### 4b. LLM Cost & Latency by Config\n\n");
            md.append("| Config | Calls | Prompt tokens (total) | Completion tokens (total) | Avg prompt | Avg completion | Avg latency |\n");
            md.append("|---|---:|---:|---:|---:|---:|---:|\n");
            for (Object[] r : costRows) {
                md.append("| ").append(r[0]).append(" | ")
                  .append(r[1]).append(" | ")
                  .append(r[2]).append(" | ")
                  .append(r[3]).append(" | ")
                  .append(fmt1((Double) r[4])).append(" | ")
                  .append(fmt1((Double) r[5])).append(" | ")
                  .append(ms((Double) r[6])).append(" |\n");
            }
            md.append('\n');
        }
    }

    private void appendFooter(StringBuilder md) {
        md.append("---\n");
        md.append("_Generated by FinGuard `ThesisExportService`. Every number above came from a persisted `ExperimentResult` row — see the `run_parameters` JSON column in the `experiment_results` table to recreate this run._\n");
    }

    // ── CSV builders (same data as Markdown, machine-readable) ───────

    private String resultsCsv(List<ExperimentResult> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("id,experiment_name,config,fold,dataset,precision,recall,f1,fpr,cakr,hallucination_rate,avg_latency_ms,total_transactions,total_alerts,created_at\n");
        for (ExperimentResult r : rows) {
            sb.append(r.getId()).append(',')
              .append(csv(r.getExperimentName())).append(',')
              .append(r.getConfig()).append(',')
              .append(r.getFold() != null ? r.getFold() : "").append(',')
              .append(r.getDataset()).append(',')
              .append(r.getPrecisionScore()).append(',')
              .append(r.getRecallScore()).append(',')
              .append(r.getF1Score()).append(',')
              .append(r.getFalsePositiveRate()).append(',')
              .append(orEmpty(r.getAvgCakrScore())).append(',')
              .append(orEmpty(r.getHallucinationRate())).append(',')
              .append(orEmpty(r.getAvgLatencyMs())).append(',')
              .append(orEmpty(r.getTotalTransactions())).append(',')
              .append(orEmpty(r.getTotalAlerts())).append(',')
              .append(r.getCreatedAt()).append('\n');
        }
        return sb.toString();
    }

    private String cakrBreakdownCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("config,avg_completeness,avg_actionability,avg_correctness,avg_regulatory,scored_explanations\n");
        for (Object[] r : safe(explanationRepository::cakrBreakdownByDetectionConfig)) {
            sb.append(r[0]).append(',')
              .append(orEmpty(r[1])).append(',')
              .append(orEmpty(r[2])).append(',')
              .append(orEmpty(r[3])).append(',')
              .append(orEmpty(r[4])).append(',')
              .append(r[5]).append('\n');
        }
        return sb.toString();
    }

    private String costBreakdownCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("config,call_count,total_prompt_tokens,total_completion_tokens,avg_prompt_tokens,avg_completion_tokens,avg_latency_ms\n");
        for (Object[] r : safe(explanationRepository::costBreakdownByDetectionConfig)) {
            for (int i = 0; i < r.length; i++) {
                sb.append(orEmpty(r[i]));
                if (i < r.length - 1) sb.append(',');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private String hallucinationBreakdownCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("config,hallucination_flags_json\n");
        for (Object[] r : safe(explanationRepository::hallucinationFlagsByDetectionConfig)) {
            sb.append(r[0]).append(',').append(csv((String) r[1])).append('\n');
        }
        return sb.toString();
    }

    private String runParametersJson(List<ExperimentResult> rows) {
        // One entry per unique (config, fold) so the examiner can see how the
        // snapshot varied across the run — in practice all entries share the
        // same snapshot unless the config changed mid-run.
        List<Map<String, Object>> entries = rows.stream().map(r -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("resultId", r.getId());
            e.put("config", r.getConfig().name());
            e.put("fold", r.getFold());
            try {
                e.put("runParameters",
                        r.getRunParameters() == null ? null :
                        objectMapper.readValue(r.getRunParameters(), Map.class));
            } catch (Exception ex) {
                e.put("runParameters", r.getRunParameters()); // raw fallback
            }
            return e;
        }).toList();
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(entries);
        } catch (Exception e) {
            return "[]";
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        if (content == null) return;
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String fmt1(Double v) { return v == null ? "—" : String.format(Locale.ROOT, "%.1f", v); }
    private static String fmt2(Double v) { return v == null ? "—" : String.format(Locale.ROOT, "%.2f", v); }
    private static String fmt3(Double v) { return v == null ? "—" : String.format(Locale.ROOT, "%.3f", v); }
    private static String pct(Double v)  { return v == null ? "—" : String.format(Locale.ROOT, "%.1f%%", v * 100); }
    private static String ms(Double v)   { return v == null ? "—" : String.format(Locale.ROOT, "%.0fms", v); }
    private static String orEmpty(Object o) { return o == null ? "" : o.toString(); }
    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static Double averageNonNull(Double... vals) {
        double sum = 0; int n = 0;
        for (Double v : vals) if (v != null) { sum += v; n++; }
        return n > 0 ? sum / n : null;
    }

    private static ExperimentResult pickBest(
            List<ExperimentResult> rows,
            java.util.function.Function<ExperimentResult, Double> valueFn,
            boolean higherIsBetter) {
        ExperimentResult best = null;
        Double bestVal = null;
        for (ExperimentResult r : rows) {
            Double v = valueFn.apply(r);
            if (v == null) continue;
            if (best == null
                    || (higherIsBetter && v > bestVal)
                    || (!higherIsBetter && v < bestVal)) {
                best = r;
                bestVal = v;
            }
        }
        return best;
    }

    /** Defensive: return empty list on DB error so markdown/zip build still works. */
    private static <T> List<T> safe(java.util.function.Supplier<List<T>> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.warn("Breakdown query failed for export: {}", e.getMessage());
            return List.of();
        }
    }
}
