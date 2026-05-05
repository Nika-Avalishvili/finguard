package dev.finguard.evaluation.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.finguard.domain.model.Alert;
import dev.finguard.domain.model.Explanation;
import dev.finguard.domain.repository.ExplanationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.finguard.llm.DynamicChatClientService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Evaluates LLM-generated explanations using the CAKR framework.
 *
 * <p>CAKR stands for the four evaluation dimensions:</p>
 * <ul>
 *   <li><b>C</b>ompleteness — Does the explanation cover all detection signals?</li>
 *   <li><b>A</b>ctionability — Are the recommended actions concrete and useful?</li>
 *   <li><b>K</b>nowledge — Is the explanation grounded in recognized fraud patterns?</li>
 *   <li><b>R</b>egulatory — Does it reference applicable regulatory requirements?</li>
 * </ul>
 *
 * <p>Each dimension is scored 1–5 by a separate LLM evaluation call.
 * The evaluator LLM acts as a "judge" — ideally a stronger model (Claude)
 * evaluating outputs from a weaker model (Ollama/Llama).</p>
 *
 * <p>Scores are persisted on the {@link Explanation} entity for analysis.</p>
 */
@Service
public class CAKRScorer {

    private static final Logger log = LoggerFactory.getLogger(CAKRScorer.class);

    /**
     * System prompt fallback. Replaced at startup by {@code classpath:prompts/cakr/system.md}
     * if present. The classpath version is the authoritative rubric — keep this constant
     * in sync only as a safety net for missing-resource boots.
     */
    private static final String EVAL_SYSTEM_PROMPT_FALLBACK = """
            You are an expert financial fraud compliance evaluator. You will be given a \
            fraud detection explanation and asked to score it on a specific quality dimension.

            Score STRICTLY on a 1-5 scale:
            1 = Very poor — critical gaps or completely irrelevant
            2 = Below average — major issues, missing key elements
            3 = Adequate — covers basics but lacks depth
            4 = Good — thorough, with minor gaps
            5 = Excellent — comprehensive, precise, and professionally useful

            Respond with ONLY a valid JSON object:
            {"score": <1-5>, "reasoning": "<one sentence justification>"}

            Do not add any text before or after the JSON.
            """;

    /** Lazily loaded system prompt (classpath:prompts/cakr/system.md, fallback inline). */
    private volatile String evalSystemPrompt;

    private final DynamicChatClientService dynamicChatClientService;
    private final ExplanationRepository explanationRepository;
    private final ObjectMapper objectMapper;
    // Virtual-thread executor for parallel dimension scoring (4 LLM calls → 4 concurrent VTs)
    private static final Executor SCORER_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Max explanations being CAKR-scored concurrently. Each explanation itself
     * fans out 4 parallel dim calls, so the actual LLM concurrency is
     * {@code outerConcurrency * 4}. Defaults to 8 (≈32 concurrent LLM HTTP
     * requests). Lower for local Ollama (OLLAMA_NUM_PARALLEL default = 4),
     * raise for Claude API which tolerates higher concurrency.
     */
    @Value("${finguard.evaluation.cakr.outer-concurrency:8}")
    private int outerConcurrency;

    /** Cached prompts loaded from classpath:prompts/cakr/*.md (resolved lazily — see loadPromptOrFallback). */
    private volatile String completenessPromptTemplate;
    private volatile String actionabilityPrompt;
    private volatile String knowledgePrompt;
    private volatile String regulatoryPrompt;

    public CAKRScorer(DynamicChatClientService dynamicChatClientService,
                       ExplanationRepository explanationRepository,
                       ObjectMapper objectMapper) {
        this.dynamicChatClientService = dynamicChatClientService;
        this.explanationRepository = explanationRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Score a single explanation on all four CAKR dimensions.
     *
     * <p>Makes 4 separate LLM calls (one per dimension). Each call includes
     * the explanation text, the alert context, and a dimension-specific prompt.</p>
     *
     * @param explanation the explanation to evaluate
     * @return the updated explanation with CAKR scores set
     */
    public Explanation scoreExplanation(Explanation explanation) {
        Alert alert = explanation.getAlert();
        String explanationText = explanation.getExplanationText();
        String riskSummary = explanation.getRiskSummary();
        String patterns = explanation.getSuspiciousPatterns();
        String actions = explanation.getRecommendedActions();

        String context = buildEvalContext(alert, explanationText, riskSummary, patterns, actions);

        // Run all 4 CAKR dimensions in parallel (each is a blocking LLM HTTP call).
        // Virtual threads make this safe and efficient — no thread pool starvation.
        CompletableFuture<Double> fC = CompletableFuture.supplyAsync(
                () -> scoreDimension(context, buildCompletenessPrompt(alert)), SCORER_EXECUTOR);
        CompletableFuture<Double> fA = CompletableFuture.supplyAsync(
                () -> scoreDimension(context, buildActionabilityPrompt()), SCORER_EXECUTOR);
        CompletableFuture<Double> fK = CompletableFuture.supplyAsync(
                () -> scoreDimension(context, buildKnowledgePrompt()), SCORER_EXECUTOR);
        CompletableFuture<Double> fR = CompletableFuture.supplyAsync(
                () -> scoreDimension(context, buildRegulatoryPrompt()), SCORER_EXECUTOR);

        CompletableFuture.allOf(fC, fA, fK, fR).join();

        // Null signifies a failed dim; see parseScore/scoreDimension contract.
        explanation.setCakrCompleteness(fC.join());
        explanation.setCakrActionability(fA.join());
        explanation.setCakrCorrectness(fK.join());
        explanation.setCakrRegulatory(fR.join());

        Double avg = explanation.getCakrAverage();
        log.info("CAKR scores for explanation {}: C={}, A={}, K={}, R={} (avg={})",
                explanation.getId(),
                formatScore(explanation.getCakrCompleteness()),
                formatScore(explanation.getCakrActionability()),
                formatScore(explanation.getCakrCorrectness()),
                formatScore(explanation.getCakrRegulatory()),
                avg != null ? String.format("%.2f", avg) : "N/A");

        return explanationRepository.save(explanation);
    }

    private static String formatScore(Double s) {
        return s == null ? "N/A" : String.format("%.1f", s);
    }

    /**
     * Score all explanations for a list of alerts.
     *
     * @param explanations explanations to evaluate
     * @return number of explanations scored
     */
    public int scoreAll(List<Explanation> explanations) {
        int total = explanations.size();
        if (total == 0) return 0;

        // Audit B-10: bound outer concurrency. Without this, 50 explanations
        // × 4 dim calls = 200 concurrent HTTP requests to the LLM —
        // overwhelming local Ollama and blowing the connection pool.
        // Configurable via finguard.evaluation.cakr.outer-concurrency.
        int cap = Math.max(1, outerConcurrency);
        java.util.concurrent.Semaphore gate = new java.util.concurrent.Semaphore(cap);
        java.util.concurrent.atomic.AtomicInteger scored = new java.util.concurrent.atomic.AtomicInteger();

        List<CompletableFuture<Void>> futures = new java.util.ArrayList<>(total);
        for (Explanation explanation : explanations) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    gate.acquire();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    scoreExplanation(explanation);
                    int done = scored.incrementAndGet();
                    if (done % 10 == 0 || done == total) {
                        log.info("CAKR scoring progress: {}/{} explanations scored", done, total);
                    }
                } catch (Exception e) {
                    log.error("Failed to score explanation {}: {}", explanation.getId(), e.getMessage());
                } finally {
                    gate.release();
                }
            }, SCORER_EXECUTOR));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        int finalScored = scored.get();
        log.info("CAKR scoring complete: {}/{} explanations scored successfully", finalScored, total);
        return finalScored;
    }

    /**
     * Call the evaluator LLM for a single CAKR dimension.
     *
     * <p><b>Failure semantics (post-014):</b> returns {@code null} when the LLM
     * call fails, the response is blank, cannot be parsed, or the reported score
     * is outside the valid range [1, 5]. Using {@code null} (not 0.0) ensures
     * that SQL {@code AVG(...)} skips failed evaluations rather than depressing
     * the reported mean. Callers and persistence paths must accept
     * {@code Double} and tolerate nulls.</p>
     *
     * @param context         the explanation + alert context
     * @param dimensionPrompt the dimension-specific evaluation question
     * @return score in [1.0, 5.0] on success; {@code null} on any failure
     */
    Double scoreDimension(String context, String dimensionPrompt) {
        String fullPrompt = context + "\n\n## Evaluation Task\n" + dimensionPrompt;

        try {
            ChatClient chatClient = dynamicChatClientService.getCurrentClient();
            ChatResponse response = chatClient.prompt()
                    .system(systemPrompt())
                    .user(fullPrompt)
                    .call()
                    .chatResponse();

            String raw = response.getResult().getOutput().getText();
            return parseScore(raw);
        } catch (Exception e) {
            log.warn("CAKR dimension scoring failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parse the LLM evaluator's JSON response to extract the numeric score.
     *
     * @return the score in [1, 5] on success; {@code null} on failure (null/blank
     *         input, malformed JSON, missing score field, or out-of-range value).
     *         Note: unlike the pre-014 version, this no longer silently clamps
     *         {@code 0} or {@code 7} to the nearest valid value — those are
     *         treated as failed evaluations.
     */
    Double parseScore(String raw) {
        if (raw == null || raw.isBlank()) return null;

        try {
            String cleaned = raw.strip();
            if (cleaned.startsWith("```json")) cleaned = cleaned.substring(7);
            else if (cleaned.startsWith("```")) cleaned = cleaned.substring(3);
            if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
            cleaned = cleaned.strip();

            var node = objectMapper.readTree(cleaned);
            var scoreNode = node.get("score");
            if (scoreNode == null || scoreNode.isNull()) {
                log.warn("CAKR response missing 'score' field: {}", raw);
                return null;
            }
            double score = scoreNode.asDouble();
            if (Double.isNaN(score) || score < 1.0 || score > 5.0) {
                log.warn("CAKR score out of range [1,5]: {}", score);
                return null;
            }
            return score;
        } catch (Exception e) {
            log.warn("Failed to parse CAKR score from: {}", raw);
            return null;
        }
    }

    // ================================================================
    // Prompt builders for each CAKR dimension
    // ================================================================

    private String buildEvalContext(Alert alert, String explanationText,
                                    String riskSummary, String patterns, String actions) {
        StringBuilder sb = new StringBuilder("## Explanation Under Evaluation\n\n");
        sb.append("**Risk Summary:** ").append(riskSummary != null ? riskSummary : "N/A").append("\n\n");
        sb.append("**Full Explanation:**\n").append(explanationText != null ? explanationText : "N/A").append("\n\n");
        sb.append("**Suspicious Patterns:** ").append(patterns != null ? patterns : "[]").append("\n");
        sb.append("**Recommended Actions:** ").append(actions != null ? actions : "[]").append("\n\n");

        sb.append("## Alert Context\n");
        sb.append("- Detection Config: ").append(alert.getDetectionConfig()).append("\n");
        if (alert.getRuleTriggered() != null) {
            sb.append("- Rules Triggered: ").append(alert.getRuleTriggered()).append("\n");
        }
        if (alert.getMlRiskScore() != null) {
            sb.append("- ML Risk Score: ").append(String.format("%.4f", alert.getMlRiskScore())).append("\n");
        }
        if (alert.getMlModelName() != null) {
            sb.append("- ML Model: ").append(alert.getMlModelName()).append("\n");
        }

        return sb.toString();
    }

    /**
     * Build the COMPLETENESS dimension prompt by appending alert-specific context to the
     * externalised template at {@code classpath:prompts/cakr/completeness.md}.
     */
    private String buildCompletenessPrompt(Alert alert) {
        StringBuilder sb = new StringBuilder(loadPromptOrFallback(
                "classpath:prompts/cakr/completeness.md", COMPLETENESS_FALLBACK));

        if (alert.getRuleTriggered() != null) {
            sb.append("\nExpected rule references: ").append(alert.getRuleTriggered()).append("\n");
        }
        if (alert.getMlRiskScore() != null) {
            sb.append("Expected ML score reference: ")
                    .append(String.format("%.4f", alert.getMlRiskScore())).append("\n");
        }
        return sb.toString();
    }

    private String buildActionabilityPrompt() {
        if (actionabilityPrompt == null) {
            actionabilityPrompt = loadPromptOrFallback(
                    "classpath:prompts/cakr/actionability.md", ACTIONABILITY_FALLBACK);
        }
        return actionabilityPrompt;
    }

    private String buildKnowledgePrompt() {
        if (knowledgePrompt == null) {
            knowledgePrompt = loadPromptOrFallback(
                    "classpath:prompts/cakr/knowledge.md", KNOWLEDGE_FALLBACK);
        }
        return knowledgePrompt;
    }

    private String buildRegulatoryPrompt() {
        if (regulatoryPrompt == null) {
            regulatoryPrompt = loadPromptOrFallback(
                    "classpath:prompts/cakr/regulatory.md", REGULATORY_FALLBACK);
        }
        return regulatoryPrompt;
    }

    /** Lazy + cached system-prompt accessor. Resolves to classpath file with inline fallback. */
    private String systemPrompt() {
        if (evalSystemPrompt == null) {
            evalSystemPrompt = loadPromptOrFallback(
                    "classpath:prompts/cakr/system.md", EVAL_SYSTEM_PROMPT_FALLBACK);
        }
        return evalSystemPrompt;
    }

    /**
     * Load a prompt template from the classpath. On any IO error the inline fallback is
     * used and a WARN logged once — we never want a missing prompt file to break evaluation.
     * Package-private for tests.
     */
    String loadPromptOrFallback(String classpathLocation, String fallback) {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources(classpathLocation);
            if (resources.length == 0 || !resources[0].exists()) {
                log.warn("CAKR prompt {} not found on classpath — using inline fallback", classpathLocation);
                return fallback;
            }
            try (var in = resources[0].getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("CAKR prompt {} failed to load ({}) — using inline fallback",
                    classpathLocation, e.getMessage());
            return fallback;
        } catch (UncheckedIOException e) {
            log.warn("CAKR prompt {} failed to load ({}) — using inline fallback",
                    classpathLocation, e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            return fallback;
        }
    }

    // ── Inline fallbacks (kept in sync with the .md files) ─────────────────
    private static final String COMPLETENESS_FALLBACK = """
            Score the COMPLETENESS of this explanation (1-5).

            Consider whether the explanation:
            - References all detection signals (rules triggered, ML score)
            - Explains the specific numeric values (amounts, thresholds, velocities)
            - Covers both the 'what' (what was detected) and the 'why' (why it's suspicious)
            - Addresses the transaction context (type, timing, accounts involved)
            """;

    private static final String ACTIONABILITY_FALLBACK = """
            Score the ACTIONABILITY of this explanation's recommended actions (1-5).

            Consider whether the recommended actions:
            - Are specific and concrete (not vague like "investigate further")
            - Follow standard compliance workflows (SAR filing, account freezing, escalation)
            - Prioritize actions by urgency or risk level
            - Are appropriate for a compliance officer audience
            - Include both immediate and follow-up steps
            """;

    private static final String KNOWLEDGE_FALLBACK = """
            Score the KNOWLEDGE grounding of this explanation (1-5).

            Consider whether the explanation:
            - References recognized fraud typologies by name (structuring, money mule, ATO, etc.)
            - Demonstrates understanding of the fraud mechanics described
            - Uses accurate financial terminology
            - Connects the detected pattern to known fraud indicators
            - Avoids making claims unsupported by the provided data
            """;

    private static final String REGULATORY_FALLBACK = """
            Score the REGULATORY relevance of this explanation (1-5).

            Consider whether the explanation:
            - References applicable AML/CFT regulations or guidelines
            - Mentions relevant reporting thresholds (e.g., BSA $10,000 CTR threshold)
            - Aligns with FinCEN, FATF, or other regulatory body guidance
            - Supports the compliance decision-making process
            - Indicates awareness of regulatory obligations for the detected pattern
            """;
}
