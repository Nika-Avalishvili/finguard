package dev.finguard.domain.repository;

import dev.finguard.domain.model.MetricSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link MetricSnapshot} (audit D-3).
 *
 * <p>The dashboard reads the single most-recent row. Older rows are kept for
 * historical trend charts (future feature) and incur no runtime cost — the
 * index on {@code snapshot_at DESC} ensures {@link #findLatest()} is O(1).</p>
 */
@Repository
public interface MetricSnapshotRepository extends JpaRepository<MetricSnapshot, Long> {

    @Query("SELECT m FROM MetricSnapshot m ORDER BY m.snapshotAt DESC LIMIT 1")
    Optional<MetricSnapshot> findLatest();

    /**
     * Ordered snapshots (oldest first) taken after the given cutoff.
     * Used by the history endpoint for trend charts.
     */
    @Query("SELECT m FROM MetricSnapshot m WHERE m.snapshotAt >= :since ORDER BY m.snapshotAt ASC")
    List<MetricSnapshot> findSince(@Param("since") LocalDateTime since);
}
