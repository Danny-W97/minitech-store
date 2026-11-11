package org.demo.paymentservice.order;

import org.demo.paymentservice.config.ResilientHttpExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class OrderClient {

    private final RestClient restClient;
    private final ResilientHttpExecutor resilientHttpExecutor;

    public OrderClient(
            RestClient.Builder restClientBuilder,
            @Value("${services.order.base-url}") String orderServiceBaseUrl,
            ResilientHttpExecutor resilientHttpExecutor
    ) {
        this.restClient = restClientBuilder.baseUrl(orderServiceBaseUrl).build();
        this.resilientHttpExecutor = resilientHttpExecutor;
    }

    public OrderResponse getOrder(Long orderId) {
        try {
            OrderResponse order = resilientHttpExecutor.execute("order", () -> restClient.get()
                    .uri("/orders/{id}", orderId)
                    .retrieve()
                    .body(OrderResponse.class));

            if (order == null) {
                throw new OrderServiceException("Order Service returned an empty response");
            }
            return order;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 404) {
                throw new OrderNotFoundException(orderId);
            }
            throw new OrderServiceException("Order Service returned HTTP " + exception.getStatusCode(), exception);
        } catch (RestClientException exception) {
            throw new OrderServiceException("Could not reach Order Service", exception);
        }
    }

    public OrderResponse markPaid(Long orderId) {
        return changeStatus(orderId, "PAID", "payment status update");
    }

    public OrderResponse cancel(Long orderId) {
        return changeStatus(orderId, "CANCELLED", "cancellation");
    }
    public OrderResponse refund(Long orderId,String step,Long paymentId,java.math.BigDecimal amount,String currency,boolean cancel) {
        return refund(orderId,step,paymentId,amount,currency,cancel,false);
    }
    public OrderResponse refund(Long orderId,String step,Long paymentId,java.math.BigDecimal amount,String currency,boolean cancel,boolean external) {
        try {
            var result=resilientHttpExecutor.execute("order",() -> restClient.post()
                    .uri("/internal/orders/{id}/stripe-refund/{step}",orderId,step)
                    .body(java.util.Map.of("paymentId",paymentId,"amount",amount,"currency",currency,"cancelCompensated",cancel,"external",external))
                    .retrieve().body(OrderResponse.class));
            if(result==null || !orderId.equals(result.id()) || !("REFUND_PENDING".equals(result.paymentState()) || "REFUNDED".equals(result.paymentState())))
                throw new OrderServiceException("Order did not confirm financial hold");
            return result;
        } catch(RestClientException error) { throw new OrderServiceException("Order refund hold could not be confirmed",error); }
    }
    public OrderResponse acceptDeferred(Long orderId,Long paymentId,String method,java.math.BigDecimal amount,String currency) {
        return deferred(orderId,"accept",java.util.Map.of("paymentId",paymentId,"paymentMethod",method,"amount",amount,"currency",currency));
    }
    public OrderResponse collectDeferred(Long orderId,Long paymentId,String reference,java.math.BigDecimal amount,String currency) {
        return deferred(orderId,"collect",java.util.Map.of("paymentId",paymentId,"receiptReference",reference,"amount",amount,"currency",currency));
    }
    private OrderResponse deferred(Long orderId,String operation,Object command) {
        try {
            var result=resilientHttpExecutor.execute("order",() -> restClient.post().uri("/internal/orders/{id}/deferred/"+operation,orderId)
                    .contentType(MediaType.APPLICATION_JSON).body(command).retrieve().body(OrderResponse.class));
            if(result==null || !orderId.equals(result.id())) throw new OrderServiceException("Order Service returned no matching deferred order");
            return result;
        } catch(RestClientException error) { throw new OrderServiceException("Deferred order operation could not be completed",error); }
    }

    private OrderResponse changeStatus(Long orderId, String status, String operation) {
        try {
            OrderResponse order = resilientHttpExecutor.execute("order", () -> restClient.patch()
                    .uri("/orders/{id}/status", orderId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ChangeOrderStatusRequest(status))
                    .retrieve()
                    .body(OrderResponse.class));

            if (order == null) {
                throw new OrderServiceException("Order Service returned an empty " + operation + " response");
            }
            return order;
        } catch (RestClientResponseException exception) {
            throw new OrderServiceException(
                    "Order Service returned HTTP " + exception.getStatusCode() + " during " + operation,
                    exception
            );
        } catch (RestClientException exception) {
            throw new OrderServiceException("Could not update the order during " + operation, exception);
        }
    }
}
