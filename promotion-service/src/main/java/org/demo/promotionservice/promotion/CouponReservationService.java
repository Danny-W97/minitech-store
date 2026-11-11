package org.demo.promotionservice.promotion;

import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;

/** Retry an insertion race only after the failed transaction has rolled back. */
@Service
public class CouponReservationService {
    private final CouponReservationStateStore states;
    public CouponReservationService(CouponReservationStateStore states) { this.states=states; }
    public CouponReservationStateStore.Response reserve(Long id,CouponReservationStateStore.Request request) {
        try { return states.reserve(id,request); }
        catch(DataIntegrityViolationException race) { return states.reserve(id,request); }
    }
    public CouponReservationStateStore.Response release(Long id,String code) {
        try { return states.release(id,code); }
        catch(DataIntegrityViolationException race) { return states.release(id,code); }
    }
}
