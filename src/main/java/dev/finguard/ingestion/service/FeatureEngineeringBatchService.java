package dev.finguard.ingestion.service;

import dev.finguard.domain.model.TransactionFeatures;
import dev.finguard.domain.repository.TransactionFeaturesRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Saves a batch of computed {@link TransactionFeatures} in its own independent transaction.
 *
 * <p>This bean exists so that {@link FeatureEngineeringService#computeAllFeatures(long)}
 * can commit each batch independently — crash-safe, memory-efficient, and lock-friendly.
 * Without it, a single {@code @Transactional} on the outer loop would hold one DB
 * connection for the entire multi-hour computation, preventing PostgreSQL checkpoints
 * and losing all progress on failure.</p>
 *
 * <p>Must be called through the Spring proxy (i.e., from a different bean) for
 * {@code REQUIRES_NEW} to take effect. Calling from within the same class would
 * bypass the proxy and join the caller's transaction instead.</p>
 */
@Service
public class FeatureEngineeringBatchService {

    private final TransactionFeaturesRepository featuresRepository;
    private final EntityManager entityManager;

    public FeatureEngineeringBatchService(TransactionFeaturesRepository featuresRepository,
                                          EntityManager entityManager) {
        this.featuresRepository = featuresRepository;
        this.entityManager = entityManager;
    }

    /**
     * Persist a batch of features in a new transaction that commits immediately.
     *
     * <p>Using {@code REQUIRES_NEW} ensures this transaction commits independently of any
     * caller transaction. {@code flush()} + {@code clear()} after save release the
     * first-level cache so processed entities become eligible for GC.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveBatch(List<TransactionFeatures> batch) {
        featuresRepository.saveAll(batch);
        entityManager.flush();
        entityManager.clear();
    }
}
