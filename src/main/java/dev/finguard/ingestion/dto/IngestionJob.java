package dev.finguard.ingestion.dto;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe state object for a running (or completed) CSV import job.
 *
 * <p>Updated by {@code IngestionService} from the async executor thread and
 * read by the controller thread via the status endpoint. All mutable fields
 * use {@link AtomicLong} or {@code volatile} to ensure visibility across threads.</p>
 */
public class IngestionJob {

    public enum Status { PENDING, RUNNING, DONE, FAILED, CANCELLED }

    /**
     * Coarse phase within a RUNNING job, used by the UI to show phase-specific messaging.
     * {@code null} when the job has not started yet or has finished.
     */
    public enum Phase { IMPORTING, COMPUTING_FEATURES }

    private final String jobId;
    private final Instant startedAt;
    private final AtomicReference<Status> status = new AtomicReference<>(Status.PENDING);
    private final AtomicLong totalRows = new AtomicLong(0);
    private final AtomicLong processedRows = new AtomicLong(0);
    private final AtomicLong importedRows = new AtomicLong(0);
    private final AtomicLong skippedRows = new AtomicLong(0);
    private final AtomicLong featuresComputed = new AtomicLong(0);
    private volatile String errorMessage;
    private volatile Instant completedAt;
    private volatile Phase phase;
    private volatile boolean cancelRequested = false;

    public IngestionJob(String jobId) {
        this.jobId = jobId;
        this.startedAt = Instant.now();
    }

    public void markRunning() {
        status.set(Status.RUNNING);
    }

    /** Called once after countDataRows() to set the denominator for percentage display. */
    public void updateTotal(long total) {
        totalRows.set(total);
    }

    /** Called after each batch flush to push live progress to the status endpoint. */
    public void updateProgress(long processed, long imported, long skipped) {
        processedRows.set(processed);
        importedRows.set(imported);
        skippedRows.set(skipped);
    }

    public void markDone(long imported, long skipped, long total, long features) {
        importedRows.set(imported);
        skippedRows.set(skipped);
        processedRows.set(total);
        featuresComputed.set(features);
        completedAt = Instant.now();
        phase = null;
        status.set(Status.DONE);
    }

    public void markFailed(String error) {
        errorMessage = error;
        completedAt = Instant.now();
        phase = null;
        status.set(Status.FAILED);
    }

    public void markCancelled() {
        completedAt = Instant.now();
        phase = null;
        status.set(Status.CANCELLED);
    }

    /** Signal the import loop to stop after the current batch. */
    public void cancel() {
        cancelRequested = true;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    /**
     * Switch to the feature-computation phase. Resets row counters so the UI
     * can show feature progress (X / total features computed) independently of
     * the import counters.
     */
    public void beginFeaturePhase(long featureTotal) {
        totalRows.set(featureTotal);
        processedRows.set(0);
        phase = Phase.COMPUTING_FEATURES;
    }

    /**
     * Update live feature-computation progress. Called after each SQL batch commit.
     * Also increments {@code featuresComputed} for the final summary.
     */
    public void updateFeaturesProgress(long computed) {
        processedRows.set(computed);
        featuresComputed.set(computed);
    }

    public void setPhase(Phase phase) {
        this.phase = phase;
    }

    // --- Getters ---

    public String getJobId() { return jobId; }
    public Status getStatus() { return status.get(); }
    public long getTotalRows() { return totalRows.get(); }
    public long getProcessedRows() { return processedRows.get(); }
    public long getImportedRows() { return importedRows.get(); }
    public long getSkippedRows() { return skippedRows.get(); }
    public long getFeaturesComputed() { return featuresComputed.get(); }
    public String getErrorMessage() { return errorMessage; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Phase getPhase() { return phase; }

    public long getElapsedSeconds() {
        Instant end = completedAt != null ? completedAt : Instant.now();
        return Duration.between(startedAt, end).toSeconds();
    }

    public double getPercentComplete() {
        long total = totalRows.get();
        return total > 0 ? (processedRows.get() * 100.0 / total) : 0.0;
    }
}
