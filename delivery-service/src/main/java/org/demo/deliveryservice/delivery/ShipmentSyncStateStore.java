package org.demo.deliveryservice.delivery;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ShipmentSyncStateStore {
    public record Ticket(Long orderId,String trackingNumber,String methodCode,long revision,String status,String token) {}
    @io.swagger.v3.oas.annotations.media.Schema(name="ShipmentSynchronizationResponse")
    public record Response(Long orderId,String trackingNumber,long revision,String shipmentStatus,String state,String diagnosticCode,int attempts,boolean processing,Instant nextAttemptAt) {}
    private final ShipmentRepository shipments;
    public ShipmentSyncStateStore(ShipmentRepository shipments) { this.shipments=shipments; }
    @Transactional(readOnly=true) public List<Long> due() { return shipments.due(Instant.now(),org.springframework.data.domain.PageRequest.of(0,20)); }
    @Transactional public Ticket claim(Long id) {
        var row=lock(id);if(!row.syncDue()) return null;
        if(row.getSyncAttempts()>=20) { row.syncResult("REVIEW_REQUIRED","ATTEMPT_LIMIT");return null; }
        String token=UUID.randomUUID().toString();row.claimSync(token);
        return new Ticket(id,row.getTrackingNumber(),row.getMethodCode(),row.getProgressRevision(),row.getStatus(),token);
    }
    @Transactional public void result(Ticket ticket,String state,String code) {
        var row=lock(ticket.orderId());
        if(ticket.token().equals(row.getSyncToken()) && row.getProgressRevision()==ticket.revision()) row.syncResult(state,code);
    }
    @Transactional public void reconcile(Long id) {
        var row=lock(id);if(row.syncProcessing()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Shipment synchronization is in progress");
        row.retrySync();
    }
    @Transactional(readOnly=true) public Response status(Long id) {
        var row=shipments.findByOrderId(id).orElseThrow(() -> new ShipmentNotFoundException(id));
        return new Response(id,row.getTrackingNumber(),row.getProgressRevision(),row.getStatus(),row.getSyncState(),row.getSyncError(),row.getSyncAttempts(),row.syncProcessing(),row.getSyncNextAttemptAt());
    }
    private Shipment lock(Long id) { return shipments.lockOrder(id).orElseThrow(() -> new ShipmentNotFoundException(id)); }
}
