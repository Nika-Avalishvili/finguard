package dev.finguard.detection.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Tracks the status of asynchronous pipeline operations.
 *
 * <p>The detection pipeline and experiment runner submit long-running
 * jobs that process millions of transactions. This tracker provides
 * a thread-safe way to monitor progress from the REST API or UI.</p>
 *
 * <p>Each job is identified by a unique ID and maintains a {@link PipelineStatus}
 * with phase, progress percentage, counts, and timing information.</p>
 */
@Component
public class PipelineStatusTracker {

    private static final Logger log = LoggerFactory.getLogger(PipelineStatusTracker.class);

    /** Minimum gap between progress-log lines for the same job (avoids log flooding). */
    private static final long MIN_LOG_INTERVAL_MS = 2_000L;

    /** Audit C-5: cap retained job statuses to bound memory over long deployments. */
    private static final long MAX_RETAINED_JOBS = 1_000;
    private static final Duration RETENTION = Duration.ofHours(24);

    /**
     * Bounded status store: Caffeine evicts LRU once {@link #MAX_RETAINED_JOBS}
     * is exceeded, and drops rows older than {@link #RETENTION}. The underlying
     * {@link ConcurrentMap} view is still atomic for {@code computeIfPresent}
     * so the concurrency guarantees from A-4 are preserved.
     */
    private final Cache<String, PipelineStatus> cache = Caffeine.newBuilder()
            .maximumSize(MAX_RETAINED_JOBS)
            .expireAfterWrite(RETENTION)
            .build();
    private final ConcurrentMap<String, PipelineStatus> statuses = cache.asMap();

    /** Last INFO-log timestamp per jobId — used to throttle high-frequency updates. */
    private final Map<String, Long> lastLoggedAtMs = new ConcurrentHashMap<>();

    /**
     * Cooperative cancel flags. The user can call {@link #requestCancel} from a
     * REST endpoint; long-running jobs (k-fold loops, benchmark sweeps,
     * explanation batches) periodically call {@link #isCancelled} between
     * units of work and exit cleanly. We never interrupt threads — we just
     * set a flag and trust callers to check it.
     */
    private final java.util.Set<String> cancelRequested = ConcurrentHashMap.newKeySet();

    /**
     * Mark a job for cooperative cancellation. The job will exit at the next
     * unit-of-work boundary that checks {@link #isCancelled}. Returns
     * {@code true} if the flag was newly set, {@code false} if the job was
     * already cancelled or doesn't exist.
     */
    public boolean requestCancel(String jobId) {
        if (jobId == null) return false;
        boolean isNew = cancelRequested.add(jobId);
        if (isNew) {
            log.warn("[pipeline jobId={}] CANCEL REQUESTED", jobId);
        }
        return isNew;
    }

    /** True if the job has been asked to stop. Long-running loops should poll this. */
    public boolean isCancelled(String jobId) {
        return jobId != null && cancelRequested.contains(jobId);
    }

    /** Clear cancellation state — called from {@link #complete} / {@link #fail}. */
    private void clearCancel(String jobId) {
        if (jobId != null) cancelRequested.remove(jobId);
    }

    /**
     * Start tracking a new pipeline job.
     *
     * @param jobId unique identifier for the job
     * @param phase initial phase description (e.g., "DETECTION", "EVALUATION")
     */
    public void start(String jobId, String phase) {
        Instant now = Instant.now();
        statuses.put(jobId, new PipelineStatus(
                jobId, Phase.RUNNING, phase, 0, 0, 0, 0,
                now, null, null
        ));
        lastLoggedAtMs.put(jobId, System.currentTimeMillis());
        log.info("[pipeline jobId={} phase={}] START", jobId, phase);
    }

    /**
     * Update the progress of an existing job.
     *
     * @param jobId      the job identifier
     * @param phase      current phase description
     * @param processed  number of items processed so far
     * @param total      total items to process
     * @param alerts     number of alerts created so far
     */
    public void updateProgress(String jobId, String phase, long processed, long total, long alerts) {
        int progressPercent = total > 0 ? (int) (100 * processed / total) : 0;

        // Atomic read-modify-write: prevents lost-update races when multiple
        // worker threads (e.g., detection + explanation) update the same jobId.
        PipelineStatus updated = statuses.computeIfPresent(jobId, (id, current) -> new PipelineStatus(
                jobId, Phase.RUNNING, phase, progressPercent, processed, total, alerts,
                current.startedAt(), null, null
        ));

        // Emit a throttled progress log so the operator/user always has a
        // picture of what's running, without flooding the log at 10k updates/sec.
        if (updated != null) {
            maybeLogProgress(jobId, updated, processed, total);
        }
    }

    /**
     * Emit a single-line human-readable progress entry if enough time has
     * elapsed since the last log for this job. Format:
     *
     * <pre>[pipeline jobId=abc123 phase=DETECTION] 42% (42000/100000) alerts=318 elapsed=12s eta=17s</pre>
     */
    private void maybeLogProgress(String jobId, PipelineStatus s, long processed, long total) {
        long now = System.currentTimeMillis();
        Long last = lastLoggedAtMs.get(jobId);
        if (last != null && (now - last) < MIN_LOG_INTERVAL_MS) {
            return;
        }
        lastLoggedAtMs.put(jobId, now);

        long elapsedMs = Duration.between(s.startedAt(), Instant.now()).toMillis();
        String etaStr;
        if (processed > 0 && total > processed) {
            long etaMs = (long) ((double) elapsedMs * (total - processed) / processed);
            etaStr = humanize(etaMs);
        } else {
            etaStr = "?";
        }
        log.info("[pipeline jobId={} phase={}] {}% ({}/{}) alerts={} elapsed={} eta={}",
                jobId, s.currentStep(), s.progressPercent(),
                processed, total, s.alertsCreated(),
                humanize(elapsedMs), etaStr);
    }

    private static String humanize(long ms) {
        if (ms < 1000) return ms + "ms";
        long s = ms / 1000;
        if (s < 60) return s + "s";
        long m = s / 60; s %= 60;
        if (m < 60) return m + "m" + s + "s";
        long h = m / 60; m %= 60;
        return h + "h" + m + "m";
    }

    /**
     * Emit a one-off milestone/phase transition log line.
     *
     * <p>Use this at high-signal points — "starting detection", "finished
     * explanations", "persisting metrics" — so the user can follow what the
     * pipeline is doing without polling the status endpoint.</p>
     */
    public void logMilestone(String jobId, String milestone) {
        PipelineStatus s = statuses.get(jobId);
        if (s == null) {
            log.info("[pipeline jobId={}] {}", jobId, milestone);
            return;
        }
        long elapsedMs = Duration.between(s.startedAt(), Instant.now()).toMillis();
        log.info("[pipeline jobId={} phase={}] {} (elapsed={})",
                jobId, s.currentStep(), milestone, humanize(elapsedMs));
    }

    /**
     * Mark a job as successfully completed.
     *
     * @param jobId  the job identifier
     * @param alerts total alerts created
     */
    public void complete(String jobId, long alerts) {
        Instant now = Instant.now();
        // Atomic: preserve startedAt from prior snapshot if present.
        PipelineStatus s = statuses.compute(jobId, (id, current) -> new PipelineStatus(
                jobId, Phase.COMPLETED, "Done", 100,
                current != null ? current.processed() : 0,
                current != null ? current.total() : 0,
                alerts,
                current != null ? current.startedAt() : now, now, null
        ));
        lastLoggedAtMs.remove(jobId);
        clearCancel(jobId);
        long elapsedMs = Duration.between(s.startedAt(), now).toMillis();
        log.info("[pipeline jobId={}] COMPLETE alerts={} elapsed={}",
                jobId, alerts, humanize(elapsedMs));
    }

    /**
     * Mark a job as cancelled (clean stop after the user requested cancellation).
     * Distinct from {@link #fail} because no error occurred — the operator just
     * asked to stop. UI should show "Cancelled" rather than an error toast.
     */
    public void cancelled(String jobId, long alerts) {
        Instant now = Instant.now();
        PipelineStatus s = statuses.compute(jobId, (id, current) -> new PipelineStatus(
                jobId, Phase.CANCELLED, "Cancelled by user",
                current != null ? current.progressPercent() : 0,
                current != null ? current.processed() : 0,
                current != null ? current.total() : 0,
                alerts,
                current != null ? current.startedAt() : now, now, null
        ));
        lastLoggedAtMs.remove(jobId);
        clearCancel(jobId);
        long elapsedMs = Duration.between(s.startedAt(), now).toMillis();
        log.warn("[pipeline jobId={}] CANCELLED alerts={} elapsed={}",
                jobId, alerts, humanize(elapsedMs));
    }

    /**
     * Mark a job as failed.
     *
     * @param jobId the job identifier
     * @param error error message
     */
    public void fail(String jobId, String error) {
        Instant now = Instant.now();
        PipelineStatus s = statuses.compute(jobId, (id, current) -> new PipelineStatus(
                jobId, Phase.FAILED, "Failed",
                current != null ? current.progressPercent() : 0,
                current != null ? current.processed() : 0,
                current != null ? current.total() : 0,
                current != null ? current.alertsCreated() : 0,
                current != null ? current.startedAt() : now, now, error
        ));
        lastLoggedAtMs.remove(jobId);
        clearCancel(jobId);
        long elapsedMs = Duration.between(s.startedAt(), now).toMillis();
        log.error("[pipeline jobId={}] FAILED elapsed={} error={}",
                jobId, humanize(elapsedMs), error);
    }

    /**
     * Get the current status of a job.
     *
     * @param jobId the job identifier
     * @return the current status, or null if not found
     */
    public PipelineStatus getStatus(String jobId) {
        return statuses.get(jobId);
    }

    /**
     * Check if any pipeline job is currently running.
     */
    public boolean isAnyRunning() {
        return statuses.values().stream()
                .anyMatch(s -> s.phase() == Phase.RUNNING);
    }

    /**
     * Pipeline execution phase.
     */
    public enum Phase {
        RUNNING, COMPLETED, FAILED, CANCELLED
    }

    /**
     * Status snapshot of a pipeline job.
     *
     * @param jobId           unique job identifier
     * @param phase           current execution phase
     * @param currentStep     human-readable step description
     * @param progressPercent 0-100 progress percentage
     * @param processed       items processed so far
     * @param alertsCreated   alerts created so far
     * @param startedAt       job start time
     * @param completedAt     job completion time (null if running)
     * @param error           error message (null if no error)
     */
    /**
     * Status snapshot of a pipeline job.
     *
     * @param jobId           unique job identifier
     * @param phase           current execution phase
     * @param currentStep     human-readable step description
     * @param progressPercent 0-100 progress percentage
     * @param processed       items processed so far
     * @param total           total items to process (0 if not yet known)
     * @param alertsCreated   alerts created so far
     * @param startedAt       job start time
     * @param completedAt     job completion time (null if running)
     * @param error           error message (null if no error)
     */
    public record PipelineStatus(
            String jobId,
            Phase phase,
            String currentStep,
            int progressPercent,
            long processed,
            long total,
            long alertsCreated,
            Instant startedAt,
            Instant completedAt,
            String error
    ) {}
}
