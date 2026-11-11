package org.demo.returnsservice.returns;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="return_events",uniqueConstraints={@UniqueConstraint(columnNames={"return_id","revision"}),@UniqueConstraint(columnNames={"return_id","operation_id"})})
public class ReturnEvent {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="return_id",nullable=false) private Long returnId;
    @Column(nullable=false) private long revision;
    @Column(name="expected_version") private Long expectedVersion;
    @Column(name="operation_id") private UUID operationId;
    @Column(nullable=false,length=32) private String action;
    @Column(name="from_status",length=20) private String fromStatus;
    @Column(name="to_status",nullable=false,length=20) private String toStatus;
    @Column(length=1000) private String note;
    @Column(name="actor_ref",length=320) private String actorRef;
    @Column(name="order_status",length=30) private String orderStatus;
    @Column(name="payment_state",length=30) private String paymentState;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    protected ReturnEvent() {}
    public ReturnEvent(ReturnRequest request,String action,ReturnStatus before,Long expected,UUID operation,String note,String actor,ReturnOrderClient.Snapshot order) {
        returnId=request.getId();revision=request.getVersion();this.action=action;fromStatus=before==null?null:before.name();toStatus=request.getStatus().name();
        expectedVersion=expected;operationId=operation;this.note=note;actorRef=actor;orderStatus=order==null?null:order.status();paymentState=order==null?null:order.paymentState();createdAt=Instant.now();
    }
    public Long getId() { return id; }
    public long getRevision() { return revision; }
    public String getAction() { return action; }
    public String getFromStatus() { return fromStatus; }
    public String getToStatus() { return toStatus; }
    public String getNote() { return note; }
    public String getActorRef() { return actorRef; }
    public String getOrderStatus() { return orderStatus; }
    public String getPaymentState() { return paymentState; }
    public Instant getCreatedAt() { return createdAt; }
    public UUID getOperationId() { return operationId; }
    public void requireSame(String action,ReturnStatus target,Long expected,String note) {
        if(!this.action.equals(action) || !java.util.Objects.equals(this.expectedVersion,expected) || !java.util.Objects.equals(this.note,note)
                || target!=null && !target.name().equals(toStatus)) throw ReturnIntent.conflict("RETURN_OPERATION_CONFLICT");
    }
}
