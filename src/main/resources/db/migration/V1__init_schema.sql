CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE knowledge_base (
    id               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    name             VARCHAR(255) NOT NULL,
    description      TEXT,
    embedding_model  VARCHAR(100) NOT NULL DEFAULT 'text-embedding-3-small',
    chunk_size       INT NOT NULL DEFAULT 512,
    chunk_overlap    INT NOT NULL DEFAULT 64,
    status           VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at       TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE document (
    id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    name              VARCHAR(255) NOT NULL,
    file_type         VARCHAR(20) NOT NULL,
    file_path         VARCHAR(1024),
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    error_message     TEXT,
    chunk_count       INT NOT NULL DEFAULT 0,
    created_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE document_chunk (
    id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    document_id       UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    knowledge_base_id UUID NOT NULL,
    content           TEXT NOT NULL,
    metadata          JSONB,
    embedding         VECTOR(1536),
    chunk_index       INT NOT NULL,
    created_at        TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_document_kb ON document(knowledge_base_id);
CREATE INDEX idx_chunk_kb    ON document_chunk(knowledge_base_id);
CREATE INDEX idx_chunk_doc   ON document_chunk(document_id);

CREATE TABLE conversation (
    id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    messages          JSONB NOT NULL DEFAULT '[]',
    created_at        TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMP NOT NULL DEFAULT NOW()
);
