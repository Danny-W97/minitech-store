package org.demo.billingservice.billing;

import org.demo.platformsecurity.PlatformSecurityConfiguration;
import org.demo.platformsecurity.ServiceAuthenticationInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.List;

@Component
public class BillingOrderClient {
    private final RestClient client;

    public BillingOrderClient(@Value("${services.order.base-url:${ORDER_SERVICE_URL:http://localhost:18082}}") String url,
                              ServiceAuthenticationInterceptor authentication) {
        client = PlatformSecurityConfiguration.client().baseUrl(url).requestInterceptor(authentication).build();
    }

    public PaidOrder get(Long orderId) {
        try {
            PaidOrder order = client.get().uri("/orders/{id}", orderId).retrieve().body(PaidOrder.class);
            if (order == null || !orderId.equals(order.id())) throw new RestClientException("Invalid order response");
            return order;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order does not exist");
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Order verification unavailable", exception);
        } catch (RestClientException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Order verification unavailable", exception);
        }
    }

    public record PaidOrder(Long id, Long customerId, String customerEmail, String status, BigDecimal totalPrice,
                            BigDecimal deliveryCost, BigDecimal discountAmount, List<Item> items,String paymentState,boolean lifecycleReviewRequired) {
        public PaidOrder(Long id,Long customerId,String customerEmail,String status,BigDecimal totalPrice,
                BigDecimal deliveryCost,BigDecimal discountAmount,List<Item> items,String paymentState) {
            this(id,customerId,customerEmail,status,totalPrice,deliveryCost,discountAmount,items,paymentState,false);
        }
        public PaidOrder(Long id,Long customerId,String customerEmail,String status,BigDecimal totalPrice,
                BigDecimal deliveryCost,BigDecimal discountAmount,List<Item> items) {
            this(id,customerId,customerEmail,status,totalPrice,deliveryCost,discountAmount,items,null);
        }
    }
    public record Item(Long productId, String sku, String productName, Integer quantity,
                       BigDecimal unitPrice, BigDecimal totalPrice) {}
}
