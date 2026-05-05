package dev.finguard.domain.repository;

import dev.finguard.domain.model.TransactionFeatures;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

import java.util.Optional;

@Repository
public interface TransactionFeaturesRepository extends JpaRepository<TransactionFeatures, Long> {

    Optional<TransactionFeatures> findByTransactionId(Long transactionId);

    boolean existsByTransactionId(Long transactionId);

    /**
     * Batch-load features for a set of transaction IDs in a single query.
     * JOIN FETCH eagerly loads the transaction association so callers can
     * access tf.getTransaction().getId() without triggering lazy-load N+1 queries.
     * Use instead of N individual findByTransactionId calls.
     */
    @Query("SELECT tf FROM TransactionFeatures tf JOIN FETCH tf.transaction WHERE tf.transaction.id IN :ids")
    List<TransactionFeatures> findAllByTransactionIdIn(@Param("ids") Collection<Long> ids);

    /** Single-statement bulk delete — O(1) SQL, no entity loading. */
    @Modifying
    @Transactional
    @Query("DELETE FROM TransactionFeatures tf")
    int bulkDeleteAll();
}
