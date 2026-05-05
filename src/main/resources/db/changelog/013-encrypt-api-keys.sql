--liquibase formatted sql

--changeset nika.avalishvili:013-encrypt-api-keys
-- Widen api_key column to TEXT to safely hold AES-256-GCM ciphertext encoded as Base64.
-- Clear any existing plaintext keys — they cannot be decrypted by the new encryptor
-- and must be re-entered via Settings after this migration is applied.
ALTER TABLE llm_provider_config ALTER COLUMN api_key TYPE TEXT;
UPDATE llm_provider_config SET api_key = NULL WHERE api_key IS NOT NULL;

COMMENT ON COLUMN llm_provider_config.api_key
    IS 'AES-256-GCM encrypted API key. Wire format: Base64( IV[12 bytes] || ciphertext+GCM-tag[16 bytes] ). Never store or log plaintext.';
