--liquibase formatted sql

--changeset nika.avalishvili:005-create-fraud-patterns
CREATE TABLE fraud_patterns (
    id                   BIGSERIAL PRIMARY KEY,
    pattern_type         VARCHAR(50)  NOT NULL,
    title                VARCHAR(200) NOT NULL,
    description          TEXT         NOT NULL,
    indicators           JSONB,
    regulatory_reference VARCHAR(200),
    example_scenario     TEXT,
    source               VARCHAR(100)
);

--rollback DROP TABLE fraud_patterns;