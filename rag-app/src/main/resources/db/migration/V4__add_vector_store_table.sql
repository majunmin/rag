-- Replace the broken document_chunk table with Spring AI's standard vector_store schema.
-- The previous design used a customized document_chunk table with extra NOT NULL columns
-- (document_id, knowledge_base_id, chunk_index) that PgVectorStore cannot populate,
-- causing every ingestion to fail at INSERT time. We now let PgVectorStore manage its
-- own table, and use JSONB metadata (document_id, knowledge_base_id, chunk_index) for
-- filtering and ordering.

CREATE TABLE IF NOT EXISTS vector_store (
    id        UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    content   TEXT,
    metadata  JSONB,
    embedding VECTOR(1024)
);

CREATE INDEX IF NOT EXISTS vector_store_embedding_hnsw_idx
    ON vector_store USING HNSW (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

CREATE INDEX IF NOT EXISTS vector_store_doc_id_idx
    ON vector_store ((metadata->>'document_id'));

CREATE INDEX IF NOT EXISTS vector_store_kb_id_idx
    ON vector_store ((metadata->>'knowledge_base_id'));

DROP TABLE IF EXISTS document_chunk;
