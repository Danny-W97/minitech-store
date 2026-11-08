package org.demo.deliveryservice.delivery;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.client.RestClient;

@Component
public class ShipmentOrderClient {
    public record Result(Long orderId,String trackingNumber,long revision,String deliveryStatus,String orderStatus,String paymentState,String outcome) {}
    private final RestClient client;
    public ShipmentOrderClient(RestClient.Builder builder,@Value("${ORDER_SERVICE_URL:http://localhost:18082}") String baseUrl) {
        client=builder.baseUrl(baseUrl).build();
    }
    public Result apply(ShipmentSyncStateStore.Ticket ticket) {
        return client.post().uri("/internal/orders/{id}/shipment-progress",ticket.orderId())
                .body(java.util.Map.of("trackingNumber",ticket.trackingNumber(),"methodCode",ticket.methodCode(),"revision",ticket.revision(),"status",ticket.status()))
                .retrieve().body(Result.class);
    }
}
