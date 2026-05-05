-- liquibase formatted sql

-- changeset nika.avalishvili:012-add-training-split
-- comment: Add is_training_set flag for proper train/test split (stratified 80/20 by fraud label)
-- runOnChange: false

ALTER TABLE transactions
    ADD COLUMN IF NOT EXISTS is_training_set BOOLEAN NOT NULL DEFAULT FALSE;

WITH numbered AS (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY is_fraud ORDER BY id) AS rn
    FROM transactions
)
UPDATE transactions
SET is_training_set = (numbered.rn % 5 != 0)
FROM numbered
WHERE transactions.id = numbered.id;

CREATE INDEX IF NOT EXISTS idx_transactions_training_split ON transactions (is_training_set, dataset_source, is_fraud);
