# P0 Reliability and Contract Design

## Goal

Complete the three P0 improvements across `rag0429` and `rag-admin`:

1. Return the exact chunks used by RAG in the chat SSE stream.
2. Make document ingestion publication reliable and vector writes idempotent.
3. Align API-key handling, document failure details, and README configuration
   with the implemented API contract.

Server-side pagination, OIDC/RBAC, object storage, and observability remain
outside this P0 change.

## 1. Exact Retrieval Context

### Backend contract

`ChatService` will return a `ChatStream` value containing:

- `context`: the `SearchResultItem` values produced by the same retrieval call
  used to build the LLM prompt;
- `tokens`: the LLM token `Flux<String>`.

`ChatSseEvents` will emit events in this order:

1. one `context` event whose data is a JSON array of `SearchResultItem`;
2. zero or more `token` events;
3. one `done` event, or one terminal `error` event.

The context event is emitted even when retrieval returns no chunks, using `[]`.
Both single-turn and multi-turn endpoints use the same event contract.

The context payload uses the existing `SearchResultItem` fields:

```json
[
  {
    "content": "retrieved chunk text",
    "metadata": {
      "document_id": "...",
      "document_name": "guide.pdf",
      "chunk_index": 3,
      "rerank_score": 0.91
    }
  }
]
```

### Frontend consumption

The SSE client will expose a discriminated stream event:

```ts
type ChatStreamEvent =
  | { type: 'context'; chunks: SearchResultItem[] }
  | { type: 'token'; text: string }
```

`useStreamingChat` attaches the `context` chunks to the assistant message and
appends only `token` text to its content. `ChatPage` removes the post-answer
`/search` request, so the UI shows exactly what the model received and makes no
duplicate embedding/rerank call.

Malformed `context` JSON terminates the stream as a client parsing error. The
existing structured server `error` event continues to raise
`StreamServerError`.

## 2. Reliable and Idempotent Ingestion

### Transactional outbox

Migration `V6` adds `ingestion_outbox` with one row per uploaded document:

- `id` UUID primary key;
- `document_id` and `knowledge_base_id` UUID values;
- `status` (`PENDING` or `PUBLISHED`);
- `attempt_count`, `next_attempt_at`, `published_at`, `last_error`;
- creation and update timestamps;
- a unique constraint on `document_id`.

`DocumentUploadService.upload()` stores the `Document` and its outbox row in the
same database transaction. The current in-memory application event and
`AFTER_COMMIT` Kafka listener are removed.

A scheduled `IngestionOutboxPublisher` polls ready rows. It locks a bounded
batch with PostgreSQL `FOR UPDATE SKIP LOCKED`, sends each Kafka record with the
document ID as key, waits for broker acknowledgement, and then marks the row
`PUBLISHED`. Failed sends remain `PENDING`, record the error, increment the
attempt count, and receive a capped exponential retry delay.

The publisher provides at-least-once delivery. A crash after Kafka acknowledgement
but before the outbox update may publish the same document again; the consumer
therefore must be idempotent.

### Consumer idempotency

Migration `V6` removes any existing duplicate `(document_id, chunk_index)` rows
and adds a unique expression index for that pair.

The consumer assigns every chunk a deterministic UUID derived from
`documentId + ':' + chunkIndex`. It upserts the complete new set first and only
then deletes trailing old chunks whose index is outside the new set. A failed
embedding attempt therefore preserves the previous complete vector set, while
a successful retry converges without accumulating duplicates.

Migration `V7` adds a generated relational `document_id` column to
`vector_store` and an `ON DELETE CASCADE` foreign key. The processor locks the
document row and performs vector upsert, trailing-chunk pruning, and the `DONE`
transition in one transaction. Concurrent deliveries serialize on that lock;
the later delivery observes `DONE` and performs no embedding. A process crash
rolls back vector and status changes together.

Migration `V8` removes pre-P0 vector rows owned by documents that are not
`DONE`. Those legacy partial rows used random primary keys and would otherwise
conflict with deterministic retry IDs on the V6 document/chunk unique index.
Completed documents and their vectors are preserved.

If the document is already `DONE`, `markProcessing` reports that no work is
needed and the consumer acknowledges the duplicate Kafka message without
parsing or embedding again. Failed attempts still transition the document to
`FAILED`; a Kafka retry may transition it back to `PROCESSING`.

Deleting a document or knowledge base continues to delete associated vectors
and files. Outbox and vector rows are removed through foreign keys with
`ON DELETE CASCADE`, so an unpublished event cannot resurrect deleted data.
If deletion races with a running processor, the document row lock serializes
the operations; deletion either wins before processing starts or cascades the
atomically committed vector rows afterward.

### Configuration

The publisher is configurable through:

- `APP_INGESTION_OUTBOX_DELAY_MS` (default `1000`);
- `APP_INGESTION_OUTBOX_BATCH_SIZE` (default `20`);
- `APP_INGESTION_OUTBOX_SEND_TIMEOUT_MS` (default `10000`).

## 3. Contract and Authentication Alignment

### API key

`rag-admin` reads `VITE_API_KEY` and adds `X-API-Key` to both normal JSON/file
requests and streaming chat requests when the value is non-blank. No header is
sent when the variable is absent.

This is compatibility for an internal deployment, not secret storage: Vite
variables are visible in the browser bundle. The README will state this and
recommend same-origin reverse-proxy authentication for production.

### Document failure details

The backend document-list DTO adds nullable `errorMessage`, matching the
existing frontend type. Successful and in-progress documents return `null`;
failed documents return the persisted ingestion error.

### README

The frontend README will document the actual default API base `/api/v1`, the
optional `VITE_API_KEY`, its browser-visibility limitation, and the new exact
context behavior. Backend architecture documentation will record the `context`
SSE event and outbox flow.

## 4. Error Handling

- Retrieval or prompt construction failures that happen before streaming remain
  regular HTTP errors.
- Mid-stream LLM failures remain structured SSE `error` events.
- Kafka publication failures do not change document status; the outbox retries.
- Parsing, embedding, or vector-store failures set the document to `FAILED` and
  continue through the existing Kafka retry/DLT policy.
- Duplicate messages for a `DONE` document are successful no-ops.

## 5. Testing

Backend tests will cover:

- context is emitted before tokens and serializes the exact retrieval metadata;
- single-turn and multi-turn services preserve retrieved chunks in `ChatStream`;
- upload writes an outbox event instead of publishing an application event;
- outbox success and retry state transitions;
- duplicate delivery of a `DONE` document skips embedding;
- a retry replaces previous vectors and uses deterministic chunk IDs;
- document list includes `errorMessage`.

Frontend tests will cover:

- parsing `context` across arbitrary TCP chunk boundaries;
- attaching context to the assistant message without a second search;
- adding `X-API-Key` to normal and streaming requests;
- omitting the header when `VITE_API_KEY` is absent.

Final verification runs the complete Maven test suite and the frontend test,
lint, and production build commands.
