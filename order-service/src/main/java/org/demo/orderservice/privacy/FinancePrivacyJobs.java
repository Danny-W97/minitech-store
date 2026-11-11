package org.demo.orderservice.privacy;

import org.demo.platformsecurity.privacy.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import javax.sql.DataSource;
import java.util.*;

@Component
public class FinancePrivacyJobs {
    public static final List<String> PEERS=List.of("returns-service","payment-service","inventory-service","promotion-service","delivery-service","billing-service","notification-service");
    public record Work(long customer,UUID request,long order,long version,int step,String cursor,UUID token){}
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final PrivacyFenceStore fences;
    public FinancePrivacyJobs(DataSource source,PlatformTransactionManager manager,PrivacyFenceStore fences){jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(2);tx=new TransactionTemplate(manager);tx.setTimeout(5);this.fences=fences;}
    public static void enqueue(JdbcTemplate jdbc,long customer,UUID request,long order,long version){
        jdbc.update("INSERT INTO finance_privacy_jobs(order_id,customer_id,request_id,order_version) SELECT ?,?,?,? WHERE EXISTS(SELECT 1 FROM finance_privacy_consents WHERE customer_id=? AND request_id=? AND policy_hash=?) ON CONFLICT(order_id) DO NOTHING",order,customer,request,version,customer,request,FinancePolicy.hash());
    }
    public void consent(long customer,UUID request,String policy,long generation){
        if(!FinancePolicy.hash().equals(policy)||generation<0)throw new ResponseStatusException(HttpStatus.CONFLICT,"Finance privacy plan changed");
        fences.underFence(customer,request,db->{
            var rows=db.queryForList("SELECT request_id,policy_hash,retry_generation FROM finance_privacy_consents WHERE customer_id=? FOR UPDATE",customer);
            if(rows.isEmpty())db.update("INSERT INTO finance_privacy_consents(customer_id,request_id,policy_hash,retry_generation) VALUES(?,?,?,?)",customer,request,policy,generation);
            else{
                var previous=rows.getFirst();if(!request.equals(previous.get("request_id"))||!policy.equals(previous.get("policy_hash")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Finance privacy identity conflict");
                if(generation>((Number)previous.get("retry_generation")).longValue()){
                    db.update("UPDATE finance_privacy_consents SET retry_generation=? WHERE customer_id=?",generation,customer);
                    db.update("UPDATE finance_privacy_jobs SET state='PENDING',attempts=0,retry_at=CURRENT_TIMESTAMP,code=NULL WHERE customer_id=? AND state IN ('FAILED','WAIT') AND (run_until IS NULL OR run_until<=CURRENT_TIMESTAMP)",customer);
                }
            }return true;
        });
    }
    public Work claim(){return tx.execute(status->{
        var rows=jdbc.queryForList("SELECT j.* FROM finance_privacy_jobs j JOIN finance_privacy_consents c ON c.customer_id=j.customer_id AND c.request_id=j.request_id AND c.policy_hash=? WHERE state IN ('PENDING','WAIT','FAILED','RUNNING') AND attempts<8 AND retry_at<=CURRENT_TIMESTAMP AND (run_until IS NULL OR run_until<=CURRENT_TIMESTAMP) ORDER BY retry_at,order_id LIMIT 1 FOR UPDATE OF j SKIP LOCKED",FinancePolicy.hash());
        if(rows.isEmpty())return null;var row=rows.getFirst();long order=((Number)row.get("order_id")).longValue();UUID token=UUID.randomUUID();
        jdbc.update("UPDATE finance_privacy_jobs SET state='RUNNING',attempts=attempts+1,run_token=?,run_until=CURRENT_TIMESTAMP+INTERVAL '15 seconds' WHERE order_id=?",token,order);
        return new Work(((Number)row.get("customer_id")).longValue(),(UUID)row.get("request_id"),order,((Number)row.get("order_version")).longValue(),((Number)row.get("step")).intValue(),row.get("cursor").toString(),token);
    });}
    private boolean owns(JdbcTemplate db,Work work){if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM finance_privacy_consents WHERE customer_id=? AND request_id=? AND policy_hash=?)",Boolean.class,work.customer(),work.request(),FinancePolicy.hash())))return false;return Boolean.TRUE.equals(db.queryForObject("SELECT run_token=? AND run_until>CURRENT_TIMESTAMP AND step=? AND order_version=? FROM finance_privacy_jobs WHERE order_id=? FOR UPDATE",Boolean.class,work.token(),work.step(),work.version(),work.order()));}
    public void finish(Work work,FinancePreparationAdapter.Result result){fences.underFence(work.customer(),work.request(),db->{
        if(!owns(db,work))return false;
        String state=result==null?"FAILED":result.state().equals("WAIT")?"WAIT":"PENDING",code=result==null?"FINANCE_PEER_UNAVAILABLE":result.code();
        int step=work.step();String cursor=result==null?work.cursor():result.cursor();
        if(result!=null&&result.state().equals("PREPARED")){
            db.update("INSERT INTO finance_privacy_receipts(order_id,service,request_id,order_version) VALUES(?,?,?,?) ON CONFLICT(order_id,service) DO NOTHING",work.order(),PEERS.get(step),work.request(),work.version());step++;cursor="0-0";
        }
        db.update("UPDATE finance_privacy_jobs SET state=?,code=CASE WHEN ?='FAILED' AND attempts>=8 THEN 'RETRY_LIMIT' ELSE ? END,step=?,cursor=?,attempts=CASE WHEN ? THEN 0 ELSE attempts END,retry_at=CURRENT_TIMESTAMP+make_interval(secs=>?),run_token=NULL,run_until=NULL WHERE order_id=?",state,state,code,step,cursor,result!=null,state.equals("WAIT")?30:1,work.order());return true;
    });}
    public void seal(Work work){fences.underFence(work.customer(),work.request(),db->{
        if(!owns(db,work))return false;CommerceLocks.lock(db,work.order());
        var current=OrderPrivacySource.describe(db,work.customer(),work.order(),false);
        int count=db.queryForObject("SELECT count(*) FROM finance_privacy_receipts WHERE order_id=? AND request_id=? AND order_version=? AND service IN ('returns-service','payment-service','inventory-service','promotion-service','delivery-service','billing-service','notification-service')",Integer.class,work.order(),work.request(),work.version());
        if(work.step()!=7||count!=7||current.orderVersion()!=work.version()||!current.eligibility().equals("ELIGIBLE")){
            db.update("UPDATE finance_privacy_jobs SET state='REVIEW',code='ORDER_VERSION_REVIEW_REQUIRED',run_token=NULL,run_until=NULL WHERE order_id=?",work.order());return false;
        }
        CommerceLocks.retain(db,work.order(),work.customer(),work.request());
        db.update("UPDATE finance_privacy_jobs SET state='READY',code='FINANCE_PREPARED',attempts=0,run_token=NULL,run_until=NULL WHERE order_id=?",work.order());return true;
    });}
    public void recover(){tx.executeWithoutResult(status->jdbc.update("UPDATE finance_privacy_jobs SET state='FAILED',code='RETRY_LIMIT',run_token=NULL,run_until=NULL WHERE order_id IN (SELECT order_id FROM finance_privacy_jobs WHERE state='RUNNING' AND attempts>=8 AND run_until<=CURRENT_TIMESTAMP LIMIT 25 FOR UPDATE SKIP LOCKED)"));}
    public Map<String,Object> progress(long customer,UUID request){return fences.underFence(customer,request,db->{
        // Independent bounded discovery avoids a circular wait with A43.35's finance gate.
        var consent=db.queryForList("SELECT scan_after FROM finance_privacy_consents WHERE customer_id=? AND request_id=? AND policy_hash=? FOR UPDATE",customer,request,FinancePolicy.hash());
        if(!consent.isEmpty()){
            long after=((Number)consent.getFirst().get("scan_after")).longValue();
            var ids=db.queryForList("SELECT id FROM orders WHERE customer_id=? AND id>? ORDER BY id LIMIT 1",Long.class,customer,after);
            if(ids.isEmpty())db.update("UPDATE finance_privacy_consents SET scan_after=0 WHERE customer_id=?",customer);
            else{
                long order=ids.getFirst();CommerceLocks.lock(db,order);
                var entry=OrderPrivacySource.describe(db,customer,order,false);
                if(entry.eligibility().equals("ELIGIBLE"))enqueue(db,customer,request,order,entry.orderVersion());
                db.update("UPDATE finance_privacy_consents SET scan_after=? WHERE customer_id=?",order,customer);
            }
        }
        long orders=db.queryForObject("SELECT count(*) FROM orders WHERE customer_id=?",Long.class,customer);
        long ready=db.queryForObject("SELECT count(*) FROM finance_privacy_jobs WHERE customer_id=? AND request_id=? AND state='READY'",Long.class,customer,request);
        boolean failed=Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM finance_privacy_jobs WHERE customer_id=? AND request_id=? AND state='FAILED' AND attempts>=8)",Boolean.class,customer,request));
        var codes=db.queryForList("SELECT DISTINCT code FROM finance_privacy_jobs WHERE customer_id=? AND request_id=? AND state<>'READY' AND code IS NOT NULL ORDER BY code LIMIT 8",String.class,customer,request);
        return Map.of("schemaVersion",1,"customerId",Long.toString(customer),"requestId",request,"state",failed?"FAILED":orders==ready?"PREPARED":"WAIT","code",failed?"RETRY_LIMIT":orders==ready?"FINANCE_PREPARED":codes.isEmpty()?"WAITING_COMMERCE_SCAN":codes.getFirst(),"orders",Long.toString(orders),"preparedOrders",Long.toString(ready),"fullErasureComplete",false);
    });}
    public void attachJournal(long customer,UUID request,String proof){fences.underFence(customer,request,db->{db.update("UPDATE finance_privacy_consents SET journal_receipt=? WHERE customer_id=? AND request_id=?",proof,customer,request);return true;});}
    public String journalHeader(Work work){return jdbc.queryForObject("SELECT journal_receipt FROM finance_privacy_consents WHERE customer_id=? AND request_id=?",String.class,work.customer(),work.request());}
}
