package org.demo.orderservice.promotion;

import org.demo.orderservice.config.ResilientHttpExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class PromotionClient {
    public record CouponReservation(Long orderId,String code,BigDecimal subtotal,BigDecimal discountAmount,String state,java.time.Instant acceptedAt,java.time.Instant expiresAt) {}
    public record ReservationRequest(String code,BigDecimal subtotal,BigDecimal discountAmount,java.time.Instant acceptedAt,java.time.Instant expiresAt) {}
    private final RestClient restClient;
    private final ResilientHttpExecutor resilientHttpExecutor;

    public PromotionClient(
            RestClient.Builder restClientBuilder,
            @Value("${services.promotion.base-url}") String promotionServiceBaseUrl,
            ResilientHttpExecutor resilientHttpExecutor
    ) {
        this.restClient = restClientBuilder.baseUrl(promotionServiceBaseUrl).build();
        this.resilientHttpExecutor = resilientHttpExecutor;
    }

    public CouponValidationResponse validateCoupon(String code, BigDecimal subtotal) {
        if (code == null || code.isBlank()) return null;
        BigDecimal normalizedSubtotal = subtotal == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : subtotal.setScale(2, RoundingMode.HALF_UP);
        try {
            CouponValidationResponse response = resilientHttpExecutor.execute("promotion", () -> restClient.post()
                    .uri("/promotions/codes/validate")
                    .body(new CouponValidationRequest(code, normalizedSubtotal))
                    .retrieve()
                    .body(CouponValidationResponse.class));
            if (response == null) throw new PromotionServiceException("Promotion Service returned an empty response");
            if (!response.valid()) throw new InvalidPromotionCodeException(response.message());
            return response;
        } catch (InvalidPromotionCodeException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().is4xxClientError()) {
                throw new InvalidPromotionCodeException("Kod rabatowy jest nieprawidłowy.");
            }
            throw new PromotionServiceException("Promotion Service returned HTTP " + exception.getStatusCode(), exception);
        } catch (RestClientException exception) {
            throw new PromotionServiceException("Could not reach Promotion Service", exception);
        }
    }
    public CouponReservation reserve(String code,Long orderId,BigDecimal subtotal,BigDecimal discount,java.time.Instant acceptedAt,java.time.Instant expiresAt) {
        try {
            var result=resilientHttpExecutor.execute("promotion",() -> restClient.post().uri("/internal/promotions/orders/{id}/coupon/reserve",orderId)
                    .body(new ReservationRequest(code,subtotal,discount,acceptedAt,expiresAt)).retrieve().body(CouponReservation.class));
            if(result==null || !orderId.equals(result.orderId()) || !code.equalsIgnoreCase(result.code())
                    || !java.util.Set.of("RESERVED","CONSUMED").contains(result.state()==null?"":result.state())
                    || result.subtotal()==null || result.subtotal().compareTo(subtotal)!=0 || result.discountAmount()==null || result.discountAmount().compareTo(discount)!=0)
                throw new PromotionServiceException("Promotion did not confirm the reserved coupon quote");
            return result;
        } catch(RestClientResponseException rejected) {
            if(rejected.getStatusCode().is4xxClientError() && rejected.getStatusCode().value()!=429) throw new CouponReservationConflictException();
            throw new PromotionServiceException("Coupon reservation unavailable",rejected);
        } catch(RestClientException unavailable) { throw new PromotionServiceException("Coupon reservation unavailable",unavailable); }
    }
    public void release(String code,Long orderId) {
        try {
            var result=resilientHttpExecutor.execute("promotion",() -> restClient.post().uri("/internal/promotions/orders/{id}/coupon/release",orderId)
                    .body(java.util.Map.of("code",code)).retrieve().body(CouponReservation.class));
            if(result==null || !orderId.equals(result.orderId()) || !code.equalsIgnoreCase(result.code()) || !"RELEASED".equals(result.state()))
                throw new PromotionServiceException("Coupon release was not confirmed");
        } catch(RestClientResponseException rejected) {
            if(rejected.getStatusCode().is4xxClientError() && rejected.getStatusCode().value()!=429) throw new CouponReservationConflictException();
            throw new PromotionServiceException("Coupon release unavailable",rejected);
        } catch(RestClientException unavailable) { throw new PromotionServiceException("Coupon release unavailable",unavailable); }
    }
    public CouponReservation reservation(Long id) {
        try { return resilientHttpExecutor.execute("promotion",() -> restClient.get().uri("/internal/promotions/orders/{id}/coupon",id).retrieve().body(CouponReservation.class)); }
        catch(RestClientResponseException unavailable) {
            if(unavailable.getStatusCode().value()==404) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"Coupon reservation has not been recorded");
            throw new PromotionServiceException("Coupon reservation lookup unavailable",unavailable);
        }
        catch(RestClientException unavailable) { throw new PromotionServiceException("Coupon reservation lookup unavailable",unavailable); }
    }
}
