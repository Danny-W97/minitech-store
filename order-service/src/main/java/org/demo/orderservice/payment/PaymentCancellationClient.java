package org.demo.orderservice.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.demo.orderservice.config.ResilientHttpExecutor;

@Component
public class PaymentCancellationClient {
    private record Fence(Long orderId,boolean cancelled) {}
    private final RestClient client;
    private final ResilientHttpExecutor executor;
    public PaymentCancellationClient(RestClient.Builder builder,
            @Value("${services.payment.base-url:http://localhost:18084}") String url,ResilientHttpExecutor executor) {
        client=builder.baseUrl(url).build();this.executor=executor;
    }
    public void fence(Long orderId) {
        try {
            var result=executor.execute("payment",() -> client.post().uri("/internal/payments/orders/{id}/cancel",orderId)
                    .retrieve().body(Fence.class));
            if(result==null || !orderId.equals(result.orderId()) || !result.cancelled())
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Payment did not confirm cancellation fence");
        } catch(RestClientResponseException failure) {
            if(failure.getStatusCode().value()==409)
                throw new CancellationRefusedException();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Payment cancellation verification unavailable");
        } catch(RestClientException failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Payment cancellation verification unavailable");
        }
    }
}
