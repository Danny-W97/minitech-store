package org.demo.notificationservice.privacy;

import org.demo.platformsecurity.*;
import org.demo.platformsecurity.privacy.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

@Component
public class NotificationFinancePreparation implements FinancePreparationAdapter {
    private static final String ORDER_TYPES="'ORDER_CREATED','ORDER_STATUS_CHANGED','PAYMENT_PENDING','PAYMENT_SUCCEEDED','PAYMENT_FAILED'";
    private static final String ACCOUNT_TYPES="'CUSTOMER_REGISTERED','PASSWORD_RESET_REQUESTED'";
    private final PrivacyFenceStore fences;private final CommerceOrderSource source;private final FinanceHttp payment;
    public NotificationFinancePreparation(PrivacyFenceStore fences,CommerceOrderSource source,ServiceAuthenticationInterceptor auth,@Value("${PAYMENT_SERVICE_URL:http://localhost:18084}") String paymentOrigin){this.fences=fences;this.source=source;payment=new FinanceHttp(paymentOrigin,auth);}
    public Result prepare(long customer,UUID request,long order,long version,String cursor){
        fences.underFence(customer,request,jdbc->true);
        var canonical=FinancePreparationAdapter.exact(source,customer,request,order,version);
        if(!canonical.eligibility().equals("ELIGIBLE"))return Result.waitFor(canonical.eligibility(),cursor);
        var events=payment.paymentEvents(customer,request,order,version);
        return fences.underFence(customer,request,jdbc->{
            CommerceLocks.lock(jdbc,order);
            for(String id:events)if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM event_inbox e JOIN notifications n ON n.id=e.related_id WHERE e.event_id=? AND e.status='ACCEPTED' AND n.source_id=? AND n.status='SENT' AND n.event_type IN ("+ORDER_TYPES+"))",Boolean.class,id,order)))return Result.waitFor("WAITING_NOTIFICATION_RECEIPTS",cursor);
            var readiness=ready(jdbc,order,ORDER_TYPES);if(readiness!=null)return Result.waitFor(readiness,cursor);
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM event_inbox WHERE order_id=? AND status='REJECTED')",Boolean.class,order)))return Result.waitFor("FINANCE_REVIEW_REQUIRED",cursor);
            CommerceLocks.retain(jdbc,order,customer,request);
            int changed=redact(jdbc,order,ORDER_TYPES);
            changed+=jdbc.update("UPDATE event_inbox SET payload=NULL,error=NULL,diagnostics_compacted_at=coalesce(diagnostics_compacted_at,CURRENT_TIMESTAMP) WHERE event_id IN (SELECT e.event_id FROM event_inbox e JOIN notifications n ON n.id=e.related_id WHERE n.source_id=? AND n.event_type IN ("+ORDER_TYPES+") AND e.status='ACCEPTED' AND (e.payload IS NOT NULL OR e.error IS NOT NULL) ORDER BY e.event_id LIMIT 100)",order);
            return changed>0?Result.more(cursor):Result.prepared();
        });
    }
    public Result account(long customer,UUID request){return fences.underFence(customer,request,jdbc->{
        var readiness=ready(jdbc,customer,ACCOUNT_TYPES);if(readiness!=null)return Result.waitFor(readiness,"0-0");
        jdbc.update("INSERT INTO account_privacy_copy_locks(customer_id,request_id) VALUES(?,?) ON CONFLICT(customer_id) DO NOTHING",customer,request);
        if(!request.equals(jdbc.queryForObject("SELECT request_id FROM account_privacy_copy_locks WHERE customer_id=?",UUID.class,customer)))throw new IllegalStateException("Account privacy identity conflict");
        return redact(jdbc,customer,ACCOUNT_TYPES)>0?Result.more("0-0"):Result.prepared();
    });}

    private String scope(String types){
        return types.equals(ACCOUNT_TYPES)?"event_type IN ("+ACCOUNT_TYPES+") AND CASE WHEN event_type='CUSTOMER_REGISTERED' THEN coalesce(subject_customer_id,source_id) ELSE subject_customer_id END=?":"source_id=? AND event_type IN ("+ORDER_TYPES+")";
    }
    private String ready(JdbcTemplate jdbc,long source,String types){
        String own=scope(types);
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM notifications WHERE "+own+" AND (status<>'SENT' OR processing_token IS NOT NULL OR processing_until IS NOT NULL OR next_attempt_at IS NOT NULL))",Boolean.class,source)))return "WAITING_NOTIFICATION_PROCESS";
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM notification_dead_letters WHERE notification_id IN (SELECT id FROM notifications WHERE "+own+") AND (status<>'RESOLVED' OR resolved_at IS NULL))",Boolean.class,source)))return "WAITING_DEAD_LETTER_REVIEW";
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM notification_dead_letters d JOIN notifications n ON n.id=d.notification_id WHERE n.id IN (SELECT id FROM notifications WHERE "+own+") AND (d.source_id<>n.source_id OR d.event_type<>n.event_type))",Boolean.class,source)))return "FINANCE_REVIEW_REQUIRED";
        if(types.equals(ORDER_TYPES)&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM notification_dead_letters d WHERE d.source_id=? AND d.event_type IN ("+ORDER_TYPES+") AND NOT EXISTS(SELECT 1 FROM notifications n WHERE n.id=d.notification_id AND n.source_id=d.source_id AND n.event_type=d.event_type))",Boolean.class,source)))return "FINANCE_REVIEW_REQUIRED";
        return null;
    }
    private int redact(JdbcTemplate jdbc,long source,String types){
        String own=scope(types);
        int count=jdbc.update("UPDATE notifications SET recipient_hash=coalesce(recipient_hash,privacy_identity_hash(recipient)),recipient=NULL,subject=NULL,message=NULL,failure_reason=NULL,privacy_redacted=TRUE,content_compacted_at=coalesce(content_compacted_at,CURRENT_TIMESTAMP),updated_at=CURRENT_TIMESTAMP WHERE id IN (SELECT id FROM notifications WHERE "+own+" AND NOT privacy_redacted ORDER BY id LIMIT 100)",source);
        count+=jdbc.update("UPDATE notification_dead_letters SET recipient=NULL,subject=NULL,message=NULL,failure_reason=NULL,content_compacted_at=coalesce(content_compacted_at,CURRENT_TIMESTAMP),updated_at=CURRENT_TIMESTAMP,row_version=row_version+1 WHERE id IN (SELECT id FROM notification_dead_letters WHERE notification_id IN (SELECT id FROM notifications WHERE "+own+") AND (recipient IS NOT NULL OR subject IS NOT NULL OR message IS NOT NULL OR failure_reason IS NOT NULL) ORDER BY id LIMIT 100)",source);
        return count;
    }
}
