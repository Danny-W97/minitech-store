package org.demo.chatservice.privacy;

import org.demo.platformsecurity.Access;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.UUID;

@Repository
public class ChatPrivacyRequestStore {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    public ChatPrivacyRequestStore(DataSource source,PlatformTransactionManager manager){jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(2);tx=new TransactionTemplate(manager);tx.setTimeout(5);}
    private String proof(long conversation,String token){
        if(conversation<1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid conversation");
        if(token==null||!token.matches("[A-Za-z0-9_-]{43,128}"))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Conversation proof required");
        return Access.hash(token);
    }
    public ChatPrivacyRequestStatus get(long conversation,String token){
        String hash=proof(conversation,token);
        var rows=jdbc.query("""
            SELECT r.request_id,r.conversation_id,r.state,r.created_at
            FROM chat_privacy_requests r JOIN chat_messages c ON c.id=r.conversation_id
            WHERE r.conversation_id=? AND c.access_token_hash=?
            """,(rs,n)->new ChatPrivacyRequestStatus(1,rs.getObject("request_id",UUID.class),Long.toString(rs.getLong("conversation_id")),rs.getString("state"),rs.getTimestamp("created_at").toInstant(),false,false),conversation,hash);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Conversation request not available");
        return rows.getFirst();
    }
    public ChatPrivacyRequestStatus submit(long conversation,String token,UUID key){
        String hash=proof(conversation,token);
        if(key==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Idempotency key required");
        try{return tx.execute(status->{
            if(jdbc.queryForList("SELECT id FROM chat_messages WHERE id=? AND access_token_hash=? FOR UPDATE",Long.class,conversation,hash).isEmpty())
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Conversation request not available");
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM chat_privacy_requests WHERE request_id=? AND conversation_id<>?)",Boolean.class,key,conversation)))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Request key belongs to another conversation");
            jdbc.update("INSERT INTO chat_privacy_requests(conversation_id,request_id,state,created_at) VALUES(?,?,'MANUAL_REVIEW',CURRENT_TIMESTAMP) ON CONFLICT(conversation_id) DO NOTHING",conversation,key);
            return get(conversation,token);
        });}catch(DataIntegrityViolationException conflict){throw new ResponseStatusException(HttpStatus.CONFLICT,"Request key belongs to another conversation");}
    }
}
