package org.demo.customerservice.export.jobs;

import org.demo.customerservice.auth.CustomerPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;

@Repository
public class ExportJobStore {
    public static final long MAX_BYTES=16777216;public static final int MAX_TASKS=4096;
    private final NamedParameterJdbcTemplate jdbc;private final TransactionTemplate tx;
    public record Job(UUID id,long customerId,String state,Instant createdAt,Instant expiresAt,Instant updatedAt,String policyHash,long recordedBytes,int partCount,UUID runToken,Instant runUntil,boolean expired,boolean busy){}
    public record Counts(long done,long pending,long failed){}
    public record Part(int sequence,String source,long parentId,long detailId,long cursor,String data,int bytes,String sha256,Instant startedAt,Instant finishedAt){}
    public ExportJobStore(DataSource source,PlatformTransactionManager manager){var template=new JdbcTemplate(source);template.setQueryTimeout(2);jdbc=new NamedParameterJdbcTemplate(template);tx=new TransactionTemplate(manager);tx.setTimeout(5);}
    private static Map<String,Object> owner(CustomerPrincipal p){if(p==null||p.customerId()==null||p.customerId()<1||p.email()==null||p.email().isBlank())throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Authentication required");return new HashMap<>(Map.of("customer",p.customerId(),"email",p.email()));}
    private static Instant instant(ResultSet rs,String name)throws SQLException{return rs.getTimestamp(name)==null?null:rs.getTimestamp(name).toInstant();}
    private static final String OWNED="""
            SELECT j.*,j.expires_at<=CURRENT_TIMESTAMP AS expired,coalesce(j.run_until>CURRENT_TIMESTAMP,FALSE) AS busy FROM account_export_jobs j
            WHERE j.id=:job AND j.customer_id=:customer AND EXISTS(SELECT 1 FROM customers c WHERE c.id=:customer AND c.email=:email AND c.enabled=TRUE)
            """;
    private Job owned(UUID id,CustomerPrincipal p,boolean lock){
        var args=owner(p);args.put("job",id);
        var rows=jdbc.query(OWNED+(lock?" FOR UPDATE OF j":""),args,(rs,n)->new Job(rs.getObject("id",UUID.class),rs.getLong("customer_id"),rs.getString("state"),instant(rs,"created_at"),instant(rs,"expires_at"),instant(rs,"updated_at"),rs.getString("policy_hash"),rs.getLong("recorded_bytes"),rs.getInt("part_count"),rs.getObject("run_token",UUID.class),instant(rs,"run_until"),rs.getBoolean("expired"),rs.getBoolean("busy")));
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Export not found");var job=rows.getFirst();
        if(job.expired())throw new ResponseStatusException(HttpStatus.GONE,"Export expired");return job;
    }
    public Job get(UUID id,CustomerPrincipal p){return owned(id,p,false);}
    public List<UUID> recent(CustomerPrincipal p){
        return jdbc.queryForList("SELECT j.id FROM account_export_jobs j WHERE j.customer_id=:customer AND j.expires_at>CURRENT_TIMESTAMP AND EXISTS(SELECT 1 FROM customers c WHERE c.id=:customer AND c.email=:email AND c.enabled=TRUE) ORDER BY j.created_at DESC,j.id LIMIT 3",owner(p),UUID.class);
    }
    public Job create(CustomerPrincipal p,UUID request,String policyHash){return tx.execute(status->{
        var args=owner(p);args.put("request",request);
        if(jdbc.queryForList("SELECT id FROM customers WHERE id=:customer AND email=:email AND enabled=TRUE FOR UPDATE",args).isEmpty())throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Authentication required");
        var previous=jdbc.queryForList("SELECT id FROM account_export_jobs WHERE customer_id=:customer AND request_id=:request",args,UUID.class);
        if(!previous.isEmpty())return owned(previous.getFirst(),p,false);
        jdbc.update("UPDATE account_export_jobs SET state='EXPIRED',run_token=NULL,run_until=NULL WHERE customer_id=:customer AND expires_at<=CURRENT_TIMESTAMP AND state IN ('ACTIVE','PARTIAL')",args);
        if(jdbc.queryForObject("SELECT count(*) FROM account_export_jobs WHERE customer_id=:customer AND expires_at>CURRENT_TIMESTAMP AND state IN ('ACTIVE','PARTIAL')",args,Long.class)>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"An unfinished export already exists");
        if(jdbc.queryForObject("SELECT count(*) FROM account_export_jobs WHERE customer_id=:customer AND created_at>CURRENT_TIMESTAMP-INTERVAL '1 hour'",args,Long.class)>=3)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Export creation limit reached");
        UUID id=UUID.randomUUID();args.put("job",id);args.put("policy",policyHash);
        jdbc.update("INSERT INTO account_export_jobs(id,customer_id,request_id,state,created_at,expires_at,updated_at,policy_hash) VALUES(:job,:customer,:request,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+INTERVAL '1 hour',CURRENT_TIMESTAMP,:policy)",args);
        plans(id,List.of(ExportKind.PROFILE,ExportKind.METHODS,ExportKind.EVENTS,ExportKind.PASSKEYS,ExportKind.RECOVERY,ExportKind.WISHLIST,ExportKind.REVIEWS,ExportKind.RECOMMENDATIONS,ExportKind.ORDERS,ExportKind.RETURNS).stream().map(k->new ExportTask.Plan(k,0,0,0)).toList());
        return owned(id,p,false);
    });}
    public ExportTask claim(UUID id,CustomerPrincipal p,UUID request){return tx.execute(status->{
        var job=owned(id,p,true);if(!Set.of("ACTIVE","PARTIAL").contains(job.state()))return null;
        var args=new HashMap<String,Object>(Map.of("job",id,"request",request));
        if(job.busy())throw new ResponseStatusException(HttpStatus.CONFLICT,"Export step in progress");
        jdbc.update("UPDATE account_export_tasks SET state='FAILED',error_code='RETRY_LIMIT' WHERE job_id=:job AND state IN ('PENDING','RUNNING') AND attempts>=8",args);
        var replay=jdbc.queryForList("SELECT id,state FROM account_export_tasks WHERE job_id=:job AND CAST(:request AS UUID)=ANY(request_ids)",args);
        UUID selected;
        if(!replay.isEmpty()){
            if(!replay.getFirst().get("state").equals("RUNNING")){refresh(id);return null;}
            selected=(UUID)replay.getFirst().get("id");
        }else{
            var candidates=jdbc.queryForList("SELECT id FROM account_export_tasks WHERE job_id=:job AND state IN ('PENDING','RUNNING') AND attempts<8 ORDER BY CASE WHEN kind='PROFILE' THEN 0 WHEN parent_id=0 AND cursor_value=0 THEN 1 ELSE 2 END,id LIMIT 1",args,UUID.class);
            if(candidates.isEmpty()){refresh(id);return null;}selected=candidates.getFirst();
        }
        args.put("task",selected);UUID token=UUID.randomUUID();args.put("token",token);
        int changed=jdbc.update("""
                UPDATE account_export_tasks SET state='RUNNING',attempts=attempts+1,error_code=NULL,
                  request_ids=CASE WHEN CAST(:request AS UUID)=ANY(request_ids) THEN request_ids ELSE array_append(request_ids,CAST(:request AS UUID)) END
                WHERE id=:task AND attempts<8
                """,args);
        if(changed!=1){jdbc.update("UPDATE account_export_tasks SET state='FAILED',error_code='RETRY_LIMIT' WHERE id=:task",args);refresh(id);return null;}
        jdbc.update("UPDATE account_export_jobs SET run_token=:token,run_until=CURRENT_TIMESTAMP+INTERVAL '20 seconds',updated_at=CURRENT_TIMESTAMP WHERE id=:job",args);
        return jdbc.queryForObject("SELECT id,kind,parent_id,detail_id,cursor_value FROM account_export_tasks WHERE id=:task",args,
                (rs,n)->new ExportTask(rs.getObject("id",UUID.class),id,ExportKind.valueOf(rs.getString("kind")),rs.getLong("parent_id"),rs.getLong("detail_id"),rs.getLong("cursor_value"),token));
    });}
    public void commit(ExportTask task,CustomerPrincipal p,ExportTask.Result result,List<ExportTask.Plan> children,ExportJson json){tx.executeWithoutResult(status->{
        var job=owned(task.jobId(),p,true);if(!live(job,task))return;
        int bytes=json.bytes(result.data());if(bytes>ExportSourceClient.MAX_PART_BYTES||job.recordedBytes()+bytes>MAX_BYTES)throw new ExportFailure("JOB_LIMIT");
        plans(task.jobId(),children);
        if(jdbc.queryForObject("SELECT count(*) FROM account_export_tasks WHERE job_id=:job",Map.of("job",task.jobId()),Long.class)>MAX_TASKS)throw new ExportFailure("JOB_LIMIT");
        var args=new MapSqlParameterSource().addValue("job",task.jobId()).addValue("sequence",job.partCount()+1).addValue("task",task.id()).addValue("data",result.data()).addValue("bytes",bytes)
                .addValue("hash",org.demo.platformsecurity.Access.hash(result.data())).addValue("started",Timestamp.from(result.started())).addValue("finished",Timestamp.from(result.finished()));
        jdbc.update("INSERT INTO account_export_chunks(job_id,sequence_no,task_id,data_json,byte_count,sha256,read_started_at,read_finished_at) VALUES(:job,:sequence,:task,:data,:bytes,:hash,:started,:finished)",args);
        jdbc.update("UPDATE account_export_tasks SET state='DONE',error_code=NULL WHERE id=:task",args);
        jdbc.update("UPDATE account_export_jobs SET part_count=part_count+1,recorded_bytes=recorded_bytes+:bytes,run_token=NULL,run_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=:job",args);
        refresh(task.jobId());
    });}
    public void fail(ExportTask task,CustomerPrincipal p,String code){tx.executeWithoutResult(status->{
        var job=owned(task.jobId(),p,true);if(!live(job,task))return;var args=Map.of("job",task.jobId(),"task",task.id(),"code",code);
        jdbc.update("UPDATE account_export_tasks SET state='FAILED',error_code=:code WHERE id=:task",args);
        jdbc.update("UPDATE account_export_jobs SET run_token=NULL,run_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=:job",args);
        if(code.equals("JOB_LIMIT")||code.equals("PART_LIMIT"))jdbc.update("UPDATE account_export_jobs SET state='LIMIT_REACHED' WHERE id=:job",args);else refresh(task.jobId());
    });}
    private static boolean live(Job job,ExportTask task){return Set.of("ACTIVE","PARTIAL").contains(job.state())&&job.busy()&&task.token().equals(job.runToken());}
    private void plans(UUID job,List<ExportTask.Plan> plans){
        if(plans.isEmpty())return;if(plans.size()>501)throw new ExportFailure("JOB_LIMIT");
        var args=plans.stream().map(p->new MapSqlParameterSource().addValue("id",UUID.randomUUID()).addValue("job",job).addValue("kind",p.kind().name()).addValue("parent",p.parent()).addValue("detail",p.detail()).addValue("cursor",p.cursor())).toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("INSERT INTO account_export_tasks(id,job_id,kind,parent_id,detail_id,cursor_value,state) VALUES(:id,:job,:kind,:parent,:detail,:cursor,'PENDING') ON CONFLICT(job_id,kind,parent_id,detail_id,cursor_value) DO NOTHING",args);
    }
    public Counts counts(UUID id){return jdbc.queryForObject("SELECT count(*) FILTER(WHERE state='DONE') AS done,count(*) FILTER(WHERE state IN ('PENDING','RUNNING')) AS pending,count(*) FILTER(WHERE state='FAILED') AS failed FROM account_export_tasks WHERE job_id=:job",Map.of("job",id),(rs,n)->new Counts(rs.getLong("done"),rs.getLong("pending"),rs.getLong("failed")));}
    private void refresh(UUID id){var counts=counts(id);jdbc.update("UPDATE account_export_jobs SET state=:state,updated_at=CURRENT_TIMESTAMP WHERE id=:job",Map.of("job",id,"state",counts.pending()>0?"ACTIVE":counts.failed()>0?"PARTIAL":"READY"));}
    public Job retry(UUID id,CustomerPrincipal p){return tx.execute(status->{
        var job=owned(id,p,true);if(!Set.of("ACTIVE","PARTIAL").contains(job.state()))return job;
        if(job.busy())throw new ResponseStatusException(HttpStatus.CONFLICT,"Export step in progress");
        jdbc.update("UPDATE account_export_tasks SET state='PENDING',error_code=NULL WHERE job_id=:job AND state='FAILED' AND attempts<8",Map.of("job",id));refresh(id);return owned(id,p,false);
    });}
    public Job cancel(UUID id,CustomerPrincipal p){return tx.execute(status->{
        var job=owned(id,p,true);jdbc.update("UPDATE account_export_jobs SET state='CANCELLED',run_token=NULL,run_until=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=:job",Map.of("job",id));return owned(id,p,false);
    });}
    public List<Part> parts(UUID id,CustomerPrincipal p,int after,int size){
        if(after<0||size<1||size>3)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"after must be nonnegative; size must be 1..3");
        var job=owned(id,p,false);if(job.state().equals("CANCELLED"))throw new ResponseStatusException(HttpStatus.GONE,"Export cancelled");
        var args=owner(p);args.putAll(Map.of("job",id,"after",after,"limit",size));
        return jdbc.query("""
                SELECT c.*,t.kind,t.parent_id,t.detail_id,t.cursor_value FROM account_export_chunks c
                JOIN account_export_jobs j ON j.id=c.job_id JOIN account_export_tasks t ON t.id=c.task_id
                WHERE j.id=:job AND j.customer_id=:customer AND j.state NOT IN ('CANCELLED','EXPIRED') AND j.expires_at>CURRENT_TIMESTAMP
                  AND EXISTS(SELECT 1 FROM customers u WHERE u.id=:customer AND u.email=:email AND u.enabled=TRUE)
                  AND c.sequence_no>:after ORDER BY c.sequence_no LIMIT :limit
                """,args,(rs,n)->new Part(rs.getInt("sequence_no"),rs.getString("kind"),rs.getLong("parent_id"),rs.getLong("detail_id"),rs.getLong("cursor_value"),rs.getString("data_json"),rs.getInt("byte_count"),rs.getString("sha256"),instant(rs,"read_started_at"),instant(rs,"read_finished_at")));
    }
    public List<Map<String,Object>> taskSummary(UUID id){return jdbc.queryForList("SELECT kind,state,error_code,count(*) AS count FROM account_export_tasks WHERE job_id=:job GROUP BY kind,state,error_code ORDER BY kind,state",Map.of("job",id));}
    public int cleanup(){return tx.execute(status->{
        int changed=jdbc.update("""
                WITH eligible AS (SELECT id FROM account_export_jobs WHERE expires_at<=CURRENT_TIMESTAMP AND state<>'EXPIRED' ORDER BY expires_at,id LIMIT 25 FOR UPDATE SKIP LOCKED)
                UPDATE account_export_jobs j SET state='EXPIRED',run_token=NULL,run_until=NULL FROM eligible e WHERE j.id=e.id
                """,Map.of());
        changed+=jdbc.update("""
                DELETE FROM account_export_chunks WHERE (job_id,sequence_no) IN (
                 SELECT c.job_id,c.sequence_no FROM account_export_chunks c JOIN account_export_jobs j ON j.id=c.job_id
                 WHERE j.expires_at<=CURRENT_TIMESTAMP OR j.state='CANCELLED' ORDER BY c.job_id,c.sequence_no LIMIT 50 FOR UPDATE OF c SKIP LOCKED)
                """,Map.of());
        changed+=jdbc.update("""
                DELETE FROM account_export_tasks WHERE id IN (
                 SELECT t.id FROM account_export_tasks t JOIN account_export_jobs j ON j.id=t.job_id
                 WHERE (j.expires_at<=CURRENT_TIMESTAMP OR j.state='CANCELLED') AND NOT EXISTS(SELECT 1 FROM account_export_chunks c WHERE c.task_id=t.id)
                 ORDER BY t.id LIMIT 100 FOR UPDATE OF t SKIP LOCKED)
                """,Map.of());
        changed+=jdbc.update("""
                DELETE FROM account_export_jobs WHERE id IN (
                 SELECT j.id FROM account_export_jobs j WHERE j.expires_at<=CURRENT_TIMESTAMP
                 AND NOT EXISTS(SELECT 1 FROM account_export_tasks t WHERE t.job_id=j.id)
                 AND NOT EXISTS(SELECT 1 FROM account_export_chunks c WHERE c.job_id=j.id)
                 ORDER BY j.expires_at,j.id LIMIT 25 FOR UPDATE SKIP LOCKED)
                """,Map.of());
        return changed;
    });}
}
