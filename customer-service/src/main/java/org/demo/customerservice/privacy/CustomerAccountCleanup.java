package org.demo.customerservice.privacy;

import org.demo.platformsecurity.privacy.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class CustomerAccountCleanup implements AccountCleanupAdapter {
    private final PrivacyFenceStore fences;private final StringRedisTemplate redis;
    public CustomerAccountCleanup(PrivacyFenceStore fences,StringRedisTemplate redis){this.fences=fences;this.redis=redis;}
    private void eligible(JdbcTemplate jdbc,long customer,UUID request){
        boolean ready=Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM account_privacy_requests r WHERE r.id=? AND r.customer_id=? AND r.confirmed_at IS NOT NULL
             AND r.account_cleanup_policy_hash=? AND r.phase='WAITING_ADAPTERS'
             AND (SELECT count(*) FROM account_privacy_services WHERE request_id=r.id)=14
             AND NOT EXISTS(SELECT 1 FROM account_privacy_services WHERE request_id=r.id AND fence_state<>'FENCED'))
            """,Boolean.class,request,customer,PrivacyPlan.cleanupPolicyHash()));
        if(!ready)throw new ResponseStatusException(HttpStatus.CONFLICT,"Reviewed policy and all service fences required");
    }
    public Result cleanup(long customer,UUID request){
        fences.underFence(customer,request,jdbc->{eligible(jdbc,customer,request);return true;});
        // Redis runs outside the SQL transaction. Missing keys are an idempotent success;
        // connection errors stop the batch before profile/credential deletion.
        if(redis.delete(List.of("customer:mfa:enrollment:"+customer,"customer:mfa:webauthn:registration:"+customer))==null)
            throw new IllegalStateException("Redis cleanup not acknowledged");
        return fences.underFence(customer,request,jdbc->{
            eligible(jdbc,customer,request);
            for(var step:CoreCleanupSql.customerSteps()){
                int count=jdbc.update(step.sql(),step.parameters(customer));
                if(count>0&&!step.terminal())return Result.more();
            }
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM customers WHERE id=?)",Boolean.class,customer)))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"Active profile cannot be removed");
            return new Result(Outcome.DONE,"CORE_REMOVED_AUTH_TTL");
        });
    }
}
