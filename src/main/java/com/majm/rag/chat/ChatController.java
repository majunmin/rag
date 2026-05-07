package com.majm.rag.chat;

import com.majm.rag.chat.domain.Conversation;
import com.majm.rag.chat.dto.ChatRequest;
import com.majm.rag.chat.dto.ConversationMessageRequest;
import com.majm.rag.chat.dto.ConversationResponse;
import com.majm.rag.chat.dto.CreateConversationRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.util.UUID;

@Tag(name = "Chat", description = "RAG chat with SSE streaming. Supports stateless single-turn and stateful multi-turn conversations.")
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @Operation(summary = "Single-turn RAG chat (SSE stream of answer tokens)")
    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@Valid @RequestBody ChatRequest request) {
        return chatService.chat(request);
    }

    @Operation(summary = "Create a new conversation bound to a knowledge base")
    @PostMapping("/conversations")
    public ResponseEntity<ConversationResponse> createConversation(
            @Valid @RequestBody CreateConversationRequest request) {
        Conversation conversation = chatService.createConversation(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(conversation.getId())
            .toUri();
        return ResponseEntity.created(location).body(ConversationResponse.from(conversation));
    }

    @Operation(summary = "Send a message in an existing conversation (SSE stream of answer tokens)")
    @PostMapping(value = "/conversations/{id}/messages",
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> continueConversation(@PathVariable UUID id,
            @Valid @RequestBody ConversationMessageRequest request) {
        return chatService.continueConversation(id, request);
    }
}
