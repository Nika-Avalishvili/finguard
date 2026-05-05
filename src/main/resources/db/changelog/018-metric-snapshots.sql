-- liquibase formatted sql

-- changeset nika.avalishvili:018-metric-snapshots
-- comment: Audit D-3 — aggregate snapshot cache for dashboard totals.
--          Dashboard previously ran 5+ COUNT(*) queries per page load
--          across multi-million-row tables. A background @Scheduled job now
--          refreshes this table every minute; the dashboard reads the latest
--          row with fallback to live queries if the snapshot is stale.
--          Rows are append-only; a retention job is not needed at thesis
--          scale (1 row/min × 1 year ≈ 525 k rows, ~30 MB).
-- runOnChange: false

CREATE TABLE IF NOT EXISTS metric_snapshots (
    id                  BIGSERIAL     PRIMARY KEY,
    snapshot_at         TIMESTAMP     NOT NULL,
    total_transactions  BIGINT        NOT NULL,
    total_fraud_tx      BIGINT        NOT NULL,
    total_alerts        BIGINT        NOT NULL,
    total_explanations  BIGINT        NOT NULL,
    total_fraud_patterns BIGINT       NOT NULL,
    total_experiments   BIGINT        NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_metric_snapshots_at
    ON metric_snapshots (snapshot_at DESC);
