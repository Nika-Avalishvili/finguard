package dev.finguard.config;

import dev.finguard.detection.ml.TribuoModelService;
import dev.finguard.explanation.llm.LLMExplanationService;
import dev.finguard.explanation.rag.RAGContextService;
import dev.finguard.domain.model.Alert;
import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.enums.AlertStatus;
import dev.finguard.domain.enums.DetectionConfig;
import dev.finguard.domain.enums.TransactionType;
import dev.finguard.domain.enums.DatasetSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.retry.annotation.Retryable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration tests verifying Spring Retry resilience configuration.
 *
 * <p>Tests verify that:
 * <ul>
 *   <li>Retry annotations are correctly placed on critical methods</li>
 *   <li>RAG context retrieval degrades gracefully on failure (returns empty list)</li>
 *   <li>Model loading is annotated for retry on I/O failures</li>
 *   <li>The Spring Retry context is active (annotations are proxied)</li>
 * </ul>
 */
@SpringBootTest
@Import({TestContainersConfig.class, MockAiConfig.class})
@DisplayName("Resilience (Spring Retry integration)")
class ResilienceIT {

    @Autowired private LLMExplanationService llmExplanationService;
    @Autowired private RAGContextService ragContextService;
    @Autowired private TribuoModelService tribuoModelService;
    @Autowired private VectorStore vectorStore;
    @Autowired private CacheManager cacheManager;

    @BeforeEach
    void resetMocksAndCaches() {
        reset(vectorStore);
        var cache = cacheManager.getCache("rag-context");
        if (cache != null) {
            cache.clear();
        }
    }

    // ================================================================
    // LLM retry annotations
    // ================================================================

    @Nested
    @DisplayName("LLM call retry configuration")
    class LlmRetryConfig {

        @Test
        @DisplayName("callLlmWithRetry is annotated with @Retryable")
        void callLlmWithRetry_hasRetryAnnotation() throws NoSuchMethodException {
            Method method = LLMExplanationService.class.getMethod(
                    "callLlmWithRetry", String.class, Long.class);

            Retryable retryable = method.getAnnotation(Retryable.class);

            assertThat(retryable).isNotNull();
            assertThat(retryable.maxAttempts()).isEqualTo(3);
            assertThat(retryable.backoff().delay()).isEqualTo(1000);
            assertThat(retryable.backoff().multiplier()).isEqualTo(2.0);
        }

        @Test
        @DisplayName("Spring Retry proxy is active on LLMExplanationService")
        void springRetryProxy_isActive() {
            // If Spring Retry is properly configured, the service bean will be
            // wrapped in a CGLIB proxy that intercepts @Retryable calls
            assertThat(llmExplanationService.getClass().getName())
                    .satisfiesAnyOf(
                            name -> assertThat(name).contains("$$SpringCGLIB$$"),
                            name -> assertThat(name).contains("$$EnhancerBySpringCGLIB$$"),
                            // Spring Boot 3.4+ may use a different proxy mechanism
                            name -> assertThat(name).isNotEqualTo(LLMExplanationService.class.getName())
                    );
        }
    }

    // ================================================================
    // RAG context graceful degradation
    // ================================================================

    @Nested
    @DisplayName("RAG context graceful degradation")
    class RagGracefulDegradation {

        @Test
        @DisplayName("Returns empty list when vector store throws exception (graceful degradation)")
        void returnsEmptyList_onVectorStoreFailure() {
            when(vectorStore.similaritySearch(any(SearchRequest.class)))
                    .thenThrow(new RuntimeException("pgvector connection timeout"));

            Alert alert = buildAlert();
            Transaction tx = alert.getTransaction();

            List<Document> result = ragContextService.retrieveContext(alert, tx);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("retrieveContext is annotated with @Retryable")
        void retrieveContext_hasRetryAnnotation() throws NoSuchMethodException {
            Method method = RAGContextService.class.getMethod(
                    "retrieveContext", Alert.class, Transaction.class);

            Retryable retryable = method.getAnnotation(Retryable.class);

            assertThat(retryable).isNotNull();
            assertThat(retryable.maxAttempts()).isEqualTo(2);
        }

        @Test
        @DisplayName("Vector store is called with retries before falling back")
        void retriesBeforeFallback() {
            when(vectorStore.similaritySearch(any(SearchRequest.class)))
                    .thenThrow(new RuntimeException("Connection refused"))
                    .thenThrow(new RuntimeException("Connection refused"));

            Alert alert = buildAlert();
            Transaction tx = alert.getTransaction();

            List<Document> result = ragContextService.retrieveContext(alert, tx);

            // Should have tried twice (maxAttempts=2), then recovered with empty list
            assertThat(result).isEmpty();
            verify(vectorStore, times(2)).similaritySearch(any(SearchRequest.class));
        }

        @Test
        @DisplayName("Returns results when vector store succeeds after retry")
        void succeedsAfterRetry() {
            Document doc = new Document("Structuring fraud pattern context");
            when(vectorStore.similaritySearch(any(SearchRequest.class)))
                    .thenThrow(new RuntimeException("Transient failure"))
                    .thenReturn(List.of(doc));

            Alert alert = buildAlert();
            Transaction tx = alert.getTransaction();

            List<Document> result = ragContextService.retrieveContext(alert, tx);

            assertThat(result).hasSize(1);
            verify(vectorStore, times(2)).similaritySearch(any(SearchRequest.class));
        }
    }

    // ================================================================
    // ML model loading retry
    // ================================================================

    @Nested
    @DisplayName("ML model loading retry configuration")
    class MlModelRetryConfig {

        @Test
        @DisplayName("loadModels is annotated with @Retryable for IOException")
        void loadModels_hasRetryAnnotation() throws NoSuchMethodException {
            Method method = TribuoModelService.class.getMethod("loadModels", java.nio.file.Path.class);

            Retryable retryable = method.getAnnotation(Retryable.class);

            assertThat(retryable).isNotNull();
            assertThat(retryable.maxAttempts()).isEqualTo(3);
            assertThat(retryable.retryFor()).contains(IOException.class);
        }
    }

    // ================================================================
    // Helpers
    // ================================================================

    private Alert buildAlert() {
        Transaction tx = new Transaction();
        tx.setId(1L);
        tx.setAmount(new BigDecimal("500000.00"));
        tx.setTransactionType(TransactionType.TRANSFER);
        tx.setSenderAccount("SENDER-RETRY-TEST");
        tx.setReceiverAccount("RECEIVER-RETRY-TEST");
        tx.setTimestamp(LocalDateTime.now());
        tx.setDatasetSource(DatasetSource.PAYSIM);
        tx.setExternalId("RETRY-TEST-1");

        Alert alert = new Alert();
        alert.setId(1L);
        alert.setTransaction(tx);
        alert.setDetectionConfig(DetectionConfig.ML_LLM_RAG);
        alert.setIsAnomaly(true);
        alert.setStatus(AlertStatus.NEW);
        alert.setRuleTriggered("LARGE_TRANSACTION: amount exceeds threshold");

        return alert;
    }
}
