package dev.finguard.explanation.rag;

import dev.finguard.domain.model.Alert;
import dev.finguard.domain.model.Transaction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Retrieves relevant fraud pattern context from the pgvector knowledge base.
 *
 * <p>Builds a semantic search query from the alert's detection signals
 * and transaction metadata, then retrieves the top-N most similar
 * fraud pattern documents from the vector store.</p>
 *
 * <p>The retrieved documents are used as RAG context in the LLM prompt
 * to ground explanations in recognized fraud typologies.</p>
 */
@Service
public class RAGContextService {

    private static final Logger log = LoggerFactory.getLogger(RAGContextService.class);

    private final VectorStore vectorStore;
    private final Counter ragSearchCounter;
    private final Timer ragSearchTimer;

    @Value("${finguard.explanation.max-rag-results:5}")
    private int maxResults;

    public RAGContextService(VectorStore vectorStore,
                              Counter ragSearchCounter,
                              Timer ragSearchTimer) {
        this.vectorStore = vectorStore;
        this.ragSearchCounter = ragSearchCounter;
        this.ragSearchTimer = ragSearchTimer;
    }

    /**
     * Retrieve relevant fraud pattern documents for an alert.
     *
     * <p>The search query is constructed from:
     * <ul>
     *   <li>Triggered rule names (e.g., "LARGE_TRANSACTION", "STRUCTURING")</li>
     *   <li>Transaction type (e.g., "TRANSFER", "CASH_OUT")</li>
     *   <li>ML model name if available</li>
     * </ul>
     *
     * <p>Retries up to 2 times on transient vector store failures (e.g.,
     * pgvector connection timeouts) before falling back to an empty context list.</p>
     *
     * @param alert the alert to find context for
     * @param tx    the flagged transaction
     * @return list of relevant fraud pattern documents (may be empty)
     */
    // Audit B-6: cache key must align with the search-query inputs
    // (ruleTriggered + transactionType). Keying on alert.id defeated the
    // pre-warm strategy in ExplanationBatchAsyncService (which warms by
    // distinct (rule, type) pairs) — every alert became a cache miss.
    // Null-safe via `?:` so alerts without a rule still cache coherently.
    @Cacheable(value = "rag-context",
              key = "(#alert.ruleTriggered ?: '') + '|' + (#tx.transactionType != null ? #tx.transactionType.name() : '')")
    @Retryable(
            maxAttempts = 2,
            backoff = @Backoff(delay = 500, multiplier = 2.0),
            retryFor = Exception.class,
            noRetryFor = IllegalArgumentException.class
    )
    public List<Document> retrieveContext(Alert alert, Transaction tx) {
        String query = buildSearchQuery(alert, tx);
        log.debug("RAG search query: {}", query);

        ragSearchCounter.increment();

        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(maxResults)
                .build();

        List<Document> results = ragSearchTimer.record(() ->
                vectorStore.similaritySearch(request));
        // Per-alert log is DEBUG to avoid hundreds of thousands of spam lines on
        // large batch runs. The surrounding explanation batch logs an aggregate
        // progress line every N alerts; per-alert detail is still available at
        // DEBUG for diagnosing a specific misfire.
        log.debug("RAG retrieved {} documents for alert {}", results.size(), alert.getId());
        return results;
    }

    /**
     * Recovery method when RAG retrieval fails after all retries.
     * Returns an empty context list so the explanation pipeline can continue
     * without RAG augmentation (graceful degradation).
     */
    @Recover
    public List<Document> recoverContext(Exception e, Alert alert, Transaction tx) {
        log.warn("RAG search failed after retries for alert {}: {}. Continuing without RAG context.",
                alert.getId(), e.getMessage());
        return List.of();
    }

    private String buildSearchQuery(Alert alert, Transaction tx) {
        StringBuilder query = new StringBuilder();

        // Include triggered rules as primary search terms
        if (alert.getRuleTriggered() != null && !alert.getRuleTriggered().isBlank()) {
            // Extract rule names from "RULE_NAME: reason; RULE_NAME2: reason2"
            String[] parts = alert.getRuleTriggered().split(";");
            for (String part : parts) {
                String ruleName = part.split(":")[0].trim()
                        .replace("_", " ")
                        .toLowerCase();
                query.append(ruleName).append(" ");
            }
        }

        // Include transaction type
        if (tx.getTransactionType() != null) {
            query.append(tx.getTransactionType().name().toLowerCase().replace("_", " ")).append(" ");
        }

        // Add generic fraud context terms
        query.append("fraud anomaly suspicious transaction");

        return query.toString().trim();
    }
}
