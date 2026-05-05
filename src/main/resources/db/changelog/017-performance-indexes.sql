-- liquibase formatted sql

-- changeset nika.avalishvili:017-performance-indexes
-- comment: Audit B-4 — align index column order with actual query filters.
--          Replaces idx_transactions_training_split (which was
--          (is_training_set, dataset_source, is_fraud)) with
--          (is_training_set, is_fraud, dataset_source) so queries that filter
--          on is_fraud can use the index prefix directly instead of having
--          to scan all rows of a given dataset_source. Also adds a partial
--          index on alerts for the hot anomaly filter, and a composite index
--          on explanations(explanation_type, alert_id) used by CAKR queries.
-- runOnChange: false

DROP INDEX IF EXISTS idx_transactions_training_split;
CREATE INDEX IF NOT EXISTS idx_transactions_training_split
    ON transactions (is_training_set, is_fraud, dataset_source);

-- Partial index: covers >90% of dashboard/evaluation queries where
-- is_anomaly = true is always in the WHERE clause.
CREATE INDEX IF NOT EXISTS idx_alerts_config_anomaly_true
    ON alerts (detection_config)
    WHERE is_anomaly = true;

-- Used by CAKR avg/hallucination count queries that filter on
-- (explanation_type IN (..)) AND (alert_id IN (..)).
CREATE INDEX IF NOT EXISTS idx_explanations_type_alert
    ON explanations (explanation_type, alert_id);

-- Used by the bulk feature window aggregation (sender_account, timestamp).
-- The existing idx_transactions_sender covers the equality predicate but
-- leaves timestamp unordered; this composite lets window functions stream
-- results in order without a sort step.
CREATE INDEX IF NOT EXISTS idx_tx_sender_ts
    ON transactions (sender_account, "timestamp" DESC);
