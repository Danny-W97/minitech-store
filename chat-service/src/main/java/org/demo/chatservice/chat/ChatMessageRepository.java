package org.demo.chatservice.chat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select m from ChatMessage m where m.id=:id")
    java.util.Optional<ChatMessage> lockId(Long id);
    List<ChatMessage> findAllByOrderByCreatedAtDesc();
    List<ChatMessage> findAllByStatusOrderByCreatedAtAsc(ChatMessageStatus status);

    @org.springframework.data.jpa.repository.Query("select m from ChatMessage m where m.id <= :ceiling "
            + "and (:status is null or m.status = :status) and (lower(m.customerEmail) like :search "
            + "or lower(m.subject) like :search or lower(m.message) like :search) order by m.id desc")
    List<ChatMessage> page(@org.springframework.data.repository.query.Param("ceiling") long ceiling,
            @org.springframework.data.repository.query.Param("status") ChatMessageStatus status,
            @org.springframework.data.repository.query.Param("search") String search, org.springframework.data.domain.Pageable pageable);
}
