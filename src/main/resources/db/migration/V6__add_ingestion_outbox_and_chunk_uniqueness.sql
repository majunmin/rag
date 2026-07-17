CREATE TABLE ingestion_outbox (
    id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    document_id       UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempt_count     INT NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at      TIMESTAMPTZ,
    last_error        TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ingestion_outbox_document_unique UNIQUE (document_id),
    CONSTRAINT ingestion_outbox_status_check CHECK (status IN ('PENDING', 'PUBLISHED'))
);

CREATE INDEX ingestion_outbox_ready_idx
    ON ingestion_outbox (next_attempt_at, created_at)
    WHERE status = 'PENDING';

-- Keep the oldest copy if an earlier retry already created duplicate chunks.
DELETE FROM vector_store
 WHERE id IN (
    SELECT id
      FROM (
        SELECT id,
               ROW_NUMBER() OVER (
                   PARTITION BY metadata->>'document_id', metadata->>'chunk_index'
                   ORDER BY id
               ) AS duplicate_number
          FROM vector_store
         WHERE metadata ? 'document_id'
           AND metadata ? 'chunk_index'
      ) duplicates
     WHERE duplicate_number > 1
 );

CREATE UNIQUE INDEX vector_store_document_chunk_unique_idx
    ON vector_store ((metadata->>'document_id'), (metadata->>'chunk_index'))
    WHERE metadata ? 'document_id'
      AND metadata ? 'chunk_index';
