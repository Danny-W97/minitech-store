package org.demo.wishlistservice.privacy;

import org.demo.platformsecurity.privacy.*;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class WishlistAccountCleanup implements AccountCleanupAdapter {
    private final PrivacyFenceStore fences;
    public WishlistAccountCleanup(PrivacyFenceStore fences){this.fences=fences;}
    public Result cleanup(long customer,UUID request){return fences.underFence(customer,request,jdbc->{
        int count=jdbc.update(CoreCleanupSql.query("wishlist"),customer);
        return count>0?Result.more():new Result(Outcome.DONE,"PRIVATE_ROWS_REMOVED");
    });}
}
