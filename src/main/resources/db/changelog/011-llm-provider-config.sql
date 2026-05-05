--liquibase formatted sql

--changeset nika.avalishvili:011-create-llm-provider-config
CREATE TABLE llm_provider_config (
    id          BIGSERIAL PRIMARY KEY,
    provider    VARCHAR(20)  NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    api_key     VARCHAR(500),
    base_url    VARCHAR(200),
    model_name  VARCHAR(100) NOT NULL,
    is_active   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- Only one row can be active at a time
CREATE UNIQUE INDEX idx_llm_provider_config_active ON llm_provider_config (is_active) WHERE is_active = TRUE;

-- Seed the default Ollama config as active
INSERT INTO llm_provider_config (provider, display_name, api_key, base_url, model_name, is_active)
VALUES ('OLLAMA', 'Local Ollama', NULL, 'http://localhost:11434', 'llama3.2', TRUE);
