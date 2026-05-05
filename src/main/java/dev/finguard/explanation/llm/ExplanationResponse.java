package dev.finguard.explanation.llm;

import java.util.List;

/**
 * Parsed response from the LLM explanation generation.
 *
 * <p>Maps directly to the JSON structure that the LLM is instructed to produce.
 * Parsed via Jackson from the raw LLM output.</p>
 *
 * @param riskSummary        one-sentence summary of why the transaction is suspicious
 * @param explanationText    detailed multi-paragraph explanation
 * @param suspiciousPatterns list of recognized fraud typology names
 * @param recommendedActions list of concrete compliance officer actions
 * @param confidenceScore    LLM self-assessed confidence (0.0-1.0)
 */
public record ExplanationResponse(
        String riskSummary,
        String explanationText,
        List<String> suspiciousPatterns,
        List<String> recommendedActions,
        double confidenceScore
) {

    /**
     * Fallback response when LLM output cannot be parsed.
     */
    public static ExplanationResponse parseError(String rawResponse) {
        return new ExplanationResponse(
                "Unable to parse LLM response",
                rawResponse,
                List.of(),
                List.of("Manual review required"),
                0.0
        );
    }
}
