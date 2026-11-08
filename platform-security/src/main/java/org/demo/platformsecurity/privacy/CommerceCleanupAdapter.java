package org.demo.platformsecurity.privacy;

import java.util.Set;
import java.util.UUID;

/** One order, or at most 100 text rows within it, per call. */
public interface CommerceCleanupAdapter {
    record Batch(long nextCursor,String outcome,String code){
        private static final Set<String> CODES=Set.of("MORE_ROWS","ORDER_REDACTED","RETURN_REDACTED","IDENTIFIERS_RETAINED","NO_MORE_ORDERS","WAITING_ORDER_PROCESS","ORDER_REVIEW_REQUIRED","WAITING_RESERVATION","WAITING_COUPON","WAITING_SHIPMENT","SHIPMENT_REVIEW_REQUIRED","WAITING_RETURN_PROCESS","RETURN_REVIEW_REQUIRED","WAITING_A43_36");
        public Batch{
            if(nextCursor<0||!Set.of("MORE","WAIT","END").contains(outcome)||!CODES.contains(code))throw new IllegalArgumentException("Invalid commerce result");
            boolean waiting=code.startsWith("WAITING_")||code.endsWith("REVIEW_REQUIRED");
            if(outcome.equals("WAIT")!=waiting||outcome.equals("END")!=code.equals("NO_MORE_ORDERS"))throw new IllegalArgumentException("Invalid commerce outcome");
        }
        public static Batch waitFor(long cursor,String code){return new Batch(cursor,"WAIT",code);}
        public static Batch end(long cursor){return new Batch(cursor,"END","NO_MORE_ORDERS");}
    }
    Batch cleanup(long customer,UUID request,long cursor);
}
