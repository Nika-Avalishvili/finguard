package dev.finguard.domain.repository;

import dev.finguard.domain.model.RuleThreshold;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * JPA access for {@link RuleThreshold} — one row per detection rule, PK is the
 * rule name (matches {@code RuleMetadata} enum). Reads go through
 * {@code RuleThresholdService} which caches in memory; writes invalidate the
 * cache.
 */
@Repository
public interface RuleThresholdRepository extends JpaRepository<RuleThreshold, String> {
}
