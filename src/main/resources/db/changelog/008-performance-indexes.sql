--liquibase formatted sql

--changeset nika.avalishvili:008-performance-indexes
-- Composite index for feature computation velocity/amount/diversity queries.
-- Covers: findBySenderAccountAndTimestampBetween, avgAmountByAccount,
--         stddevAmountByAccount, countDistinctReceiversByAccount.
-- Replaces two independent index lookups (sender_account + timestamp) with a
-- single covering scan - critical for the 6.3M-row PaySim dataset.
CREATE INDEX idx_transactions_sender_ts
    ON transactions (sender_account, timestamp);

-- Composite index for new-receiver detection.
-- Covers: existsPriorTransfer (sender + receiver + timestamp < txTime).
-- Without this, every existsPriorTransfer call scans the sender full history
-- and then filters by receiver - one of the most frequent per-transaction queries.
CREATE INDEX idx_transactions_sender_receiver_ts
    ON transactions (sender_account, receiver_account, timestamp);

--rollback DROP INDEX idx_transactions_sender_ts;
--rollback DROP INDEX idx_transactions_sender_receiver_ts;
