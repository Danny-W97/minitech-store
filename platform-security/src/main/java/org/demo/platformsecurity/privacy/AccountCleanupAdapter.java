package org.demo.platformsecurity.privacy;

import java.util.Set;
import java.util.UUID;

/** One bounded local batch, invoked only by the trusted account coordinator. */
public interface AccountCleanupAdapter {
    enum Outcome { MORE, DONE, SEPARATE_PROOF_REQUIRED }
    record Result(Outcome outcome,String code) {
        private static final Set<String> CODES=Set.of("MORE_ROWS","PRIVATE_ROWS_REMOVED","REVIEW_KEYS_RETAINED","CORE_REMOVED_AUTH_TTL","SEPARATE_PROOF_REQUIRED");
        public Result {if(outcome==null||!CODES.contains(code))throw new IllegalArgumentException("Invalid cleanup result");}
        public static Result more(){return new Result(Outcome.MORE,"MORE_ROWS");}
    }
    Result cleanup(long customer,UUID request);
}
