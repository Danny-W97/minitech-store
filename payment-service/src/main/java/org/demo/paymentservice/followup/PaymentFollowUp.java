package org.demo.paymentservice.followup;

import jakarta.persistence.*;
import java.time.Instant;

/** One durable, independently retryable business effect per paid order and kind. */
@Entity
@Table(name="payment_follow_ups",uniqueConstraints=@UniqueConstraint(columnNames={"order_id","kind"}))
public class PaymentFollowUp {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="order_id",nullable=false) private Long orderId;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=32) private FollowUpKind kind;
    @Column(columnDefinition="text") private String payload;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private FollowUpStatus status;
    @Column(nullable=false) private int attempts;
    @Column(name="replay_count",nullable=false) private int replayCount;
    @Column(name="next_attempt_at") private Instant nextAttemptAt;
    @Column(name="processing_token",length=36) private String processingToken;
    @Column(name="processing_until") private Instant processingUntil;
    @Column(name="result_id") private Long resultId;
    @Column(name="last_error_code",length=60) private String lastErrorCode;
    @Column(name="created_at",nullable=false,updatable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;

    protected PaymentFollowUp() {}
    public PaymentFollowUp(Long orderId,FollowUpKind kind,String payload,boolean waiting) {
        this.orderId=orderId;this.kind=kind;this.payload=payload;
        status=waiting ? FollowUpStatus.WAITING : FollowUpStatus.PENDING;
        nextAttemptAt=waiting ? null : Instant.now();
    }
    public boolean processing() { return processingUntil!=null && processingUntil.isAfter(Instant.now()); }
    public void claim(String token,Instant until) { processingToken=token;processingUntil=until;attempts++; }
    public void release() { processingToken=null;processingUntil=null; }
    public void succeed(Long result) {
        resultId=result;status=FollowUpStatus.SUCCEEDED;lastErrorCode=null;nextAttemptAt=null;payload=null;release();
    }
    public void fail(String code,boolean terminal) {
        lastErrorCode=code;status=terminal ? FollowUpStatus.DEAD_LETTER : FollowUpStatus.RETRY;
        nextAttemptAt=terminal ? null : Instant.now().plusSeconds(Math.min(300,1L << Math.min(attempts,9)));
        release();
    }
    public void ready() { status=FollowUpStatus.PENDING;nextAttemptAt=Instant.now(); }
    public void replay() { ready();attempts=0;replayCount++;lastErrorCode=null;release(); }
    @PrePersist void create() { createdAt=Instant.now();updatedAt=createdAt; }
    @PreUpdate void update() { updatedAt=Instant.now(); }
    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public FollowUpKind getKind() { return kind; }
    public String getPayload() { return payload; }
    public FollowUpStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public int getReplayCount() { return replayCount; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getProcessingToken() { return processingToken; }
    public Long getResultId() { return resultId; }
    public String getLastErrorCode() { return lastErrorCode; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
