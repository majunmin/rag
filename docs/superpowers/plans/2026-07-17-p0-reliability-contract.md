# P0 Reliability and Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make chat citations exact, ingestion publication reliable and vector writes idempotent, and align the admin API-key/document contracts with the backend.

**Architecture:** Chat returns a `ChatStream` that carries the retrieval results beside the token flux, and the SSE adapter serializes those exact results before tokens. Upload persists a PostgreSQL outbox row in the document transaction; a scheduled publisher delivers it at least once. The consumer serializes duplicate work with a document row lock and atomically commits deterministic vector upserts, trailing-chunk pruning, and `DONE`; a vector-to-document foreign key prevents orphan rows. The React client consumes typed SSE events and applies one shared optional API-key header policy.

**Tech Stack:** Java 21, Spring Boot 3.3, Spring AI 1.1.5, Spring Kafka, PostgreSQL/Flyway, JUnit 5/Mockito/Reactor Test, React 19, TypeScript 6, Vitest.

---

## File Map

Backend additions:

- `src/main/java/com/majm/rag/chat/ChatStream.java`: exact retrieved context plus token flux.
- `src/main/java/com/majm/rag/ingestion/IngestionOutboxEvent.java`: outbox row projection.
- `src/main/java/com/majm/rag/ingestion/IngestionOutboxRepository.java`: transactional SQL operations for enqueue, lock, publish, and retry.
- `src/main/java/com/majm/rag/ingestion/IngestionOutboxPublisher.java`: scheduled Kafka relay.
- `src/main/resources/db/migration/V6__add_ingestion_outbox_and_chunk_uniqueness.sql`: outbox schema and chunk uniqueness.
- `src/main/resources/db/migration/V7__enforce_vector_document_integrity.sql`: generated document ownership column and cascading foreign key.
- `src/main/java/com/majm/rag/ingestion/IngestionProcessor.java`: row-locked transactional vector processing.
- `src/test/java/com/majm/rag/ingestion/IngestionOutboxPublisherTest.java`: publisher state-transition tests.
- `src/test/java/com/majm/rag/ingestion/IngestionConsumerTest.java`: duplicate-delivery and deterministic-ID tests.

Backend modifications:

- `ChatService.java`, `ChatSseEvents.java`, `ChatController.java` and their tests.
- `DocumentUploadService.java`, `IngestionStatusService.java`, `IngestionConsumer.java` and upload tests.
- `DocumentService.java` and `DocumentServiceTest.java` for `errorMessage` contract coverage.
- `application.yml`, architecture docs, and README.
- Remove `IngestionRequestedEvent.java` and `IngestionEventListener.java` after outbox tests pass.

Frontend modifications:

- `../rag-admin/src/constants.ts`: optional API key.
- `../rag-admin/src/api/client.ts`: shared headers and typed SSE parsing.
- `../rag-admin/src/types/api.ts`: stream event union.
- `../rag-admin/src/hooks/useStreamingChat.ts`: attach backend context.
- `../rag-admin/src/pages/chat/ChatPage.tsx`: remove duplicate `/search` call.
- `../rag-admin/src/test/api.client.test.ts`: context and API-key tests.
- `../rag-admin/README.md`: actual environment contract.

### Task 1: Backend Exact Context Stream

**Files:**
- Create: `src/main/java/com/majm/rag/chat/ChatStream.java`
- Modify: `src/main/java/com/majm/rag/chat/ChatService.java`
- Modify: `src/main/java/com/majm/rag/chat/ChatSseEvents.java`
- Modify: `src/main/java/com/majm/rag/chat/ChatController.java`
- Test: `src/test/java/com/majm/rag/chat/ChatServiceTest.java`
- Test: `src/test/java/com/majm/rag/chat/ChatSseEventsTest.java`

- [ ] **Step 1: Write failing service and SSE tests**

Add a service assertion that the returned stream exposes the same content and
metadata returned by `RetrievalService`. Add an SSE assertion that `context`
is the first event, followed by tokens and `done`:

```java
Document retrieved = new Document("chunk-id", "chunk text",
    Map.of("document_name", "guide.pdf", "chunk_index", 2));
when(retrievalService.search(any(), any(), anyInt(), any(RewriteResult.class)))
    .thenReturn(List.of(retrieved));

ChatStream stream = chatService.chat(new ChatRequest(kbId, "question", 5));
assertThat(stream.context()).singleElement().satisfies(item -> {
    assertThat(item.content()).isEqualTo("chunk text");
    assertThat(item.metadata()).containsEntry("chunk_index", 2);
});
```

```java
ChatStream stream = new ChatStream(
    List.of(new SearchResultItem("chunk text", Map.of("chunk_index", 2))),
    Flux.just("hello"));
StepVerifier.create(ChatSseEvents.wrap(stream))
    .assertNext(e -> {
        assertThat(e.event()).isEqualTo("context");
        assertThat(e.data()).contains("chunk text");
    })
    .assertNext(e -> assertThat(e.event()).isEqualTo("token"))
    .assertNext(e -> assertThat(e.event()).isEqualTo("done"))
    .verifyComplete();
```

- [ ] **Step 2: Run focused tests and verify RED**

Run: `./mvnw -q -Dtest=ChatServiceTest,ChatSseEventsTest test`

Expected: compilation failure because `ChatStream` does not exist and
`ChatService.chat` still returns `Flux<String>`.

- [ ] **Step 3: Implement `ChatStream` and preserve retrieval results**

Create:

```java
public record ChatStream(List<SearchResultItem> context, Flux<String> tokens) {
    public ChatStream {
        context = context == null ? List.of() : List.copyOf(context);
        Objects.requireNonNull(tokens, "tokens is required");
    }
}
```

Replace `buildContext` with a small result carrying both prompt text and items.
Return `ChatStream` from single-turn and multi-turn methods. Change
`ChatSseEvents.wrap` to serialize one `context` event with its configured
`ObjectMapper`, concatenate token events and `done`, and preserve the existing
terminal error mapping. Update controller signatures and OpenAPI summaries to
include `context / token / done / error`.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run: `./mvnw -q -Dtest=ChatServiceTest,ChatSseEventsTest test`

Expected: all chat service and SSE tests pass.

- [ ] **Step 5: Commit backend context contract**

```bash
git add -A
git commit -m "feat(chat): stream exact retrieval context"
```

### Task 2: Frontend Typed Context Consumption and API-Key Headers

**Files:**
- Modify: `../rag-admin/src/constants.ts`
- Modify: `../rag-admin/src/types/api.ts`
- Modify: `../rag-admin/src/api/client.ts`
- Modify: `../rag-admin/src/hooks/useStreamingChat.ts`
- Modify: `../rag-admin/src/pages/chat/ChatPage.tsx`
- Test: `../rag-admin/src/test/api.client.test.ts`

- [ ] **Step 1: Write failing context and API-key tests**

Update the collector to read `ChatStreamEvent[]`, then add:

```ts
it('emits exact context before token events across chunks', async () => {
  mockFetch.mockResolvedValueOnce(makeStreamResponse([
    'event:context\ndata:[{"content":"chunk","metadata":{"chunk_index":2}}]',
    '\n\nevent:token\ndata:hello\n\nevent:done\ndata:\n\n',
  ]))
  expect(await collect(await streamRequest('/chat', {}))).toEqual([
    { type: 'context', chunks: [{ content: 'chunk', metadata: { chunk_index: 2 } }] },
    { type: 'token', text: 'hello' },
  ])
})
```

Use `vi.stubEnv('VITE_API_KEY', 'internal-key')`, reset modules, import the
client, and assert both `request` and `streamRequest` send
`X-API-Key: internal-key`. Add the inverse assertion with an empty value.

- [ ] **Step 2: Run frontend client tests and verify RED**

Run from `../rag-admin`: `pnpm test -- --run src/test/api.client.test.ts`

Expected: context is treated as token text and no `X-API-Key` header exists.

- [ ] **Step 3: Implement typed events and shared headers**

Add:

```ts
export type ChatStreamEvent =
  | { type: 'context'; chunks: SearchResultItem[] }
  | { type: 'token'; text: string }
```

Add `API_KEY = import.meta.env.VITE_API_KEY?.trim()` and one `buildHeaders`
helper used by both request paths. Parse `context` JSON into the union, emit
token events as `{ type: 'token', text }`, and keep `done` non-emitting and
`error` exceptional.

In `useStreamingChat`, append only token text and assign context chunks to the
same assistant message. Remove `attachChunks` from the hook. In `ChatPage`,
remove `searchKb`, the post-stream search, and the now-unused import.

- [ ] **Step 4: Run frontend client tests and verify GREEN**

Run: `pnpm test -- --run src/test/api.client.test.ts`

Expected: all SSE parsing and header tests pass.

- [ ] **Step 5: Commit frontend stream and auth contract**

```bash
git add -A
git commit -m "feat(chat): consume exact context stream"
```

### Task 3: Transactional Ingestion Outbox

**Files:**
- Create: `src/main/resources/db/migration/V6__add_ingestion_outbox_and_chunk_uniqueness.sql`
- Create: `src/main/java/com/majm/rag/ingestion/IngestionOutboxEvent.java`
- Create: `src/main/java/com/majm/rag/ingestion/IngestionOutboxRepository.java`
- Modify: `src/main/java/com/majm/rag/ingestion/DocumentUploadService.java`
- Delete: `src/main/java/com/majm/rag/ingestion/IngestionRequestedEvent.java`
- Delete: `src/main/java/com/majm/rag/ingestion/IngestionEventListener.java`
- Test: `src/test/java/com/majm/rag/ingestion/DocumentUploadServiceTest.java`

- [ ] **Step 1: Replace the upload event test with a failing outbox test**

Mock `IngestionOutboxRepository`, call upload, and verify:

```java
verify(outboxRepository).enqueue(savedDoc.getId(), kbId);
verifyNoInteractions(eventPublisher);
```

Remove the event-publisher mock after the test proves the constructor mismatch.

- [ ] **Step 2: Run upload tests and verify RED**

Run: `./mvnw -q -Dtest=DocumentUploadServiceTest test`

Expected: compilation failure because `IngestionOutboxRepository` does not
exist and upload has no outbox dependency.

- [ ] **Step 3: Add migration and repository**

Migration creates `ingestion_outbox`, foreign keys with `ON DELETE CASCADE`, a
unique `document_id`, and a ready-row index. It also removes duplicate vectors
using `row_number() over (partition by metadata->>'document_id',
metadata->>'chunk_index' order by id)` before adding the partial unique
expression index.

Repository methods use `JdbcTemplate`:

```java
void enqueue(UUID documentId, UUID knowledgeBaseId);
List<IngestionOutboxEvent> lockReadyBatch(int limit);
void markPublished(UUID id);
void markRetry(UUID id, int attemptCount, Instant nextAttemptAt, String error);
```

`lockReadyBatch` uses `FOR UPDATE SKIP LOCKED` and parameterizes `LIMIT`.

- [ ] **Step 4: Switch upload to the outbox**

Inject `IngestionOutboxRepository`, call `enqueue` after `documentRepository.save`,
and remove the application event classes and publisher dependency. The existing
`@Transactional` boundary makes document and outbox atomic.

- [ ] **Step 5: Run upload tests and verify GREEN**

Run: `./mvnw -q -Dtest=DocumentUploadServiceTest test`

Expected: all upload validation and outbox tests pass.

- [ ] **Step 6: Commit outbox persistence**

```bash
git add -A
git commit -m "feat(ingestion): persist upload outbox events"
```

### Task 4: Scheduled Outbox Publisher

**Files:**
- Create: `src/main/java/com/majm/rag/ingestion/IngestionOutboxPublisher.java`
- Create: `src/test/java/com/majm/rag/ingestion/IngestionOutboxPublisherTest.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Write failing publisher tests**

Test a successful send marks published and a failed send schedules retry:

```java
when(repository.lockReadyBatch(20)).thenReturn(List.of(event));
when(kafkaTemplate.send(anyString(), anyString(), any()))
    .thenReturn(CompletableFuture.completedFuture(sendResult));
publisher.publishReady();
verify(repository).markPublished(event.id());
```

```java
CompletableFuture<SendResult<String, Object>> failed = new CompletableFuture<>();
failed.completeExceptionally(new IllegalStateException("broker down"));
when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(failed);
publisher.publishReady();
verify(repository).markRetry(eq(event.id()), eq(event.attemptCount() + 1),
    any(Instant.class), contains("broker down"));
```

- [ ] **Step 2: Run publisher test and verify RED**

Run: `./mvnw -q -Dtest=IngestionOutboxPublisherTest test`

Expected: compilation failure because the publisher does not exist.

- [ ] **Step 3: Implement bounded scheduled publisher**

Create a `@Service` with `@Scheduled(fixedDelayString =
"${app.ingestion.outbox.delay-ms:1000}")` and `@Transactional` on
`publishReady`. Send `IngestionMessage` with document ID as key, call
`get(sendTimeoutMs, MILLISECONDS)`, mark success, and catch each event failure
so the remaining batch continues. Retry delay is
`min(300, 2^(min(attempt, 8)))` seconds.

Add batch size, delay, and send timeout properties under `app.ingestion.outbox`.

- [ ] **Step 4: Run publisher and upload tests and verify GREEN**

Run: `./mvnw -q -Dtest=IngestionOutboxPublisherTest,DocumentUploadServiceTest test`

Expected: all tests pass.

- [ ] **Step 5: Commit publisher**

```bash
git add -A
git commit -m "feat(ingestion): relay outbox events to kafka"
```

### Task 5: Idempotent Consumer and Failure DTO

**Files:**
- Modify: `src/main/java/com/majm/rag/ingestion/IngestionStatusService.java`
- Modify: `src/main/java/com/majm/rag/ingestion/IngestionConsumer.java`
- Modify: `src/main/java/com/majm/rag/knowledge/DocumentService.java`
- Create: `src/test/java/com/majm/rag/ingestion/IngestionConsumerTest.java`
- Create: `src/test/java/com/majm/rag/knowledge/DocumentServiceTest.java`

- [ ] **Step 1: Write failing duplicate and deterministic-ID tests**

For a `DONE` document, make `markProcessing` return `ALREADY_DONE` and verify
no processor interactions. For work, capture the list passed to
`vectorStore.add` and assert IDs equal
`UUID.nameUUIDFromBytes((documentId + ":" + index).getBytes(UTF_8)).toString()`;
verify `vectorStore.add` occurs before
`chunkQueryService.deleteByDocumentFromIndex(documentId, chunkCount)`.

Add a document-list assertion:

```java
assertThat(result.getContent().get(0).errorMessage())
    .isEqualTo("embedding provider unavailable");
```

- [ ] **Step 2: Run focused tests and verify RED**

Run: `./mvnw -q -Dtest=IngestionConsumerTest,DocumentServiceTest test`

Expected: tests fail because status returns `Document`, consumer has no replace
operation, and `DocumentListItem` lacks `errorMessage`.

- [ ] **Step 3: Implement idempotent processing**

Make `markProcessing` lock the document row and return a typed decision. Move
parsing and vector writes into a transactional `IngestionProcessor` that locks
the same row, rechecks `DONE`, and constructs each split document with:

```java
String id = UUID.nameUUIDFromBytes(
    (doc.getId() + ":" + i).getBytes(StandardCharsets.UTF_8)).toString();
Document indexed = new Document(id, chunk.getText(), metadata);
```

Preserve all parser metadata before adding knowledge-base, document, name, and
chunk-index metadata. Upsert before pruning trailing chunks, then persist
`DONE` in the same transaction. Add nullable `errorMessage` to
`DocumentListItem`, and add the V7 vector ownership foreign key.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run: `./mvnw -q -Dtest=IngestionConsumerTest,DocumentServiceTest test`

Expected: all focused tests pass.

- [ ] **Step 5: Commit idempotency and DTO contract**

```bash
git add -A
git commit -m "fix(ingestion): make document processing idempotent"
```

### Task 6: Documentation and Full Verification

**Files:**
- Modify: `README.md`
- Modify: `docs/architecture/TECHNICAL_DESIGN.md`
- Modify: `docs/architecture/SYSTEM_ARCHITECTURE.md`
- Modify: `../rag-admin/README.md`

- [ ] **Step 1: Update documented contracts**

Document the `context / token / done / error` SSE sequence, remove the old
frontend second-search description, describe transactional outbox recovery and
idempotent vector replacement, list the three outbox environment variables,
set the frontend default API base to `/api/v1`, and explain that
`VITE_API_KEY` is browser-visible internal compatibility rather than secret
storage.

- [ ] **Step 2: Run backend full verification**

Run: `./mvnw test`

Expected: all unit tests pass; local-infrastructure integration tests pass when
PostgreSQL/Kafka are available or skip under their documented assumptions.

- [ ] **Step 3: Run frontend full verification**

Run from `../rag-admin`:

```bash
pnpm test -- --run
pnpm lint
pnpm build
```

Expected: all tests and lint pass; TypeScript and Vite production build finish
successfully.

- [ ] **Step 4: Inspect final diffs**

Run in both repositories: `git diff --check` and `git status --short`.

Expected: no whitespace errors; only intended P0 files are modified.

- [ ] **Step 5: Commit documentation**

Commit backend and frontend documentation in their respective repositories:

```bash
git add -A
git commit -m "docs: document P0 reliability contracts"
```
