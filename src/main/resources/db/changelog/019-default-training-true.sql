-- liquibase formatted sql

-- changeset nika.avalishvili:019-default-training-true
-- comment: Flip the DB default for transactions.is_training_set from FALSE to TRUE.
--          This matches the application-layer change where the ingestion COPY and
--          the JPA entity now set is_training_set = TRUE for every imported row.
--          Why: with the old FALSE default, re-importing the CSV after migration 016
--          had already run meant 100% of freshly imported rows were flagged as test
--          set, so ML training had zero rows to work with. Defaulting to TRUE makes
--          training work out-of-the-box after import; the newest 20% is then carved
--          off as a temporal test set via the /ingestion/finalize-train-test-split
--          endpoint (non-destructive, idempotent, re-runnable after every import).
-- runOnChange: false

ALTER TABLE transactions ALTER COLUMN is_training_set SET DEFAULT TRUE;

-- Recreate the composite index if an operator dropped it during emergency
-- repartitioning (it's IF NOT EXISTS-guarded, so harmless when already present).
CREATE INDEX IF NOT EXISTS idx_transactions_training_split
    ON transactions (is_training_set, is_fraud, dataset_source);
