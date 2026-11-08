package org.demo.promotionservice.promotion;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.util.Set;

/** Canonical Order verification happens before the local redemption transaction. */
@Component
public class CouponRedemptionVerifier {
    public record Order(Long id,String promotionCode,BigDecimal totalPrice,BigDecimal deliveryCost,BigDecimal discountAmount,
            String status,String paymentState,String deferredPaymentMethod,Long deferredPaymentId,boolean lifecycleReviewRequired) {}
    private final RestClient client;
    public CouponRedemptionVerifier(RestClient.Builder builder,@Value("${services.order.base-url:${ORDER_SERVICE_URL:${PLATFORM_ORDER_URL:http://localhost:18082}}}") String base) { client=builder.baseUrl(base).build(); }
    public void verify(String code,Long id,CouponReservationStateStore.Response hold) {
        Order order;
        try { order=client.get().uri("/orders/{id}",id).retrieve().body(Order.class); }
        catch(RestClientException unavailable) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Order verification unavailable"); }
        boolean paid=order!=null && Set.of("PAID","SHIPPED","COMPLETED").contains(order.status()==null?"":order.status())
                && (order.paymentState()==null || "PAID".equals(order.paymentState()));
        boolean deferred=order!=null && order.deferredPaymentId()!=null && order.deferredPaymentMethod()!=null
                && Set.of("CONFIRMED","SHIPPED","COMPLETED").contains(order.status()==null?"":order.status())
                && Set.of("COLLECTION_DUE","PAID").contains(order.paymentState()==null?"":order.paymentState());
        if(order==null || !id.equals(order.id()) || order.lifecycleReviewRequired() || !(paid || deferred)
                || order.promotionCode()==null || !code.equalsIgnoreCase(order.promotionCode())
                || order.totalPrice()==null || order.deliveryCost()==null || order.discountAmount()==null
                || order.discountAmount().compareTo(hold.discountAmount())!=0
                || order.totalPrice().add(order.discountAmount()).subtract(order.deliveryCost()).compareTo(hold.subtotal())!=0)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Coupon redemption requires this order's accepted purchase and reserved quote");
    }
}
