package dev.finguard.explanation.llm;

import dev.finguard.domain.enums.DetectionConfig;
import dev.finguard.domain.enums.ExplanationType;
import dev.finguard.domain.model.Alert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ExplanationRouter")
class ExplanationRouterTest {

    private ExplanationRouter router;

    @BeforeEach
    void setUp() {
        router = new ExplanationRouter();
        ReflectionTestUtils.setField(router, "directThreshold", 0.5);
        ReflectionTestUtils.setField(router, "ragThreshold", 0.75);
    }

    @Nested
    @DisplayName("route() — by DetectionConfig")
    class RouteByConfig {

        @Test
        @DisplayName("RULES_ONLY and ML_ONLY always return SKIP")
        void nonLlmConfigs_alwaysSkip() {
            Alert alert = alertWithScore(0.99);
            assertThat(router.route(alert, DetectionConfig.RULES_ONLY)).isEqualTo(ExplanationRouter.RouteDecision.SKIP);
            assertThat(router.route(alert, DetectionConfig.ML_ONLY)).isEqualTo(ExplanationRouter.RouteDecision.SKIP);
        }

        @Test
        @DisplayName("ML_LLM_DIRECT with high score returns DIRECT")
        void mlLlmDirect_highScore_returnsDirect() {
            Alert alert = alertWithScore(0.95);
            assertThat(router.route(alert, DetectionConfig.ML_LLM_DIRECT)).isEqualTo(ExplanationRouter.RouteDecision.DIRECT);
        }

        @Test
        @DisplayName("ML_LLM_RAG with score above rag threshold returns RAG")
        void mlLlmRag_highScore_returnsRag() {
            Alert alert = alertWithScore(0.80);
            assertThat(router.route(alert, DetectionConfig.ML_LLM_RAG)).isEqualTo(ExplanationRouter.RouteDecision.RAG);
        }

        @Test
        @DisplayName("ML_LLM_RAG with medium score 0.5 to 0.75 returns DIRECT")
        void mlLlmRag_mediumScore_returnsDirect() {
            Alert alert = alertWithScore(0.60);
            assertThat(router.route(alert, DetectionConfig.ML_LLM_RAG)).isEqualTo(ExplanationRouter.RouteDecision.DIRECT);
        }

        @Test
        @DisplayName("Score below direct threshold returns SKIP for any LLM config")
        void anyConfig_veryLowScore_returnsSkip() {
            Alert alert = alertWithScore(0.30);
            assertThat(router.route(alert, DetectionConfig.ML_LLM_DIRECT)).isEqualTo(ExplanationRouter.RouteDecision.SKIP);
            assertThat(router.route(alert, DetectionConfig.ML_LLM_RAG)).isEqualTo(ExplanationRouter.RouteDecision.SKIP);
            assertThat(router.route(alert, DetectionConfig.FULL_SYSTEM)).isEqualTo(ExplanationRouter.RouteDecision.SKIP);
        }

        @Test
        @DisplayName("FULL_SYSTEM with high score returns RAG")
        void fullSystem_highScore_returnsRag() {
            Alert alert = alertWithScore(0.90);
            assertThat(router.route(alert, DetectionConfig.FULL_SYSTEM)).isEqualTo(ExplanationRouter.RouteDecision.RAG);
        }

        @Test
        @DisplayName("Rule-only alert null score with RAG config returns RAG")
        void ruleOnlyAlert_ragConfig_returnsRag() {
            Alert alert = alertWithScore(null);
            assertThat(router.route(alert, DetectionConfig.ML_LLM_RAG)).isEqualTo(ExplanationRouter.RouteDecision.RAG);
            assertThat(router.route(alert, DetectionConfig.FULL_SYSTEM)).isEqualTo(ExplanationRouter.RouteDecision.RAG);
        }

        @Test
        @DisplayName("Rule-only alert null score with DIRECT config returns DIRECT")
        void ruleOnlyAlert_directConfig_returnsDirect() {
            Alert alert = alertWithScore(null);
            assertThat(router.route(alert, DetectionConfig.ML_LLM_DIRECT)).isEqualTo(ExplanationRouter.RouteDecision.DIRECT);
        }
    }

    @Nested
    @DisplayName("effectiveType() — confidence gating")
    class EffectiveType {

        @Test
        @DisplayName("LLM_DIRECT always returned unchanged regardless of score")
        void direct_alwaysReturnsDirectUnchanged() {
            assertThat(router.effectiveType(alertWithScore(0.1), ExplanationType.LLM_DIRECT)).isEqualTo(ExplanationType.LLM_DIRECT);
            assertThat(router.effectiveType(alertWithScore(0.9), ExplanationType.LLM_DIRECT)).isEqualTo(ExplanationType.LLM_DIRECT);
        }

        @Test
        @DisplayName("LLM_RAG with score above ragThreshold stays LLM_RAG")
        void rag_highScore_staysRag() {
            assertThat(router.effectiveType(alertWithScore(0.80), ExplanationType.LLM_RAG)).isEqualTo(ExplanationType.LLM_RAG);
        }

        @Test
        @DisplayName("LLM_RAG with medium score is downgraded to LLM_DIRECT")
        void rag_mediumScore_downgradedToDirect() {
            assertThat(router.effectiveType(alertWithScore(0.60), ExplanationType.LLM_RAG)).isEqualTo(ExplanationType.LLM_DIRECT);
        }

        @Test
        @DisplayName("LLM_RAG with score below directThreshold returns null")
        void rag_lowScore_returnsNull() {
            assertThat(router.effectiveType(alertWithScore(0.30), ExplanationType.LLM_RAG)).isNull();
        }

        @Test
        @DisplayName("Null score honours requested type unchanged")
        void ruleOnly_noScore_returnsRequestedType() {
            assertThat(router.effectiveType(alertWithScore(null), ExplanationType.LLM_RAG)).isEqualTo(ExplanationType.LLM_RAG);
            assertThat(router.effectiveType(alertWithScore(null), ExplanationType.LLM_DIRECT)).isEqualTo(ExplanationType.LLM_DIRECT);
        }

        @Test
        @DisplayName("Score at directThreshold boundary 0.5 returns DIRECT")
        void scoreBoundary_atDirectThreshold_returnsDirect() {
            assertThat(router.effectiveType(alertWithScore(0.5), ExplanationType.LLM_RAG)).isEqualTo(ExplanationType.LLM_DIRECT);
        }

        @Test
        @DisplayName("Score at ragThreshold boundary 0.75 returns RAG")
        void scoreBoundary_atRagThreshold_returnsRag() {
            assertThat(router.effectiveType(alertWithScore(0.75), ExplanationType.LLM_RAG)).isEqualTo(ExplanationType.LLM_RAG);
        }
    }

    private Alert alertWithScore(Double score) {
        Alert alert = new Alert();
        alert.setMlRiskScore(score);
        return alert;
    }
}
