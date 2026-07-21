-- Change embedding dimension from 1536 (OpenAI text-embedding-3-small) to 1024 (Bailian text-embedding-v3)
-- Drop the HNSW index first since we're changing the column type
DROP INDEX IF EXISTS idx_chunk_embedding_hnsw;

-- Recreate the embedding column with new dimensions
ALTER TABLE document_chunk DROP COLUMN IF EXISTS embedding;
ALTER TABLE document_chunk ADD COLUMN embedding VECTOR(1024);

-- Recreate HNSW index
CREATE INDEX idx_chunk_embedding_hnsw
    ON document_chunk USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- Update default embedding model for knowledge_base
ALTER TABLE knowledge_base ALTER COLUMN embedding_model SET DEFAULT 'text-embedding-v3';
