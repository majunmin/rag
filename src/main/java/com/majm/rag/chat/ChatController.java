package com.majm.rag.chat;

import com.majm.rag.chat.domain.Conversation;
import com.majm.rag.chat.dto.ChatRequest;
import com.majm.rag.chat.dto.ConversationMessageRequest;
import com.majm.rag.chat.dto.CreateConversationRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@Valid @RequestBody ChatRequest request) {
        return chatService.chat(request);
    }

    @PostMapping("/conversations")
    public ResponseEntity<Conversation> createConversation(
            @Valid @RequestBody CreateConversationRequest request) {
        return ResponseEntity.ok(chatService.createConversation(request));
    }

    @PostMapping(value = "/conversations/{id}/messages",
                 produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> continueConversation(@PathVariable UUID id,
            @Valid @RequestBody ConversationMessageRequest request) {
        return chatService.continueConversation(id, request);
    }
}
