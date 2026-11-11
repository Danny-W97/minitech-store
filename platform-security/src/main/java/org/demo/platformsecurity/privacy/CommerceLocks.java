package org.demo.platformsecurity.privacy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

/** Called inside the existing short local fence transaction, never around HTTP. */
public final class CommerceLocks {
    private CommerceLocks(){}
    public static void lock(JdbcTemplate jdbc,long order){if(order<1)throw new IllegalArgumentException("Invalid order");jdbc.queryForObject("SELECT pg_advisory_xact_lock(435,hashint8(CAST(? AS BIGINT)))",(rs,n)->true,order);}
    public static boolean retained(JdbcTemplate jdbc,long order,long customer,UUID request){
        var rows=jdbc.queryForList("SELECT customer_id,request_id FROM commerce_privacy_locks WHERE order_id=?",order);
        if(rows.isEmpty())return false;var row=rows.getFirst();
        if(((Number)row.get("customer_id")).longValue()!=customer||!request.equals(row.get("request_id")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Commerce privacy identity conflict");
        return true;
    }
    public static void retain(JdbcTemplate jdbc,long order,long customer,UUID request){
        if(!retained(jdbc,order,customer,request))jdbc.update("INSERT INTO commerce_privacy_locks(order_id,customer_id,request_id) VALUES(?,?,?)",order,customer,request);
    }
}
