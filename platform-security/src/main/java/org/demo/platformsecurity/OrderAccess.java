package org.demo.platformsecurity;

import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

public record OrderAccess(RestClient client) {
    public record Ownership(Long customerId, String customerEmail, boolean guestAllowed) {}
    /** Account exports require a customer owner, with no operator or guest bypass. */
    public Ownership requireCustomerOwner(Long orderId) {
        var actor = Access.actor();
        Access.require("CUSTOMER".equals(actor.role()) && actor.customerId() != null && actor.customerId() > 0);
        if (orderId == null || orderId < 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        Ownership ownership;
        try {
            // Only the configured service identity is sent; never forward guest capability.
            ownership = client.get().uri("/internal/orders/{id}/access", orderId).retrieve().body(Ownership.class);
        } catch (HttpClientErrorException.NotFound missing) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        } catch (RestClientException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Order authorization unavailable");
        }
        if (ownership == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Order authorization unavailable");
        if (!actor.customerId().equals(ownership.customerId()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        return ownership;
    }
    public Ownership require(Long orderId) {
        if (Access.actor().operator() || Access.actor().internal()) return null;
        try {
            var request = client.get().uri("/internal/orders/{id}/access", orderId);
            String capability = Access.header(Access.ORDER_TOKEN);
            if (capability != null && capability.length() <= 128) request.header(Access.ORDER_TOKEN, capability);
            Ownership ownership = request.retrieve().body(Ownership.class);
            Access.require(ownership != null && (ownership.customerId() != null && ownership.customerId().equals(Access.actor().customerId())
                    || ownership.customerId() == null && ownership.guestAllowed()));
            return ownership;
        } catch (RestClientException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Order authorization unavailable");
        }
    }
}
