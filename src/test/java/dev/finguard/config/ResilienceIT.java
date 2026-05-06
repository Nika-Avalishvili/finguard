package dev.finguard.config;

import dev.finguard.detection.ml.TribuoModelService;
import dev.finguard.explanation.llm.LLMExplanationService;
import dev.finguard.explanation.rag.RAGContextService;
import dev.finguard.llm.DynamicChatClientService;
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
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
    @Autowired private DynamicChatClientService dynamicChatClientService;
    @Autowired private CacheManager cacheManager;

    @BeforeEach
    void resetMocksAndCaches() {
        reset(vectorStore, dynamicChatClientService);
        var cache = cacheManager.getCache("rag-context");
        if (cache != null) {
            cache.clear();
        }
    }

    // ================================================================
    // LLM retry annotations
    // ================================================================

    @Nested
    @DisplayName("LLM call retry behavior")
    class LlmRetryBehavior {

        @SuppressWarnings("unchecked")
        private ChatClient.CallResponseSpec mockCallChain() {
            ChatClient client = mock(ChatClient.class);
            ChatClient.ChatClientRequestSpec reqSpec = mock(ChatClient.ChatClientRequestSpec.class);
            ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

            when(dynamicChatClientService.getCurrentClient()).thenReturn(client);
            when(client.prompt()).thenReturn(reqSpec);
            when(reqSpec.user(anyString())).thenReturn(reqSpec);
            when(reqSpec.call()).thenReturn(callSpec);

            return callSpec;
        }

        @Test
        @DisplayName("Succeeds on first attempt without retry")
        void callLlm_shouldSucceed_onFirstAttempt() {
            ChatClient.CallResponseSpec callSpec = mockCallChain();
            ChatResponse response = new ChatResponse(
                    List.of(new Generation(new AssistantMessage("{\"riskSummary\":\"test\"}"))));
            when(callSpec.chatResponse()).thenReturn(response);

            ChatResponse result = llmExplanationService.callLlmWithRetry("test prompt", 1L);

            assertThat(result).isNotNull();
            verify(dynamicChatClientService, times(1)).getCurrentClient();
        }

        @Test
        @DisplayName("Retries and succeeds after transient failure")
        void callLlm_shouldRetryAndSucceed_afterTransientFailure() {
            ChatClient.CallResponseSpec callSpec = mockCallChain();
            ChatResponse response = new ChatResponse(
                    List.of(new Generation(new AssistantMessage("{\"riskSummary\":\"test\"}"))));
            when(callSpec.chatResponse())
                    .thenThrow(new RuntimeException("Connection timeout"))
                    .thenReturn(response);

            ChatResponse result = llmExplanationService.callLlmWithRetry("test prompt", 1L);

            assertThat(result).isNotNull();
            verify(dynamicChatClientService, times(2)).getCurrentClient();
        }

        @Test
        @DisplayName("Exhausts all retries and throws after persistent failure")
        void callLlm_shouldExhaustRetries_afterPersistentFailure() {
            ChatClient.CallResponseSpec callSpec = mockCallChain();
            when(callSpec.chatResponse())
                    .thenThrow(new RuntimeException("Service unavailable"));

            assertThatThrownBy(() -> llmExplanationService.callLlmWithRetry("test prompt", 1L))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("LLM service unavailable after 3 attempts");

            // 3 attempts = maxAttempts
            verify(dynamicChatClientService, times(3)).getCurrentClient();
        }

        @Test
        @DisplayName("Does not retry on IllegalArgumentException")
        void callLlm_shouldNotRetry_onIllegalArgument() {
            ChatClient.CallResponseSpec callSpec = mockCallChain();
            when(callSpec.chatResponse())
                    .thenThrow(new IllegalArgumentException("Bad input"));

            assertThatThrownBy(() -> llmExplanationService.callLlmWithRetry("test prompt", 1L))
                    .isInstanceOf(RuntimeException.class);

            // Should fail immediately — no retries for IllegalArgumentException
            verify(dynamicChatClientService, times(1)).getCurrentClient();
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
    @DisplayName("ML model loading retry behavior")
    class MlModelRetryBehavior {

        @Test
        @DisplayName("Spring Retry proxy is active on TribuoModelService")
        void springRetryProxy_isActive() {
            assertThat(tribuoModelService.getClass().getName())
                    .isNotEqualTo(TribuoModelService.class.getName());
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
