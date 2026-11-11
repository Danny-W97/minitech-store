package org.demo.customerservice.privacy;

import org.demo.customerservice.auth.CustomerPrincipal;
import org.demo.customerservice.mfa.CustomerWebAuthnUserEntityRepository;
import org.demo.platformsecurity.Access;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

@Repository
public class PrivacyRequestStore {
    private final NamedParameterJdbcTemplate jdbc;private final TransactionTemplate tx;
    private final org.demo.platformsecurity.privacy.JournalReceiptVerifier journal;
    public record Request(UUID id,long customer,UUID key,String hash,Instant created,Instant previewExpires,Instant confirmed,Instant statusExpires,String phase){}
    public record Work(UUID request,long customer,String service,UUID token){}
    public record CommerceWork(UUID request,long customer,String service,UUID token,long cursor){}
    public PrivacyRequestStore(DataSource source,PlatformTransactionManager manager){this(source,manager,null);}
    @org.springframework.beans.factory.annotation.Autowired public PrivacyRequestStore(DataSource source,PlatformTransactionManager manager,org.demo.platformsecurity.privacy.JournalReceiptVerifier journal){this.journal=journal;var template=new JdbcTemplate(source);template.setQueryTimeout(2);jdbc=new NamedParameterJdbcTemplate(template);tx=new TransactionTemplate(manager);tx.setTimeout(5);}
    private static ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
    private static Map<String,Object> owner(CustomerPrincipal p){if(p==null||p.customerId()==null||p.customerId()<1||p.email()==null)throw error(HttpStatus.UNAUTHORIZED,"Customer authentication required");return new HashMap<>(Map.of("customer",p.customerId(),"email",p.email()));}
    private static Instant at(ResultSet rs,String name)throws SQLException{var t=rs.getTimestamp(name);return t==null?null:t.toInstant();}
    private static final RowMapper<Request> MAPPER=(rs,n)->new Request(rs.getObject("id",UUID.class),rs.getLong("customer_id"),rs.getObject("preview_key",UUID.class),rs.getString("plan_hash"),at(rs,"created_at"),at(rs,"preview_expires_at"),at(rs,"confirmed_at"),at(rs,"status_expires_at"),rs.getString("phase"));
    private Request get(UUID id,boolean lock){var rows=jdbc.query("SELECT * FROM account_privacy_requests WHERE id=:id"+(lock?" FOR UPDATE":""),Map.of("id",id),MAPPER);if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Privacy request not found");return rows.getFirst();}
    public Request preview(CustomerPrincipal p,UUID key){return tx.execute(status->{
        var args=owner(p);
        if(jdbc.queryForList("SELECT id FROM customers WHERE id=:customer AND email=:email AND enabled=TRUE FOR UPDATE",args).isEmpty())throw error(HttpStatus.UNAUTHORIZED,"Customer authentication required");
        var existing=jdbc.query("SELECT * FROM account_privacy_requests WHERE customer_id=:customer FOR UPDATE",args,MAPPER);
        Instant now=jdbc.queryForObject("SELECT CURRENT_TIMESTAMP",Map.of(),(rs,n)->rs.getTimestamp(1).toInstant());
        if(!existing.isEmpty()){
            var r=existing.getFirst();if(r.confirmed()!=null)throw error(HttpStatus.CONFLICT,"Privacy request already confirmed");
            if(r.previewExpires().isAfter(now)){
                String binding=PrivacyPlan.binding(r.id(),r.customer(),r.created(),r.key());
                if(!binding.equals(r.hash())){jdbc.update("UPDATE account_privacy_requests SET plan_hash=:hash WHERE id=:id",Map.of("id",r.id(),"hash",binding));return get(r.id(),false);}
                return r;
            }
            jdbc.update("DELETE FROM account_privacy_requests WHERE id=:id",Map.of("id",r.id()));
        }
        UUID id=UUID.randomUUID();String hash=PrivacyPlan.binding(id,p.customerId(),now,key);
        args.putAll(Map.of("id",id,"key",key,"hash",hash,"now",Timestamp.from(now)));
        jdbc.update("INSERT INTO account_privacy_requests(id,customer_id,preview_key,plan_hash,created_at,preview_expires_at,phase) VALUES(:id,:customer,:key,:hash,:now,CAST(:now AS TIMESTAMPTZ)+INTERVAL '10 minutes','PREVIEW')",args);
        return get(id,false);
    });}
    public Request confirm(UUID id,CustomerPrincipal p,String planHash,String password,String capability,Set<String> methods,PasswordEncoder encoder){return tx.execute(status->{
        var args=owner(p);args.put("id",id);
        // Existing login/refresh/MFA paths use this same customer row lock.
        var accounts=jdbc.queryForList("SELECT password_hash FROM customers WHERE id=:customer AND email=:email AND enabled=TRUE FOR UPDATE",args);
        if(accounts.isEmpty())throw error(HttpStatus.UNAUTHORIZED,"Customer authentication required");
        if(!encoder.matches(password,(String)accounts.getFirst().get("password_hash")))throw new org.demo.customerservice.customer.InvalidCredentialsException();
        var r=get(id,true);if(r.customer()!=p.customerId())throw error(HttpStatus.NOT_FOUND,"Privacy request not found");
        if(r.confirmed()!=null)throw error(HttpStatus.CONFLICT,"Privacy request already confirmed");
        if(!r.hash().equals(planHash)||!r.hash().equals(PrivacyPlan.binding(r.id(),r.customer(),r.created(),r.key())))throw error(HttpStatus.CONFLICT,"Privacy plan changed");
        if(jdbc.queryForObject("SELECT preview_expires_at<=CURRENT_TIMESTAMP FROM account_privacy_requests WHERE id=:id",args,Boolean.class))throw error(HttpStatus.GONE,"Privacy preview expired");
        boolean totp=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM customer_totp_methods WHERE customer_id=:customer AND enabled=TRUE)",args,Boolean.class);
        args.put("user",CustomerWebAuthnUserEntityRepository.userId(p.customerId()).toBase64UrlString());
        boolean passkey=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM user_credentials WHERE user_entity_user_id=:user)",args,Boolean.class);
        if((totp||passkey)&&!(totp&&methods.contains("totp")||passkey&&methods.contains("webauthn")))throw error(HttpStatus.FORBIDDEN,"Sign in with the configured MFA method before confirmation");
        args.put("tokenHash",Access.hash(id+":"+capability));args.put("cleanupPolicy",PrivacyPlan.cleanupPolicyHash());args.put("commercePolicy",PrivacyPlan.commercePolicyHash());args.put("financePolicy",org.demo.platformsecurity.privacy.FinancePolicy.hash());
        jdbc.update("INSERT INTO account_privacy_fences(customer_id,request_id,state) VALUES(:customer,:id,'BLOCKED')",args);
        jdbc.update("UPDATE customers SET enabled=FALSE,verification_token_hash=NULL,verification_expires_at=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=:customer",args);
        jdbc.update("UPDATE refresh_tokens SET revoked_at=coalesce(revoked_at,CURRENT_TIMESTAMP) WHERE customer_id=:customer",args);
        jdbc.update("UPDATE account_privacy_requests SET confirmed_at=CURRENT_TIMESTAMP,status_expires_at=CURRENT_TIMESTAMP+INTERVAL '168 hours',status_token_hash=:tokenHash,phase='FENCING',account_cleanup_policy_hash=:cleanupPolicy,account_cleanup_pending=TRUE,commerce_cleanup_policy_hash=:commercePolicy,commerce_cleanup_pending=TRUE,finance_policy_hash=:financePolicy,finance_pending=TRUE,finance_state='PENDING' WHERE id=:id",args);
        var batch=PrivacyPlan.SCOPES.stream().map(scope->new MapSqlParameterSource().addValue("id",id).addValue("service",scope.service()).addValue("action",scope.action())).toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("INSERT INTO account_privacy_services(request_id,service,fence_state,data_action,retry_at) VALUES(:id,:service,'PENDING',:action,CURRENT_TIMESTAMP)",batch);
        jdbc.update("UPDATE account_privacy_services SET cleanup_state='PENDING' WHERE request_id=:id AND service IN (:services)",Map.of("id",id,"services",PrivacyPlan.ACCOUNT_SERVICES));
        jdbc.update("UPDATE account_privacy_services SET cleanup_state='PENDING' WHERE request_id=:id AND service IN (:services)",Map.of("id",id,"services",PrivacyPlan.COMMERCE_SERVICES));
        jdbc.update("UPDATE account_privacy_services SET cleanup_state='PENDING',data_action='KEEP_FINANCE_ERASE_COPIES' WHERE request_id=:id AND service IN ('payment-service','billing-service','notification-service')",Map.of("id",id));
        return get(id,false);
    });}
    public Request status(UUID id,String capability){
        if(capability==null||!capability.matches("[A-Za-z0-9_-]{43,128}"))throw error(HttpStatus.UNAUTHORIZED,"Privacy status proof required");
        var rows=jdbc.query("SELECT * FROM account_privacy_requests WHERE id=:id AND status_token_hash=:hash AND confirmed_at IS NOT NULL AND status_expires_at>CURRENT_TIMESTAMP",Map.of("id",id,"hash",Access.hash(id+":"+capability)),MAPPER);
        if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Privacy status not available");return rows.getFirst();
    }
    public Request operator(UUID id){return get(id,false);}
    public List<Map<String,Object>> services(UUID id){return jdbc.queryForList("SELECT service,fence_state,data_action,code,cleanup_state,cleanup_code FROM account_privacy_services WHERE request_id=:id ORDER BY service",Map.of("id",id));}
    public Request retry(UUID id){return tx.execute(status->{var r=get(id,true);if(r.confirmed()==null)throw error(HttpStatus.CONFLICT,"Privacy request not confirmed");
        if(jdbc.queryForObject("SELECT coalesce(run_until>CURRENT_TIMESTAMP,FALSE) FROM account_privacy_requests WHERE id=:id",Map.of("id",id),Boolean.class))throw error(HttpStatus.CONFLICT,"Privacy step in progress");
        jdbc.update("UPDATE account_privacy_services SET attempts=0,retry_at=CURRENT_TIMESTAMP,code=NULL,fence_state='PENDING' WHERE request_id=:id AND fence_state<>'FENCED'",Map.of("id",id));
        jdbc.update("UPDATE account_privacy_services SET cleanup_attempts=0,cleanup_retry_at=CURRENT_TIMESTAMP,cleanup_code=NULL,cleanup_state='PENDING' WHERE request_id=:id AND cleanup_state IN ('FAILED','WAITING_PROCESS','WAITING_FINANCE','WAITING_REVIEW')",Map.of("id",id));
        jdbc.update("UPDATE account_privacy_requests SET finance_pending=TRUE,finance_state='PENDING',finance_attempts=0,finance_retry_at=CURRENT_TIMESTAMP,finance_retry_generation=finance_retry_generation+1 WHERE id=:id AND finance_policy_hash=:policy AND finance_state<>'DONE'",Map.of("id",id,"policy",org.demo.platformsecurity.privacy.FinancePolicy.hash()));
        refresh(id);refreshAccountCleanup(id);refreshCommerceCleanup(id);return get(id,false);
    });}
    public Work claim(){return tx.execute(status->{
        var rows=jdbc.queryForList("SELECT id,customer_id FROM account_privacy_requests r WHERE phase='FENCING' AND (run_until IS NULL OR run_until<=CURRENT_TIMESTAMP) AND EXISTS(SELECT 1 FROM account_privacy_services s WHERE s.request_id=r.id AND s.fence_state<>'FENCED' AND s.attempts<8 AND s.retry_at<=CURRENT_TIMESTAMP) ORDER BY confirmed_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",Map.of());
        if(rows.isEmpty())return null;var row=rows.getFirst();UUID id=(UUID)row.get("id"),token=UUID.randomUUID();
        var args=new HashMap<String,Object>(Map.of("id",id,"token",token));
        String service=jdbc.queryForObject("SELECT service FROM account_privacy_services WHERE request_id=:id AND fence_state<>'FENCED' AND attempts<8 AND retry_at<=CURRENT_TIMESTAMP ORDER BY service LIMIT 1",args,String.class);args.put("service",service);
        jdbc.update("UPDATE account_privacy_services SET attempts=attempts+1 WHERE request_id=:id AND service=:service",args);
        jdbc.update("UPDATE account_privacy_requests SET run_token=:token,run_until=CURRENT_TIMESTAMP+INTERVAL '15 seconds' WHERE id=:id",args);
        return new Work(id,((Number)row.get("customer_id")).longValue(),service,token);
    });}
    public void finish(Work work,boolean success,String code){tx.executeWithoutResult(status->{
        get(work.request(),true);var args=Map.of("id",work.request(),"token",work.token(),"service",work.service(),"state",success?"FENCED":"FAILED","code",success?"FENCE_ACKNOWLEDGED":code);
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT run_token=:token AND run_until>CURRENT_TIMESTAMP FROM account_privacy_requests WHERE id=:id",args,Boolean.class)))return;
        jdbc.update("UPDATE account_privacy_services SET fence_state=:state,code=:code,retry_at=CURRENT_TIMESTAMP+make_interval(secs=>LEAST(300,attempts*5)) WHERE request_id=:id AND service=:service",args);
        jdbc.update("UPDATE account_privacy_requests SET run_token=NULL,run_until=NULL WHERE id=:id",args);refresh(work.request());
    });}
    private void refresh(UUID id){jdbc.update("UPDATE account_privacy_requests SET phase=CASE WHEN NOT EXISTS(SELECT 1 FROM account_privacy_services WHERE request_id=:id AND fence_state<>'FENCED') THEN 'WAITING_ADAPTERS' WHEN EXISTS(SELECT 1 FROM account_privacy_services WHERE request_id=:id AND fence_state<>'FENCED' AND attempts<8) THEN 'FENCING' ELSE 'NEEDS_ATTENTION' END WHERE id=:id",Map.of("id",id));}
    private void refreshAccountCleanup(UUID id){
        jdbc.update("UPDATE account_privacy_requests SET account_cleanup_pending=coalesce(account_cleanup_policy_hash=:policy,FALSE) AND EXISTS(SELECT 1 FROM account_privacy_services WHERE request_id=:id AND service IN (:services) AND cleanup_state IN ('PENDING','FAILED','RUNNING') AND cleanup_attempts<8) WHERE id=:id",Map.of("id",id,"policy",PrivacyPlan.cleanupPolicyHash(),"services",PrivacyPlan.ACCOUNT_SERVICES));
    }
    public Map<String,Object> accountCleanupStatus(UUID id){
        boolean reviewed=Boolean.TRUE.equals(jdbc.queryForObject("SELECT account_cleanup_policy_hash=:policy FROM account_privacy_requests WHERE id=:id",Map.of("id",id,"policy",PrivacyPlan.cleanupPolicyHash()),Boolean.class));
        boolean core=jdbc.queryForObject("SELECT count(*)=4 FROM account_privacy_services WHERE request_id=:id AND service IN ('customer-service','wishlist-service','recommendation-service','review-service') AND cleanup_state='DONE'",Map.of("id",id),Boolean.class);
        return Map.of("policyReviewed",reviewed,"coreComplete",core,"chatRequiresSeparateProof",true,"fullErasureComplete",false);
    }
    public Work claimAccountCleanup(){if(journal!=null&&!journal.configured())return null;return tx.execute(status->{
        var args=new HashMap<String,Object>(Map.of("policy",PrivacyPlan.cleanupPolicyHash(),"services",PrivacyPlan.ACCOUNT_SERVICES));
        var rows=jdbc.queryForList("""
            SELECT r.id,r.customer_id FROM account_privacy_requests r
            WHERE r.account_cleanup_pending AND %s r.phase='WAITING_ADAPTERS' AND r.account_cleanup_policy_hash=:policy
             AND (r.run_until IS NULL OR r.run_until<=CURRENT_TIMESTAMP)
             AND (SELECT count(*) FROM account_privacy_services WHERE request_id=r.id)=14
             AND NOT EXISTS(SELECT 1 FROM account_privacy_services WHERE request_id=r.id AND fence_state<>'FENCED')
             AND EXISTS(SELECT 1 FROM account_privacy_services s WHERE s.request_id=r.id AND s.service IN (:services)
              AND s.cleanup_state IN ('PENDING','FAILED','RUNNING') AND s.cleanup_attempts<8 AND s.cleanup_retry_at<=CURRENT_TIMESTAMP)
            ORDER BY r.confirmed_at,r.id LIMIT 1 FOR UPDATE OF r SKIP LOCKED
            """.formatted(journalCondition()),args);
        if(rows.isEmpty())return null;var row=rows.getFirst();UUID id=(UUID)row.get("id"),token=UUID.randomUUID();args.put("id",id);args.put("token",token);
        String service=jdbc.queryForObject("SELECT service FROM account_privacy_services WHERE request_id=:id AND service IN (:services) AND cleanup_state IN ('PENDING','FAILED','RUNNING') AND cleanup_attempts<8 AND cleanup_retry_at<=CURRENT_TIMESTAMP ORDER BY CASE WHEN service='customer-service' THEN 1 ELSE 0 END,service LIMIT 1",args,String.class);args.put("service",service);
        jdbc.update("UPDATE account_privacy_services SET cleanup_state='RUNNING',cleanup_attempts=cleanup_attempts+1 WHERE request_id=:id AND service=:service",args);
        jdbc.update("UPDATE account_privacy_requests SET run_token=:token,run_until=CURRENT_TIMESTAMP+INTERVAL '15 seconds' WHERE id=:id",args);
        return new Work(id,((Number)row.get("customer_id")).longValue(),service,token);
    });}
    public void finishAccountCleanup(Work work,org.demo.platformsecurity.privacy.AccountCleanupAdapter.Result result){tx.executeWithoutResult(status->{
        get(work.request(),true);var args=new HashMap<String,Object>(Map.of("id",work.request(),"token",work.token(),"service",work.service()));
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT run_token=:token AND run_until>CURRENT_TIMESTAMP FROM account_privacy_requests WHERE id=:id",args,Boolean.class)))return;
        boolean more=result!=null&&result.outcome()==org.demo.platformsecurity.privacy.AccountCleanupAdapter.Outcome.MORE;
        args.put("state",result==null?"FAILED":more?"PENDING":result.outcome().name());args.put("code",result==null?"CLEANUP_UNAVAILABLE":result.code());args.put("more",more);
        jdbc.update("UPDATE account_privacy_services SET cleanup_state=:state,cleanup_code=CASE WHEN :state='FAILED' AND cleanup_attempts>=8 THEN 'RETRY_LIMIT' ELSE :code END,cleanup_retry_at=CURRENT_TIMESTAMP+make_interval(secs=>CASE WHEN :more THEN 1 ELSE LEAST(300,cleanup_attempts*5) END),cleanup_attempts=CASE WHEN :more THEN 0 ELSE cleanup_attempts END WHERE request_id=:id AND service=:service AND cleanup_state='RUNNING'",args);
        jdbc.update("UPDATE account_privacy_requests SET run_token=NULL,run_until=NULL WHERE id=:id",args);refreshAccountCleanup(work.request());
    });}
    public void cleanup(){tx.executeWithoutResult(status->{
        jdbc.update("UPDATE account_privacy_requests SET status_token_hash=NULL WHERE id IN (SELECT id FROM account_privacy_requests WHERE status_expires_at<=CURRENT_TIMESTAMP AND status_token_hash IS NOT NULL LIMIT 25 FOR UPDATE SKIP LOCKED)",Map.of());
        jdbc.update("DELETE FROM account_privacy_requests WHERE id IN (SELECT id FROM account_privacy_requests WHERE confirmed_at IS NULL AND preview_expires_at<=CURRENT_TIMESTAMP LIMIT 25 FOR UPDATE SKIP LOCKED)",Map.of());
        // A worker may die after its last allowed claim. Exhaustion must still become visible.
        for(UUID id:jdbc.queryForList("SELECT id FROM account_privacy_requests WHERE phase='FENCING' AND run_until<=CURRENT_TIMESTAMP ORDER BY run_until LIMIT 25 FOR UPDATE SKIP LOCKED",Map.of(),UUID.class)){
            jdbc.update("UPDATE account_privacy_services SET fence_state='FAILED',code='RETRY_LIMIT' WHERE request_id=:id AND fence_state<>'FENCED' AND attempts>=8",Map.of("id",id));
            jdbc.update("UPDATE account_privacy_requests SET run_token=NULL,run_until=NULL WHERE id=:id",Map.of("id",id));refresh(id);}
        for(UUID id:jdbc.queryForList("SELECT id FROM account_privacy_requests WHERE phase='WAITING_ADAPTERS' AND run_until<=CURRENT_TIMESTAMP ORDER BY run_until LIMIT 25 FOR UPDATE SKIP LOCKED",Map.of(),UUID.class)){
            jdbc.update("UPDATE account_privacy_services SET cleanup_state='FAILED',cleanup_code=CASE WHEN cleanup_attempts>=8 THEN 'RETRY_LIMIT' ELSE 'INTERRUPTED' END WHERE request_id=:id AND cleanup_state='RUNNING'",Map.of("id",id));
            jdbc.update("UPDATE account_privacy_requests SET run_token=NULL,run_until=NULL WHERE id=:id",Map.of("id",id));refreshAccountCleanup(id);refreshCommerceCleanup(id);
            jdbc.update("UPDATE account_privacy_requests SET finance_state='FAILED',finance_pending=finance_attempts<8,finance_code=CASE WHEN finance_attempts>=8 THEN 'RETRY_LIMIT' ELSE 'INTERRUPTED' END,finance_retry_at=CURRENT_TIMESTAMP+INTERVAL '5 seconds' WHERE id=:id AND finance_state='RUNNING'",Map.of("id",id));}
    });}

    private void refreshCommerceCleanup(UUID id){
        jdbc.update("UPDATE account_privacy_requests SET commerce_cleanup_pending=coalesce(commerce_cleanup_policy_hash=:policy,FALSE) AND EXISTS(SELECT 1 FROM account_privacy_services WHERE request_id=:id AND service IN (:services) AND cleanup_state IN ('PENDING','FAILED','RUNNING','WAITING_PROCESS','WAITING_FINANCE','WAITING_REVIEW') AND cleanup_attempts<8) WHERE id=:id",Map.of("id",id,"policy",PrivacyPlan.commercePolicyHash(),"services",PrivacyPlan.COMMERCE_SERVICES));
    }
    public Map<String,Object> commerceCleanupStatus(UUID id){
        var args=Map.of("id",id,"policy",PrivacyPlan.commercePolicyHash(),"services",PrivacyPlan.COMMERCE_SERVICES);
        boolean reviewed=Boolean.TRUE.equals(jdbc.queryForObject("SELECT commerce_cleanup_policy_hash=:policy FROM account_privacy_requests WHERE id=:id",args,Boolean.class));
        long done=jdbc.queryForObject("SELECT count(*) FROM account_privacy_services WHERE request_id=:id AND service IN (:services) AND cleanup_state='DONE'",args,Long.class);
        boolean finance=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM account_privacy_services WHERE request_id=:id AND service IN (:services) AND cleanup_state='WAITING_FINANCE')",args,Boolean.class);
        return Map.of("policyReviewed",reviewed,"completedScopes",done,"scopeCount",5,"financeHandoffPending",finance,"fullErasureComplete",false);
    }
    private static final String COMMERCE_READY="""
        s.service IN (:services) AND s.cleanup_state IN ('PENDING','FAILED','RUNNING','WAITING_PROCESS','WAITING_FINANCE','WAITING_REVIEW')
        AND s.cleanup_attempts<8 AND s.cleanup_retry_at<=CURRENT_TIMESTAMP
        AND (s.service<>'order-service' OR (SELECT count(*) FROM account_privacy_services d WHERE d.request_id=s.request_id
          AND d.service IN ('inventory-service','promotion-service','delivery-service','returns-service') AND d.cleanup_state='DONE')=4)
        """;
    public CommerceWork claimCommerceCleanup(){if(journal!=null&&!journal.configured())return null;return tx.execute(status->{
        var args=new HashMap<String,Object>(Map.of("policy",PrivacyPlan.commercePolicyHash(),"services",PrivacyPlan.COMMERCE_SERVICES));
        var rows=jdbc.queryForList("SELECT r.id,r.customer_id FROM account_privacy_requests r WHERE r.commerce_cleanup_pending AND "+journalCondition()+" r.phase='WAITING_ADAPTERS' AND r.commerce_cleanup_policy_hash=:policy AND (r.run_until IS NULL OR r.run_until<=CURRENT_TIMESTAMP) AND (SELECT count(*) FROM account_privacy_services WHERE request_id=r.id)=14 AND NOT EXISTS(SELECT 1 FROM account_privacy_services WHERE request_id=r.id AND fence_state<>'FENCED') AND EXISTS(SELECT 1 FROM account_privacy_services s WHERE s.request_id=r.id AND "+COMMERCE_READY+") ORDER BY r.confirmed_at,r.id LIMIT 1 FOR UPDATE OF r SKIP LOCKED",args);
        if(rows.isEmpty())return null;var row=rows.getFirst();UUID id=(UUID)row.get("id"),token=UUID.randomUUID();args.put("id",id);args.put("token",token);
        var step=jdbc.queryForMap("SELECT s.service,s.cleanup_cursor FROM account_privacy_services s WHERE s.request_id=:id AND "+COMMERCE_READY+" ORDER BY s.service LIMIT 1",args);
        String service=(String)step.get("service");args.put("service",service);
        jdbc.update("UPDATE account_privacy_services SET cleanup_state='RUNNING',cleanup_attempts=cleanup_attempts+1 WHERE request_id=:id AND service=:service",args);
        jdbc.update("UPDATE account_privacy_requests SET run_token=:token,run_until=CURRENT_TIMESTAMP+INTERVAL '15 seconds' WHERE id=:id",args);
        return new CommerceWork(id,((Number)row.get("customer_id")).longValue(),service,token,((Number)step.get("cleanup_cursor")).longValue());
    });}
    public void finishCommerceCleanup(CommerceWork work,org.demo.platformsecurity.privacy.CommerceCleanupAdapter.Batch result){tx.executeWithoutResult(status->{
        get(work.request(),true);var args=new HashMap<String,Object>(Map.of("id",work.request(),"token",work.token(),"service",work.service(),"oldCursor",work.cursor()));
        if(!PrivacyPlan.COMMERCE_SERVICES.contains(work.service()))throw new IllegalArgumentException("Invalid commerce scope");
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT run_token=:token AND run_until>CURRENT_TIMESTAMP FROM account_privacy_requests WHERE id=:id",args,Boolean.class)))return;
        if(result!=null&&(result.nextCursor()<work.cursor()||!result.outcome().equals("MORE")&&result.nextCursor()!=work.cursor()))throw new IllegalArgumentException("Invalid commerce cursor");
        String state="FAILED",code="CLEANUP_UNAVAILABLE";boolean success=result!=null;int delay=5;
        if(success){code=result.code();state=switch(result.outcome()){
            case "END" -> "DONE";
            case "MORE" -> "PENDING";
            default -> code.equals("WAITING_A43_36")?"WAITING_FINANCE":code.endsWith("REVIEW_REQUIRED")?"WAITING_REVIEW":"WAITING_PROCESS";};
            delay=state.equals("PENDING")?1:state.equals("WAITING_PROCESS")?300:3600;
        }
        args.put("state",state);args.put("code",code);args.put("cursor",success?result.nextCursor():work.cursor());args.put("success",success);args.put("delay",delay);
        jdbc.update("UPDATE account_privacy_services SET cleanup_state=:state,cleanup_code=CASE WHEN NOT :success AND cleanup_attempts>=8 THEN 'RETRY_LIMIT' ELSE :code END,cleanup_cursor=:cursor,cleanup_retry_at=CURRENT_TIMESTAMP+make_interval(secs=>:delay),cleanup_attempts=CASE WHEN :success THEN 0 ELSE cleanup_attempts END WHERE request_id=:id AND service=:service AND cleanup_state='RUNNING' AND cleanup_cursor=:oldCursor",args);
        jdbc.update("UPDATE account_privacy_requests SET run_token=NULL,run_until=NULL WHERE id=:id",args);refreshCommerceCleanup(work.request());
    });}

    public record FinanceWork(UUID request,long customer,UUID token,long generation){}
    public record FinanceResult(String state,String code,long orders,long prepared){
        public FinanceResult{if(!Set.of("MORE","WAIT","PREPARED","FAILED").contains(state)||code==null||!code.matches("[A-Z0-9_]{1,60}")||orders<0||prepared<0||prepared>orders)throw new IllegalArgumentException("Invalid finance result");}
    }
    public FinanceWork claimFinance(){if(journal!=null&&!journal.configured())return null;return tx.execute(status->{
        var rows=jdbc.queryForList("SELECT r.id,r.customer_id,r.finance_retry_generation FROM account_privacy_requests r WHERE r.finance_pending AND "+journalCondition()+" r.finance_policy_hash=:policy AND r.phase='WAITING_ADAPTERS' AND r.finance_attempts<8 AND r.finance_retry_at<=CURRENT_TIMESTAMP AND (r.run_until IS NULL OR r.run_until<=CURRENT_TIMESTAMP) AND (SELECT count(*) FROM account_privacy_services WHERE request_id=r.id)=14 AND NOT EXISTS(SELECT 1 FROM account_privacy_services WHERE request_id=r.id AND fence_state<>'FENCED') ORDER BY r.finance_retry_at,r.id LIMIT 1 FOR UPDATE OF r SKIP LOCKED",Map.of("policy",org.demo.platformsecurity.privacy.FinancePolicy.hash()));
        if(rows.isEmpty())return null;var row=rows.getFirst();UUID id=(UUID)row.get("id"),token=UUID.randomUUID();
        jdbc.update("UPDATE account_privacy_requests SET finance_state='RUNNING',finance_attempts=finance_attempts+1,run_token=:token,run_until=CURRENT_TIMESTAMP+INTERVAL '15 seconds' WHERE id=:id",Map.of("id",id,"token",token));
        return new FinanceWork(id,((Number)row.get("customer_id")).longValue(),token,((Number)row.get("finance_retry_generation")).longValue());
    });}
    public void finishFinance(FinanceWork work,FinanceResult result){tx.executeWithoutResult(status->{
        get(work.request(),true);var args=new HashMap<String,Object>(Map.of("id",work.request(),"token",work.token()));
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT run_token=:token AND run_until>CURRENT_TIMESTAMP FROM account_privacy_requests WHERE id=:id",args,Boolean.class)))return;
        String state=result==null?"FAILED":result.state().equals("PREPARED")?"DONE":result.state().equals("MORE")?"PENDING":result.state();
        boolean healthy=result!=null&&!result.state().equals("FAILED");args.put("state",state);args.put("code",result==null?"FINANCE_PEER_UNAVAILABLE":result.code());args.put("healthy",healthy);args.put("backendFailed",result!=null&&result.state().equals("FAILED"));args.put("orders",result==null?0:result.orders());args.put("prepared",result==null?0:result.prepared());args.put("known",result!=null);
        jdbc.update("UPDATE account_privacy_requests SET finance_state=:state,finance_code=CASE WHEN NOT :healthy AND (finance_attempts>=8 OR :backendFailed) THEN 'RETRY_LIMIT' ELSE :code END,finance_pending=:state<>'DONE' AND NOT :backendFailed AND (:healthy OR finance_attempts<8),finance_attempts=CASE WHEN :healthy THEN 0 WHEN :backendFailed THEN 8 ELSE finance_attempts END,finance_retry_at=CURRENT_TIMESTAMP+make_interval(secs=>CASE WHEN :state='PENDING' THEN 1 WHEN :state='WAIT' THEN 30 ELSE 5 END),finance_orders=CASE WHEN :known THEN :orders ELSE finance_orders END,finance_prepared_orders=CASE WHEN :known THEN :prepared ELSE finance_prepared_orders END,run_token=NULL,run_until=NULL WHERE id=:id",args);
        if(state.equals("DONE"))jdbc.update("UPDATE account_privacy_services SET cleanup_state='PENDING',cleanup_retry_at=CURRENT_TIMESTAMP WHERE request_id=:id AND service IN ('order-service','inventory-service','promotion-service','delivery-service','returns-service') AND cleanup_state='WAITING_FINANCE' AND cleanup_code='WAITING_A43_36'",args);
        String serviceState=state.equals("DONE")?"DONE":state.equals("PENDING")?"PENDING":state.equals("FAILED")?"FAILED":"WAITING_FINANCE";args.put("serviceState",serviceState);refreshCommerceCleanup(work.request());
        jdbc.update("UPDATE account_privacy_services SET cleanup_state=:serviceState,cleanup_code=:code WHERE request_id=:id AND service IN ('payment-service','billing-service','notification-service')",args);
    });}
    public Map<String,Object> financeStatus(UUID id){
        var row=jdbc.queryForMap("SELECT finance_policy_hash,finance_state,finance_code,finance_orders,finance_prepared_orders FROM account_privacy_requests WHERE id=:id",Map.of("id",id));
        var result=new LinkedHashMap<String,Object>();result.put("policyReviewed",org.demo.platformsecurity.privacy.FinancePolicy.hash().equals(row.get("finance_policy_hash")));result.put("state",row.get("finance_state"));result.put("code",row.get("finance_code"));result.put("orders",row.get("finance_orders"));result.put("preparedOrders",row.get("finance_prepared_orders"));result.put("documentsRetained",true);result.put("unlinkedSecurityNotificationsRequireManualReview",true);result.put("chatRequiresSeparateProof",true);result.put("retentionPolicyPending","A43.40");result.put("fullErasureComplete",false);return result;
    }
    private String journalCondition(){return journal==null?"":"r.journal_receipt IS NOT NULL AND ";}
    public Map<String,Object> journalCapsule(UUID request){
        var row=jdbc.queryForMap("SELECT customer_id,confirmed_at,account_cleanup_policy_hash,commerce_cleanup_policy_hash,finance_policy_hash FROM account_privacy_requests WHERE id=:id AND confirmed_at IS NOT NULL",Map.of("id",request));
        var result=new TreeMap<String,Object>();result.put("schemaVersion",1);result.put("confirmedAt",((java.sql.Timestamp)row.get("confirmed_at")).toInstant().toString());result.put("requestId",request.toString());result.put("customerId",row.get("customer_id").toString());result.put("accountPolicyHash",row.get("account_cleanup_policy_hash"));result.put("commercePolicyHash",row.get("commerce_cleanup_policy_hash"));result.put("financePolicyHash",row.get("finance_policy_hash"));return result;
    }
    public Map<String,Object> journalPage(long after,int size){
        if(after<0||size<1||size>100)throw error(HttpStatus.BAD_REQUEST,"Invalid journal window");
        var ids=jdbc.queryForList("SELECT id FROM account_privacy_requests WHERE customer_id>:after AND confirmed_at IS NOT NULL ORDER BY customer_id LIMIT :size",Map.of("after",after,"size",size),UUID.class);
        var records=new ArrayList<Map<String,Object>>();long cursor=after;
        for(var id:ids){var capsule=journalCapsule(id);cursor=Long.parseLong(capsule.get("customerId").toString());records.add(Map.of("capsule",capsule,"acknowledged",jdbc.queryForObject("SELECT journal_receipt IS NOT NULL FROM account_privacy_requests WHERE id=:id",Map.of("id",id),Boolean.class)));}
        return Map.of("schemaVersion",1,"requests",records,"nextCustomerId",Long.toString(cursor),"end",ids.size()<size);
    }
    public void acknowledgeJournal(UUID request,String encoded){
        if(journal==null)throw error(HttpStatus.CONFLICT,"Journal verification unavailable");
        tx.executeWithoutResult(status->{var row=get(request,true);var entry=journal.verify(encoded,row.customer(),request,null);
            var json=tools.jackson.databind.json.JsonMapper.builder().build();
            if(!org.demo.platformsecurity.privacy.JournalReceiptVerifier.capsuleHash(json.valueToTree(journalCapsule(request))).equals(org.demo.platformsecurity.privacy.JournalReceiptVerifier.capsuleHash(entry.path("capsule"))))throw error(HttpStatus.CONFLICT,"Journal policy mismatch");
            jdbc.update("UPDATE account_privacy_requests SET journal_receipt=:receipt WHERE id=:id",Map.of("receipt",encoded,"id",request));
        });
    }
    public String journalHeader(UUID request){
        if(journal==null)return "";
        String encoded=jdbc.queryForObject("SELECT journal_receipt FROM account_privacy_requests WHERE id=:id",Map.of("id",request),String.class);
        var row=get(request,false);var entry=journal.verify(encoded,row.customer(),request,null);
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        if(!org.demo.platformsecurity.privacy.JournalReceiptVerifier.capsuleHash(json.valueToTree(journalCapsule(request))).equals(org.demo.platformsecurity.privacy.JournalReceiptVerifier.capsuleHash(entry.path("capsule"))))throw error(HttpStatus.CONFLICT,"Journal policy mismatch");
        return encoded;
    }
    public Map<String,Object> journalStatus(UUID request){return Map.of("required",true,"verificationConfigured",journal!=null&&journal.configured(),"acknowledged",Boolean.TRUE.equals(jdbc.queryForObject("SELECT journal_receipt IS NOT NULL FROM account_privacy_requests WHERE id=:id",Map.of("id",request),Boolean.class)));}
}
