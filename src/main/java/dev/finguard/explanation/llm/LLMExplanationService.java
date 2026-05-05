package dev.finguard.explanation.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import dev.finguard.domain.enums.ExplanationType;
import dev.finguard.domain.model.Alert;
import dev.finguard.domain.model.Explanation;
import dev.finguard.domain.model.Transaction;
import dev.finguard.domain.model.TransactionFeatures;
import dev.finguard.domain.repository.ExplanationRepository;
import dev.finguard.domain.repository.TransactionFeaturesRepository;
import dev.finguard.explanation.prompt.PromptBuilder;
import dev.finguard.explanation.rag.RAGContextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.finguard.llm.DynamicChatClientService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Generates human-readable fraud explanations using LLM (Spring AI ChatClient).
 *
 * <p>Supports two explanation modes:</p>
 * <ul>
 *   <li><b>LLM_DIRECT</b> — sends transaction data + detection signals directly to the LLM</li>
 *   <li><b>LLM_RAG</b> — enriches the prompt with fraud pattern context from the pgvector
 *       knowledge base before sending to the LLM</li>
 * </ul>
 *
 * <p>The LLM is instructed to respond in a structured JSON format that is parsed
 * into an {@link ExplanationResponse} record. Token counts, latency, and the raw
 * prompt/response are persisted on the {@link Explanation} entity for evaluation.</p>
 */
@Service
public class LLMExplanationService {

    private static final Logger log = LoggerFactory.getLogger(LLMExplanationService.class);

    private final DynamicChatClientService dynamicChatClientService;
    private final PromptBuilder promptBuilder;
    private final RAGContextService ragContextService;
    private final TransactionFeaturesRepository featuresRepository;
    private final ExplanationRepository explanationRepository;
    private final ObjectMapper objectMapper;
    private final Counter llmCallCounter;
    private final Counter llmFailureCounter;
    private final Counter llmAttemptCounter;
    private final Timer llmCallTimer;

    public LLMExplanationService(DynamicChatClientService dynamicChatClientService,
                                  PromptBuilder promptBuilder,
                                  RAGContextService ragContextService,
                                  TransactionFeaturesRepository featuresRepository,
                                  ExplanationRepository explanationRepository,
                                  ObjectMapper objectMapper,
                                  Counter llmCallCounter,
                                  Counter llmFailureCounter,
                                  Counter llmAttemptCounter,
                                  Timer llmCallTimer) {
        this.dynamicChatClientService = dynamicChatClientService;
        this.promptBuilder = promptBuilder;
        this.ragContextService = ragContextService;
        this.featuresRepository = featuresRepository;
        this.explanationRepository = explanationRepository;
        this.objectMapper = objectMapper;
        this.llmCallCounter = llmCallCounter;
        this.llmFailureCounter = llmFailureCounter;
        this.llmAttemptCounter = llmAttemptCounter;
        this.llmCallTimer = llmCallTimer;
    }

    /**
     * Generate an explanation for an alert using LLM_DIRECT mode (no RAG context).
     *
     * @param alert the alert to explain
     * @return the persisted Explanation entity
     */
    public Explanation generateDirectExplanation(Alert alert) {
        Transaction tx = alert.getTransaction();
        TransactionFeatures features = featuresRepository
                .findByTransactionId(tx.getId())
                .orElse(null);

        String prompt = promptBuilder.buildDirectPrompt(alert, tx, features);

        return callLlmAndPersist(alert, prompt, ExplanationType.LLM_DIRECT, null);
    }

    /**
     * Generate a focused LLM_DIRECT explanation using a signal-conditioned prompt template.
     *
     * <p>Shorter than the generic direct prompt — selects the template most relevant
     * to the alert's primary anomaly signal (velocity, structuring, large amount, etc.).
     * Use this for medium-risk alerts routed via {@link ExplanationRouter#RouteDecision#DIRECT}
     * where a full RAG round-trip would be wasteful.</p>
     *
     * @param alert the alert to explain
     * @return the persisted Explanation entity
     */
    public Explanation generateFocusedDirectExplanation(Alert alert) {
        Transaction tx = alert.getTransaction();
        TransactionFeatures features = featuresRepository
                .findByTransactionId(tx.getId())
                .orElse(null);

        String prompt = promptBuilder.buildFocusedDirectPrompt(alert, tx, features);
        return callLlmAndPersist(alert, prompt, ExplanationType.LLM_DIRECT, null);
    }

    /**
     * Generate an explanation for an alert using LLM_RAG mode (with fraud pattern context).
     *
     * @param alert the alert to explain
     * @return the persisted Explanation entity
     */
    public Explanation generateRagExplanation(Alert alert) {
        Transaction tx = alert.getTransaction();
        TransactionFeatures features = featuresRepository
                .findByTransactionId(tx.getId())
                .orElse(null);

        List<Document> ragContext = ragContextService.retrieveContext(alert, tx);

        String prompt = promptBuilder.buildRagPrompt(alert, tx, features, ragContext);

        List<String> contextIds = ragContext.stream()
                .map(Document::getId)
                .collect(Collectors.toList());

        return callLlmAndPersist(alert, prompt, ExplanationType.LLM_RAG, contextIds);
    }

    /**
     * Call the LLM with retry support, parse the response, and persist the Explanation entity.
     */
    private Explanation callLlmAndPersist(Alert alert, String prompt,
                                           ExplanationType type,
                                           List<String> ragContextIds) {
        // Per-alert generation log is DEBUG — the batch orchestrator
        // (ExplanationBatchAsyncService) already emits an aggregate progress line
        // every N alerts with count / percentage / throughput / ETA. Keeping this
        // at INFO would spam the log 1-per-alert (100k+ lines on large runs).
        log.debug("Generating {} explanation for alert {}", type, alert.getId());

        long startTime = System.currentTimeMillis();
        String rawResponse;
        Integer promptTokens = null;
        Integer completionTokens = null;

        llmCallCounter.increment();
        try {
            ChatResponse chatResponse = llmCallTimer.recordCallable(
                    () -> callLlmWithRetry(prompt, alert.getId()));

            rawResponse = chatResponse.getResult().getOutput().getText();

            // Extract token usage if available
            if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                var usage = chatResponse.getMetadata().getUsage();
                promptTokens = (int) usage.getPromptTokens();
                completionTokens = (int) usage.getCompletionTokens();
            }
        } catch (Exception e) {
            llmFailureCounter.increment();
            log.error("LLM call failed permanently for alert {} after retries: {}",
                    alert.getId(), e.getMessage());
            // Rethrow so no corrupt explanation is saved — caller handles the error.
            // Swallowing this would create a junk Explanation row that blocks future retries
            // because findAnomalyAlertsWithoutExplanation would treat it as already explained.
            throw new dev.finguard.config.exception.ServiceUnavailableException("Ollama", e.getMessage(), e);
        }

        int latencyMs = (int) (System.currentTimeMillis() - startTime);
        log.info("LLM response received for alert {} in {}ms", alert.getId(), latencyMs);

        // Parse structured response
        ExplanationResponse parsed = parseResponse(rawResponse);

        // Build and persist Explanation entity
        Explanation explanation = new Explanation();
        explanation.setAlert(alert);
        explanation.setExplanationType(type);
        explanation.setRiskSummary(truncate(parsed.riskSummary(), 500));
        explanation.setExplanationText(parsed.explanationText());
        explanation.setConfidenceScore(parsed.confidenceScore());
        explanation.setPromptTokens(promptTokens);
        explanation.setCompletionTokens(completionTokens);
        explanation.setLatencyMs(latencyMs);
        explanation.setFullPrompt(prompt);
        explanation.setRawResponse(rawResponse);

        // Serialize lists to JSON for jsonb columns
        try {
            explanation.setSuspiciousPatterns(
                    objectMapper.writeValueAsString(parsed.suspiciousPatterns()));
            explanation.setRecommendedActions(
                    objectMapper.writeValueAsString(parsed.recommendedActions()));
        } catch (Exception e) {
            log.warn("Failed to serialize patterns/actions for alert {}", alert.getId(), e);
        }

        // Store RAG context document IDs if present
        if (ragContextIds != null && !ragContextIds.isEmpty()) {
            try {
                explanation.setRagContextIds(objectMapper.writeValueAsString(ragContextIds));
            } catch (Exception e) {
                log.warn("Failed to serialize RAG context IDs for alert {}", alert.getId(), e);
            }
        }

        return explanationRepository.save(explanation);
    }

    /**
     * Execute the LLM call with automatic retry on transient failures.
     *
     * <p>Retries up to 3 times with exponential backoff (1s → 2s → 4s).
     * Handles network timeouts, connection resets, and temporary LLM
     * provider outages transparently.</p>
     *
     * @param prompt  the prompt to send to the LLM
     * @param alertId the alert ID (for logging only)
     * @return the LLM chat response
     */
    // Backoff tuning rationale (after observed CloudFront 504 storms with DeepSeek):
    //   delay=2000, multiplier=3.0, maxDelay=30000 →  attempts at 0s, 2s, 6s
    //   plus Spring AI's INTERNAL retry layer (3 attempts with its own ~50s backoff
    //   inside OpenAiChatModel) → total worst-case ~3 minutes per failed call.
    //   On a 504, the backend is overloaded; spreading retries far apart lets
    //   it recover. Rapid retries (the previous 1s-2s-4s) just compound the
    //   overload because all 16 worker threads retry near-simultaneously.
    @Retryable(
            maxAttempts = 3,
            backoff = @Backoff(delay = 2000, multiplier = 3.0, maxDelay = 30000),
            retryFor = Exception.class,
            noRetryFor = IllegalArgumentException.class
    )
    public ChatResponse callLlmWithRetry(String prompt, Long alertId) {
        // Audit B-8: increment per-attempt so retries are visible in metrics.
        // Spring Retry calls this method once per attempt; by placing the
        // increment here we count both the initial call and each retry.
        llmAttemptCounter.increment();
        log.debug("Sending prompt to LLM for alert {} (retryable)", alertId);
        return dynamicChatClientService.getCurrentClient()
                .prompt()
                .user(prompt)
                .call()
                .chatResponse();
    }

    /**
     * Recovery method called after all retry attempts are exhausted.
     *
     * @param e       the final exception
     * @param prompt  the prompt that was being sent
     * @param alertId the alert ID
     * @return never returns — rethrows as RuntimeException
     */
    @Recover
    public ChatResponse recoverLlmCall(Exception e, String prompt, Long alertId) {
        log.error("LLM call exhausted all retries for alert {}: {}", alertId, e.getMessage());
        throw new RuntimeException("LLM service unavailable after 3 attempts: " + e.getMessage(), e);
    }

    /**
     * Parse the LLM's raw text output into a structured {@link ExplanationResponse}.
     *
     * <p>Three-stage recovery strategy for truncated/malformed LLM output:</p>
     * <ol>
     *   <li><b>Strict parse</b> — normal Jackson deserialization</li>
     *   <li><b>JSON repair</b> — closes unclosed brackets/strings when truncation is detected</li>
     *   <li><b>Regex extraction</b> — pulls individual fields via pattern matching as a last resort</li>
     * </ol>
     */
    ExplanationResponse parseResponse(String rawResponse) {
        if (rawResponse == null || rawResponse.isBlank()) {
            return ExplanationResponse.parseError("Empty LLM response");
        }

        String cleaned = stripMarkdownFences(rawResponse);

        // Stage 1: strict parse
        try {
            return objectMapper.readValue(cleaned, ExplanationResponse.class);
        } catch (Exception e) {
            if (!isTruncationError(e)) {
                log.warn("Failed to parse LLM response as JSON: {}", e.getMessage());
                return ExplanationResponse.parseError(rawResponse);
            }
            log.debug("LLM response appears truncated ({}chars), attempting repair", cleaned.length());
        }

        // Stage 2: repair truncated JSON — close open strings then close open brackets
        try {
            String repaired = repairTruncatedJson(cleaned);
            return objectMapper.readValue(repaired, ExplanationResponse.class);
        } catch (Exception e) {
            log.debug("JSON repair failed: {}", e.getMessage());
        }

        // Stage 3: regex-based field extraction
        try {
            ExplanationResponse partial = extractPartialResponse(cleaned, rawResponse);
            log.info("Recovered partial LLM response via regex extraction for truncated output");
            return partial;
        } catch (Exception e) {
            log.debug("Partial extraction failed: {}", e.getMessage());
        }

        log.warn("All JSON parsing strategies exhausted for LLM response ({}chars)", rawResponse.length());
        return ExplanationResponse.parseError(rawResponse);
    }

    private String stripMarkdownFences(String raw) {
        String s = raw.strip();
        if (s.startsWith("```json")) s = s.substring(7);
        else if (s.startsWith("```")) s = s.substring(3);
        if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        return s.strip();
    }

    private boolean isTruncationError(Exception e) {
        String msg = e.getMessage();
        return msg != null && (msg.contains("end-of-input") || msg.contains("Unexpected end")
                || msg.contains("was expecting") || msg.contains("Unexpected character"));
    }

    /**
     * Attempt to repair a truncated JSON string by:
     * <ol>
     *   <li>Trimming back to the last safe position if the input ended inside a string literal</li>
     *   <li>Closing any unclosed bracket pairs using a stack</li>
     * </ol>
     */
    private String repairTruncatedJson(String truncated) {
        String safe = trimToLastSafePosition(truncated);
        return closeOpenBrackets(safe);
    }

    private String trimToLastSafePosition(String json) {
        boolean inString = false;
        boolean escape = false;
        int lastSafePos = 0;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\' && inString) { escape = true; continue; }
            if (c == '"') {
                inString = !inString;
                if (!inString) lastSafePos = i + 1; // just closed a string
                continue;
            }
            if (!inString && (c == '}' || c == ']' || c == ',' || Character.isDigit(c))) {
                lastSafePos = i + 1;
            }
        }

        if (!inString) return json;

        // Ended inside a string — trim back to lastSafePos and clean trailing comma
        String trimmed = json.substring(0, lastSafePos).stripTrailing();
        if (trimmed.endsWith(",")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        return trimmed;
    }

    private String closeOpenBrackets(String json) {
        boolean inString = false;
        boolean escape = false;
        Deque<Character> stack = new ArrayDeque<>();

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\' && inString) { escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') stack.push('}');
            else if (c == '[') stack.push(']');
            else if ((c == '}' || c == ']') && !stack.isEmpty() && stack.peek() == c) stack.pop();
        }

        if (stack.isEmpty()) return json;

        String stripped = json.stripTrailing();
        if (stripped.endsWith(",")) stripped = stripped.substring(0, stripped.length() - 1);
        StringBuilder sb = new StringBuilder(stripped);
        while (!stack.isEmpty()) sb.append(stack.pop());
        return sb.toString();
    }

    private static final Pattern FIELD_PATTERN =
            Pattern.compile("\"(\\w+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern CONFIDENCE_PATTERN =
            Pattern.compile("\"confidenceScore\"\\s*:\\s*([0-9.]+)");

    private ExplanationResponse extractPartialResponse(String cleaned, String rawResponse) {
        String riskSummary = null;
        String explanationText = null;
        Matcher m = FIELD_PATTERN.matcher(cleaned);
        while (m.find()) {
            String key = m.group(1);
            String val = m.group(2).replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n");
            if ("riskSummary".equals(key)) riskSummary = val;
            else if ("explanationText".equals(key)) explanationText = val;
        }

        if (riskSummary == null && explanationText == null) {
            throw new IllegalStateException("No extractable fields found in partial LLM response");
        }

        double confidence = 0.0;
        Matcher cm = CONFIDENCE_PATTERN.matcher(cleaned);
        if (cm.find()) {
            try { confidence = Double.parseDouble(cm.group(1)); } catch (NumberFormatException ignored) {}
        }

        return new ExplanationResponse(
                riskSummary != null ? riskSummary : "Partial LLM response (truncated)",
                explanationText != null ? explanationText : rawResponse,
                List.of(),
                List.of("Manual review recommended — LLM response was incomplete"),
                confidence
        );
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
