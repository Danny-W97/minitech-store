package org.demo.paymentservice.promotion;

import org.demo.paymentservice.config.ResilientHttpExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class PromotionClient {
    public record CouponReservation(Long orderId,String code,java.math.BigDecimal subtotal,java.math.BigDecimal discountAmount,String state) {}

    private final RestClient restClient;
    private final ResilientHttpExecutor resilientHttpExecutor;

    public PromotionClient(
            RestClient.Builder restClientBuilder,
            @Value("${services.promotion.base-url:http://localhost:18089}") String promotionServiceBaseUrl,
            ResilientHttpExecutor resilientHttpExecutor
    ) {
        this.restClient = restClientBuilder.baseUrl(promotionServiceBaseUrl).build();
        this.resilientHttpExecutor = resilientHttpExecutor;
    }

    public void redeemCoupon(String code, Long orderId) {
        if (code == null || code.isBlank() || orderId == null) {
            return;
        }
        try {
            resilientHttpExecutor.execute("promotion", () -> restClient.post()
                    .uri("/promotions/codes/redeem")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new PromotionRedemptionRequest(code, orderId))
                    .retrieve()
                    .toBodilessEntity());
        } catch (RestClientResponseException exception) {
            throw new PromotionServiceException(
                    "Promotion Service returned HTTP " + exception.getStatusCode(), exception);
        } catch (RestClientException exception) {
            throw new PromotionServiceException("Could not reach Promotion Service", exception);
        }
    }
    public void requireReservation(org.demo.paymentservice.order.OrderResponse order) {
        if(order.promotionCode()==null || order.promotionCode().isBlank()) return;
        try {
            var hold=resilientHttpExecutor.execute("promotion",() -> restClient.get().uri("/internal/promotions/orders/{id}/coupon",order.id()).retrieve().body(CouponReservation.class));
            var subtotal=order.totalPrice().add(order.discountAmount()).subtract(order.deliveryCost());
            if(hold==null || !order.id().equals(hold.orderId()) || !order.promotionCode().equalsIgnoreCase(hold.code())
                    || !java.util.Set.of("RESERVED","CONSUMED").contains(hold.state()==null?"":hold.state())
                    || hold.subtotal()==null || hold.subtotal().compareTo(subtotal)!=0 || hold.discountAmount()==null || hold.discountAmount().compareTo(order.discountAmount())!=0)
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Coupon reservation does not match the accepted price");
        } catch(RestClientResponseException unavailable) {
            if(unavailable.getStatusCode().value()==404 || unavailable.getStatusCode().value()==409)
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Coupon reservation requires reconciliation before payment");
            throw new PromotionServiceException("Coupon reservation lookup unavailable",unavailable);
        } catch(RestClientException unavailable) { throw new PromotionServiceException("Coupon reservation lookup unavailable",unavailable); }
    }
}
