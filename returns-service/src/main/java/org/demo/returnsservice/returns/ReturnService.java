package org.demo.returnsservice.returns;

import org.demo.returnsservice.web.*;
import org.demo.platformsecurity.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class ReturnService {
    private final ReturnRequestRepository repository;
    private final ReturnStateStore states;
    private final ReturnOrderClient orders;
    private final ReturnEventRepository events;
    private final ReturnDomainEffectsService effectsService;
    public ReturnService(ReturnRequestRepository repository,ReturnStateStore states,ReturnOrderClient orders,ReturnEventRepository events,ReturnDomainEffectsService effectsService) {
        this.repository=repository;this.states=states;this.orders=orders;this.events=events;this.effectsService=effectsService;
    }

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public ReturnResponse create(CreateReturnRequest request) {
        if(Access.request()!=null) {
            Access.customer(request.customerId());
            if(!Access.actor().operator()) Access.require(request.customerEmail().equalsIgnoreCase(Access.actor().email()));
        }
        var intent=ReturnIntent.from(request);var replay=states.replay(request,intent);if(replay!=null) return replay;
        var order=orders.get(request.orderId(),request.customerId(),request.customerEmail());orders.eligible(order,request.requestType());
        try { return states.create(request,intent,order,actor()); }
        catch(org.springframework.dao.DataIntegrityViolationException race) {
            var winner=states.replay(request,intent);if(winner==null) throw race;return winner;
        }
    }
    @Transactional(readOnly = true) public List<ReturnResponse> forCustomer(Long customerId) {
        return list(customerId, null, org.demo.platformsecurity.ListWindow.FIRST).items();
    }
    @Transactional(readOnly = true) public List<ReturnResponse> all() {
        return list(null, null, org.demo.platformsecurity.ListWindow.FIRST).items();
    }
    @Transactional(readOnly = true)
    public org.demo.platformsecurity.ListWindow.ListPage<ReturnResponse> list(Long customerId, ReturnStatus status,
            org.demo.platformsecurity.ListWindow window) {
        if(Access.request()!=null) { if(customerId==null) Access.require(Access.actor().operator());else Access.customer(customerId); }
        var ids=repository.pageIds(window.ceiling(),customerId,status,window.fetch());var records=new HashMap<Long,ReturnRequest>();
        if(!ids.isEmpty()) repository.withItems(ids.stream().limit(window.size()).toList()).forEach(r -> records.put(r.getId(),r));
        return window.map(ids,Long::longValue,id -> ReturnResponse.from(records.get(id)));
    }
    public ReturnResponse get(Long id) {
        var result=states.get(id);if(Access.request()!=null) Access.customer(result.customerId());return result;
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public ReturnResponse changeStatus(Long id,ChangeReturnStatusRequest command) {
        operator();var replay=states.operation(id,"STATUS_CHANGED",command.status(),command.expectedVersion(),command.operationId(),command.note().trim());if(replay!=null) return replay;
        var request=states.get(id);var order=orders.get(request.orderId(),request.customerId(),request.customerEmail());
        return states.change(id,command,order,actor());
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public ReturnResponse reconcile(Long id,ReconcileReturnRequest command) {
        operator();var replay=states.operation(id,"LEGACY_VERIFIED",null,command.expectedVersion(),command.operationId(),command.note().trim());if(replay!=null) return replay;
        var request=states.get(id);var order=orders.get(request.orderId(),request.customerId(),request.customerEmail());
        return states.reconcile(id,command,order,actor());
    }
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public ReturnResponse reconcileEffects(Long id,ReconcileReturnRequest command) {
        operator();
        var replay=states.operation(id,"DOMAIN_EFFECTS_RECONCILED",ReturnStatus.COMPLETED,command.expectedVersion(),command.operationId(),command.note().trim());
        if(replay!=null) return replay;
        var current=states.get(id);
        if(current.status()!=ReturnStatus.APPROVED) throw ReturnIntent.conflict("RETURN_STATUS_NOT_APPROVED");
        if(command.expectedVersion()==null || !command.expectedVersion().equals(current.version()))
            throw ReturnIntent.conflict("RETURN_VERSION_CONFLICT");
        var order=orders.get(current.orderId(),current.customerId(),current.customerEmail());
        orders.eligible(order,current.requestType());
        var outcome=effectsService.reconcile(current,order,command.operationId(),command.note());
        return states.completeWithEffects(id,command,order,actor(),outcome.summary());
    }
    @Transactional(readOnly=true) public ListWindow.ListPage<ReturnEventResponse> history(Long id,ListWindow window) {
        operator();states.get(id);return window.map(events.page(id,window.ceiling(),window.fetch()),ReturnEvent::getId,ReturnEventResponse::from);
    }
    private void operator() { if(Access.request()!=null) Access.require(Access.actor().operator()); }
    private String actor() {
        var actor=Access.actor();if(Access.request()==null) return "local-test";
        if(actor.customerId()!=null) return actor.role()+":customer:"+actor.customerId();
        if(actor.email()!=null && actor.email().length()<=255) return actor.role()+":"+actor.email();
        return "configured-operator";
    }
}
