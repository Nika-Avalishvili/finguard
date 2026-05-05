package dev.finguard.dashboard.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finguard.dashboard.service.DashboardFacadeService;
import dev.finguard.dashboard.service.DashboardService;
import dev.finguard.dashboard.service.DashboardService.*;
import dev.finguard.detection.ml.TribuoModelService;
import dev.finguard.detection.rule.RuleThresholdService;
import dev.finguard.domain.enums.*;
import dev.finguard.domain.model.Alert;
import dev.finguard.domain.model.ExperimentResult;
import dev.finguard.domain.model.FraudPattern;
import dev.finguard.domain.model.Transaction;
import dev.finguard.llm.LlmProviderConfigService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Thymeleaf MVC controller serving all web pages.
 *
 * <p>This controller populates view models and returns template names.
 * Heavy operations (training, detection, seeding) are handled by the
 * existing REST controllers via JavaScript fetch calls from the templates.</p>
 */
@Controller
public class WebController {

    private final DashboardService dashboardService;
    private final DashboardFacadeService facade;
    private final TribuoModelService tribuoModelService;
    private final ObjectMapper objectMapper;
    private final LlmProviderConfigService llmProviderConfigService;
    private final RuleThresholdService ruleThresholdService;

    public WebController(DashboardService dashboardService,
                          DashboardFacadeService facade,
                          TribuoModelService tribuoModelService,
                          ObjectMapper objectMapper,
                          LlmProviderConfigService llmProviderConfigService,
                          RuleThresholdService ruleThresholdService) {
        this.dashboardService         = dashboardService;
        this.facade                   = facade;
        this.tribuoModelService       = tribuoModelService;
        this.objectMapper             = objectMapper;
        this.llmProviderConfigService = llmProviderConfigService;
        this.ruleThresholdService     = ruleThresholdService;
    }

    // ==============================================================
    // Dashboard
    // ==============================================================

    @GetMapping("/")
    public String landing(Model model) {
        // Live at-a-glance stats so the landing page doubles as a system
        // status summary — examiner/first-time user sees real numbers rather
        // than a static brochure.
        SystemOverview overview = dashboardService.getSystemOverview();
        boolean hasData = overview.totalTransactions() > 0;

        model.addAttribute("overview", overview);
        model.addAttribute("hasData", hasData);
        // Active LLM provider shown on the landing "Active Judge" tile
        llmProviderConfigService.findAll().stream()
                .filter(dev.finguard.domain.model.LlmProviderConfig::isActive)
                .findFirst()
                .ifPresent(cfg -> {
                    model.addAttribute("activeProvider", cfg.getProvider());
                    model.addAttribute("activeModel", cfg.getModelName());
                });
        model.addAttribute("activePage", "home");
        return "landing";
    }

    @GetMapping("/dashboard")
    public String dashboard(Model model, HttpServletResponse response) {
        response.setHeader("Cache-Control", "public, max-age=300");
        SystemOverview overview = dashboardService.getSystemOverview();
        DetectionAnalytics detection = dashboardService.getDetectionAnalytics();
        ExplanationQuality explQuality = dashboardService.getExplanationQuality();
        List<DatasetBreakdown> datasets = dashboardService.getDatasetBreakdown();
        List<ExperimentSummary> experiments = dashboardService.getExperimentSummaries(null);
        // Pipeline-stage timings powering the "Pipeline Stage Timings" panel.
        // Cheap to compute (in-memory MeterRegistry lookups) so we render server-side
        // on initial load and let HTMX swap in fresh data via /api/v1/dashboard/timings.
        dev.finguard.dashboard.service.DashboardService.TimingsSummary timings =
                dashboardService.getTimings();

        model.addAttribute("overview", overview);
        model.addAttribute("detection", detection);
        model.addAttribute("explQuality", explQuality);
        model.addAttribute("datasets", datasets);
        model.addAttribute("experiments", experiments);
        model.addAttribute("timings", timings);

        Map<String, Double> cakrEntries = new LinkedHashMap<>();
        cakrEntries.put("Completeness",  explQuality.cakrAverages().completeness());
        cakrEntries.put("Correctness",   explQuality.cakrAverages().correctness());
        cakrEntries.put("Actionability", explQuality.cakrAverages().actionability());
        cakrEntries.put("Regulatory",    explQuality.cakrAverages().regulatory());
        model.addAttribute("cakrEntries", cakrEntries);

        // The "Alerts by Status" / "Alerts by Detection Config" / "Transaction Types"
        // mini-charts were removed from the dashboard — their raw counts weren't
        // meaningful enough to justify visual space. The JSON payloads they were
        // serialising (alertsByStatusJson, alertsByConfigJson, txTypesJson) go with
        // them; the underlying REST endpoints on DashboardController are still
        // available for anyone building custom views.

        model.addAttribute("activePage", "dashboard");
        return "dashboard";
    }

    /**
     * HTMX fragment endpoint for the dashboard's "Pipeline Stage Timings" card.
     * Returns ONLY that card's HTML (server-rendered Thymeleaf fragment) so
     * the wrapper div can swap-in fresh values every 30 s without a full reload.
     */
    @GetMapping("/dashboard/timings-fragment")
    public String dashboardTimingsFragment(Model model) {
        model.addAttribute("timings", dashboardService.getTimings());
        return "fragments/dashboard-timings :: panel";
    }

    // ==============================================================
    // Transactions
    // ==============================================================

    @GetMapping("/transactions")
    public String transactions(
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "false") boolean fraudOnly,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            Model model) {

        Pageable pageable = PageRequest.of(page, 25, Sort.by("id").descending());

        DatasetSource sourceEnum = (source != null && !source.isBlank())
                ? DatasetSource.valueOf(source) : null;
        TransactionType typeEnum = (type != null && !type.isBlank())
                ? TransactionType.valueOf(type) : null;

        Page<Transaction> txPage = facade.transactionsPage(sourceEnum, fraudOnly, typeEnum, search, pageable);
        DashboardFacadeService.TransactionStats stats = facade.transactionStats();

        model.addAttribute("page", txPage);
        model.addAttribute("sources", DatasetSource.values());
        model.addAttribute("transactionTypes", TransactionType.values());
        model.addAttribute("selectedSource", source);
        model.addAttribute("selectedType", type);
        model.addAttribute("search", search);
        model.addAttribute("fraudOnly", fraudOnly);
        model.addAttribute("totalTransactions", stats.total());
        model.addAttribute("fraudTransactions", stats.fraud());
        model.addAttribute("fraudRatePercent", stats.fraudRatePercent());
        model.addAttribute("activePage", "transactions");
        return "transactions";
    }

    // ==============================================================
    // Alerts
    // ==============================================================

    @GetMapping("/alerts")
    public String alerts(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String config,
            @RequestParam(defaultValue = "0") int page,
            Model model) {

        Pageable pageable = PageRequest.of(page, 25, Sort.by("createdAt").descending());
        AlertStatus statusFilter = (status != null && !status.isBlank())
                ? AlertStatus.valueOf(status) : null;
        DetectionConfig configFilter = (config != null && !config.isBlank())
                ? DetectionConfig.valueOf(config) : null;

        Page<Alert> alertPage = facade.alertsPage(statusFilter, configFilter, pageable);
        DashboardFacadeService.AlertStats stats = facade.alertStats();

        model.addAttribute("page", alertPage);
        model.addAttribute("statuses", AlertStatus.values());
        model.addAttribute("configs", DetectionConfig.values());
        model.addAttribute("selectedStatus", status);
        model.addAttribute("selectedConfig", config);
        model.addAttribute("alertsByStatus", stats.alertsByStatus());
        model.addAttribute("totalAnomalies", stats.totalAnomalies());
        model.addAttribute("avgRiskScore", stats.avgRiskScore());
        model.addAttribute("activePage", "alerts");
        return "alerts";
    }

    @GetMapping("/alerts/{id}")
    public String alertDetail(@PathVariable Long id, Model model) {
        Alert alert = facade.loadAlertWithTransaction(id);
        if (alert == null) {
            return "redirect:/alerts";
        }
        model.addAttribute("alert", alert);
        model.addAttribute("transaction", alert.getTransaction());
        model.addAttribute("explanations", facade.explanationsForAlert(id));
        model.addAttribute("statuses", AlertStatus.values());
        model.addAttribute("activePage", "alerts");
        return "alert-detail";
    }

    @PostMapping("/alerts/{id}/status")
    public String updateAlertStatus(@PathVariable Long id, @RequestParam AlertStatus status) {
        facade.updateAlertStatus(id, status);
        return "redirect:/alerts/" + id;
    }

    // ==============================================================
    // Explanations
    // ==============================================================

    @GetMapping("/explanations")
    public String explanations(
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "all") String flag,
            @RequestParam(defaultValue = "0") int page,
            Model model) {
        Pageable pageable = PageRequest.of(page, 25, Sort.by("id").descending());
        ExplanationType typeFilter = null;
        if (type != null && !type.isBlank()) {
            try { typeFilter = ExplanationType.valueOf(type); }
            catch (IllegalArgumentException ignored) { /* invalid filter → show all */ }
        }
        model.addAttribute("page", facade.explanationsPage(typeFilter, flag, pageable));

        DashboardFacadeService.ExplanationStats stats = facade.explanationStats();
        model.addAttribute("total",         stats.total());
        model.addAttribute("avgConfidence", stats.avgConfidence());
        model.addAttribute("avgLatencyMs",  stats.avgLatencyMs());
        model.addAttribute("clean",         stats.clean());
        model.addAttribute("flagged",       stats.flagged());
        model.addAttribute("types",         stats.presentTypes());
        model.addAttribute("selectedType", type);
        model.addAttribute("selectedFlag", flag);
        model.addAttribute("activePage", "explanations");
        return "explanations";
    }

    // ==============================================================
    // Operations
    // ==============================================================

    @GetMapping("/ingestion")
    public String ingestion(Model model) {
        DashboardFacadeService.IngestionStats stats = facade.ingestionStats();
        model.addAttribute("totalTransactions", stats.totalTransactions());
        model.addAttribute("featuresCount",     stats.features());
        model.addAttribute("pendingFeatures",   stats.pending());
        model.addAttribute("activePage", "ingestion");
        return "ingestion";
    }

    @GetMapping("/detection")
    public String detection(Model model) {
        DashboardFacadeService.DetectionPageStats stats = facade.detectionPageStats();
        model.addAttribute("configs", DetectionConfig.values());
        model.addAttribute("totalTransactions", stats.totalTransactions());
        model.addAttribute("totalAlerts", stats.totalAlerts());
        model.addAttribute("featuresComputed", stats.featuresComputed());
        model.addAttribute("modelsAvailable", tribuoModelService.isModelAvailable());
        model.addAttribute("activePage", "detection");
        return "detection";
    }

    @GetMapping("/models")
    public String models(Model model) {
        DashboardFacadeService.DetectionPageStats stats = facade.detectionPageStats();
        model.addAttribute("modelsAvailable", tribuoModelService.isModelAvailable());
        model.addAttribute("totalTransactions", stats.totalTransactions());
        model.addAttribute("featuresComputed", stats.featuresComputed());
        model.addAttribute("activePage", "models");
        return "models";
    }

    @GetMapping("/knowledge")
    public String knowledge(Model model,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "50") int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 500));
        Page<FraudPattern> patternPage = facade.fraudPatternsPage(pageable);
        model.addAttribute("patterns", patternPage.getContent());
        // int-cast keeps the Thymeleaf expression simple; totals are always page-sized (<< Integer.MAX_VALUE).
        model.addAttribute("patternCount", (int) patternPage.getTotalElements());
        model.addAttribute("currentPage", patternPage.getNumber());
        model.addAttribute("totalPages", patternPage.getTotalPages());
        model.addAttribute("pageSize", patternPage.getSize());
        model.addAttribute("uniquePatternTypes", facade.uniquePatternTypes(patternPage));
        // Detection-rule metadata + current thresholds — feeds the editable
        // "Detection Rules" section on the Knowledge Base page.
        model.addAttribute("ruleThresholds", ruleThresholdService.listAll());
        model.addAttribute("activePage", "knowledge");
        return "knowledge";
    }

    @GetMapping("/evaluation")
    public String evaluation(Model model,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "50") int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0),
                Math.min(Math.max(size, 1), 200),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<ExperimentResult> resultPage = facade.experimentResultsPage(pageable);

        Set<String> experimentNames = facade.experimentNamesFromPage(resultPage);

        DashboardFacadeService.EvaluationPreflight pf = facade.evaluationPreflight();
        boolean modelsReady = tribuoModelService.isModelAvailable();

        model.addAttribute("preflightTotalTx",     pf.totalTransactions());
        model.addAttribute("preflightTestSetSize", pf.testSetSize());
        model.addAttribute("preflightFeatures",    pf.featuresComputed());
        model.addAttribute("preflightModelsReady", modelsReady);
        // User-actionable warnings the page surfaces above the form.
        boolean noTransactions  = pf.totalTransactions() == 0;
        boolean noFeatures      = !noTransactions && pf.featuresComputed() == 0;
        boolean noTestSet       = !noTransactions && pf.testSetSize() == 0;
        boolean noModels        = !noTransactions && !modelsReady;
        model.addAttribute("preflightNoTransactions", noTransactions);
        model.addAttribute("preflightNoFeatures",     noFeatures);
        model.addAttribute("preflightNoTestSet",      noTestSet);
        model.addAttribute("preflightNoModels",       noModels);

        model.addAttribute("configs", DetectionConfig.values());
        model.addAttribute("datasets", DatasetSource.values());
        model.addAttribute("results", resultPage.getContent());
        model.addAttribute("resultCount", resultPage.getTotalElements());
        model.addAttribute("currentPage", resultPage.getNumber());
        model.addAttribute("totalPages", resultPage.getTotalPages());
        model.addAttribute("pageSize", resultPage.getSize());
        model.addAttribute("experimentNames", experimentNames);
        model.addAttribute("activePage", "evaluation");
        return "evaluation";
    }

    /**
     * Side-by-side comparison view for every fold/config of a named experiment.
     *
     * <p>Renders a dedicated thesis-friendly page with:</p>
     * <ul>
     *   <li>Config-grouped results table with colour-coded precision / recall / F1 / FPR</li>
     *   <li>Overlay radar chart of CAKR dimensions per config</li>
     *   <li>F1-score bar chart for quick visual comparison</li>
     *   <li>"Winner" tiles highlighting best F1, best CAKR, lowest latency, lowest hallucination</li>
     * </ul>
     *
     * <p>Redirects back to {@code /evaluation} with a not-found message if the
     * experiment name matches no rows — avoids rendering an empty page.</p>
     */
    @GetMapping("/evaluation/compare/{experimentName}")
    public String evaluationCompare(@PathVariable String experimentName, Model model) {
        List<ExperimentResult> rows = facade.experimentResultsByName(experimentName);
        if (rows.isEmpty()) {
            return "redirect:/evaluation";
        }

        // Split fold rows (per-config x per-fold) from summary rows (fold == null,
        // one per config carrying mean ± stddev for that config).
        List<ExperimentResult> foldRows = rows.stream()
                .filter(r -> r.getFold() != null)
                .toList();
        List<ExperimentResult> summaryRows = rows.stream()
                .filter(r -> r.getFold() == null)
                .sorted((a, b) -> a.getConfig().ordinal() - b.getConfig().ordinal())
                .toList();

        // When no k-fold summary exists yet (single-run experiment), fall back
        // to the fold rows themselves as the "one row per config" view.
        List<ExperimentResult> displayRows = summaryRows.isEmpty() ? rows : summaryRows;

        model.addAttribute("experimentName", experimentName);
        model.addAttribute("displayRows", displayRows);
        model.addAttribute("allRows", rows);
        model.addAttribute("foldCount", foldRows.size());
        model.addAttribute("summaryCount", summaryRows.size());
        model.addAttribute("activePage", "evaluation");
        return "comparison";
    }

    @GetMapping("/guide")
    public String guide(Model model) {
        model.addAttribute("activePage", "guide");
        return "guide";
    }

    // ==============================================================
    // Settings
    // ==============================================================

    /**
     * Consolidated <strong>System</strong> page — a tabbed view that merges
     * Health Check, Performance, and Design Decisions into a single entry.
     *
     * <p>These three pages were previously standalone sidebar items. For a
     * single-user thesis application they're all "meta/observability" content
     * read once by the examiner, so collapsing them keeps the sidebar short
     * without losing any content. Bootstrap tabs switch between panes
     * client-side; deep links like {@code /system?tab=perf} pre-select the
     * right tab server-side via the {@code initialTab} model attribute.</p>
     */
    @GetMapping("/system")
    public String system(@RequestParam(defaultValue = "health") String tab, Model model) {
        // Whitelist to prevent tab param injection into the template.
        String initialTab = switch (tab) {
            case "perf", "design" -> tab;
            default -> "health";
        };
        model.addAttribute("initialTab", initialTab);
        model.addAttribute("activePage", "system");
        return "system";
    }

    // ----- Legacy redirects (preserve bookmarks for the old standalone pages)
    @GetMapping("/system/perf")
    public String perfRedirect() {
        return "redirect:/system?tab=perf";
    }

    @GetMapping("/system/health-check")
    public String healthCheckRedirect() {
        return "redirect:/system?tab=health";
    }

    @GetMapping("/design-decisions")
    public String designDecisionsRedirect() {
        return "redirect:/system?tab=design";
    }

    @GetMapping("/settings")
    public String settings(Model model) throws JsonProcessingException {
        // Active config for the top banner (API key is encrypted — template only reads provider/model)
        llmProviderConfigService.findAll().stream()
                .filter(dev.finguard.domain.model.LlmProviderConfig::isActive)
                .findFirst()
                .ifPresent(cfg -> model.addAttribute("activeConfig", cfg));

        // Full map for "Key stored" badge — encrypted value stays server-side, never sent to browser
        Map<String, dev.finguard.domain.model.LlmProviderConfig> byProvider =
                llmProviderConfigService.findAllByProviderName();
        model.addAttribute("configByProvider", byProvider);

        // Flat "provider → hasKey?" map so the template isn't repeating a 5-condition
        // `configByProvider != null and configByProvider['X'] != null and …` dance
        // for every provider tab. Keeps the Thymeleaf markup clean.
        Map<String, Boolean> hasKey = new LinkedHashMap<>();
        for (LlmProvider p : LlmProvider.values()) {
            dev.finguard.domain.model.LlmProviderConfig cfg = byProvider.get(p.name());
            hasKey.put(p.name(), cfg != null
                    && cfg.getApiKey() != null
                    && !cfg.getApiKey().isBlank());
        }
        model.addAttribute("hasKey", hasKey);

        // Serialised model/baseUrl/hasApiKey for every provider so JS pre-populates ALL tabs.
        // Contains only safe fields — no API key value.
        model.addAttribute("storedInfoJson",
                objectMapper.writeValueAsString(llmProviderConfigService.storedInfoByProviderName()));

        model.addAttribute("providers", LlmProvider.values());
        model.addAttribute("activePage", "settings");
        return "settings";
    }

}
