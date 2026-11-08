package org.demo.promotionservice.promotion;

import jakarta.validation.constraints.*;
import org.demo.promotionservice.web.PromotionRedemptionResponse;
import org.demo.platformsecurity.ListWindow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.*;
import java.util.Locale;
import java.util.Set;

@Service
public class CouponReservationStateStore {
    public record Request(@NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9_-]{2,39}") String code,
            @NotNull @DecimalMin("0.00") @Digits(integer=17,fraction=2) BigDecimal subtotal,
            @NotNull @DecimalMin("0.00") @Digits(integer=17,fraction=2) BigDecimal discountAmount,
            @NotNull Instant acceptedAt,Instant expiresAt) {}
    @io.swagger.v3.oas.annotations.media.Schema(name="CouponReservationResponse")
    public record Response(Long orderId,String code,BigDecimal subtotal,BigDecimal discountAmount,String state,Instant acceptedAt,Instant expiresAt) {
        static Response from(CouponReservation row) { return new Response(row.getOrderId(),row.getCode(),row.getSubtotal(),row.getDiscountAmount(),row.getState(),row.getAcceptedAt(),row.getExpiresAt()); }
    }
    @io.swagger.v3.oas.annotations.media.Schema(name="CouponCapacityResponse")
    public record Capacity(String code,int redeemedCount,long reservedCount,Integer maxRedemptions,Long availableUses) {}
    private final PromotionCodeRepository codes;
    private final CouponReservationRepository reservations;
    private final PromotionRedemptionRepository redemptions;
    public CouponReservationStateStore(PromotionCodeRepository codes,CouponReservationRepository reservations,PromotionRedemptionRepository redemptions) {
        this.codes=codes;this.reservations=reservations;this.redemptions=redemptions;
    }
    @Transactional public Response reserve(Long id,Request request) {
        String name=normalize(request.code());var code=codes.lockCode(name).orElseThrow(() -> conflict("COUPON_NOT_FOUND"));
        var previous=reservations.lockId(id).orElse(null);
        if(previous!=null) {
            if(!name.equals(previous.getCode()) || !Set.of("RESERVED","CONSUMED").contains(previous.getState())
                    || request.subtotal().compareTo(previous.getSubtotal())!=0 || request.discountAmount().compareTo(previous.getDiscountAmount())!=0)
                throw conflict("COUPON_RESERVATION_CONFLICT");
            return Response.from(previous);
        }
        boolean consumed=redemptions.existsByCodeIgnoreCaseAndOrderId(name,id);
        var date=request.acceptedAt().atZone(ZoneOffset.UTC).toLocalDate();
        if(!consumed && (!code.isActive() || date.isBefore(code.getValidFrom()) || date.isAfter(code.getValidTo())
                || request.acceptedAt().isAfter(Instant.now().plusSeconds(5)) || request.expiresAt()!=null && !request.expiresAt().isAfter(Instant.now())
                || request.subtotal().compareTo(code.getMinimumOrderValue())<0 || CouponPricing.discount(code,request.subtotal()).compareTo(request.discountAmount())!=0))
            throw conflict("COUPON_QUOTE_CHANGED_OR_EXPIRED");
        if(!consumed && code.getMaxRedemptions()!=null && (long)code.getRedeemedCount()+reservations.countByCodeAndState(name,"RESERVED")>=code.getMaxRedemptions())
            throw conflict("COUPON_CAPACITY_EXHAUSTED");
        var row=new CouponReservation(id,name,request.subtotal(),request.discountAmount(),consumed?"CONSUMED":"RESERVED",request.acceptedAt(),request.expiresAt());
        return Response.from(reservations.saveAndFlush(row));
    }
    @Transactional public Response release(Long id,String requestedCode) {
        String name=normalize(requestedCode);codes.lockCode(name);
        var row=reservations.lockId(id).orElse(null);
        if(row!=null && !name.equals(row.getCode())) throw conflict("COUPON_RESERVATION_CONFLICT");
        if(redemptions.existsByCodeIgnoreCaseAndOrderId(name,id) || row!=null && "CONSUMED".equals(row.getState()))
            throw conflict("CONSUMED_COUPON_REQUIRES_RECONCILIATION");
        if(row==null) row=new CouponReservation(id,name,BigDecimal.ZERO,BigDecimal.ZERO,"RELEASED",null,null);
        else row.release();
        return Response.from(reservations.saveAndFlush(row));
    }
    @Transactional public PromotionRedemptionResponse redeem(String requestedCode,Long id) {
        String name=normalize(requestedCode);var code=codes.lockCode(name).orElseThrow(() -> conflict("COUPON_NOT_FOUND"));
        if(redemptions.existsByCodeIgnoreCaseAndOrderId(name,id)) return redeemed(code,id);
        var hold=reservations.lockId(id).orElseThrow(() -> conflict("COUPON_RESERVATION_REQUIRED"));
        if(!name.equals(hold.getCode()) || !"RESERVED".equals(hold.getState())) throw conflict("COUPON_RESERVATION_CONFLICT");
        if(codes.incrementRedeemedCountIfAvailable(name)!=1) throw conflict("COUPON_COUNTER_REQUIRES_RECONCILIATION");
        code.incrementRedeemedCount();hold.consume();redemptions.saveAndFlush(new PromotionRedemption(name,id));
        return redeemed(code,id);
    }
    @Transactional(readOnly=true) public boolean alreadyRedeemed(String code,Long id) { return redemptions.existsByCodeIgnoreCaseAndOrderId(normalize(code),id); }
    @Transactional(readOnly=true) public Response get(Long id) {
        return reservations.findById(id).map(Response::from).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"COUPON_RESERVATION_REQUIRED"));
    }
    @Transactional public Capacity capacity(String requestedCode) {
        var code=codes.lockCode(normalize(requestedCode)).orElseThrow(() -> conflict("COUPON_NOT_FOUND"));
        long reserved=reservations.countByCodeAndState(code.getCode(),"RESERVED");
        return new Capacity(code.getCode(),code.getRedeemedCount(),reserved,code.getMaxRedemptions(),
                code.getMaxRedemptions()==null ? null : (long)code.getMaxRedemptions()-code.getRedeemedCount()-reserved);
    }
    @Transactional(readOnly=true) public ListWindow.ListPage<Response> page(String code,String state,ListWindow window) {
        if(state!=null && !Set.of("RESERVED","CONSUMED","RELEASED").contains(state)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown coupon reservation state");
        return window.map(reservations.page(window.ceiling(),code==null?null:normalize(code),state,window.fetch()),CouponReservation::getOrderId,Response::from);
    }
    private PromotionRedemptionResponse redeemed(PromotionCode code,Long id) { return new PromotionRedemptionResponse(code.getCode(),id,code.getRedeemedCount(),code.getMaxRedemptions(),true); }
    private String normalize(String code) { return code.trim().toUpperCase(Locale.ROOT); }
    private ResponseStatusException conflict(String code) { return new ResponseStatusException(HttpStatus.CONFLICT,code); }
}
