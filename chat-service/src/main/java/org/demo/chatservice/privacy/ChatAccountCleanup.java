package org.demo.chatservice.privacy;

import org.demo.platformsecurity.privacy.*;
import org.springframework.stereotype.Component;
import java.util.UUID;

/** Account identity is not proof of ownership of any conversation. */
@Component
public class ChatAccountCleanup implements AccountCleanupAdapter {
    private final PrivacyFenceStore fences;
    public ChatAccountCleanup(PrivacyFenceStore fences){this.fences=fences;}
    public Result cleanup(long customer,UUID request){return fences.underFence(customer,request,jdbc->new Result(Outcome.SEPARATE_PROOF_REQUIRED,"SEPARATE_PROOF_REQUIRED"));}
}
