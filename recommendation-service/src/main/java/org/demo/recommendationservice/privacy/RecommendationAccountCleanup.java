package org.demo.recommendationservice.privacy;

import org.demo.platformsecurity.privacy.*;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class RecommendationAccountCleanup implements AccountCleanupAdapter {
    private final PrivacyFenceStore fences;
    public RecommendationAccountCleanup(PrivacyFenceStore fences){this.fences=fences;}
    public Result cleanup(long customer,UUID request){return fences.underFence(customer,request,jdbc->{
        int count=jdbc.update(CoreCleanupSql.query("recommendation"),customer);
        return count>0?Result.more():new Result(Outcome.DONE,"PRIVATE_ROWS_REMOVED");
    });}
}
