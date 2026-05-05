package dev.finguard.explanation.llm;

import dev.finguard.domain.enums.DetectionConfig;
import dev.finguard.domain.enums.ExplanationType;
import dev.finguard.domain.model.Alert;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Confidence-gated LLM routing strategy (FrugalGPT pattern, arXiv:2305.05176).
 *
 * <p>Routes each alert to the cheapest explanation tier that covers its risk level.
 * This prevents over-spending LLM tokens on low-confidence alerts while ensuring
 * high-risk anomalies get full RAG context augmentation.</p>
 *
 * <ul>
 *   <li>Score &lt; {@code directThreshold} → {@link RouteDecision#SKIP} (no LLM call)</li>
 *   <li>Score in [{@code directThreshold}, {@code ragThreshold}) → {@link RouteDecision#DIRECT}</li>
 *   <li>Score ≥ {@code ragThreshold} → {@link RouteDecision#RAG}</li>
 * </ul>
 *
 * <p>Rule-only alerts (no ML score) default to the config's intended mode without gating.</p>
 */
@Component
public class ExplanationRouter {

    @Value("${finguard.explanation.routing.direct-threshold:0.5}")
    private double directThreshold;

    @Value("${finguard.explanation.routing.rag-threshold:0.75}")
    private double ragThreshold;

    public enum RouteDecision { SKIP, DIRECT, RAG }

    /**
     * Decide which explanation strategy to apply for a given alert and config.
     *
     * @param alert  the alert with ML risk score and rule trigger state
     * @param config the detection configuration in force
     * @return the routing decision
     */
    public RouteDecision route(Alert alert, DetectionConfig config) {
        return switch (config) {
            case RULES_ONLY, ML_ONLY -> RouteDecision.SKIP;
            case ML_LLM_DIRECT -> routeByScore(alert, false);
            case ML_LLM_RAG, FULL_SYSTEM -> routeByScore(alert, true);
        };
    }

    /**
     * Determine the effective explanation type for a requested type, applying confidence gating.
     *
     * <p>Used by the async batch service to potentially downgrade LLM_RAG calls to LLM_DIRECT
     * for medium-confidence alerts, avoiding unnecessary pgvector round-trips.</p>
     *
     * @param alert         the alert to route
     * @param requestedType the explanation type initially requested
     * @return the effective type to use, or {@code null} to skip explanation entirely
     */
    public ExplanationType effectiveType(Alert alert, ExplanationType requestedType) {
        if (requestedType == ExplanationType.LLM_DIRECT || requestedType == ExplanationType.LLM_RAG_VALIDATED) {
            return requestedType;
        }

        Double score = alert.getMlRiskScore();
        if (score == null) {
            // Rule-only alert: honour requested type unchanged
            return requestedType;
        }

        if (score < directThreshold) {
            return null; // skip
        }

        if (requestedType == ExplanationType.LLM_RAG && score < ragThreshold) {
            return ExplanationType.LLM_DIRECT; // downgrade — DIRECT is sufficient
        }

        return requestedType;
    }

    private RouteDecision routeByScore(Alert alert, boolean ragAllowed) {
        Double score = alert.getMlRiskScore();

        if (score == null) {
            // Rule-triggered alert without ML score: use the intended mode
            return ragAllowed ? RouteDecision.RAG : RouteDecision.DIRECT;
        }

        if (score < directThreshold) {
            return RouteDecision.SKIP;
        }

        if (!ragAllowed || score < ragThreshold) {
            return RouteDecision.DIRECT;
        }

        return RouteDecision.RAG;
    }
}