package dev.finguard.ingestion.service;

import dev.finguard.ingestion.dto.IngestionJob;
import dev.finguard.ingestion.dto.IngestionJob.Phase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Runs feature computation in the background via Spring's async executor.
 *
 * <p>Exists as a separate bean so that calling
 * {@link FeatureEngineeringService#computeAllFeatures()} goes through the
 * Spring proxy — ensuring {@code @Transactional} is applied correctly.</p>
 *
 * <p>All {@code runAsync} overloads accept an optional {@code onComplete} callback
 * invoked in a {@code finally} block — callers can release concurrency guards without
 * polling.</p>
 */
@Service
public class FeatureEngineeringAsyncService {

    private static final Logger log = LoggerFactory.getLogger(FeatureEngineeringAsyncService.class);

    private final FeatureEngineeringBulkService featureEngineeringBulkService;
    private final FeatureEngineeringService featureEngineeringService;
    private final IngestionJobStore jobStore;

    public FeatureEngineeringAsyncService(FeatureEngineeringBulkService featureEngineeringBulkService,
                                           FeatureEngineeringService featureEngineeringService,
                                           IngestionJobStore jobStore) {
        this.featureEngineeringBulkService = featureEngineeringBulkService;
        this.featureEngineeringService = featureEngineeringService;
        this.jobStore = jobStore;
    }

    /**
     * Compute features for all unprocessed transactions asynchronously using bulk SQL.
     */
    @Async
    public void runAsync(String jobId) {
        runAsync(jobId, () -> {});
    }

    /**
     * Compute features for all unprocessed transactions asynchronously using bulk SQL.
     * Invokes {@code onComplete} when finished (success or failure).
     */
    @Async
    public void runAsync(String jobId, Runnable onComplete) {
        IngestionJob job = jobStore.find(jobId)
                .orElseThrow(() -> new IllegalStateException("Job not found: " + jobId));

        log.info("[job={}] Async bulk feature computation started", jobId);
        job.setPhase(Phase.COMPUTING_FEATURES);
        job.markRunning();

        try {
            long computed = featureEngineeringBulkService.computeAllBulk(job);
            job.markDone(0, 0, computed, computed);
            log.info("[job={}] Bulk feature computation complete — {} features in {}s",
                    jobId, computed, job.getElapsedSeconds());
        } catch (Exception e) {
            log.error("[job={}] Bulk feature computation failed: {}", jobId, e.getMessage(), e);
            job.markFailed(e.getMessage());
        } finally {
            onComplete.run();
        }
    }

    /**
     * Compute features for up to {@code limit} transactions using the per-transaction approach.
     */
    @Async
    public void runAsync(String jobId, long limit) {
        runAsync(jobId, limit, () -> {});
    }

    /**
     * Compute features for up to {@code limit} transactions using the per-transaction approach.
     * Invokes {@code onComplete} when finished (success or failure).
     */
    @Async
    public void runAsync(String jobId, long limit, Runnable onComplete) {
        IngestionJob job = jobStore.find(jobId)
                .orElseThrow(() -> new IllegalStateException("Job not found: " + jobId));

        log.info("[job={}] Async per-transaction feature computation started (limit={})", jobId, limit);
        job.markRunning();

        try {
            long computed = featureEngineeringService.computeAllFeatures(limit);
            job.markDone(0, 0, computed, computed);
            log.info("[job={}] Feature computation complete — {} features computed in {}s",
                    jobId, computed, job.getElapsedSeconds());
        } catch (Exception e) {
            log.error("[job={}] Feature computation failed: {}", jobId, e.getMessage(), e);
            job.markFailed(e.getMessage());
        } finally {
            onComplete.run();
        }
    }
}
