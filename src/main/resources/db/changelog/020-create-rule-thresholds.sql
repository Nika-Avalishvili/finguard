--liquibase formatted sql

--changeset nika.avalishvili:020-create-rule-thresholds
--validCheckSum: ANY
--comment: Store user-editable thresholds for the RULES_ONLY detection baseline.
--         Idempotent: CREATE TABLE IF NOT EXISTS + INSERT ... ON CONFLICT DO NOTHING
--         so a re-run against a DB that already has the table (e.g., after a
--         partial TRUNCATE that nuked databasechangelog but kept application
--         tables) succeeds cleanly instead of failing with "relation already exists".
--         Seed values are the thesis-calibrated defaults (see THESIS_EVALUATION_GUIDE §0.3c).

CREATE TABLE IF NOT EXISTS rule_thresholds (
    rule_name      VARCHAR(50)     PRIMARY KEY,
    current_value  NUMERIC(19, 4)  NOT NULL,
    updated_at     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Thesis-calibrated defaults. Each corresponds to an entry in
-- dev.finguard.detection.rule.RuleMetadata (Java registry).
-- ON CONFLICT DO NOTHING preserves any user-edited values across redeploys.
INSERT INTO rule_thresholds (rule_name, current_value) VALUES
    ('LARGE_TRANSACTION',       1000000.00),
    ('NEW_RECEIVER_HIGH_VALUE',  500000.00),
    ('STRUCTURING',               10000.00),
    ('RAPID_VELOCITY',                3.00)
ON CONFLICT (rule_name) DO NOTHING;

--rollback DROP TABLE rule_thresholds;
