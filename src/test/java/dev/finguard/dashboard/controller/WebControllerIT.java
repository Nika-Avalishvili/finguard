package dev.finguard.dashboard.controller;

import dev.finguard.config.MockAiConfig;
import dev.finguard.config.TestContainersConfig;
import dev.finguard.detection.ml.TribuoModelService;
import dev.finguard.domain.enums.*;
import dev.finguard.domain.model.*;
import dev.finguard.domain.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the WebController (Thymeleaf MVC views).
 *
 * <p>Tests that each page renders correctly with the right view name,
 * model attributes, and HTML content. Verifies the full stack from
 * HTTP request through Spring MVC to Thymeleaf template rendering.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, MockAiConfig.class})
@DisplayName("WebController (Thymeleaf views integration)")
class WebControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private TransactionFeaturesRepository featuresRepository;
    @Autowired private AlertRepository alertRepository;
    @Autowired private ExplanationRepository explanationRepository;
    @Autowired private ExperimentResultRepository experimentResultRepository;
    @Autowired private FraudPatternRepository fraudPatternRepository;
    @Autowired private dev.finguard.domain.repository.MetricSnapshotRepository metricSnapshotRepository;
    @Autowired private org.springframework.cache.CacheManager cacheManager;

    @MockitoBean private TribuoModelService tribuoModelService;

    @BeforeEach
    void cleanUp() {
        experimentResultRepository.deleteAll();
        explanationRepository.deleteAll();
        alertRepository.deleteAll();
        featuresRepository.deleteAll();
        transactionRepository.deleteAll();
        fraudPatternRepository.deleteAll();
        // D-3 snapshot rows + dashboard Caffeine cache would otherwise leak
        // stale counts between tests that check model attributes like hasData.
        metricSnapshotRepository.deleteAll();
        cacheManager.getCacheNames().forEach(n -> {
            var c = cacheManager.getCache(n);
            if (c != null) c.clear();
        });
    }

    // ==============================================================
    // Dashboard (/)
    // ==============================================================

    @Nested
    @DisplayName("GET /dashboard (Dashboard)")
    class DashboardPage {

        @Test
        @DisplayName("Renders dashboard template with model attributes")
        void rendersDashboard() throws Exception {
            mockMvc.perform(get("/dashboard"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("dashboard"))
                    .andExpect(model().attributeExists(
                            "overview", "detection", "explQuality",
                            "datasets", "experiments", "cakrEntries"))
                    // The alertsByStatus / alertsByConfig / txTypes mini-charts were removed —
                    // their JSON model attributes go with them. Underlying REST endpoints on
                    // DashboardController remain if anyone wants the raw data.
                    .andExpect(model().attributeDoesNotExist(
                            "alertsByStatusJson", "alertsByConfigJson", "txTypesJson"))
                    .andExpect(model().attribute("activePage", "dashboard"));
        }

        @Test
        @DisplayName("Dashboard shows correct counts with data")
        void dashboardWithData() throws Exception {
            persistTransaction("50000.00", TransactionType.TRANSFER, true);
            persistTransaction("100.00", TransactionType.PAYMENT, false);

            mockMvc.perform(get("/dashboard"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("2")));  // total transactions
        }

        @Test
        @DisplayName("Dashboard renders with empty database")
        void dashboardEmpty() throws Exception {
            mockMvc.perform(get("/dashboard"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("dashboard"));
        }
    }

    @Nested
    @DisplayName("GET / (Landing page)")
    class LandingPage {

        @Test
        @DisplayName("Renders landing template with activePage home")
        void rendersLanding() throws Exception {
            mockMvc.perform(get("/"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("landing"))
                    .andExpect(model().attribute("activePage", "home"));
        }
    }

    // ==============================================================
    // Transactions (/transactions)
    // ==============================================================

    @Nested
    @DisplayName("GET /transactions")
    class TransactionsPage {

        @Test
        @DisplayName("Renders transactions page with pagination")
        void rendersTransactionsPage() throws Exception {
            persistTransaction("50000.00", TransactionType.TRANSFER, false);

            mockMvc.perform(get("/transactions"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("transactions"))
                    .andExpect(model().attributeExists("page", "sources"))
                    .andExpect(model().attribute("activePage", "transactions"))
                    .andExpect(model().attribute("fraudOnly", false));
        }

        @Test
        @DisplayName("Filters by fraud only")
        void filtersFraudOnly() throws Exception {
            persistTransaction("50000.00", TransactionType.TRANSFER, true);
            persistTransaction("100.00", TransactionType.PAYMENT, false);

            mockMvc.perform(get("/transactions").param("fraudOnly", "true"))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("fraudOnly", true));
        }

        @Test
        @DisplayName("Filters by dataset source")
        void filtersBySource() throws Exception {
            Transaction tx = new Transaction();
            tx.setAmount(new BigDecimal("100.00"));
            tx.setTransactionType(TransactionType.PAYMENT);
            tx.setSenderAccount("S-" + System.nanoTime());
            tx.setReceiverAccount("R-" + System.nanoTime());
            tx.setTimestamp(LocalDateTime.now());
            tx.setDatasetSource(DatasetSource.IBM_AML);
            tx.setExternalId("IBM-" + System.nanoTime());
            transactionRepository.save(tx);

            mockMvc.perform(get("/transactions").param("source", "IBM_AML"))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("selectedSource", "IBM_AML"));
        }

        @Test
        @DisplayName("Pagination works with page parameter")
        void paginationWorks() throws Exception {
            for (int i = 0; i < 30; i++) {
                persistTransaction("100.00", TransactionType.PAYMENT, false);
            }

            mockMvc.perform(get("/transactions").param("page", "1"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("transactions"));
        }

        @Test
        @DisplayName("Empty database renders without errors")
        void emptyDatabase() throws Exception {
            mockMvc.perform(get("/transactions"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("transactions"));
        }
    }

    // ==============================================================
    // Alerts (/alerts)
    // ==============================================================

    @Nested
    @DisplayName("GET /alerts")
    class AlertsPage {

        @Test
        @DisplayName("Renders alerts page with filters")
        void rendersAlertsPage() throws Exception {
            mockMvc.perform(get("/alerts"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("alerts"))
                    .andExpect(model().attributeExists("page", "statuses", "configs"))
                    .andExpect(model().attribute("activePage", "alerts"));
        }

        @Test
        @DisplayName("Filters by alert status")
        void filtersByStatus() throws Exception {
            Transaction tx = persistTransaction("500000.00", TransactionType.TRANSFER, true);
            Alert alert = persistAlert(tx, DetectionConfig.RULES_ONLY);
            alert.setStatus(AlertStatus.CONFIRMED_FRAUD);
            alertRepository.save(alert);

            mockMvc.perform(get("/alerts").param("status", "CONFIRMED_FRAUD"))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("selectedStatus", "CONFIRMED_FRAUD"));
        }

        @Test
        @DisplayName("Filters by detection config")
        void filtersByConfig() throws Exception {
            Transaction tx = persistTransaction("500000.00", TransactionType.TRANSFER, true);
            persistAlert(tx, DetectionConfig.FULL_SYSTEM);

            mockMvc.perform(get("/alerts").param("config", "FULL_SYSTEM"))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("selectedConfig", "FULL_SYSTEM"));
        }
    }

    // ==============================================================
    // Alert Detail (/alerts/{id})
    // ==============================================================

    @Nested
    @DisplayName("GET /alerts/{id}")
    class AlertDetailPage {

        @Test
        @DisplayName("Renders alert detail with transaction and explanations")
        void rendersAlertDetail() throws Exception {
            Transaction tx = persistTransaction("500000.00", TransactionType.TRANSFER, true);
            Alert alert = persistAlert(tx, DetectionConfig.RULES_ONLY);
            persistExplanation(alert);

            mockMvc.perform(get("/alerts/{id}", alert.getId()))
                    .andExpect(status().isOk())
                    .andExpect(view().name("alert-detail"))
                    .andExpect(model().attributeExists("alert", "transaction", "explanations", "statuses"))
                    .andExpect(model().attribute("activePage", "alerts"));
        }

        @Test
        @DisplayName("Redirects to alerts list when ID not found")
        void notFound_redirects() throws Exception {
            mockMvc.perform(get("/alerts/999999"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/alerts"));
        }

        @Test
        @DisplayName("Alert detail with no explanations renders correctly")
        void noExplanations_rendersCorrectly() throws Exception {
            Transaction tx = persistTransaction("500000.00", TransactionType.TRANSFER, true);
            Alert alert = persistAlert(tx, DetectionConfig.RULES_ONLY);

            mockMvc.perform(get("/alerts/{id}", alert.getId()))
                    .andExpect(status().isOk())
                    .andExpect(view().name("alert-detail"))
                    .andExpect(model().attribute("explanations", hasSize(0)));
        }
    }

    // ==============================================================
    // Alert Status Update (POST /alerts/{id}/status)
    // ==============================================================

    @Nested
    @DisplayName("POST /alerts/{id}/status")
    class AlertStatusUpdate {

        @Test
        @DisplayName("Updates status and redirects back to alert detail")
        void updatesAndRedirects() throws Exception {
            Transaction tx = persistTransaction("500000.00", TransactionType.TRANSFER, true);
            Alert alert = persistAlert(tx, DetectionConfig.RULES_ONLY);

            mockMvc.perform(post("/alerts/{id}/status", alert.getId())
                            .param("status", "REVIEWED"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/alerts/" + alert.getId()));

            Alert updated = alertRepository.findById(alert.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(AlertStatus.REVIEWED);
        }
    }

    // ==============================================================
    // Explanations (/explanations)
    // ==============================================================

    @Nested
    @DisplayName("GET /explanations")
    class ExplanationsPage {

        @Test
        @DisplayName("Renders explanations page with pagination")
        void rendersExplanationsPage() throws Exception {
            mockMvc.perform(get("/explanations"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("explanations"))
                    .andExpect(model().attributeExists("page"))
                    .andExpect(model().attribute("activePage", "explanations"));
        }

        @Test
        @DisplayName("Shows explanations when they exist")
        void withExplanations() throws Exception {
            Transaction tx = persistTransaction("500000.00", TransactionType.TRANSFER, true);
            Alert alert = persistAlert(tx, DetectionConfig.ML_LLM_RAG);
            persistExplanation(alert);

            mockMvc.perform(get("/explanations"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("explanations"));
        }
    }

    // ==============================================================
    // Operations Pages (static model attributes)
    // ==============================================================

    @Nested
    @DisplayName("Operations Pages")
    class OperationsPages {

        @Test
        @DisplayName("GET /ingestion renders ingestion page")
        void ingestionPage() throws Exception {
            mockMvc.perform(get("/ingestion"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("ingestion"))
                    .andExpect(model().attribute("activePage", "ingestion"));
        }

        @Test
        @DisplayName("GET /detection renders detection page with counts")
        void detectionPage() throws Exception {
            when(tribuoModelService.isModelAvailable()).thenReturn(false);
            persistTransaction("100.00", TransactionType.PAYMENT, false);

            mockMvc.perform(get("/detection"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("detection"))
                    .andExpect(model().attributeExists("configs", "totalTransactions", "totalAlerts"))
                    .andExpect(model().attribute("modelsAvailable", false))
                    .andExpect(model().attribute("totalTransactions", 1L))
                    .andExpect(model().attribute("activePage", "detection"));
        }

        @Test
        @DisplayName("GET /detection shows models available when trained")
        void detectionPage_modelsAvailable() throws Exception {
            when(tribuoModelService.isModelAvailable()).thenReturn(true);

            mockMvc.perform(get("/detection"))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("modelsAvailable", true));
        }

        @Test
        @DisplayName("GET /models renders models page")
        void modelsPage() throws Exception {
            when(tribuoModelService.isModelAvailable()).thenReturn(true);

            mockMvc.perform(get("/models"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("models"))
                    .andExpect(model().attribute("modelsAvailable", true))
                    .andExpect(model().attribute("activePage", "models"));
        }

        @Test
        @DisplayName("GET /knowledge renders knowledge page with patterns")
        void knowledgePage() throws Exception {
            FraudPattern fp = new FraudPattern();
            fp.setPatternType("STRUCTURING");
            fp.setTitle("Smurfing");
            fp.setDescription("Breaking deposits below reporting threshold");
            fp.setSource("FinCEN");
            fraudPatternRepository.save(fp);

            mockMvc.perform(get("/knowledge"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("knowledge"))
                    .andExpect(model().attribute("patternCount", 1))
                    .andExpect(model().attribute("activePage", "knowledge"));
        }

        @Test
        @DisplayName("GET /knowledge renders correctly with no patterns")
        void knowledgePage_empty() throws Exception {
            mockMvc.perform(get("/knowledge"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("knowledge"))
                    .andExpect(model().attribute("patternCount", 0));
        }

        @Test
        @DisplayName("GET /evaluation renders evaluation page with results and config options")
        void evaluationPage() throws Exception {
            ExperimentResult result = new ExperimentResult();
            result.setExperimentName("test-exp");
            result.setConfig(DetectionConfig.RULES_ONLY);
            result.setDataset(DatasetSource.PAYSIM);
            result.setPrecisionScore(0.85);
            result.setRecallScore(0.80);
            result.setF1Score(0.82);
            experimentResultRepository.save(result);

            mockMvc.perform(get("/evaluation"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("evaluation"))
                    .andExpect(model().attributeExists("configs", "datasets", "results"))
                    .andExpect(model().attribute("activePage", "evaluation"));
        }

        @Test
        @DisplayName("GET /evaluation renders with no experiment results")
        void evaluationPage_empty() throws Exception {
            mockMvc.perform(get("/evaluation"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("evaluation"))
                    .andExpect(model().attribute("results", hasSize(0)));
        }
    }

    // ==============================================================
    // User Guide
    // ==============================================================

    @Nested
    @DisplayName("GET /guide (User Guide page)")
    class GuidePage {

        @Test
        @DisplayName("GET /guide renders guide page with correct view and activePage")
        void guidePage() throws Exception {
            mockMvc.perform(get("/guide"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("guide"))
                    .andExpect(model().attribute("activePage", "guide"));
        }

        @Test
        @DisplayName("GET /guide contains quick start steps and tab navigation")
        void guidePage_containsContent() throws Exception {
            mockMvc.perform(get("/guide"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Quick Start")))
                    .andExpect(content().string(containsString("Ingest Data")))
                    .andExpect(content().string(containsString("Train Models")))
                    .andExpect(content().string(containsString("Run Detection")))
                    .andExpect(content().string(containsString("RULES_ONLY")))
                    .andExpect(content().string(containsString("FULL_SYSTEM")))
                    .andExpect(content().string(containsString("Swagger UI")))
                    .andExpect(content().string(containsString("test-transactions.csv")));
        }

        @Test
        @DisplayName("GET /guide contains the curated 'most useful endpoints' list and a Swagger pointer")
        void guidePage_containsApiReference() throws Exception {
            // The full hand-written endpoint table was removed because it kept
            // drifting from reality (audit ticket). What's left is a curated
            // "starter" list pointing to Swagger UI as the canonical source of
            // truth — assert on the entries that actually appear there now.
            mockMvc.perform(get("/guide"))
                    .andExpect(status().isOk())
                    // Pipeline starters
                    .andExpect(content().string(containsString("/api/v1/ingestion/paysim/path")))
                    .andExpect(content().string(containsString("/api/v1/ingestion/features/compute")))
                    .andExpect(content().string(containsString("/api/v1/models/train/async")))
                    // Evaluation
                    .andExpect(content().string(containsString("/api/v1/evaluation/run")))
                    .andExpect(content().string(containsString("/api/v1/evaluation/benchmark")))
                    // Export
                    .andExpect(content().string(containsString("/api/v1/export/experiments")))
                    // Data management
                    .andExpect(content().string(containsString("/api/v1/data/transactions")))
                    // Monitoring
                    .andExpect(content().string(containsString("/actuator/prometheus")))
                    // Swagger pointer is the headline of the section
                    .andExpect(content().string(containsString("/swagger-ui/index.html")));
        }
    }

    // ==============================================================
    // UX-work regression tests — attributes added during the UI overhaul
    // ==============================================================

    /**
     * These cover the model attributes the new stat strips / onboarding
     * banners / filter chips depend on. Each corresponds to a specific
     * UX change and would turn red if a future refactor dropped the
     * attribute from the controller.
     */
    @Nested
    @DisplayName("UX Model Attributes (stats strips + filters + onboarding)")
    class UxModelAttributes {

        @Test
        @DisplayName("Landing: hasData flag is true when transactions exist")
        void landing_hasData_true() throws Exception {
            persistTransaction("100.00", TransactionType.PAYMENT, false);

            mockMvc.perform(get("/"))
                    .andExpect(model().attribute("hasData", true))
                    .andExpect(model().attributeExists("overview"));
        }

        @Test
        @DisplayName("Landing: hasData flag is false on empty database (drives onboarding banner)")
        void landing_hasData_false_whenEmpty() throws Exception {
            mockMvc.perform(get("/"))
                    .andExpect(model().attribute("hasData", false));
        }

        @Test
        @DisplayName("Alerts: combined status+config filter keeps only matching rows")
        void alerts_combinedFilter_appliesBoth() throws Exception {
            Transaction tx = persistTransaction("500000.00", TransactionType.TRANSFER, true);
            Alert a1 = persistAlert(tx, DetectionConfig.RULES_ONLY);
            a1.setStatus(AlertStatus.CONFIRMED_FRAUD);
            alertRepository.save(a1);
            Alert a2 = persistAlert(tx, DetectionConfig.ML_ONLY);
            a2.setStatus(AlertStatus.NEW);
            alertRepository.save(a2);

            // Filter "status=CONFIRMED_FRAUD AND config=RULES_ONLY" must return
            // ONLY a1 (1 row), not a2 — the pre-UX-refactor bug returned both.
            mockMvc.perform(get("/alerts")
                            .param("status", "CONFIRMED_FRAUD")
                            .param("config", "RULES_ONLY"))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("selectedStatus", "CONFIRMED_FRAUD"))
                    .andExpect(model().attribute("selectedConfig", "RULES_ONLY"))
                    .andExpect(model().attribute("page", hasProperty("totalElements", is(1L))));
        }

        @Test
        @DisplayName("Alerts: stats-strip attributes (alertsByStatus, totalAnomalies, avgRiskScore) present")
        void alerts_statsAttributes_populated() throws Exception {
            Transaction tx = persistTransaction("500000.00", TransactionType.TRANSFER, true);
            Alert a = persistAlert(tx, DetectionConfig.RULES_ONLY);
            a.setMlRiskScore(0.85);
            alertRepository.save(a);

            mockMvc.perform(get("/alerts"))
                    .andExpect(model().attributeExists("alertsByStatus", "totalAnomalies", "avgRiskScore"))
                    .andExpect(model().attribute("totalAnomalies", 1L));
        }

        @Test
        @DisplayName("Ingestion: stats attributes (total/features/pending) present with correct math")
        void ingestion_statsAttributes_populated() throws Exception {
            // Two transactions, one with features.
            Transaction t1 = persistTransaction("100.00", TransactionType.PAYMENT, false);
            persistTransaction("200.00", TransactionType.PAYMENT, false);
            TransactionFeatures f = new TransactionFeatures();
            f.setTransaction(t1);
            // Only the FK to transaction matters for the stats-count test.
            featuresRepository.save(f);

            mockMvc.perform(get("/ingestion"))
                    .andExpect(model().attribute("totalTransactions", 2L))
                    .andExpect(model().attribute("featuresCount", 1L))
                    .andExpect(model().attribute("pendingFeatures", 1L));
        }

        @Test
        @DisplayName("Transactions: stats attributes present with correct fraud rate")
        void transactions_statsAttributes_populated() throws Exception {
            persistTransaction("100.00", TransactionType.PAYMENT, true);
            persistTransaction("200.00", TransactionType.PAYMENT, false);
            persistTransaction("300.00", TransactionType.PAYMENT, false);

            mockMvc.perform(get("/transactions"))
                    .andExpect(model().attribute("totalTransactions", 3L))
                    .andExpect(model().attribute("fraudTransactions", 1L))
                    // 1/3 = 33.33% rounded to two decimals.
                    .andExpect(model().attribute("fraudRatePercent", 33.33));
        }

        @Test
        @DisplayName("Explanations: filter params + stats attributes are surfaced to the view")
        void explanations_filterAndStats_populated() throws Exception {
            Transaction tx = persistTransaction("500000.00", TransactionType.TRANSFER, true);
            Alert alert = persistAlert(tx, DetectionConfig.ML_LLM_RAG);
            persistExplanation(alert);

            mockMvc.perform(get("/explanations")
                            .param("type", "LLM_RAG")
                            .param("flag", "clean"))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("selectedType", "LLM_RAG"))
                    .andExpect(model().attribute("selectedFlag", "clean"))
                    .andExpect(model().attributeExists(
                            "total", "avgConfidence", "avgLatencyMs", "clean", "flagged", "types"))
                    // Helper persists hallucinationFree=true, so the "clean only"
                    // filter should still match one row.
                    .andExpect(model().attribute("page", hasProperty("totalElements", is(1L))));
        }

        @Test
        @DisplayName("Explanations: 'flagged' filter excludes clean rows")
        void explanations_flaggedFilter_excludesClean() throws Exception {
            Transaction tx = persistTransaction("500000.00", TransactionType.TRANSFER, true);
            Alert alert = persistAlert(tx, DetectionConfig.ML_LLM_RAG);
            persistExplanation(alert); // hallucinationFree = true (clean)

            mockMvc.perform(get("/explanations").param("flag", "flagged"))
                    .andExpect(model().attribute("page", hasProperty("totalElements", is(0L))));
        }

        @Test
        @DisplayName("Evaluation: experimentNames set is populated for the Compare dropdown")
        void evaluation_experimentNames_populated() throws Exception {
            persistExperimentResult("bench-a", DetectionConfig.RULES_ONLY, 0.9);
            persistExperimentResult("bench-a", DetectionConfig.ML_ONLY, 0.8);
            persistExperimentResult("bench-b", DetectionConfig.FULL_SYSTEM, 0.7);

            mockMvc.perform(get("/evaluation"))
                    .andExpect(status().isOk())
                    .andExpect(model().attributeExists("experimentNames"))
                    // Two unique names despite three rows — dedup happens server-side.
                    .andExpect(model().attribute("experimentNames", hasItems("bench-a", "bench-b")))
                    .andExpect(model().attribute("experimentNames", hasSize(2)));
        }

        @Test
        @DisplayName("Comparison: known experiment renders comparison template with rows")
        void comparison_knownExperiment_renders() throws Exception {
            persistExperimentResult("my-compare", DetectionConfig.RULES_ONLY, 0.85);
            persistExperimentResult("my-compare", DetectionConfig.FULL_SYSTEM, 0.92);

            mockMvc.perform(get("/evaluation/compare/my-compare"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("comparison"))
                    .andExpect(model().attribute("experimentName", "my-compare"))
                    .andExpect(model().attribute("displayRows", hasSize(2)))
                    .andExpect(model().attribute("allRows", hasSize(2)))
                    .andExpect(model().attribute("activePage", "evaluation"));
        }

        @Test
        @DisplayName("Comparison: unknown experiment name redirects to evaluation listing")
        void comparison_unknownExperiment_redirects() throws Exception {
            mockMvc.perform(get("/evaluation/compare/does-not-exist"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/evaluation"));
        }

        @Test
        @DisplayName("GET /system renders consolidated system view with health tab active by default")
        void systemPage_defaultsToHealthTab() throws Exception {
            mockMvc.perform(get("/system"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("system"))
                    .andExpect(model().attribute("activePage", "system"))
                    .andExpect(model().attribute("initialTab", "health"));
        }

        @Test
        @DisplayName("GET /system?tab=perf pre-selects the performance tab")
        void systemPage_perfTab() throws Exception {
            mockMvc.perform(get("/system").param("tab", "perf"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("system"))
                    .andExpect(model().attribute("initialTab", "perf"));
        }

        @Test
        @DisplayName("GET /system?tab=design pre-selects the design-decisions tab")
        void systemPage_designTab() throws Exception {
            mockMvc.perform(get("/system").param("tab", "design"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("system"))
                    .andExpect(model().attribute("initialTab", "design"))
                    .andExpect(content().string(containsString("Design Decisions")))
                    .andExpect(content().string(containsString("CAKR")));
        }

        @Test
        @DisplayName("GET /system?tab=<garbage> falls back to health tab (whitelist)")
        void systemPage_invalidTab_fallsBackToHealth() throws Exception {
            mockMvc.perform(get("/system").param("tab", "' OR 1=1--"))
                    .andExpect(status().isOk())
                    .andExpect(model().attribute("initialTab", "health"));
        }

        @Test
        @DisplayName("GET /system/health-check redirects to /system?tab=health (legacy bookmark)")
        void legacyHealthCheckUrl_redirects() throws Exception {
            mockMvc.perform(get("/system/health-check"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/system?tab=health"));
        }

        @Test
        @DisplayName("Data-quality API: empty database reports FAIL overall (no transactions)")
        void dataQualityApi_emptyDb_reportsFail() throws Exception {
            mockMvc.perform(get("/api/v1/system/data-quality"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.overall").value("FAIL"))
                    .andExpect(jsonPath("$.checks").isArray())
                    .andExpect(jsonPath("$.checks[?(@.id == 'transactions')].severity")
                            .value(hasItem("FAIL")));
        }

        @Test
        @DisplayName("Data-quality API: after loading data, transactions check flips to PASS")
        void dataQualityApi_withData_transactionsPass() throws Exception {
            // Seed 10+ rows with realistic fraud rate (< 20%) so the check
            // flips to PASS rather than WARN on the "unrealistic fraud rate" path.
            persistTransaction("5000.00", TransactionType.TRANSFER, true);
            for (int i = 0; i < 9; i++) {
                persistTransaction("100.00", TransactionType.PAYMENT, false);
            }

            mockMvc.perform(get("/api/v1/system/data-quality"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.checks[?(@.id == 'transactions')].severity")
                            .value(hasItem("PASS")));
        }

        @Test
        @DisplayName("GET /system/perf redirects to /system?tab=perf (legacy bookmark)")
        void legacyPerfUrl_redirects() throws Exception {
            mockMvc.perform(get("/system/perf"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/system?tab=perf"));
        }

        @Test
        @DisplayName("GET /design-decisions redirects to /system?tab=design (legacy bookmark)")
        void legacyDesignDecisionsUrl_redirects() throws Exception {
            mockMvc.perform(get("/design-decisions"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/system?tab=design"));
        }

        @Test
        @DisplayName("Experiment markdown export returns 404 for unknown name")
        void thesisMarkdownExport_unknown_404() throws Exception {
            mockMvc.perform(get("/api/v1/export/experiments/nonexistent-run/markdown"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("Experiment markdown export returns a markdown body for a known run")
        void thesisMarkdownExport_known_returnsMarkdown() throws Exception {
            persistExperimentResult("md-export-test", DetectionConfig.FULL_SYSTEM, 0.87);

            mockMvc.perform(get("/api/v1/export/experiments/md-export-test/markdown"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("text/markdown"))
                    .andExpect(content().string(containsString("# Experiment Report: md-export-test")))
                    .andExpect(content().string(containsString("## 1. Detection Metrics")))
                    .andExpect(content().string(containsString("FULL_SYSTEM")));
        }

        @Test
        @DisplayName("Experiment bundle export streams a ZIP with RESULTS.md + CSVs")
        void thesisBundleExport_returnsZip() throws Exception {
            persistExperimentResult("bundle-test", DetectionConfig.RULES_ONLY, 0.6);

            byte[] body = mockMvc.perform(get("/api/v1/export/experiments/bundle-test/bundle"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType("application/zip"))
                    .andReturn().getResponse().getContentAsByteArray();

            // Verify ZIP structure: at minimum RESULTS.md + results.csv + run-parameters.json
            java.util.Set<String> entries = new java.util.HashSet<>();
            try (java.util.zip.ZipInputStream zis =
                         new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(body))) {
                java.util.zip.ZipEntry e;
                while ((e = zis.getNextEntry()) != null) entries.add(e.getName());
            }
            assertThat(entries).contains("RESULTS.md", "results.csv", "run-parameters.json");
        }

        @Test
        @DisplayName("Knowledge: uniquePatternTypes is deduplicated server-side")
        void knowledge_uniquePatternTypes_deduped() throws Exception {
            // Two STRUCTURING patterns + one LAYERING pattern = 2 unique types.
            FraudPattern p1 = new FraudPattern();
            p1.setPatternType("STRUCTURING");
            p1.setTitle("Smurfing");
            p1.setDescription("Depositing small amounts to avoid reporting thresholds");
            p1.setSource("FinCEN");
            fraudPatternRepository.save(p1);

            FraudPattern p2 = new FraudPattern();
            p2.setPatternType("STRUCTURING");
            p2.setTitle("Threshold avoidance");
            p2.setDescription("Same type, different pattern instance");
            p2.setSource("BSA");
            fraudPatternRepository.save(p2);

            FraudPattern p3 = new FraudPattern();
            p3.setPatternType("LAYERING");
            p3.setTitle("Rapid movement of funds");
            p3.setDescription("Moving money through multiple accounts rapidly");
            p3.setSource("FATF");
            fraudPatternRepository.save(p3);

            mockMvc.perform(get("/knowledge"))
                    .andExpect(model().attribute("uniquePatternTypes", hasSize(2)));
        }
    }

    // ==============================================================
    // Cross-cutting: sidebar navigation
    // ==============================================================

    @Nested
    @DisplayName("Sidebar Navigation (activePage attribute)")
    class SidebarNavigation {

        @Test
        @DisplayName("Each page sets its own activePage for sidebar highlighting")
        void eachPageSetsActivePage() throws Exception {
            when(tribuoModelService.isModelAvailable()).thenReturn(false);

            mockMvc.perform(get("/")).andExpect(model().attribute("activePage", "home"));
            mockMvc.perform(get("/dashboard")).andExpect(model().attribute("activePage", "dashboard"));
            mockMvc.perform(get("/transactions")).andExpect(model().attribute("activePage", "transactions"));
            mockMvc.perform(get("/alerts")).andExpect(model().attribute("activePage", "alerts"));
            mockMvc.perform(get("/explanations")).andExpect(model().attribute("activePage", "explanations"));
            mockMvc.perform(get("/ingestion")).andExpect(model().attribute("activePage", "ingestion"));
            mockMvc.perform(get("/detection")).andExpect(model().attribute("activePage", "detection"));
            mockMvc.perform(get("/models")).andExpect(model().attribute("activePage", "models"));
            mockMvc.perform(get("/knowledge")).andExpect(model().attribute("activePage", "knowledge"));
            mockMvc.perform(get("/evaluation")).andExpect(model().attribute("activePage", "evaluation"));
            mockMvc.perform(get("/guide")).andExpect(model().attribute("activePage", "guide"));
        }
    }

    // ==============================================================
    // Helpers
    // ==============================================================

    private Transaction persistTransaction(String amount, TransactionType type, boolean isFraud) {
        Transaction tx = new Transaction();
        tx.setAmount(new BigDecimal(amount));
        tx.setTransactionType(type);
        tx.setSenderAccount("SENDER-WEB-" + System.nanoTime());
        tx.setReceiverAccount("RECEIVER-WEB-" + System.nanoTime());
        tx.setTimestamp(LocalDateTime.now());
        tx.setDatasetSource(DatasetSource.PAYSIM);
        tx.setExternalId("WEB-" + System.nanoTime());
        tx.setIsFraud(isFraud);
        return transactionRepository.save(tx);
    }

    private Alert persistAlert(Transaction tx, DetectionConfig config) {
        Alert alert = new Alert();
        alert.setTransaction(tx);
        alert.setDetectionConfig(config);
        alert.setIsAnomaly(true);
        alert.setStatus(AlertStatus.NEW);
        return alertRepository.save(alert);
    }

    private ExperimentResult persistExperimentResult(String name, DetectionConfig config, double f1) {
        ExperimentResult r = new ExperimentResult();
        r.setExperimentName(name);
        r.setConfig(config);
        r.setDataset(DatasetSource.PAYSIM);
        r.setF1Score(f1);
        r.setPrecisionScore(f1);
        r.setRecallScore(f1);
        r.setFalsePositiveRate(0.05);
        return experimentResultRepository.save(r);
    }

    private Explanation persistExplanation(Alert alert) {
        Explanation explanation = new Explanation();
        explanation.setAlert(alert);
        explanation.setExplanationType(ExplanationType.LLM_RAG);
        explanation.setRiskSummary("Test risk summary");
        explanation.setExplanationText("Detailed test explanation for integration testing.");
        explanation.setConfidenceScore(0.88);
        explanation.setLatencyMs(1200);
        explanation.setHallucinationFree(true);
        return explanationRepository.save(explanation);
    }
}
