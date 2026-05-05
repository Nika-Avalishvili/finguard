package dev.finguard.detection.ml;

import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.model.TransactionFeatures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("TribuoModelService")
class TribuoModelServiceTest {

    private JdbcTemplate jdbcTemplate;
    private FeatureTransformer transformer;
    private TribuoModelService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        transformer = new FeatureTransformer();
        service = new TribuoModelService(jdbcTemplate, transformer, new SimpleMeterRegistry());
        ReflectionTestUtils.setField(service, "trainingBatchSize", 5000);
        ReflectionTestUtils.setField(service, "maxLegitTrainingSamples", 5000); // well above test data size
        ReflectionTestUtils.setField(service, "riskScoreThreshold", 0.5);
        // Disable ensemble so tests only need RandomForest (pure Java).
        // XGBoost requires native JNI libs that may not be present in all test envs.
        ReflectionTestUtils.setField(service, "ensembleEnabled", false);
        // Use small values so unit tests run fast (10 trees vs default 100).
        ReflectionTestUtils.setField(service, "rfNumTrees", 10);
        ReflectionTestUtils.setField(service, "rfMaxDepth", 4);
        ReflectionTestUtils.setField(service, "rfParallelism", 2);
        ReflectionTestUtils.setField(service, "xgbNumRounds", 10);
        ReflectionTestUtils.setField(service, "xgbNumThreads", 1);
        // @PostConstruct does not fire without Spring context — call manually
        service.initExecutor();
    }

    @Test
    @DisplayName("Should report unavailable when no models trained")
    void predict_returnsUnavailable_whenNoModels() {
        Transaction tx = txWithAmount("5000.00", false);

        MLPredictionResult result = service.predict(tx, null);

        assertThat(result.modelName()).isEqualTo("NONE");
        assertThat(result.riskScore()).isEqualTo(0.0);
        assertThat(result.predictedFraud()).isFalse();
    }

    @Test
    @DisplayName("Should report models not available before training")
    void isModelAvailable_falseBeforeTraining() {
        assertThat(service.isModelAvailable()).isFalse();
    }

    @Test
    @DisplayName("Should return empty summary when insufficient training data")
    void trainModels_insufficientData() {
        List<TribuoModelService.TrainingRow> rows = createSyntheticTrainingRows(3, 1);
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), eq(0L), anyInt()))
                .thenReturn(rows);
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), eq(3L), anyInt()))
                .thenReturn(List.of());

        TribuoModelService.TrainingSummary summary = service.trainModels();

        assertThat(summary.totalExamples()).isZero();
        assertThat(summary.trainedModels()).isEmpty();
    }

    @Test
    @DisplayName("Should train RandomForest and predict successfully")
    void trainAndPredict_withSufficientData() {
        setupTrainingData(50, 10);

        TribuoModelService.TrainingSummary summary = service.trainModels();

        assertThat(summary.totalExamples()).isGreaterThanOrEqualTo(10);
        // At least RandomForest should succeed (XGBoost may fail without native libs)
        assertThat(summary.trainedModels()).contains("RandomForest");
        assertThat(service.isModelAvailable()).isTrue();

        // Predict on a high-risk transaction
        Transaction highRisk = txWithAmount("500000.00", false);
        highRisk.setId(999L);
        TransactionFeatures highRiskFeatures = new TransactionFeatures();
        highRiskFeatures.setAmountZscore(5.0);
        highRiskFeatures.setTxVelocity1h(10);
        highRiskFeatures.setTxVelocity24h(20);
        highRiskFeatures.setIsNewReceiver(true);
        highRiskFeatures.setIsHighRiskType(true);
        highRiskFeatures.setIsRoundAmount(true);

        MLPredictionResult prediction = service.predict(highRisk, highRiskFeatures);

        assertThat(prediction.modelName()).isNotEqualTo("NONE");
        assertThat(prediction.riskScore()).isBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("Should predict on a clean transaction after training")
    void predict_cleanTransaction_afterTraining() {
        setupTrainingData(50, 10);
        service.trainModels();

        Transaction clean = txWithAmount("50.00", false);
        clean.setId(1000L);
        TransactionFeatures cleanFeatures = new TransactionFeatures();
        cleanFeatures.setAmountZscore(0.1);
        cleanFeatures.setTxVelocity1h(0);
        cleanFeatures.setTxVelocity24h(1);
        cleanFeatures.setIsNewReceiver(false);
        cleanFeatures.setIsHighRiskType(false);
        cleanFeatures.setIsRoundAmount(false);

        MLPredictionResult prediction = service.predict(clean, cleanFeatures);

        assertThat(prediction.modelName()).isNotEqualTo("NONE");
        assertThat(prediction.riskScore()).isBetween(0.0, 1.0);
    }

    // ================================================================
    // Helpers
    // ================================================================

    @SuppressWarnings("unchecked")
    private void setupTrainingData(int total, int fraudCount) {
        List<TribuoModelService.TrainingRow> rows = createSyntheticTrainingRows(total, fraudCount);
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), eq(0L), anyInt()))
                .thenReturn(rows);
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), eq((long) total), anyInt()))
                .thenReturn(List.of());
    }

    private List<TribuoModelService.TrainingRow> createSyntheticTrainingRows(int total, int fraudCount) {
        List<TribuoModelService.TrainingRow> rows = new ArrayList<>();
        for (int i = 1; i <= total; i++) {
            boolean fraud = i <= fraudCount;
            rows.add(new TribuoModelService.TrainingRow(
                    (long) i,
                    fraud,
                    fraud ? 100000.0 + i * 50000.0 : 50.0 + i * 100.0,
                    fraud ? 3.5 : 0.2,
                    fraud ? 8 : 1,
                    fraud ? 20 : 3,
                    fraud ? 500.0 : 2000.0,
                    fraud ? 8.0 : 0.6,
                    fraud ? 0.9 : 0.1,
                    fraud,
                    fraud ? 10 : 2,
                    fraud ? 3 : 14,
                    fraud ? 6 : 3,
                    fraud,
                    fraud
            ));
        }
        return rows;
    }

    private Transaction txWithAmount(String amount, boolean fraud) {
        Transaction tx = new Transaction();
        tx.setAmount(new BigDecimal(amount));
        tx.setIsFraud(fraud);
        tx.setSenderAccount("TEST_SENDER");
        tx.setReceiverAccount("TEST_RECEIVER");
        return tx;
    }
}
