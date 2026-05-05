package dev.finguard.dashboard.service;

import dev.finguard.detection.ml.TribuoModelService;
import dev.finguard.domain.model.LlmProviderConfig;
import dev.finguard.domain.repository.AlertRepository;
import dev.finguard.domain.repository.ExplanationRepository;
import dev.finguard.domain.repository.FraudPatternRepository;
import dev.finguard.domain.repository.TransactionFeaturesRepository;
import dev.finguard.domain.repository.TransactionRepository;
import dev.finguard.llm.LlmProviderConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs pre-defence sanity checks across the dataset + runtime state.
 *
 * <p>Purpose: answer <em>"would the thesis numbers I'm about to report actually
 * be meaningful?"</em> before the examiner notices they aren't. Each check
 * returns a {@link Check} with a PASS / WARN / FAIL severity, a human-readable
 * message, and when things aren't PASS, a concrete next step.</p>
 *
 * <p>Everything is read-only and cheap — the checks are a handful of indexed
 * COUNT queries that finish in milliseconds. Safe to call on every page load
 * of the sanity-check view.</p>
 */
@Service
public class DataQualityService {

    private static final Logger log = LoggerFactory.getLogger(DataQualityService.class);

    public enum Severity { PASS, WARN, FAIL }

    /**
     * One check result.
     *
     * @param id       stable slug (e.g., {@code "features-coverage"}) — used as an anchor in the UI
     * @param category short bucket name ({@code "Data"}, {@code "Models"}, {@code "LLM"})
     * @param title    human-readable check title
     * @param severity PASS / WARN / FAIL
     * @param message  one-sentence explanation
     * @param hint     when not PASS, the next action to take; else {@code null}
     * @param details  optional key→value pairs for the detail panel
     */
    public record Check(
            String id,
            String category,
            String title,
            Severity severity,
            String message,
            String hint,
            Map<String, Object> details
    ) {}

    /** Bundled response: top-line status + ordered checks. */
    public record HealthReport(
            int total, int passed, int warnings, int failed,
            Severity overall,
            List<Check> checks
    ) {}

    private final TransactionRepository txRepo;
    private final TransactionFeaturesRepository featuresRepo;
    private final AlertRepository alertRepo;
    private final ExplanationRepository explanationRepo;
    private final FraudPatternRepository patternRepo;
    private final TribuoModelService mlService;
    private final LlmProviderConfigService llmConfigService;

    public DataQualityService(TransactionRepository txRepo,
                               TransactionFeaturesRepository featuresRepo,
                               AlertRepository alertRepo,
                               ExplanationRepository explanationRepo,
                               FraudPatternRepository patternRepo,
                               TribuoModelService mlService,
                               LlmProviderConfigService llmConfigService) {
        this.txRepo = txRepo;
        this.featuresRepo = featuresRepo;
        this.alertRepo = alertRepo;
        this.explanationRepo = explanationRepo;
        this.patternRepo = patternRepo;
        this.mlService = mlService;
        this.llmConfigService = llmConfigService;
    }

    public HealthReport run() {
        List<Check> checks = new ArrayList<>();
        checks.add(checkTransactions());
        checks.add(checkFeaturesCoverage());
        checks.add(checkTrainTestSplit());
        checks.add(checkAlerts());
        checks.add(checkExplanations());
        checks.add(checkCakrCoverage());
        checks.add(checkMlModels());
        checks.add(checkLlmProvider());
        checks.add(checkKnowledgeBase());

        int passed  = (int) checks.stream().filter(c -> c.severity() == Severity.PASS).count();
        int warns   = (int) checks.stream().filter(c -> c.severity() == Severity.WARN).count();
        int fails   = (int) checks.stream().filter(c -> c.severity() == Severity.FAIL).count();
        Severity overall = fails > 0 ? Severity.FAIL : (warns > 0 ? Severity.WARN : Severity.PASS);

        return new HealthReport(checks.size(), passed, warns, fails, overall, checks);
    }

    // ── Individual checks ────────────────────────────────────────────

    private Check checkTransactions() {
        long total = safe(txRepo::count);
        long fraud = safe(txRepo::countByIsFraudTrue);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("total", total);
        details.put("fraud", fraud);
        details.put("fraudRatePercent", total > 0 ? round(100.0 * fraud / total, 3) : 0.0);

        if (total == 0) {
            return new Check("transactions", "Data", "Transactions",
                    Severity.FAIL, "No transactions loaded.",
                    "Go to Data Ingestion and import a PaySim CSV first.", details);
        }
        if (fraud == 0) {
            return new Check("transactions", "Data", "Transactions",
                    Severity.FAIL, "Transactions exist but none are labelled fraud — detection metrics will be undefined.",
                    "Verify the CSV's isFraud column is parsed correctly.", details);
        }
        double rate = 100.0 * fraud / total;
        if (rate > 20) {
            return new Check("transactions", "Data", "Transactions",
                    Severity.WARN,
                    String.format("Fraud rate is %.1f%% — higher than realistic datasets (PaySim ≈ 0.13%%, IBM AML ≈ 1–3%%).",
                            rate),
                    "If you intentionally sub-sampled, this is fine; otherwise check dataset integrity.", details);
        }
        return new Check("transactions", "Data", "Transactions",
                Severity.PASS,
                String.format("%s rows total · %s fraud (%.3f%%).",
                        format(total), format(fraud), rate),
                null, details);
    }

    private Check checkFeaturesCoverage() {
        long total = safe(txRepo::count);
        long features = safe(featuresRepo::count);
        Map<String, Object> details = Map.of(
                "transactions", total,
                "featuresComputed", features,
                "coveragePercent", total > 0 ? round(100.0 * features / total, 2) : 0.0);

        if (total == 0) {
            return new Check("features-coverage", "Data", "Feature coverage",
                    Severity.WARN, "No transactions to compute features for.",
                    "Import data first.", details);
        }
        if (features == 0) {
            return new Check("features-coverage", "Data", "Feature coverage",
                    Severity.FAIL, "No ML features computed — every ML-based detection config will skip every row.",
                    "Run 'Compute Features' on the Data Ingestion page.", details);
        }
        if (features < total) {
            long pending = total - features;
            return new Check("features-coverage", "Data", "Feature coverage",
                    Severity.WARN,
                    String.format("Features computed for %s of %s transactions (%.1f%%) — %s still pending.",
                            format(features), format(total), 100.0 * features / total, format(pending)),
                    "Re-run 'Compute Features' — it's idempotent and skips completed rows.", details);
        }
        return new Check("features-coverage", "Data", "Feature coverage",
                Severity.PASS,
                String.format("All %s transactions have features.", format(total)),
                null, details);
    }

    private Check checkTrainTestSplit() {
        long total = safe(txRepo::count);
        if (total == 0) {
            return new Check("train-test-split", "Data", "Train/test split",
                    Severity.WARN, "No transactions — split not applicable.", null, Map.of());
        }
        long training = safe(() -> txRepo.countByIsTrainingSetTrue());
        long test = total - training;
        double testShare = total > 0 ? 100.0 * test / total : 0.0;
        Map<String, Object> details = Map.of(
                "training", training,
                "test", test,
                "testSharePercent", round(testShare, 2));

        if (training == 0 || test == 0) {
            return new Check("train-test-split", "Data", "Train/test split",
                    Severity.FAIL,
                    "Either training set or test set is empty — evaluation would be degenerate.",
                    "Re-run Liquibase migration 016 (temporal split) to partition the rows.", details);
        }
        // Temporal split target: 80/20 ± 5%.
        if (testShare < 10 || testShare > 30) {
            return new Check("train-test-split", "Data", "Train/test split",
                    Severity.WARN,
                    String.format("Test-set share is %.1f%% — unusual for an 80/20 split.", testShare),
                    "Verify that changelog 016 ran and the timestamp cutoff is correct.", details);
        }
        return new Check("train-test-split", "Data", "Train/test split",
                Severity.PASS,
                String.format("%.1f%% training / %.1f%% test (%s / %s rows).",
                        100.0 - testShare, testShare, format(training), format(test)),
                null, details);
    }

    private Check checkAlerts() {
        long total = safe(alertRepo::count);
        Map<String, Object> details = Map.of("totalAlerts", total);

        if (total == 0) {
            return new Check("alerts", "Results", "Alerts",
                    Severity.WARN, "No alerts in the database — detection pipeline hasn't run yet.",
                    "Go to Run Detection and execute a configuration.", details);
        }
        return new Check("alerts", "Results", "Alerts",
                Severity.PASS,
                String.format("%s alerts across all configurations.", format(total)),
                null, details);
    }

    private Check checkExplanations() {
        long total = safe(explanationRepo::count);
        long evaluated = safe(explanationRepo::countByHallucinationFreeTrue)
                + safe(explanationRepo::countByHallucinationFreeFalse);
        long alertCount = safe(alertRepo::count);
        Map<String, Object> details = Map.of(
                "totalExplanations", total,
                "hallucinationEvaluated", evaluated,
                "alertCount", alertCount);

        if (alertCount == 0) {
            return new Check("explanations", "Results", "Explanations",
                    Severity.PASS, "No alerts yet, so no explanations expected.",
                    null, details);
        }
        if (total == 0) {
            return new Check("explanations", "Results", "Explanations",
                    Severity.WARN,
                    "Alerts exist but no explanations — LLM-using configs haven't run explanation batches.",
                    "Run detection with an LLM config (Direct / RAG / Full System) or trigger batch generation.",
                    details);
        }
        if (total > 0 && evaluated < total * 0.5) {
            return new Check("explanations", "Results", "Explanations",
                    Severity.WARN,
                    String.format("%s of %s explanations have been hallucination-evaluated (%.0f%%).",
                            format(evaluated), format(total), 100.0 * evaluated / total),
                    "Run detection again — validation runs after generation.", details);
        }
        return new Check("explanations", "Results", "Explanations",
                Severity.PASS,
                String.format("%s explanations · %s hallucination-evaluated.",
                        format(total), format(evaluated)),
                null, details);
    }

    private Check checkCakrCoverage() {
        long total = safe(explanationRepo::count);
        // Any CAKR dim populated = explanation was at least partially scored.
        long scored = countScored();
        Map<String, Object> details = Map.of(
                "totalExplanations", total,
                "cakrScored", scored);

        if (total == 0) {
            return new Check("cakr-coverage", "Results", "CAKR scoring coverage",
                    Severity.PASS, "No explanations to score.", null, details);
        }
        if (scored == 0) {
            return new Check("cakr-coverage", "Results", "CAKR scoring coverage",
                    Severity.WARN,
                    "No explanations have been CAKR-scored — explanation-quality metrics will be unavailable.",
                    "Run an experiment with 'scoreExplanations' enabled.", details);
        }
        double pct = 100.0 * scored / total;
        if (pct < 50) {
            return new Check("cakr-coverage", "Results", "CAKR scoring coverage",
                    Severity.WARN,
                    String.format("Only %s of %s explanations (%.0f%%) have CAKR scores — averages may be noisy.",
                            format(scored), format(total), pct),
                    "Increase 'maxExplanationsToScore' or re-score via the Explanations page.", details);
        }
        return new Check("cakr-coverage", "Results", "CAKR scoring coverage",
                Severity.PASS,
                String.format("%s of %s explanations CAKR-scored (%.0f%%).",
                        format(scored), format(total), pct),
                null, details);
    }

    private Check checkMlModels() {
        boolean available = mlService.isModelAvailable();
        Map<String, Object> details = Map.of("loaded", available);
        if (!available) {
            return new Check("ml-models", "Models", "ML models loaded",
                    Severity.WARN,
                    "Tribuo models are not in memory — all ML-based detection configs will short-circuit.",
                    "Go to ML Models page and either Train Both or Load from Disk.", details);
        }
        return new Check("ml-models", "Models", "ML models loaded",
                Severity.PASS,
                "Random Forest + XGBoost ensemble in memory.", null, details);
    }

    private Check checkLlmProvider() {
        LlmProviderConfig active = null;
        try {
            active = llmConfigService.findAll().stream()
                    .filter(LlmProviderConfig::isActive)
                    .findFirst().orElse(null);
        } catch (Exception ignored) { /* shown as FAIL below */ }

        if (active == null) {
            return new Check("llm-provider", "LLM", "LLM provider configured",
                    Severity.WARN,
                    "No active LLM provider — LLM-using detection configs cannot generate explanations.",
                    "Go to LLM Settings and activate a provider (Ollama for local, Anthropic/OpenAI for cloud).",
                    Map.of("active", false));
        }
        boolean needsKey = active.getProvider() != dev.finguard.domain.enums.LlmProvider.OLLAMA;
        boolean hasKey = active.getApiKey() != null && !active.getApiKey().isBlank();
        if (needsKey && !hasKey) {
            return new Check("llm-provider", "LLM", "LLM provider configured",
                    Severity.FAIL,
                    "Active provider " + active.getProvider() + " has no API key stored.",
                    "Open LLM Settings and enter the key.",
                    Map.of("provider", active.getProvider().name(), "hasApiKey", false));
        }
        return new Check("llm-provider", "LLM", "LLM provider configured",
                Severity.PASS,
                "Active: " + active.getProvider() + " · " + active.getModelName(),
                null,
                Map.of("provider", active.getProvider().name(),
                       "model", active.getModelName(),
                       "hasApiKey", hasKey));
    }

    private Check checkKnowledgeBase() {
        long count = safe(patternRepo::count);
        Map<String, Object> details = Map.of("patterns", count);
        if (count == 0) {
            return new Check("knowledge-base", "LLM", "Knowledge base (RAG)",
                    Severity.WARN,
                    "No fraud patterns seeded — RAG-based explanations will have no retrieval context.",
                    "Open Knowledge Base and click 'Seed Knowledge Base'.", details);
        }
        if (count < 5) {
            return new Check("knowledge-base", "LLM", "Knowledge base (RAG)",
                    Severity.WARN,
                    String.format("Only %d fraud patterns — top-k=5 retrieval may return duplicates.", count),
                    "Seed more patterns or lower finguard.explanation.max-rag-results.", details);
        }
        return new Check("knowledge-base", "LLM", "Knowledge base (RAG)",
                Severity.PASS,
                count + " fraud patterns seeded.", null, details);
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private long countScored() {
        try {
            // Any of the 4 dims populated counts as "scored"; simplest is completeness
            // since CAKRScorer writes them together.
            return explanationRepo.findAll().stream()
                    .filter(e -> e.getCakrCompleteness() != null)
                    .count();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Failure-tolerant Long supplier — returns 0 on any exception so a single
     *  query hiccup doesn't take down the whole health report. */
    private static long safe(java.util.function.Supplier<Long> s) {
        try { Long v = s.get(); return v == null ? 0 : v; } catch (Exception e) { return 0; }
    }
    private static String format(long n) { return String.format("%,d", n); }
    private static double round(double v, int scale) {
        double f = Math.pow(10, scale);
        return Math.round(v * f) / f;
    }
}
