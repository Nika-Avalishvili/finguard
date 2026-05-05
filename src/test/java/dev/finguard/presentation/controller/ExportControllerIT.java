package dev.finguard.presentation.controller;

import dev.finguard.config.MockAiConfig;
import dev.finguard.config.TestContainersConfig;
import dev.finguard.domain.enums.*;
import dev.finguard.domain.model.Alert;
import dev.finguard.domain.model.ExperimentResult;
import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the ExportController CSV endpoints.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestContainersConfig.class, MockAiConfig.class})
@DisplayName("ExportController (CSV export integration)")
class ExportControllerIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ExperimentResultRepository experimentResultRepository;
    @Autowired private AlertRepository alertRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private TransactionFeaturesRepository featuresRepository;
    @Autowired private ExplanationRepository explanationRepository;
    @Autowired private dev.finguard.testutil.AsyncITCleaner cleaner;

    @BeforeEach
    void cleanUp() {
        cleaner.drainAndClean();
    }

    // ================================================================
    // GET /api/v1/export/experiments
    // ================================================================

    @Nested
    @DisplayName("GET /api/v1/export/experiments")
    class ExportExperiments {

        @Test
        @DisplayName("Returns CSV with correct headers and content type")
        void returnsCsvWithHeaders() throws Exception {
            persistExperimentResult("thesis-eval", DetectionConfig.RULES_ONLY, 0.85, 0.80, 0.82);

            MvcResult result = mockMvc.perform(get("/api/v1/export/experiments"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "text/csv"))
                    .andExpect(header().string("Content-Disposition",
                            "attachment; filename=\"experiment-results.csv\""))
                    .andReturn();

            String csv = result.getResponse().getContentAsString();
            assertThat(csv).contains("id,experiment_name,config,dataset");
            assertThat(csv).contains("thesis-eval");
            assertThat(csv).contains("RULES_ONLY");
        }

        @Test
        @DisplayName("Contains all metric columns with data")
        void containsAllMetrics() throws Exception {
            persistExperimentResult("exp-1", DetectionConfig.FULL_SYSTEM, 0.92, 0.88, 0.90);

            MvcResult result = mockMvc.perform(get("/api/v1/export/experiments"))
                    .andReturn();

            String csv = result.getResponse().getContentAsString();
            assertThat(csv).contains("0.920000"); // precision
            assertThat(csv).contains("0.880000"); // recall
            assertThat(csv).contains("0.900000"); // f1
        }

        @Test
        @DisplayName("Filters by experiment name")
        void filtersByName() throws Exception {
            persistExperimentResult("keep", DetectionConfig.RULES_ONLY, 0.85, 0.80, 0.82);
            persistExperimentResult("skip", DetectionConfig.ML_ONLY, 0.90, 0.85, 0.87);

            MvcResult result = mockMvc.perform(get("/api/v1/export/experiments")
                            .param("experimentName", "keep"))
                    .andReturn();

            String csv = result.getResponse().getContentAsString();
            assertThat(csv).contains("keep");
            assertThat(csv).doesNotContain("skip");
        }

        @Test
        @DisplayName("Returns header only when no data")
        void emptyExport() throws Exception {
            MvcResult result = mockMvc.perform(get("/api/v1/export/experiments"))
                    .andExpect(status().isOk())
                    .andReturn();

            String csv = result.getResponse().getContentAsString();
            String[] lines = csv.strip().split("\n");
            assertThat(lines).hasSize(1); // header only
        }
    }

    // ================================================================
    // GET /api/v1/export/alerts
    // ================================================================

    @Nested
    @DisplayName("GET /api/v1/export/alerts")
    class ExportAlerts {

        @Test
        @DisplayName("Returns CSV with correct headers")
        void returnsCsvWithHeaders() throws Exception {
            Transaction tx = persistTransaction("500000.00", TransactionType.TRANSFER, true);
            persistAlert(tx, DetectionConfig.RULES_ONLY);

            MvcResult result = mockMvc.perform(get("/api/v1/export/alerts"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "text/csv"))
                    .andExpect(header().string("Content-Disposition",
                            "attachment; filename=\"alerts.csv\""))
                    .andReturn();

            String csv = result.getResponse().getContentAsString();
            assertThat(csv).contains("id,transaction_id,detection_config,status");
            assertThat(csv).contains("RULES_ONLY");
        }

        @Test
        @DisplayName("Filters by detection config")
        void filtersByConfig() throws Exception {
            Transaction tx1 = persistTransaction("500000.00", TransactionType.TRANSFER, true);
            Transaction tx2 = persistTransaction("300000.00", TransactionType.TRANSFER, true);
            persistAlert(tx1, DetectionConfig.RULES_ONLY);
            persistAlert(tx2, DetectionConfig.FULL_SYSTEM);

            MvcResult result = mockMvc.perform(get("/api/v1/export/alerts")
                            .param("config", "RULES_ONLY"))
                    .andReturn();

            String csv = result.getResponse().getContentAsString();
            assertThat(csv).contains("RULES_ONLY");
            assertThat(csv).doesNotContain("FULL_SYSTEM");
        }

        @Test
        @DisplayName("Handles special characters in rule descriptions (CSV escaping)")
        void csvEscaping() throws Exception {
            Transaction tx = persistTransaction("500000.00", TransactionType.TRANSFER, true);
            Alert alert = persistAlert(tx, DetectionConfig.RULES_ONLY);
            alert.setRuleTriggered("LARGE_TRANSACTION: amount exceeds 200,000 threshold");
            alertRepository.save(alert);

            MvcResult result = mockMvc.perform(get("/api/v1/export/alerts"))
                    .andReturn();

            String csv = result.getResponse().getContentAsString();
            // Commas in values should be properly escaped with quotes
            assertThat(csv).contains("\"LARGE_TRANSACTION: amount exceeds 200,000 threshold\"");
        }
    }

    // ================================================================
    // Helpers
    // ================================================================

    private Transaction persistTransaction(String amount, TransactionType type, boolean isFraud) {
        Transaction tx = new Transaction();
        tx.setAmount(new BigDecimal(amount));
        tx.setTransactionType(type);
        tx.setSenderAccount("SENDER-EXPORT-" + System.nanoTime());
        tx.setReceiverAccount("RECEIVER-EXPORT-" + System.nanoTime());
        tx.setTimestamp(LocalDateTime.now());
        tx.setDatasetSource(DatasetSource.PAYSIM);
        tx.setExternalId("EXPORT-" + System.nanoTime());
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

    private void persistExperimentResult(String name, DetectionConfig config,
                                          double precision, double recall, double f1) {
        ExperimentResult result = new ExperimentResult();
        result.setExperimentName(name);
        result.setConfig(config);
        result.setDataset(DatasetSource.PAYSIM);
        result.setPrecisionScore(precision);
        result.setRecallScore(recall);
        result.setF1Score(f1);
        result.setFalsePositiveRate(0.05);
        result.setTotalTransactions(100);
        result.setTotalAlerts(10);
        experimentResultRepository.save(result);
    }
}
