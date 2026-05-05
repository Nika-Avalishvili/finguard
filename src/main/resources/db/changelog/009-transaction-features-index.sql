--liquibase formatted sql

--changeset nika.avalishvili:009-transaction-features-index
-- Explicit index on transaction_features(transaction_id).
-- The UNIQUE constraint on this column creates an implicit unique index, but
-- an explicit named index allows BRIN/partial tuning in future and makes the
-- index visible to query planners in a predictable way.
-- Critical for batch feature lookups in DetectionPipelineService:
--   findAllByTransactionIdIn(batchIds) — called once per 5,000-row batch.
-- Without this, each batch scan hits the 6.3M-row transaction_features table.
CREATE INDEX IF NOT EXISTS idx_transaction_features_transaction_id
    ON transaction_features (transaction_id);

--rollback DROP INDEX IF EXISTS idx_transaction_features_transaction_id;