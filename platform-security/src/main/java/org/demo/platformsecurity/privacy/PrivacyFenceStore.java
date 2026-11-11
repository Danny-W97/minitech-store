package org.demo.platformsecurity.privacy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import javax.sql.DataSource;
import java.util.UUID;

public class PrivacyFenceStore {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    public PrivacyFenceStore(DataSource source,PlatformTransactionManager manager){jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(2);tx=new TransactionTemplate(manager);tx.setTimeout(5);}
    public void fence(long customer,UUID request) {
        if(customer<1||request==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid privacy fence");
        tx.executeWithoutResult(status->{
            jdbc.execute("SET LOCAL lock_timeout='1500ms'");
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)",(rs,n)->true,-customer);
            jdbc.update("INSERT INTO account_privacy_fences(customer_id,request_id,state) VALUES(?,?,'BLOCKED') ON CONFLICT(customer_id) DO NOTHING",customer,request);
            UUID stored=jdbc.queryForObject("SELECT request_id FROM account_privacy_fences WHERE customer_id=?",UUID.class,customer);
            if(!request.equals(stored))throw new ResponseStatusException(HttpStatus.CONFLICT,"Privacy fence conflict");
        });
    }
    public <T> T underFence(long customer,UUID request,java.util.function.Function<JdbcTemplate,T> batch){
        if(customer<1||request==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid privacy fence");
        return tx.execute(status->{
            jdbc.execute("SET LOCAL lock_timeout='1500ms'");
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)",(rs,n)->true,-customer);
            var rows=jdbc.queryForList("SELECT request_id FROM account_privacy_fences WHERE customer_id=? AND state='BLOCKED'",UUID.class,customer);
            if(rows.isEmpty()||!request.equals(rows.getFirst()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Matching privacy fence required");
            return batch.apply(jdbc);
        });
    }
}
