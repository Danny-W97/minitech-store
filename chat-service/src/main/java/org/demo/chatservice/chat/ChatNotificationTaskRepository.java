package org.demo.chatservice.chat;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.*;
public interface ChatNotificationTaskRepository extends JpaRepository<ChatNotificationTask,Long> {
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ChatNotificationTask t where t.entryId=:id") Optional<ChatNotificationTask> lockId(Long id);
    @Query("select t.entryId from ChatNotificationTask t where t.status in ('PENDING','RETRY') and t.nextAttemptAt<=:now and (t.processingUntil is null or t.processingUntil<=:now) order by t.entryId")
    List<Long> due(Instant now,Pageable page);
}
