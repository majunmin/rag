package com.majm.rag.chat;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
class ConversationPersistenceService {

    private final ConversationRepository conversationRepository;

    @Transactional
    public void appendAssistantMessage(UUID conversationId, String content) {
        conversationRepository.findById(conversationId).ifPresent(conv -> {
            List<Map<String, String>> messages = new ArrayList<>(conv.getMessages());
            messages.add(Map.of("role", "assistant", "content", content));
            conv.setMessages(messages);
            conversationRepository.save(conv);
        });
    }
}
