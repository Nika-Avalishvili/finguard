--liquibase formatted sql

--changeset nika.avalishvili:006-create-experiment-results
CREATE TABLE experiment_results (
    id                  BIGSERIAL PRIMARY KEY,
    experiment_name     VARCHAR(100)    NOT NULL,
    config              VARCHAR(50)     NOT NULL,
    dataset             VARCHAR(50)     NOT NULL,
    fold                INTEGER,
    precision_score     DOUBLE PRECISION,
    recall_score        DOUBLE PRECISION,
    f1_score            DOUBLE PRECISION,
    auc_roc             DOUBLE PRECISION,
    auc_pr              DOUBLE PRECISION,
    false_positive_rate DOUBLE PRECISION,
    avg_cakr_score      DOUBLE PRECISION,
    hallucination_rate  DOUBLE PRECISION,
    avg_latency_ms      DOUBLE PRECISION,
    total_transactions  INTEGER,
    total_alerts        INTEGER,
    run_parameters      JSONB,
    created_at          TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

--rollback DROP TABLE experiment_results;