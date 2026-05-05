--liquibase formatted sql

--changeset nika.avalishvili:004-create-explanations
CREATE TABLE explanations (
    id                   BIGSERIAL PRIMARY KEY,
    alert_id             BIGINT       NOT NULL REFERENCES alerts (id),
    explanation_type     VARCHAR(30)  NOT NULL,
    explanation_text     TEXT,
    risk_summary         VARCHAR(500),
    suspicious_patterns  JSONB,
    recommended_actions  JSONB,
    confidence_score     DOUBLE PRECISION,
    hallucination_flags  JSONB,
    hallucination_free   BOOLEAN,
    prompt_tokens        INTEGER,
    completion_tokens    INTEGER,
    latency_ms           INTEGER,
    cakr_completeness    DOUBLE PRECISION,
    cakr_correctness     DOUBLE PRECISION,
    cakr_actionability   DOUBLE PRECISION,
    cakr_regulatory      DOUBLE PRECISION,
    rag_context_ids      JSONB,
    full_prompt          TEXT,
    raw_response         TEXT,
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_explanations_type          ON explanations (explanation_type);
CREATE INDEX idx_explanations_hallucination ON explanations (hallucination_free);

--rollback DROP TABLE explanations;