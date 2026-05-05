--liquibase formatted sql

--changeset nika.avalishvili:003-create-alerts
CREATE TABLE alerts (
    id               BIGSERIAL PRIMARY KEY,
    transaction_id   BIGINT       NOT NULL REFERENCES transactions (id),
    detection_config VARCHAR(50)  NOT NULL,
    rule_triggered   VARCHAR(500),
    ml_risk_score    DOUBLE PRECISION,
    ml_model_name    VARCHAR(50),
    feature_importances JSONB,
    is_anomaly       BOOLEAN      NOT NULL DEFAULT FALSE,
    status           VARCHAR(20)  NOT NULL DEFAULT 'NEW',
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_alerts_status     ON alerts (status);
CREATE INDEX idx_alerts_config     ON alerts (detection_config);
CREATE INDEX idx_alerts_risk_score ON alerts (ml_risk_score);

--rollback DROP TABLE alerts;