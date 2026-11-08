package org.demo.paymentservice.privacy;

import org.demo.platformsecurity.privacy.*;
import org.demo.platformevents.RedisPrivacyStreams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class PaymentFinancePreparation implements FinancePreparationAdapter {
    private final PrivacyFenceStore fences;private final CommerceOrderSource source;private final RedisPrivacyStreams streams;private final String stream,groups;
    public PaymentFinancePreparation(PrivacyFenceStore fences,CommerceOrderSource source,StringRedisTemplate redis,
        @Value("${messaging.payment-stream:payment-events}") String stream,@Value("${messaging.retention.required-groups:notification-service,billing-service}") String groups){this.fences=fences;this.source=source;streams=new RedisPrivacyStreams(redis);this.stream=stream;this.groups=groups;}
    public Result prepare(long customer,UUID request,long order,long version,String cursor){
        fences.underFence(customer,request,jdbc->true);
        var canonical=FinancePreparationAdapter.exact(source,customer,request,order,version);
        if(!canonical.eligibility().equals("ELIGIBLE"))return Result.waitFor(canonical.eligibility(),cursor);
        var local=fences.underFence(customer,request,jdbc->{
            CommerceLocks.lock(jdbc,order);var payments=jdbc.queryForList("SELECT * FROM payments WHERE order_id=?",order);
            if(payments.isEmpty()&&!canonical.status().equals("CANCELLED"))return Result.waitFor("FINANCE_REVIEW_REQUIRED",cursor);
            if(!payments.isEmpty()){
                var p=payments.getFirst();String state=p.get("status").toString(),reference=Objects.toString(p.get("provider_reference"),"");
                if(p.get("checkout_session_id")!=null||p.get("stripe_intent_id")!=null||p.get("stripe_external_refund_id")!=null||Boolean.TRUE.equals(p.get("stripe_external_hold"))||Boolean.TRUE.equals(p.get("stripe_compensation_required"))
                    ||state.equals("SUCCEEDED")&&!reference.startsWith("SIM-")&&!reference.startsWith("CASH-"))return Result.waitFor("WAITING_PROVIDER_REVIEW",cursor);
                if(p.get("processing_token")!=null||p.get("processing_until")!=null||!Set.of("SUCCEEDED","FAILED").contains(state)||p.get("result_recorded_at")==null)return Result.waitFor("WAITING_PAYMENT_PROCESS",cursor);
                if(state.equals("FAILED")&&!Boolean.TRUE.equals(p.get("provider_declined"))||p.get("request_fingerprint")==null)return Result.waitFor("FINANCE_REVIEW_REQUIRED",cursor);
                if(((java.math.BigDecimal)p.get("amount")).compareTo(canonical.amount())!=0||!"PLN".equals(p.get("currency"))||canonical.status().equals("COMPLETED")&&!state.equals("SUCCEEDED")||canonical.status().equals("CANCELLED")&&!state.equals("FAILED"))return Result.waitFor("FINANCE_REVIEW_REQUIRED",cursor);
            }
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM payment_refunds WHERE order_id=?)",Boolean.class,order)))return Result.waitFor("WAITING_PROVIDER_REVIEW",cursor);
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM payment_follow_ups WHERE order_id=? AND (status NOT IN ('SUCCEEDED','CANCELLED') OR processing_token IS NOT NULL OR processing_until IS NOT NULL))",Boolean.class,order)))return Result.waitFor("WAITING_FOLLOWUP_PROCESS",cursor);
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM payment_outbox_events WHERE order_id=? AND (status<>'PUBLISHED' OR processing_token IS NOT NULL OR processing_until IS NOT NULL))",Boolean.class,order)))return Result.waitFor("WAITING_PAYMENT_OUTBOX",cursor);
            if(jdbc.queryForObject("SELECT count(*) FROM (SELECT id FROM payment_outbox_events WHERE order_id=? LIMIT 101) q",Integer.class,order)>100)return Result.waitFor("FINANCE_REVIEW_REQUIRED",cursor);
            CommerceLocks.retain(jdbc,order,customer,request);
            int changed=jdbc.update("""
                UPDATE payment_outbox_events SET stored_event_id=coalesce(stored_event_id,'payment-'||encode(sha256(convert_to(event_key,'UTF8')),'hex')),
                 event_key=CASE WHEN identity_version=1 AND event_key NOT LIKE 'retained:%' THEN 'retained:'||encode(sha256(convert_to(event_key,'UTF8')),'hex') ELSE event_key END,
                 recipient=NULL,delivery_address=NULL,last_error=NULL,payload_compacted_at=coalesce(payload_compacted_at,CURRENT_TIMESTAMP),updated_at=CURRENT_TIMESTAMP
                WHERE id IN (SELECT id FROM payment_outbox_events WHERE order_id=? AND (recipient IS NOT NULL OR delivery_address IS NOT NULL OR last_error IS NOT NULL OR (identity_version=1 AND event_key NOT LIKE 'retained:%')) ORDER BY id LIMIT 100)
                """,order);
            changed+=jdbc.update("UPDATE payment_follow_ups SET payload=NULL,last_error_code=NULL,updated_at=CURRENT_TIMESTAMP WHERE id IN (SELECT id FROM payment_follow_ups WHERE order_id=? AND (payload IS NOT NULL OR last_error_code IS NOT NULL) ORDER BY id LIMIT 100)",order);
            changed+=jdbc.update("UPDATE payments SET failure_reason=NULL,updated_at=CURRENT_TIMESTAMP WHERE order_id=? AND failure_reason IS NOT NULL",order);
            return changed>0?Result.more(cursor):Result.prepared();
        });
        return !local.state().equals("PREPARED")?local:streams.prepare(stream,groups,order,request,cursor);
    }
    public List<String> events(long customer,UUID request,long order,long version){
        fences.underFence(customer,request,jdbc->true);
        FinancePreparationAdapter.exact(source,customer,request,order,version);
        return fences.underFence(customer,request,jdbc->{
            CommerceLocks.lock(jdbc,order);if(!CommerceLocks.retained(jdbc,order,customer,request))throw new IllegalStateException("Payment privacy preparation required");
            var ids=jdbc.queryForList("SELECT CASE WHEN identity_version=2 THEN 'payment-'||split_part(event_key,':',2) ELSE coalesce(stored_event_id,'payment-'||encode(sha256(convert_to(event_key,'UTF8')),'hex')) END FROM payment_outbox_events WHERE order_id=? ORDER BY id LIMIT 101",String.class,order);
            if(ids.size()>100)throw new IllegalStateException("Finance history requires review");return ids;
        });
    }
}
