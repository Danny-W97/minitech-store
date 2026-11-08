package org.demo.chatservice.chat;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.Pageable;
import java.util.*;
public interface ChatEntryRepository extends JpaRepository<ChatEntry,Long> {
    Optional<ChatEntry> findByConversationIdAndAuthorAndRequestKey(Long conversation,String author,String key);
    @Query("select e from ChatEntry e where e.conversationId=:conversation and e.id<:before order by e.id desc")
    List<ChatEntry> window(Long conversation,long before,Pageable page);
}
