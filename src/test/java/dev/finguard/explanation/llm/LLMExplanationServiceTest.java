package dev.finguard.explanation.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finguard.domain.repository.ExplanationRepository;
import dev.finguard.domain.repository.TransactionFeaturesRepository;
import dev.finguard.explanation.prompt.PromptBuilder;
import dev.finguard.explanation.rag.RAGContextService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import dev.finguard.llm.DynamicChatClientService;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("LLMExplanationService")
class LLMExplanationServiceTest {

    private LLMExplanationService service;

    @BeforeEach
    void setUp() {
        DynamicChatClientService dynamicChatClientService = mock(DynamicChatClientService.class);
        ChatClient chatClient = mock(ChatClient.class);
        org.mockito.Mockito.when(dynamicChatClientService.getCurrentClient()).thenReturn(chatClient);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        service = new LLMExplanationService(
                dynamicChatClientService,
                new PromptBuilder(),
                mock(RAGContextService.class),
                mock(TransactionFeaturesRepository.class),
                mock(ExplanationRepository.class),
                new ObjectMapper(),
                Counter.builder("test.llm.calls").register(registry),
                Counter.builder("test.llm.failures").register(registry),
                Counter.builder("test.llm.attempts").register(registry), // B-8: new attempt counter
                Timer.builder("test.llm.duration").register(registry)
        );
    }

    @Nested
    @DisplayName("Response Parsing")
    class ResponseParsingTests {

        @Test
        @DisplayName("Should parse valid JSON response")
        void parseResponse_validJson() {
            String json = """
                    {
                      "riskSummary": "High-risk transaction detected",
                      "explanationText": "The transaction shows unusual patterns.",
                      "suspiciousPatterns": ["Structuring", "Money Mule"],
                      "recommendedActions": ["File SAR", "Freeze account"],
                      "confidenceScore": 0.92
                    }
                    """;

            ExplanationResponse result = service.parseResponse(json);

            assertThat(result.riskSummary()).isEqualTo("High-risk transaction detected");
            assertThat(result.explanationText()).isEqualTo("The transaction shows unusual patterns.");
            assertThat(result.suspiciousPatterns()).containsExactly("Structuring", "Money Mule");
            assertThat(result.recommendedActions()).containsExactly("File SAR", "Freeze account");
            assertThat(result.confidenceScore()).isEqualTo(0.92);
        }

        @Test
        @DisplayName("Should handle JSON wrapped in markdown code fences")
        void parseResponse_markdownFences() {
            String json = """
                    ```json
                    {
                      "riskSummary": "Suspicious transfer",
                      "explanationText": "Details here.",
                      "suspiciousPatterns": ["ATO"],
                      "recommendedActions": ["Review"],
                      "confidenceScore": 0.75
                    }
                    ```
                    """;

            ExplanationResponse result = service.parseResponse(json);

            assertThat(result.riskSummary()).isEqualTo("Suspicious transfer");
            assertThat(result.confidenceScore()).isEqualTo(0.75);
        }

        @Test
        @DisplayName("Should handle code fences without language specifier")
        void parseResponse_plainCodeFences() {
            String json = """
                    ```
                    {
                      "riskSummary": "Alert",
                      "explanationText": "Text",
                      "suspiciousPatterns": [],
                      "recommendedActions": ["Check"],
                      "confidenceScore": 0.5
                    }
                    ```
                    """;

            ExplanationResponse result = service.parseResponse(json);

            assertThat(result.riskSummary()).isEqualTo("Alert");
        }

        @Test
        @DisplayName("Should return parse error for invalid JSON")
        void parseResponse_invalidJson() {
            String invalid = "This is not JSON at all, just a text response from the LLM.";

            ExplanationResponse result = service.parseResponse(invalid);

            assertThat(result.riskSummary()).isEqualTo("Unable to parse LLM response");
            assertThat(result.explanationText()).isEqualTo(invalid);
            assertThat(result.confidenceScore()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Should return parse error for null response")
        void parseResponse_null() {
            ExplanationResponse result = service.parseResponse(null);

            assertThat(result.riskSummary()).isEqualTo("Unable to parse LLM response");
        }

        @Test
        @DisplayName("Should return parse error for blank response")
        void parseResponse_blank() {
            ExplanationResponse result = service.parseResponse("   ");

            assertThat(result.riskSummary()).isEqualTo("Unable to parse LLM response");
        }

        @Test
        @DisplayName("Should repair truncated JSON cut mid-string value")
        void parseResponse_truncatedMidString_recoversRiskSummary() {
            String truncated =
                    "{\"riskSummary\": \"High-risk transaction detected\", \"explanationText\": \"The transaction show";

            ExplanationResponse result = service.parseResponse(truncated);

            assertThat(result.riskSummary()).isEqualTo("High-risk transaction detected");
        }

        @Test
        @DisplayName("Should repair truncated JSON cut mid-array")
        void parseResponse_truncatedMidArray_recoversCompleteFields() {
            String truncated = "{\n" +
                    "  \"riskSummary\": \"Suspicious\",\n" +
                    "  \"explanationText\": \"Details.\",\n" +
                    "  \"suspiciousPatterns\": [\"Structuring\", \"Par";

            ExplanationResponse result = service.parseResponse(truncated);

            assertThat(result.riskSummary()).isEqualTo("Suspicious");
            assertThat(result.explanationText()).isEqualTo("Details.");
        }

        @Test
        @DisplayName("Should extract fields via regex when JSON repair fails")
        void parseResponse_regexFallbackExtractsKnownFields() {
            String partial =
                    "{\"riskSummary\": \"Funds structuring detected\", \"explanationText\": \"Velocity anomalous.\", \"susp";

            ExplanationResponse result = service.parseResponse(partial);

            assertThat(result.riskSummary()).isEqualTo("Funds structuring detected");
            assertThat(result.explanationText()).isEqualTo("Velocity anomalous.");
        }

        @Test
        @DisplayName("Should return parse error when all recovery strategies fail")
        void parseResponse_allStagesFail_returnsParseError() {
            String garbled = "Sorry, I cannot help with that.";

            ExplanationResponse result = service.parseResponse(garbled);

            assertThat(result.riskSummary()).isEqualTo("Unable to parse LLM response");
        }

    }
}