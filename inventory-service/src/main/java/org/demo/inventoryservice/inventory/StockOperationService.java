package org.demo.inventoryservice.inventory;

import org.demo.inventoryservice.web.*;
import org.demo.platformsecurity.ListWindow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StockOperationService {
    private final StockOperationStore states;
    private final StockOperationRepository operations;
    public StockOperationService(StockOperationStore states,StockOperationRepository operations) { this.states=states;this.operations=operations; }
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public StockOperationResponse receive(Long productId,ReceiveStockRequest request) {
        if(request.quantity()==null || request.quantity()<=0) throw new IllegalArgumentException("Positive receipt quantity required");
        return execute(new StockOperationStore.Command(key(request.operationId()),positive(productId),"RECEIPT",request.quantity(),null));
    }
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public StockOperationResponse adjust(Long productId,PatchInventoryItemRequest request) {
        if(request.availableQuantity()==null || request.availableQuantity()<0 || request.expectedVersion()==null || request.expectedVersion()<0)
            throw new IllegalArgumentException("A nonnegative stock count and expected version are required");
        return execute(new StockOperationStore.Command(key(request.operationId()),positive(productId),"ADJUSTMENT",request.availableQuantity(),request.expectedVersion()));
    }
    private StockOperationResponse execute(StockOperationStore.Command command) {
        try { return StockOperationResponse.from(states.apply(command)); }
        catch(DataIntegrityViolationException collision) {
            var winner=states.lookup(command.operationId());
            if(winner.isEmpty()) throw collision;
            return StockOperationResponse.from(StockOperationStore.same(winner.get(),command));
        }
    }
    @Transactional(readOnly=true)
    public StockOperationResponse get(String operationId) {
        return operations.findByOperationId(key(operationId)).map(StockOperationResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Stock operation has not been recorded"));
    }
    @Transactional(readOnly=true)
    public ListWindow.ListPage<StockOperationResponse> list(Long productId,ListWindow window) {
        if(productId!=null) positive(productId);
        return window.map(operations.page(window.ceiling(),productId,window.fetch()),StockOperation::getId,StockOperationResponse::from);
    }
    private Long positive(Long id) { if(id==null || id<=0) throw new IllegalArgumentException("Positive product ID required");return id; }
    private String key(String id) {
        if(id==null || !id.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            throw new IllegalArgumentException("Canonical UUID operation ID required");
        return java.util.UUID.fromString(id).toString();
    }
}
