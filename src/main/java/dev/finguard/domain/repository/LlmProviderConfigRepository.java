package dev.finguard.domain.repository;

import dev.finguard.domain.enums.LlmProvider;
import dev.finguard.domain.model.LlmProviderConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface LlmProviderConfigRepository extends JpaRepository<LlmProviderConfig, Long> {

    Optional<LlmProviderConfig> findByActiveTrue();

    Optional<LlmProviderConfig> findByProvider(LlmProvider provider);

    List<LlmProviderConfig> findAllByOrderByIdAsc();

    /**
     * Deactivate every row so we can activate exactly one.
     *
     * <p>{@code clearAutomatically = true} flushes the JPA first-level cache after the
     * bulk UPDATE so that subsequent reads within the same transaction see the updated
     * {@code is_active = false} state rather than stale cached entities.</p>
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE LlmProviderConfig c SET c.active = false")
    void deactivateAll();
}
