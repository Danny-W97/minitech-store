package org.demo.chatservice.chat;
import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name="chat_notification_outbox")
public class ChatNotificationTask {
    @Id @Column(name="entry_id") private Long entryId;
    @Column(name="event_type",nullable=false,length=30) private String eventType;
    @Column(name="source_id",nullable=false) private Long sourceId;
    @Column(nullable=false,length=20) private String status;
    @Column(nullable=false) private int attempts;
    @Column(name="next_attempt_at") private Instant nextAttemptAt;
    @Column(name="processing_token",length=36) private String processingToken;
    @Column(name="processing_until") private Instant processingUntil;
    @Column(name="notification_id") private Long notificationId;
    @Column(name="delivery_status",length=20) private String deliveryStatus;
    @Column(name="last_error",length=60) private String lastError;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    protected ChatNotificationTask() {}
    public ChatNotificationTask(ChatEntry entry) {
        entryId=entry.getId();sourceId=entryId;
        eventType="SUPPORT".equals(entry.getAuthor()) ? "CHAT_ENTRY_REPLIED" : "CHAT_ENTRY_RECEIVED";
        status="PENDING";createdAt=entry.getCreatedAt();nextAttemptAt=Instant.now();
    }
    public boolean claimable() { return ("PENDING".equals(status)||"RETRY".equals(status))
            && nextAttemptAt!=null && !nextAttemptAt.isAfter(Instant.now())
            && (processingUntil==null || !processingUntil.isAfter(Instant.now())); }
    public void claim(String token,long lease) { processingToken=token;processingUntil=Instant.now().plusMillis(lease);attempts++; }
    public void complete(Long id,String delivery) { status="ACCEPTED";notificationId=id;deliveryStatus=delivery;nextAttemptAt=null;lastError=null;release(); }
    public void fail(int maximum) { status=attempts>=maximum?"DEAD_LETTER":"RETRY";lastError="NOTIFICATION_UNAVAILABLE";
        nextAttemptAt=attempts>=maximum?null:Instant.now().plusSeconds(Math.min(300,1L<<Math.min(attempts,8)));release(); }
    public void replay() { status="PENDING";attempts=0;nextAttemptAt=Instant.now();lastError=null;release(); }
    private void release() { processingToken=null;processingUntil=null; }
    public Long getEntryId(){return entryId;}
    public String getEventType(){return eventType;}
    public Long getSourceId(){return sourceId;}
    public String getStatus(){return status;}
    public String getProcessingToken(){return processingToken;}
    public Long getNotificationId(){return notificationId;}
    public String publicStatus(){return "ACCEPTED".equals(status)&&"SENT".equals(deliveryStatus)?"SENT":status;}
}
