package com.majm.rag.chat.infrastructure.persistence;

import com.majm.rag.chat.domain.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {
}
