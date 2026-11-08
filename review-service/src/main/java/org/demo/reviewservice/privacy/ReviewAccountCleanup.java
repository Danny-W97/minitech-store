package org.demo.reviewservice.privacy;

import org.demo.platformsecurity.privacy.*;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class ReviewAccountCleanup implements AccountCleanupAdapter {
    private final PrivacyFenceStore fences;
    public ReviewAccountCleanup(PrivacyFenceStore fences){this.fences=fences;}
    public Result cleanup(long customer,UUID request){return fences.underFence(customer,request,jdbc->{
        // Key retention and removal of the entire personal row commit together.
        int count=jdbc.update(CoreCleanupSql.query("review"),customer);
        return count>0?Result.more():new Result(Outcome.DONE,"REVIEW_KEYS_RETAINED");
    });}
}
