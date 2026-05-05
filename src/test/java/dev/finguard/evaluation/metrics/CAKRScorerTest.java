package dev.finguard.evaluation.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finguard.domain.repository.ExplanationRepository;
import dev.finguard.llm.DynamicChatClientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("CAKRScorer")
class CAKRScorerTest {

    private CAKRScorer scorer;

    @BeforeEach
    void setUp() {
        DynamicChatClientService dynamicService = mock(DynamicChatClientService.class);
        when(dynamicService.getCurrentClient()).thenReturn(mock(ChatClient.class));

        scorer = new CAKRScorer(
                dynamicService,
                mock(ExplanationRepository.class),
                new ObjectMapper()
        );
    }

    @Nested
    @DisplayName("Score Parsing (post-014 null semantics)")
    class ScoreParsing {

        @Test
        @DisplayName("Should parse valid JSON score")
        void parseScore_validJson() {
            Double score = scorer.parseScore("{\"score\": 4, \"reasoning\": \"Good explanation\"}");
            assertThat(score).isEqualTo(4.0);
        }

        @Test
        @DisplayName("Should parse score with decimal")
        void parseScore_decimal() {
            Double score = scorer.parseScore("{\"score\": 3.5, \"reasoning\": \"Adequate\"}");
            assertThat(score).isEqualTo(3.5);
        }

        @Test
        @DisplayName("Should parse boundary score 1.0")
        void parseScore_boundaryLow() {
            Double score = scorer.parseScore("{\"score\": 1, \"reasoning\": \"Minimum\"}");
            assertThat(score).isEqualTo(1.0);
        }

        @Test
        @DisplayName("Should parse boundary score 5.0")
        void parseScore_boundaryHigh() {
            Double score = scorer.parseScore("{\"score\": 5, \"reasoning\": \"Maximum\"}");
            assertThat(score).isEqualTo(5.0);
        }

        @Test
        @DisplayName("Should handle markdown-wrapped JSON")
        void parseScore_markdownWrapped() {
            String raw = "```json\n{\"score\": 5, \"reasoning\": \"Excellent\"}\n```";
            Double score = scorer.parseScore(raw);
            assertThat(score).isEqualTo(5.0);
        }

        @Test
        @DisplayName("Should return null for score above 5 (out of range, not clamped)")
        void parseScore_aboveRange_returnsNull() {
            Double score = scorer.parseScore("{\"score\": 7, \"reasoning\": \"Out of range\"}");
            assertThat(score).isNull();
        }

        @Test
        @DisplayName("Should return null for score below 1 (out of range, not clamped)")
        void parseScore_belowRange_returnsNull() {
            Double score = scorer.parseScore("{\"score\": 0, \"reasoning\": \"Out of range\"}");
            assertThat(score).isNull();
        }

        @Test
        @DisplayName("Should return null for null input")
        void parseScore_null_returnsNull() {
            assertThat(scorer.parseScore(null)).isNull();
        }

        @Test
        @DisplayName("Should return null for blank input")
        void parseScore_blank_returnsNull() {
            assertThat(scorer.parseScore("   ")).isNull();
        }

        @Test
        @DisplayName("Should return null for invalid JSON")
        void parseScore_invalidJson_returnsNull() {
            assertThat(scorer.parseScore("This is not JSON")).isNull();
        }

        @Test
        @DisplayName("Should return null for JSON without score field")
        void parseScore_missingField_returnsNull() {
            assertThat(scorer.parseScore("{\"reasoning\": \"No score here\"}")).isNull();
        }

        @Test
        @DisplayName("Should handle plain code fences")
        void parseScore_plainFences() {
            String raw = "```\n{\"score\": 3, \"reasoning\": \"OK\"}\n```";
            Double score = scorer.parseScore(raw);
            assertThat(score).isEqualTo(3.0);
        }

        @Test
        @DisplayName("Should return null for JSON with null score field")
        void parseScore_explicitNullScore_returnsNull() {
            assertThat(scorer.parseScore("{\"score\": null}")).isNull();
        }
    }
}
