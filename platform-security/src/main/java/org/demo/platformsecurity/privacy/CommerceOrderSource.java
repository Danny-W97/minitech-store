package org.demo.platformsecurity.privacy;

import java.util.Optional;
import java.util.UUID;

public interface CommerceOrderSource {
    record Entry(long orderId,String eligibility,boolean financeCleared,String status,long reservationId,boolean shipmentExpected,boolean couponExpected,long orderVersion,java.math.BigDecimal amount,String paymentState,boolean invoiceRequested){
        public Entry(long orderId,String eligibility,boolean financeCleared,String status,long reservationId,boolean shipmentExpected,boolean couponExpected){this(orderId,eligibility,financeCleared,status,reservationId,shipmentExpected,couponExpected,0,java.math.BigDecimal.ZERO,null,false);}
        public Entry{if(orderId<1||reservationId<0||orderVersion<0||amount==null||amount.signum()<0||amount.precision()>19||Math.abs((long)amount.scale())>2||status==null||status.length()>30||!java.util.Set.of("ELIGIBLE","WAITING_ORDER_PROCESS","ORDER_REVIEW_REQUIRED").contains(eligibility))throw new IllegalArgumentException("Invalid order privacy proof");}
    }
    Optional<Entry> next(long customer,UUID request,long after);
}
