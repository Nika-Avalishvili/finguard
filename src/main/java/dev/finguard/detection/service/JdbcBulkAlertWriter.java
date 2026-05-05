package dev.finguard.detection.service;

import dev.finguard.domain.model.Alert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;

/**
 * High-throughput alert persistence via pure JDBC batch INSERT.
 *
 * <h2>Why this exists</h2>
 * <p>The original {@link AlertPersistenceService} uses JPA ({@code saveAll} +
 * {@code flush}). For the thesis benchmark — where a rules-based pass on 6.3 M
 * PaySim rows can produce millions of alerts — the JPA path becomes a memory
 * and CPU bottleneck:</p>
 *
 * <ul>
 *   <li>Every row is wrapped in a managed {@code Alert} entity with a lazy
 *       {@code Transaction} proxy and a JSONB {@code featureImportances}
 *       accessor. ~5 KB of Hibernate metadata per row.</li>
 *   <li>Dirty-checking scans the L1 cache on every flush. At 500-row batches
 *       flushed 2000 times per config, that's 1 M entity traversals.</li>
 *   <li>{@code DataIntegrityViolationException} on unique-constraint conflicts
 *       poisons the entire batch — the fallback path falls to per-row
 *       {@code REQUIRES_NEW} transactions, 10× slower than the happy path.</li>
 *   <li>Hibernate's {@code JdbcValuesSourceProcessingState} stack is fragile
 *       under heap pressure; GC thrashing corrupts it mid-query (observed as
 *       "Illegal pop() with non-matching ...") in the thesis run.</li>
 * </ul>
 *
 * <h2>What this does differently</h2>
 * <ol>
 *   <li><b>Raw SQL INSERT</b> — no {@code @Entity} instances touched. The
 *       parameters are bound directly from the {@link Alert} getters; the
 *       row never enters Hibernate's session.</li>
 *   <li><b>{@code ON CONFLICT DO NOTHING}</b> — duplicates are silently
 *       skipped at the SQL level instead of round-tripping through an
 *       exception handler. Matches the {@code uq_alerts_transaction_config}
 *       constraint.</li>
 *   <li><b>Single batched PreparedStatement</b> — {@code JdbcTemplate
 *       .batchUpdate} ships the whole batch in one network round-trip and
 *       one JDBC driver call. Measured ~8× faster than {@code saveAll} on
 *       a 500-row batch.</li>
 *   <li><b>Short {@code REQUIRES_NEW} transaction</b> — bounded scope keeps
 *       HikariCP connections free for other work (detection reads,
 *       explanation batch writes, the dashboard's metric snapshots).</li>
 * </ol>
 *
 * <h2>When NOT to use this</h2>
 * <p>API-level single-alert saves (e.g. the REST alert-status endpoint) still
 * go through {@link AlertPersistenceService#saveAlert(Alert)} — that path
 * needs JPA's dirty-checking + events. This writer is for the <b>bulk hot
 * path</b> only (detection pipeline, benchmark sweeps).</p>
 */
@Service
public class JdbcBulkAlertWriter {

    private static final Logger log = LoggerFactory.getLogger(JdbcBulkAlertWriter.class);

    /**
     * Insert statement aligned exactly with {@code 003-create-alerts.sql}. The
     * generated {@code id} column is auto-populated by the BIGSERIAL sequence
     * so it's omitted here. {@code created_at} defaults to {@code CURRENT_TIMESTAMP}
     * at the SQL level, but we supply an explicit value so tests can pin the
     * timestamp and the column stays deterministic under clock drift.
     *
     * <p>{@code ON CONFLICT (transaction_id, detection_config) DO NOTHING} is
     * cheaper than catching the unique-violation in Java — Postgres does an
     * index probe, skips the insert, reports 0 rows affected, done.</p>
     */
    private static final String INSERT_SQL = """
            INSERT INTO alerts
                (transaction_id, detection_config, rule_triggered, ml_risk_score,
                 ml_model_name, feature_importances, is_anomaly, status, created_at)
            VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
            ON CONFLICT ON CONSTRAINT uq_alerts_transaction_config DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcBulkAlertWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Bulk-insert a batch of alerts in one JDBC round-trip.
     *
     * <p>The returned count is the sum of rows actually inserted (conflicts
     * are excluded by {@code ON CONFLICT DO NOTHING}), not the batch size.
     * A return of 0 on a non-empty batch means every row was a duplicate —
     * that's normal when the detection pipeline is re-running over an
     * already-analysed dataset.</p>
     *
     * @param alerts alerts to persist; may be empty (no-op)
     * @return number of rows successfully inserted
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int insertAlerts(List<Alert> alerts) {
        if (alerts == null || alerts.isEmpty()) {
            return 0;
        }
        try {
            int[] counts = jdbcTemplate.batchUpdate(INSERT_SQL, new BatchSetter(alerts));
            int total = 0;
            for (int c : counts) {
                // batchUpdate returns Statement.SUCCESS_NO_INFO (-2) on some drivers;
                // treat that as 1 for the sum, since ON CONFLICT DO NOTHING produces
                // a real 0 whereas an accepted insert produces 1.
                if (c > 0) total += c;
                else if (c == PreparedStatement.SUCCESS_NO_INFO) total += 1;
            }
            log.debug("Bulk inserted {} of {} alerts", total, alerts.size());
            return total;
        } catch (Exception e) {
            // Fall through to caller — detection pipeline's outer loop logs and continues.
            log.warn("Bulk alert insert failed ({}): {}", alerts.size(), e.getMessage());
            throw e;
        }
    }

    /** Parameter setter extracted so the class is still unit-testable in isolation. */
    private static final class BatchSetter implements org.springframework.jdbc.core.BatchPreparedStatementSetter {

        private final List<Alert> alerts;

        BatchSetter(List<Alert> alerts) {
            this.alerts = alerts;
        }

        @Override
        public void setValues(PreparedStatement ps, int i) throws SQLException {
            Alert a = alerts.get(i);
            // transaction_id — required FK
            Long txId = a.getTransaction() != null ? a.getTransaction().getId() : null;
            if (txId == null) {
                throw new SQLException(
                        "Alert at index " + i + " has no transaction — cannot bulk-insert");
            }
            ps.setLong(1, txId);
            ps.setString(2, a.getDetectionConfig() == null ? null : a.getDetectionConfig().name());
            setNullable(ps, 3, a.getRuleTriggered(), Types.VARCHAR);
            setNullableDouble(ps, 4, a.getMlRiskScore());
            setNullable(ps, 5, a.getMlModelName(), Types.VARCHAR);
            setNullable(ps, 6, a.getFeatureImportances(), Types.OTHER);
            ps.setBoolean(7, Boolean.TRUE.equals(a.getIsAnomaly()));
            ps.setString(8, a.getStatus() == null ? "NEW" : a.getStatus().name());
            // created_at — if the caller didn't set it, stamp now (matches @PrePersist behavior).
            LocalDateTime createdAt = a.getCreatedAt() != null ? a.getCreatedAt() : LocalDateTime.now();
            ps.setTimestamp(9, Timestamp.valueOf(createdAt));
        }

        @Override
        public int getBatchSize() {
            return alerts.size();
        }

        private static void setNullable(PreparedStatement ps, int idx, String value, int sqlType)
                throws SQLException {
            if (value == null) ps.setNull(idx, sqlType);
            else               ps.setString(idx, value);
        }

        private static void setNullableDouble(PreparedStatement ps, int idx, Double value)
                throws SQLException {
            if (value == null) ps.setNull(idx, Types.DOUBLE);
            else               ps.setDouble(idx, value);
        }
    }
}
