package org.demo.platformsecurity.privacy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;
import static org.demo.platformsecurity.privacy.CommerceRetentionAssessment.*;
import static org.demo.platformsecurity.privacy.PeerRetentionProtocol.*;
import org.demo.platformsecurity.privacy.PeerRetentionProtocol.Code;

/** Bounded source-local observations. No mutation, inferred absence or hold clearance. */
public final class PeerRetentionEvidence {
    private static final String TYPES="'ORDER_CREATED','ORDER_STATUS_CHANGED','PAYMENT_PENDING','PAYMENT_SUCCEEDED','PAYMENT_FAILED'";
    private final Participant own;private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    private final CommerceRetentionWriteFence writeFence;
    public PeerRetentionGuardProtocol.Reply guards(PeerRetentionGuardProtocol.Command c){return new PeerRetentionGuardEvidence(own,jdbc.getDataSource(),tx.getTransactionManager()).collect(c);}
    public PeerRetentionEvidence(Participant own,DataSource ds,PlatformTransactionManager manager){if(!PEERS.contains(own))throw new IllegalArgumentException("Unsupported peer");this.own=own;jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(2);tx=new TransactionTemplate(manager);tx.setTimeout(5);writeFence=new CommerceRetentionWriteFence(own,ds,manager);}
    public PeerRetentionFenceProtocol.Reply fence(PeerRetentionFenceProtocol.Command c){return writeFence.apply(c);}
    public Reply collect(Command c){if(c==null || !c.valid() || c.scope().source()!=own)throw new IllegalArgumentException("Invalid local peer scope");return tx.execute(status->{
        var s=c.scope();jdbc.queryForObject("SELECT pg_advisory_xact_lock(436,hashint8(CAST(? AS BIGINT)))",(rs,n)->true,s.orderId());
        var rows=jdbc.queryForList("SELECT * FROM commerce_retention_source_state WHERE order_id=? FOR UPDATE",s.orderId());
        if(rows.isEmpty())return unknown(c,Code.STALE);var binding=rows.getFirst();if(Boolean.TRUE.equals(binding.get("restore_blocked")))return unknown(c,Code.QUARANTINED);
        if(!s.generation().equals(binding.get("generation")) || s.orderVersion()!=number(binding,"order_version") || !s.phase().name().equals(binding.get("phase")) || s.epoch()!=number(binding,"binding_epoch") || s.revision()!=number(binding,"source_revision") || Boolean.TRUE.equals(binding.get("invalidated")))return unknown(c,Code.STALE);
        try{
            List<String> events=inspect(c);
            need(Boolean.TRUE.equals(jdbc.queryForObject("SELECT commerce_retention_peer_ready(?)",Boolean.class,s.orderId())),Code.REVIEW_REQUIRED);
            if(!(binding.get("local_reconciled_at") instanceof Timestamp time))return unknown(c,Code.MISSING_MILESTONE);
            if(s.phase()!=Phase.REDACTION)return unknown(c,Code.MISSING_REDACTION);
            String digest=events.isEmpty()?null:eventHash(events);
            return reply(c,State.CONFIRMED,Code.OBSERVED,time.toInstant(),digest,events.size());
        }catch(NotReady blocked){return reply(c,blocked.code==Code.ACTIVE?State.BLOCKED:State.UNKNOWN,blocked.code,null,null,0);}
    });}
    private List<String> inspect(Command c){long order=c.scope().orderId();var f=c.facts();boolean complete=f.status().equals("COMPLETED");
        switch(own){
            case DELIVERY->{
                var r=single("SELECT status,tracking_number,progress_revision,sync_state,sync_token,sync_until,sync_next_attempt_at FROM shipments WHERE order_id=? LIMIT 2",order);
                need(!"REVIEW_REQUIRED".equals(r.get("sync_state")),Code.REVIEW_REQUIRED);
                need(Set.of("DELIVERED","PICKED_UP","CANCELLED").contains(r.get("status")) && "SYNCED".equals(r.get("sync_state")) && r.get("sync_token")==null && r.get("sync_until")==null && r.get("sync_next_attempt_at")==null,Code.ACTIVE);
                need(Objects.equals(f.tracking(),r.get("tracking_number")) && Objects.equals(f.deliveryStatus(),r.get("status")) && f.deliveryRevision()==number(r,"progress_revision") && (complete?!"CANCELLED".equals(r.get("status")):"CANCELLED".equals(r.get("status"))),Code.REVIEW_REQUIRED);
            }
            case PROMOTION->{
                var r=single("SELECT state,code,subtotal,discount_amount FROM coupon_reservations WHERE order_id=? LIMIT 2",order);need(!"RESERVED".equals(r.get("state")),Code.ACTIVE);
                need(Objects.equals(r.get("state"),complete?"CONSUMED":"RELEASED") && Objects.equals(r.get("code"),f.promotionCode()) && amount(r,"subtotal",f.subtotal()) && amount(r,"discount_amount",f.discount()),Code.REVIEW_REQUIRED);
                var redemptions=jdbc.queryForList("SELECT code FROM promotion_redemptions WHERE order_id=? LIMIT 2",String.class,order);
                need(complete ? redemptions.size()==1 && redemptions.getFirst().equals(f.promotionCode()):redemptions.isEmpty(),Code.REVIEW_REQUIRED);
            }
            case RETURNS->{
                var requests=jdbc.queryForList("SELECT customer_id,status,(request_hash IS NOT NULL AND length(request_hash)=64 AND order_verified_at IS NOT NULL) AS verified FROM return_requests WHERE order_id=? LIMIT 101",order);need(!requests.isEmpty() && requests.size()<=100,Code.REVIEW_REQUIRED);
                for(var r:requests){need(f.customerId()>0 && number(r,"customer_id")==f.customerId() && Boolean.TRUE.equals(r.get("verified")),Code.REVIEW_REQUIRED);need(!"COMPLETED".equals(r.get("status")),Code.REVIEW_REQUIRED);need("REJECTED".equals(r.get("status")),Code.ACTIVE);}
            }
            case PAYMENT->{
                var r=single("SELECT status,amount,currency,provider_reference,checkout_session_id,stripe_intent_id,stripe_external_refund_id,stripe_external_hold,stripe_compensation_required,processing_token,processing_until,result_recorded_at,provider_declined,(request_fingerprint IS NOT NULL) AS fingerprint FROM payments WHERE order_id=? LIMIT 2",order);
                need(r.get("checkout_session_id")==null && r.get("stripe_intent_id")==null && r.get("stripe_external_refund_id")==null && !Boolean.TRUE.equals(r.get("stripe_external_hold")) && !Boolean.TRUE.equals(r.get("stripe_compensation_required")),Code.PROVIDER_REVIEW_REQUIRED);
                need(r.get("processing_token")==null && r.get("processing_until")==null && Set.of("SUCCEEDED","FAILED").contains(r.get("status")) && r.get("result_recorded_at")!=null,Code.ACTIVE);
                String ref=Objects.toString(r.get("provider_reference"),"");need(!"SUCCEEDED".equals(r.get("status")) || ref.startsWith("SIM-") || ref.startsWith("CASH-"),Code.PROVIDER_REVIEW_REQUIRED);
                need(Objects.equals(r.get("status"),complete?"SUCCEEDED":"FAILED") && amount(r,"amount",f.amount()) && "PLN".equals(r.get("currency")) && Boolean.TRUE.equals(r.get("fingerprint")) && (complete || Boolean.TRUE.equals(r.get("provider_declined"))),Code.REVIEW_REQUIRED);
                need(!exists("SELECT EXISTS(SELECT 1 FROM payment_refunds r JOIN payments p ON p.id=r.payment_id WHERE ? IN(r.order_id,p.order_id))",order) && !exists("SELECT EXISTS(SELECT 1 FROM stripe_webhook_receipts x JOIN payments p ON p.id=x.payment_id WHERE p.order_id=?)",order),Code.PROVIDER_REVIEW_REQUIRED);
                need(!exists("SELECT EXISTS(SELECT 1 FROM payment_follow_ups WHERE order_id=? AND (status NOT IN ('SUCCEEDED','CANCELLED') OR processing_token IS NOT NULL OR processing_until IS NOT NULL))",order),Code.ACTIVE);
                need(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM payment_outbox_events WHERE order_id=? AND (amount<>CAST(? AS NUMERIC) OR event_type NOT IN ('PAYMENT_PENDING','PAYMENT_SUCCEEDED','PAYMENT_FAILED')))",Boolean.class,order,f.amount())),Code.REVIEW_REQUIRED);
                need(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM payment_outbox_events WHERE order_id=? AND event_type=?)",Boolean.class,order,complete?"PAYMENT_SUCCEEDED":"PAYMENT_FAILED")),Code.RECEIPTS_REQUIRED);
                var events=jdbc.queryForList("SELECT CASE WHEN identity_version=2 THEN 'payment-'||split_part(event_key,':',2) ELSE coalesce(stored_event_id,'payment-'||privacy_identity_hash(event_key)) END AS event_id FROM payment_outbox_events WHERE order_id=? AND status='PUBLISHED' AND published_at IS NOT NULL AND processing_token IS NULL AND processing_until IS NULL ORDER BY id LIMIT 101",String.class,order);
                need(!events.isEmpty() && events.size()<=100,Code.RECEIPTS_REQUIRED);return events;
            }
            case BILLING->{
                var r=single("SELECT customer_id,gross_amount,currency,status FROM invoices WHERE order_id=? LIMIT 2",order);
                need((r.get("customer_id")==null || number(r,"customer_id")==f.customerId()) && amount(r,"gross_amount",f.amount()) && "PLN".equals(r.get("currency")) && Objects.equals(r.get("status"),complete?"ISSUED":"CANCELLED"),Code.REVIEW_REQUIRED);
                need(!exists("SELECT EXISTS(SELECT 1 FROM invoice_corrections c JOIN invoices i ON i.id=c.invoice_id WHERE ? IN(c.order_id,i.order_id))",order),Code.REVIEW_REQUIRED);
                var events=jdbc.queryForList("SELECT event_id FROM event_inbox WHERE order_id=? AND related_id=? AND status='ACCEPTED' ORDER BY event_id LIMIT 101",String.class,order,order);receipts(c,events);return events;
            }
            case NOTIFICATION->{
                need(!exists("SELECT EXISTS(SELECT 1 FROM notifications WHERE source_id=? AND event_type IN ("+TYPES+") AND (status<>'SENT' OR processing_token IS NOT NULL OR processing_until IS NOT NULL OR next_attempt_at IS NOT NULL))",order),Code.ACTIVE);
                var events=jdbc.queryForList("SELECT e.event_id FROM event_inbox e JOIN notifications n ON n.id=e.related_id WHERE e.order_id=? AND n.source_id=? AND e.status='ACCEPTED' AND n.status='SENT' AND n.event_type IN ("+TYPES+") ORDER BY e.event_id LIMIT 101",String.class,order,order);receipts(c,events);return events;
            }
            default->throw new IllegalStateException("Unsupported local peer");
        }return List.of();
    }
    private void receipts(Command c,List<String> events){need(!events.isEmpty() && events.size()<=100 && c.facts().paymentEventsHash()!=null && events.size()==c.facts().paymentEventCount(),Code.RECEIPTS_REQUIRED);try{need(eventHash(events).equals(c.facts().paymentEventsHash()),Code.RECEIPTS_REQUIRED);}catch(IllegalArgumentException malformed){throw new NotReady(Code.REVIEW_REQUIRED);}}
    private Map<String,Object> single(String sql,long order){var rows=jdbc.queryForList(sql,order);need(rows.size()==1,Code.REVIEW_REQUIRED);return rows.getFirst();}
    private boolean exists(String sql,long order){return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,order));}
    private static boolean amount(Map<String,Object> r,String k,String expected){return ((BigDecimal)r.get(k)).compareTo(new BigDecimal(expected))==0;}
    private static long number(Map<String,Object> r,String k){return ((Number)r.get(k)).longValue();}
    private static void need(boolean condition,Code code){if(!condition)throw new NotReady(code);}
    private static final class NotReady extends RuntimeException { final Code code;NotReady(Code code){super(null,null,false,false);this.code=code;}}
    private static Reply unknown(Command c,Code code){return reply(c,State.UNKNOWN,code,null,null,0);}
}
