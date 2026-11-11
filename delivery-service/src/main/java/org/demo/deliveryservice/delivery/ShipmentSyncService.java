package org.demo.deliveryservice.delivery;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import java.util.Set;

@Service
public class ShipmentSyncService {
    private final ShipmentSyncStateStore states;
    private final ShipmentOrderClient orders;
    public ShipmentSyncService(ShipmentSyncStateStore states,ShipmentOrderClient orders) { this.states=states;this.orders=orders; }
    public void reconcile(Long id) { states.reconcile(id);process(id); }
    public void process(Long id) {
        var ticket=states.claim(id);if(ticket==null) return;
        try(var budget=org.demo.platformsecurity.RequestBudget.open(45000)) {
            var result=orders.apply(ticket);
            if(result==null || !id.equals(result.orderId()) || !ticket.trackingNumber().equals(result.trackingNumber())
                    || result.revision()<ticket.revision() || result.revision()==ticket.revision() && !ticket.status().equals(result.deliveryStatus())
                    || !(Set.of("DELIVERED","PICKED_UP").contains(ticket.status()) ? "COMPLETED".equals(result.orderStatus())
                        : Set.of("SHIPPED","COMPLETED").contains(result.orderStatus()==null ? "" : result.orderStatus()))
                    || !Set.of("APPLIED","ALREADY_APPLIED").contains(result.outcome()==null ? "" : result.outcome())) {
                states.result(ticket,"REVIEW_REQUIRED","ORDER_RECEIPT_CONFLICT");return;
            }
            states.result(ticket,"SYNCED",null);
        } catch(RestClientResponseException failure) {
            int code=failure.getStatusCode().value();
            states.result(ticket,code>=400 && code<500 && !Set.of(408,425,429).contains(code) ? "REVIEW_REQUIRED" : "RETRY","ORDER_HTTP_"+code);
        } catch(RuntimeException failure) { states.result(ticket,"RETRY","ORDER_UNAVAILABLE"); }
    }
}
