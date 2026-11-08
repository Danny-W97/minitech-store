package org.demo.inventoryservice.inventory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class StockOperationStore {
    public record Command(String operationId,Long productId,String kind,int amount,Long expectedVersion) {}
    private final InventoryItemRepository items;
    private final StockOperationRepository operations;
    public StockOperationStore(InventoryItemRepository items,StockOperationRepository operations) { this.items=items;this.operations=operations; }
    @Transactional(readOnly=true)
    public java.util.Optional<StockOperation> lookup(String operationId) { return operations.findByOperationId(operationId); }
    @Transactional
    public StockOperation apply(Command command) {
        var existing=operations.findByOperationId(command.operationId());
        if(existing.isPresent()) return same(existing.get(),command);
        var item=items.findByProductIdForUpdate(command.productId()).orElseThrow(() -> new InventoryItemNotFoundException(command.productId()));
        var recorded=operations.findByOperationId(command.operationId());
        if(recorded.isPresent()) return same(recorded.get(),command);
        int before=item.getAvailableQuantity();long version=item.getVersion();
        if("RECEIPT".equals(command.kind())) item.receive(command.amount());
        else if("ADJUSTMENT".equals(command.kind())) {
            if(command.expectedVersion()==null || command.expectedVersion()!=version) throw conflict("Stock version changed; reload before correction");
            item.setAvailableQuantity(command.amount());
        } else throw new IllegalArgumentException("Unsupported stock operation kind");
        items.flush();
        return operations.saveAndFlush(new StockOperation(command.operationId(),command.productId(),command.kind(),before,version,item));
    }
    public static StockOperation same(StockOperation recorded,Command command) {
        boolean matching=recorded.getProductId().equals(command.productId()) && recorded.getKind().equals(command.kind())
                && ("RECEIPT".equals(command.kind()) ? recorded.getQuantity()==command.amount()
                    : recorded.getAvailableQuantity()==command.amount() && command.expectedVersion()!=null && recorded.getBeforeVersion()==command.expectedVersion());
        if(!matching) throw conflict("Operation ID already belongs to a different stock command");
        return recorded;
    }
    private static ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
}
