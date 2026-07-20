-- Before deterministic chunk IDs were introduced, a failed ingestion could
-- leave rows with random primary keys. Those rows conflict with a retry on the
-- (document_id, chunk_index) unique index, so discard only incomplete sets.
-- Completed documents remain untouched and are skipped by the consumer.
DELETE FROM vector_store AS vector
 USING document AS owner
 WHERE vector.document_id = owner.id
   AND owner.status <> 'DONE';
