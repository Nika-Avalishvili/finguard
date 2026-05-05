package dev.finguard.ingestion.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.finguard.ingestion.dto.IngestionJob;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory registry of active and recently completed ingestion jobs.
 *
 * <p>Audit B-11: bounded by Caffeine to prevent unbounded growth. Previously a
 * plain {@link java.util.concurrent.ConcurrentHashMap} with no eviction —
 * every import (thousands over a demo run) leaked an {@link IngestionJob}
 * instance until JVM restart.</p>
 *
 * <ul>
 *   <li><b>Size cap:</b> 1&nbsp;000 entries. Above that, the least-recently-used
 *       job is evicted (Caffeine Window-TinyLFU).</li>
 *   <li><b>Time cap:</b> 24&nbsp;h after creation. Status polls after this
 *       window return {@link Optional#empty()}.</li>
 * </ul>
 */
@Component
public class IngestionJobStore {

    private static final long MAX_ENTRIES = 1_000;
    private static final Duration TTL = Duration.ofHours(24);

    private final Cache<String, IngestionJob> jobs = Caffeine.newBuilder()
            .maximumSize(MAX_ENTRIES)
            .expireAfterWrite(TTL)
            .build();

    /**
     * Allocate a new job slot and return its ID.
     * The job starts in {@code PENDING} status.
     */
    public String createJob() {
        String jobId = UUID.randomUUID().toString();
        jobs.put(jobId, new IngestionJob(jobId));
        return jobId;
    }

    public Optional<IngestionJob> find(String jobId) {
        return Optional.ofNullable(jobs.getIfPresent(jobId));
    }
}
