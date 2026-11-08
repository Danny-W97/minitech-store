package org.demo.paymentservice.payment;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="payment_refunds")
public class PaymentRefund {
    public enum Status { REQUESTED, PENDING, SUCCEEDED, FAILED, REVIEW_REQUIRED }
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="payment_id",nullable=false,unique=true) private Long paymentId;
    @Column(name="order_id",nullable=false) private Long orderId;
    @Column(name="request_key",nullable=false,length=100,unique=true) private String requestKey;
    @Column(name="operation_id",nullable=false,length=36,unique=true) private String operationId;
    @Column(nullable=false,precision=19,scale=2) private BigDecimal amount;
    @Column(nullable=false,length=3) private String currency;
    @Column(name="intent_id",nullable=false,length=100) private String intentId;
    @Column(name="provider_refund_id",length=100,unique=true) private String providerRefundId;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=24) private Status status;
    @Column(nullable=false) private boolean automatic;
    @Column(nullable=false,length=80) private String reason;
    @Column(name="requested_by",nullable=false,length=255) private String requestedBy;
    @Column(name="business_completed",nullable=false) private boolean businessCompleted;
    @Column(name="business_outcome",length=40) private String businessOutcome;
    @Column(name="submitted_at") private Instant submittedAt;
    @Column(name="next_attempt_at") private Instant nextAttemptAt;
    @Column(name="processing_token",length=36) private String processingToken;
    @Column(name="processing_until") private Instant processingUntil;
    @Column(nullable=false) private int attempts;
    @Column(name="last_error",length=80) private String lastError;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
    protected PaymentRefund() {}
    public PaymentRefund(Payment payment,String key,boolean automatic,String reason,String requestedBy) {
        this(payment,key,payment.getAmount(),automatic,reason,requestedBy);
    }
    public PaymentRefund(Payment payment,String key,BigDecimal amount,boolean automatic,String reason,String requestedBy) {
        paymentId=payment.getId();orderId=payment.getOrderId();this.amount=amount;currency=payment.getCurrency();
        intentId=payment.getStripeIntentId();requestKey=key;operationId=UUID.randomUUID().toString();
        this.automatic=automatic;this.reason=reason;this.requestedBy=requestedBy;status=Status.REQUESTED;nextAttemptAt=Instant.now();
    }
    public boolean processing() { return processingUntil!=null && processingUntil.isAfter(Instant.now()); }
    public void claim(String token) { processingToken=token;processingUntil=Instant.now().plusSeconds(120);attempts++; }
    public void release() { processingToken=null;processingUntil=null; }
    public void submitted() { if(submittedAt==null) submittedAt=Instant.now(); }
    public void provider(StripePaymentGateway.Refund result) {
        if(!intentId.equals(result.intent()) || amount.movePointRight(2).longValueExact()!=result.amount()
                || !currency.equalsIgnoreCase(result.currency()) || !operationId.equals(result.operationId())
                || providerRefundId!=null && !providerRefundId.equals(result.id())) throw new StripeReviewRequiredException("REFUND_IDENTITY_CONFLICT");
        providerRefundId=result.id();
        status=switch(result.status()) { case "succeeded" -> Status.SUCCEEDED;case "pending" -> Status.PENDING;
            case "failed","canceled" -> Status.FAILED;default -> Status.REVIEW_REQUIRED; };
        lastError=status==Status.FAILED ? "PROVIDER_REFUND_FAILED" : status==Status.REVIEW_REQUIRED ? "PROVIDER_REQUIRES_ACTION" : null;
        nextAttemptAt=status==Status.FAILED || status==Status.REVIEW_REQUIRED ? null : Instant.now().plusSeconds(30);
    }
    public void retry(String code) { lastError=code;nextAttemptAt=Instant.now().plusSeconds(Math.min(300,5L*Math.max(1,attempts)));release(); }
    public void review(String code) {
        if(status==Status.SUCCEEDED) businessOutcome="REVIEW_REQUIRED";
        else status=Status.REVIEW_REQUIRED;
        lastError=code;nextAttemptAt=null;release();
    }
    public void reconciliate() {
        if(rejectedWithoutMoney()) { businessCompleted=false;businessOutcome=null;lastError=null;status=Status.REQUESTED; }
        else if(businessCompleted) return;
        if(status==Status.REVIEW_REQUIRED || status==Status.FAILED) status=providerRefundId==null ? Status.REQUESTED : Status.PENDING;
        nextAttemptAt=Instant.now();
    }
    public void businessCompleted(String outcome) { businessCompleted=true;businessOutcome=outcome;nextAttemptAt=null;lastError=null;release(); }
    public boolean rejectedWithoutMoney() { return status==Status.FAILED && submittedAt==null && "REJECTED_NO_MONEY_MOVED".equals(businessOutcome); }
    public void rejectEligibility(String code) { status=Status.FAILED;businessCompleted=true;businessOutcome="REJECTED_NO_MONEY_MOVED";lastError=code;nextAttemptAt=null;release(); }
    @PrePersist void create() { createdAt=Instant.now();updatedAt=createdAt; }
    @PreUpdate void update() { updatedAt=Instant.now(); }
    public Long getId(){return id;} public Long getPaymentId(){return paymentId;} public Long getOrderId(){return orderId;}
    public String getRequestKey(){return requestKey;} public String getOperationId(){return operationId;}
    public BigDecimal getAmount(){return amount;} public String getCurrency(){return currency;}
    public String getIntentId(){return intentId;} public String getProviderRefundId(){return providerRefundId;}
    public Status getStatus(){return status;} public boolean isAutomatic(){return automatic;}
    public String getReason(){return reason;} public String getRequestedBy(){return requestedBy;}
    public boolean isBusinessCompleted(){return businessCompleted;} public String getBusinessOutcome(){return businessOutcome;}
    public Instant getSubmittedAt(){return submittedAt;} public Instant getNextAttemptAt(){return nextAttemptAt;}
    public String getProcessingToken(){return processingToken;} public String getLastError(){return lastError;}
    public Instant getCreatedAt(){return createdAt;}
}
