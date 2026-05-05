--liquibase formatted sql

--changeset nika.avalishvili:002-create-transaction-features
CREATE TABLE transaction_features (
    id                    BIGSERIAL PRIMARY KEY,
    transaction_id        BIGINT          NOT NULL UNIQUE REFERENCES transactions (id),
    amount_zscore         DOUBLE PRECISION,
    tx_velocity_1h        INTEGER,
    tx_velocity_24h       INTEGER,
    avg_amount_7d         DECIMAL(18,2),
    amount_ratio_to_avg   DOUBLE PRECISION,
    balance_change_ratio  DOUBLE PRECISION,
    is_new_receiver       BOOLEAN,
    receiver_diversity_7d INTEGER,
    hour_of_day           SMALLINT,
    day_of_week           SMALLINT,
    is_round_amount       BOOLEAN,
    is_high_risk_type     BOOLEAN,
    feature_vector        JSONB
);

--rollback DROP TABLE transaction_features;