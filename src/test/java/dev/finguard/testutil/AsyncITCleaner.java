package dev.finguard.testutil;

import dev.finguard.detection.service.PipelineStatusTracker;
import dev.finguard.domain.repository.AlertRepository;
import dev.finguard.domain.repository.ExperimentResultRepository;
import dev.finguard.domain.repository.ExplanationRepository;
import dev.finguard.domain.repository.FraudPatternRepository;
import dev.finguard.domain.repository.TransactionFeaturesRepository;
import dev.finguard.domain.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Shared cleanup helper for integration tests that run against the shared
 * {@code finguard_test} PostgreSQL database.
 *
 * <p>Two long-standing sources of IT flakiness this centralises:</p>
 * <ol>
 *   <li><b>Async bleed-over:</b> a prior test's {@code @Async} detection or
 *       explanation job can still be writing rows when the next test starts.
 *       We first wait for {@link PipelineStatusTracker#isAnyRunning()} to
 *       clear, then settle 250&nbsp;ms so that final JDBC commits land.</li>
 *   <li><b>Foreign-key ordering races:</b> {@code alerts.transaction_id} → {@code transactions}.
 *       If an async writer inserts an alert between our two deletes, the
 *       transaction delete blows up. We retry the whole cascade up to 5 times.</li>
 * </ol>
 *
 * <p>Intended usage from an IT's {@code @BeforeEach}:</p>
 * <pre>{@code
 * @Autowired AsyncITCleaner cleaner;
 *
 * @BeforeEach
 * void clean() { cleaner.drainAndClean(); }
 * }</pre>
 */
@Component
public class AsyncITCleaner {

    private static final Logger log = LoggerFactory.getLogger(AsyncITCleaner.class);

    @Autowired private PipelineStatusTracker statusTracker;
    @Autowired private ExperimentResultRepository experimentResultRepository;
    @Autowired private ExplanationRepository explanationRepository;
    @Autowired private AlertRepository alertRepository;
    @Autowired private TransactionFeaturesRepository featuresRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired(required = false) private FraudPatternRepository fraudPatternRepository;

    /** Drain in-flight async work, settle, then cascade-delete in FK-safe order. */
    public void drainAndClean() {
        waitForAsyncToSettle();

        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                experimentResultRepository.deleteAllInBatch();
                explanationRepository.deleteAllInBatch();
                alertRepository.deleteAllInBatch();
                featuresRepository.deleteAllInBatch();
                transactionRepository.deleteAllInBatch();
                if (fraudPatternRepository != null) {
                    fraudPatternRepository.deleteAllInBatch();
                }
                return;
            } catch (DataIntegrityViolationException e) {
                // An async writer committed between our deletes — retry.
                log.debug("Cleanup attempt {} lost FK race; retrying", attempt + 1);
                sleep(100);
            }
        }
        log.warn("Cleanup exhausted retries — subsequent test may see stale rows");
    }

    private void waitForAsyncToSettle() {
        long deadline = System.currentTimeMillis() + 20_000L;
        while (statusTracker.isAnyRunning() && System.currentTimeMillis() < deadline) {
            sleep(50);
        }
        // Phase flips to COMPLETED slightly before the last JDBC commit lands
        // on the @Async thread; this settle delay covers that window.
        sleep(250);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
