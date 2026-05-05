package dev.finguard.detection.service;

import dev.finguard.domain.model.Alert;
import dev.finguard.domain.repository.AlertRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Handles alert persistence with idempotency under concurrent access.
 *
 * <p>Each save runs in its own {@code REQUIRES_NEW} transaction so that a
 * unique-constraint violation (concurrent thread already inserted the same
 * transaction/config pair) rolls back only the inner transaction, leaving
 * the caller's transaction untouched.</p>
 */
@Service
public class AlertPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(AlertPersistenceService.class);

    private final AlertRepository alertRepository;

    public AlertPersistenceService(AlertRepository alertRepository) {
        this.alertRepository = alertRepository;
    }

    /**
     * Persist an alert in a dedicated transaction.
     *
     * <p>Uses {@code saveAndFlush} so the INSERT is sent to the database
     * immediately, ensuring any unique-constraint violation surfaces as a
     * {@link org.springframework.dao.DataIntegrityViolationException} before
     * this method returns rather than at the caller's commit point.</p>
     *
     * @param alert the alert to persist
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveAlert(Alert alert) {
        alertRepository.saveAndFlush(alert);
    }

    /**
     * Audit B-2: batch-save a list of alerts.
     *
     * <p>Happy path: one INSERT per row committed in a single REQUIRES_NEW
     * transaction via {@link #saveAlertsBatch(List)}. If the batch throws
     * {@link DataIntegrityViolationException} (because a concurrent thread
     * already inserted one of the rows), we fall back to per-row
     * {@link #saveAlert(Alert)} calls — each of which is an independent
     * REQUIRES_NEW transaction, so only the colliding rows are dropped.</p>
     *
     * <p>Intentionally <b>not</b> {@code @Transactional} itself: the fallback
     * path must run outside the failed batch's rollback-marked transaction.</p>
     *
     * @return number of alerts actually persisted
     */
    public int saveAlerts(List<Alert> alerts) {
        if (alerts == null || alerts.isEmpty()) {
            return 0;
        }
        try {
            saveAlertsBatch(alerts);
            return alerts.size();
        } catch (DataIntegrityViolationException batchFail) {
            log.debug("Batch save hit unique-violation ({} rows) — retrying per-row",
                    alerts.size());
        } catch (org.springframework.transaction.TransactionSystemException |
                 org.springframework.orm.jpa.JpaSystemException txFail) {
            // Batch commit failed due to the rollback marker set by the previous
            // violation inside the batch. Per-row recovery still runs below.
            log.debug("Batch commit aborted — retrying per-row: {}", txFail.getMessage());
        }

        int saved = 0;
        List<Long> skipped = new ArrayList<>();
        for (Alert a : alerts) {
            try {
                saveAlert(a);
                saved++;
            } catch (DataIntegrityViolationException dup) {
                skipped.add(a.getTransaction() != null ? a.getTransaction().getId() : -1L);
            }
        }
        if (!skipped.isEmpty()) {
            log.debug("saveAlerts dropped {} duplicate rows (tx_ids={})",
                    skipped.size(), skipped);
        }
        return saved;
    }

    /**
     * Internal: persist a batch inside its own REQUIRES_NEW transaction.
     * Package-visible so Spring's AOP proxy applies {@code @Transactional}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void saveAlertsBatch(List<Alert> alerts) {
        alertRepository.saveAll(alerts);
        alertRepository.flush();
    }
}
