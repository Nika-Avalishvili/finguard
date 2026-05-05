--liquibase formatted sql

--changeset nika.avalishvili:010-create-vector-store
-- Spring AI PgVectorStore table for RAG embeddings.
-- Dimensions must match the embedding model: nomic-embed-text outputs 768-dim vectors.
-- HNSW index with cosine distance matches the app config (distance-type: COSINE_DISTANCE).
CREATE TABLE IF NOT EXISTS vector_store (
    id       UUID    DEFAULT gen_random_uuid() PRIMARY KEY,
    content  TEXT,
    metadata JSON,
    embedding VECTOR(768)
);

CREATE INDEX IF NOT EXISTS spring_ai_vector_index
    ON vector_store USING hnsw (embedding vector_cosine_ops);

--rollback DROP INDEX IF EXISTS spring_ai_vector_index;
--rollback DROP TABLE IF EXISTS vector_store;
