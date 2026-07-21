-- Rows created by older versions may not have a valid owning document. Remove
-- them before adding the relational ownership constraint.
DELETE FROM vector_store
 WHERE metadata ? 'document_id'
   AND metadata->>'document_id' !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';

DELETE FROM vector_store
 WHERE metadata ? 'document_id'
   AND NOT EXISTS (
       SELECT 1
         FROM document
        WHERE id = (vector_store.metadata->>'document_id')::uuid
   );

ALTER TABLE vector_store
    ADD COLUMN document_id UUID GENERATED ALWAYS AS (
        CASE
            WHEN metadata ? 'document_id'
            THEN (metadata->>'document_id')::uuid
            ELSE NULL
        END
    ) STORED;

ALTER TABLE vector_store
    ADD CONSTRAINT vector_store_document_fk
    FOREIGN KEY (document_id) REFERENCES document(id) ON DELETE CASCADE;

CREATE INDEX vector_store_document_fk_idx ON vector_store (document_id);
