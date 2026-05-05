-- liquibase formatted sql

-- changeset nika.avalishvili:015-add-fold-column
-- comment: Add nullable fold column to transactions for real k-fold cross-validation
--          (audit A-1). Populated on demand by TransactionRepository.assignFolds(k).
-- runOnChange: false

ALTER TABLE transactions ADD COLUMN IF NOT EXISTS fold INTEGER;

CREATE INDEX IF NOT EXISTS idx_tx_fold ON transactions (fold);
