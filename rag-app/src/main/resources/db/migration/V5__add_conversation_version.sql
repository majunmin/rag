-- Add an optimistic-lock version column to conversation so concurrent writes
-- to the same conversation (e.g. user message + assistant reply append from a
-- different request) cannot silently lose updates via last-writer-wins on the
-- JSONB messages column.
--
-- The JPA entity is annotated with @Version Long version. Hibernate includes
-- "WHERE version = ?" in UPDATE statements; mismatched versions throw
-- ObjectOptimisticLockingFailureException, which the GlobalExceptionHandler
-- maps to HTTP 409 CONFLICT.

ALTER TABLE conversation
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
