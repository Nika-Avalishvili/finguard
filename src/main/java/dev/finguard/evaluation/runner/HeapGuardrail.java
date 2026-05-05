package dev.finguard.evaluation.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Heap-pressure circuit breaker for long-running benchmark jobs.
 *
 * <h2>Why this exists</h2>
 * <p>The thesis benchmark runs 5 detection configs sequentially over a 6.3 M
 * row dataset. Memory pressure grows monotonically during each config (alert
 * persistence + Hibernate state + detection features) and only partially
 * releases between configs. Without a guardrail, the first config that
 * exceeds the heap ceiling crashes the JVM with {@link OutOfMemoryError},
 * cascading through Tomcat, JMX, and HTTP threads — taking down the
 * application and losing all partial results.</p>
 *
 * <p>The observed failure mode in the pre-fix run:</p>
 * <pre>
 *   17:14:02  2.02M/6.36M processed, 1.13M alerts, heap ~3.8/4.0 GB
 *   17:16:07  Hibernate Illegal pop() — GC thrashing corrupts query state
 *   17:16:09  "heap 4094 MB → 4091 MB (3 MB freed)"   ← no recoverable headroom
 *   17:16:22  OutOfMemoryError in Tomcat async timeout thread
 * </pre>
 *
 * <h2>What this does</h2>
 * <p>Sampled at well-defined safe points (between benchmark configs, between
 * folds inside a config, after every N detection batches), compares the
 * <b>used</b> heap fraction to two configurable thresholds:</p>
 *
 * <ul>
 *   <li><b>Warning</b> ({@code finguard.heap.warn-threshold}, default 0.75) —
 *       logs a {@code WARN} + suggests a GC. Benchmark continues.</li>
 *   <li><b>Critical</b> ({@code finguard.heap.critical-threshold}, default 0.90) —
 *       triggers a synchronous {@code System.gc()} + short pause. Re-checks.
 *       If still critical, throws {@link HeapCriticalException} — the
 *       benchmark's outer loop catches this, marks the job as
 *       {@code FAILED (heap critical)}, and aborts remaining configs so a
 *       half-full dataset of metrics isn't written.</li>
 * </ul>
 *
 * <p>The critical threshold is deliberately conservative (90 %) so there's
 * enough heap left to <i>persist</i> the failure state and shut down cleanly.
 * Pushing it higher risks the cascade we saw in the original failure.</p>
 *
 * <h2>What this explicitly does NOT do</h2>
 * <ul>
 *   <li>It does <b>not</b> continuously poll — polling a mutable heap-size
 *       counter is itself GC pressure. Checks happen at coarse-grained safe
 *       points controlled by the caller.</li>
 *   <li>It does <b>not</b> interrupt detection mid-batch. {@link HeapCriticalException}
 *       is thrown only between phases, so partial batches are already
 *       committed when the breaker trips.</li>
 *   <li>It does <b>not</b> silence OOM. A real OOM inside a batch still
 *       crashes the JVM — this guardrail is a belt-and-braces early-warning
 *       layer, not a replacement for proper heap sizing.</li>
 * </ul>
 */
@Component
public class HeapGuardrail {

    private static final Logger log = LoggerFactory.getLogger(HeapGuardrail.class);

    @Value("${finguard.heap.warn-threshold:0.75}")
    private double warnThreshold;

    @Value("${finguard.heap.critical-threshold:0.90}")
    private double criticalThreshold;

    /**
     * Inspect current heap pressure and take action if unhealthy.
     *
     * @param context short label describing where the check happened
     *                (e.g. "before-config-ML_ONLY", "after-fold-3")
     * @return snapshot of the heap state at check time, for caller logging
     * @throws HeapCriticalException if the critical threshold is still exceeded
     *         <em>after</em> a forced GC attempt
     */
    public HeapStatus check(String context) {
        HeapStatus status = sample();
        if (status.fraction() < warnThreshold) {
            log.debug("[heap-guard {}] ok — {}", context, status);
            return status;
        }

        if (status.fraction() < criticalThreshold) {
            log.warn("[heap-guard {}] WARN — heap pressure elevated: {}", context, status);
            return status;
        }

        // Critical — try to recover.
        log.warn("[heap-guard {}] CRITICAL — forcing GC: {}", context, status);
        System.gc();
        sleepQuietly(100);
        HeapStatus after = sample();
        if (after.fraction() < criticalThreshold) {
            log.warn("[heap-guard {}] recovered after GC: {} → {}", context, status, after);
            return after;
        }

        String msg = String.format(
                "Heap critical (%.0f%%) and GC could not recover (now %.0f%%). "
                        + "Aborting to prevent OOM cascade. Relaunch with more heap "
                        + "(e.g. -Xmx12g) or reduce max-alerts-per-config.",
                status.fraction() * 100, after.fraction() * 100);
        log.error("[heap-guard {}] {}", context, msg);
        throw new HeapCriticalException(msg);
    }

    /** Non-throwing peek for progress banners / dashboards. */
    public HeapStatus peek() {
        return sample();
    }

    private HeapStatus sample() {
        Runtime rt = Runtime.getRuntime();
        long max   = rt.maxMemory();
        long total = rt.totalMemory();
        long free  = rt.freeMemory();
        long used  = total - free;
        // "committed" (total) can be smaller than "max" before G1 grows the heap;
        // compute fraction against max so we measure true ceiling pressure.
        double fraction = max > 0 ? (double) used / max : 0.0;
        return new HeapStatus(used, max, fraction);
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    /**
     * Immutable heap reading.
     *
     * @param usedBytes     heap currently in use (committed - free)
     * @param maxBytes      ceiling ({@code -Xmx})
     * @param fraction      used / max; 0.0 means no pressure, 1.0 means OOM-imminent
     */
    public record HeapStatus(long usedBytes, long maxBytes, double fraction) {
        @Override
        public String toString() {
            return String.format("%d MB / %d MB (%.1f%%)",
                    usedBytes >> 20, maxBytes >> 20, fraction * 100);
        }
    }

    /**
     * Thrown when heap pressure remains critical after a best-effort GC. The
     * benchmark's outer loop catches this and fails the job cleanly —
     * <b>not</b> a silent error. Callers should treat this as "stop all
     * remaining work and surface the situation to the user".
     */
    public static class HeapCriticalException extends RuntimeException {
        public HeapCriticalException(String message) { super(message); }
    }
}
