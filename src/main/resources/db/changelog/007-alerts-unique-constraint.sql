--liquibase formatted sql

--changeset nika.avalishvili:007-alerts-unique-tx-config
-- Prevent duplicate alerts for the same transaction + detection config combination.
-- This protects against race conditions in concurrent batch analysis runs.
ALTER TABLE alerts
    ADD CONSTRAINT uq_alerts_transaction_config
    UNIQUE (transaction_id, detection_config);

--rollback ALTER TABLE alerts DROP CONSTRAINT uq_alerts_transaction_config;
