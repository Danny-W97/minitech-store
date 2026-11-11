package org.demo.billingservice.privacy;

import org.demo.platformsecurity.*;
import org.demo.platformsecurity.privacy.*;
import org.demo.platformevents.RedisPrivacyStreams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class BillingFinancePreparation implements FinancePreparationAdapter {
    private final PrivacyFenceStore fences;private final CommerceOrderSource source;private final RedisPrivacyStreams streams;private final FinanceHttp payment;private final String stream,groups;
    public BillingFinancePreparation(PrivacyFenceStore fences,CommerceOrderSource source,StringRedisTemplate redis,ServiceAuthenticationInterceptor auth,
        @Value("${PAYMENT_SERVICE_URL:http://localhost:18084}") String paymentOrigin,@Value("${messaging.invoice-stream:invoice-events}") String stream,@Value("${messaging.invoice-stream-retention.required-groups:}") String groups){
        this.fences=fences;this.source=source;streams=new RedisPrivacyStreams(redis);payment=new FinanceHttp(paymentOrigin,auth);this.stream=stream;this.groups=groups;
    }
    public Result prepare(long customer,UUID request,long order,long version,String cursor){
        fences.underFence(customer,request,jdbc->true);
        var canonical=FinancePreparationAdapter.exact(source,customer,request,order,version);
        if(!canonical.eligibility().equals("ELIGIBLE"))return Result.waitFor(canonical.eligibility(),cursor);
        var events=payment.paymentEvents(customer,request,order,version);
        var local=fences.underFence(customer,request,jdbc->{
            CommerceLocks.lock(jdbc,order);
            if(canonical.invoiceRequested()&&canonical.status().equals("COMPLETED")&&!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM invoices WHERE order_id=?)",Boolean.class,order)))return Result.waitFor("WAITING_REQUIRED_INVOICE",cursor);
            var documents=jdbc.queryForList("SELECT customer_id,gross_amount,currency,status FROM invoices WHERE order_id=?",order);
            if(!documents.isEmpty()){
                var document=documents.getFirst();
                if(document.get("customer_id") instanceof Number owner&&owner.longValue()!=customer
                    ||((java.math.BigDecimal)document.get("gross_amount")).compareTo(canonical.amount())!=0
                    ||!"PLN".equals(document.get("currency"))
                    ||!Objects.equals(document.get("status"),canonical.status().equals("COMPLETED")?"ISSUED":"CANCELLED"))return Result.waitFor("FINANCE_REVIEW_REQUIRED",cursor);
            }
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM invoice_corrections c JOIN invoices i ON i.id=c.invoice_id WHERE i.order_id=? AND (c.ledger_version IS NULL OR c.order_id<>i.order_id))",Boolean.class,order)))return Result.waitFor("FINANCE_REVIEW_REQUIRED",cursor);
            for(String id:events)if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM event_inbox WHERE event_id=? AND status='ACCEPTED' AND related_id=?)",Boolean.class,id,order)))return Result.waitFor("WAITING_PAYMENT_RECEIPTS",cursor);
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM event_inbox WHERE order_id=? AND status='REJECTED')",Boolean.class,order)))return Result.waitFor("FINANCE_REVIEW_REQUIRED",cursor);
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM invoice_outbox_events WHERE order_id=? AND NOT privacy_safe AND (status<>'PUBLISHED' OR processing_token IS NOT NULL OR processing_until IS NOT NULL))",Boolean.class,order)))return Result.waitFor("WAITING_INVOICE_OUTBOX",cursor);
            CommerceLocks.retain(jdbc,order,customer,request);
            int changed=jdbc.update("UPDATE invoice_outbox_events SET payload=NULL,last_error=NULL,payload_compacted_at=coalesce(payload_compacted_at,CURRENT_TIMESTAMP) WHERE id IN (SELECT id FROM invoice_outbox_events WHERE order_id=? AND status='PUBLISHED' AND processing_token IS NULL AND processing_until IS NULL AND (payload IS NOT NULL OR last_error IS NOT NULL) ORDER BY id LIMIT 100)",order);
            changed+=jdbc.update("UPDATE event_inbox SET payload=NULL,error=NULL,diagnostics_compacted_at=coalesce(diagnostics_compacted_at,CURRENT_TIMESTAMP) WHERE event_id IN (SELECT event_id FROM event_inbox WHERE related_id=? AND status='ACCEPTED' AND (payload IS NOT NULL OR error IS NOT NULL) ORDER BY event_id LIMIT 100)",order);
            return changed>0?Result.more(cursor):Result.prepared();
        });
        return !local.state().equals("PREPARED")?local:streams.prepare(stream,groups,order,request,cursor);
    }
}
