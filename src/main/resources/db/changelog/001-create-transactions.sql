--liquibase formatted sql

--changeset nika.avalishvili:001-create-transactions
CREATE TABLE transactions (
    id                      BIGSERIAL PRIMARY KEY,
    external_id             VARCHAR(100) UNIQUE,
    dataset_source          VARCHAR(50)     NOT NULL,
    timestamp               TIMESTAMP       NOT NULL,
    sender_account          VARCHAR(100)    NOT NULL,
    receiver_account        VARCHAR(100)    NOT NULL,
    transaction_type        VARCHAR(30)     NOT NULL,
    amount                  DECIMAL(18,2)   NOT NULL,
    sender_balance_before   DECIMAL(18,2),
    sender_balance_after    DECIMAL(18,2),
    receiver_balance_before DECIMAL(18,2),
    receiver_balance_after  DECIMAL(18,2),
    is_fraud                BOOLEAN         NOT NULL DEFAULT FALSE,
    is_flagged_fraud        BOOLEAN,
    created_at              TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_transactions_sender    ON transactions (sender_account);
CREATE INDEX idx_transactions_receiver  ON transactions (receiver_account);
CREATE INDEX idx_transactions_timestamp ON transactions (timestamp);
CREATE INDEX idx_transactions_is_fraud  ON transactions (is_fraud);

--rollback DROP TABLE transactions;