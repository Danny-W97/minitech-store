package org.demo.chatservice.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findAllByOrderByCreatedAtDesc();
    List<ChatMessage> findAllByStatusOrderByCreatedAtAsc(ChatMessageStatus status);
}
