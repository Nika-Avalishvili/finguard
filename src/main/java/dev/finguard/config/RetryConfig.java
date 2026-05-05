package dev.finguard.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Enables Spring Retry support across the application.
 *
 * <p>Services annotated with {@code @Retryable} will automatically retry
 * failed operations according to their configured policies. This is
 * particularly important for LLM API calls (Ollama/Claude) which may
 * experience transient network failures or timeouts.</p>
 *
 * <p>Disable in tests that need deterministic single-call semantics by
 * setting {@code finguard.retry.enabled=false}.</p>
 *
 * @see dev.finguard.explanation.llm.LLMExplanationService
 * @see dev.finguard.detection.ml.TribuoModelService
 */
@Configuration
@EnableRetry
@ConditionalOnProperty(name = "finguard.retry.enabled", havingValue = "true", matchIfMissing = true)
public class RetryConfig {
}
