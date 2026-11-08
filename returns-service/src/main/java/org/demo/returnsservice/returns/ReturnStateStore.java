package org.demo.returnsservice.returns;

import org.demo.returnsservice.web.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class ReturnStateStore {
    private final ReturnRequestRepository requests;
    private final ReturnEventRepository events;
    private final ReturnOrderClient orders;
    public ReturnStateStore(ReturnRequestRepository requests,ReturnEventRepository events,ReturnOrderClient orders) { this.requests=requests;this.events=events;this.orders=orders; }
    @Transactional(readOnly=true) public ReturnResponse replay(CreateReturnRequest input,ReturnIntent intent) {
        var old=requests.findByOrderIdAndCustomerIdAndRequestType(input.orderId(),input.customerId(),input.requestType()).orElse(null);
        if(old==null) return null;requireMutable(old);
        if(old.getRequestHash()==null) throw ReturnIntent.conflict("RETURN_LEGACY_REVIEW_REQUIRED");
        if(!old.getRequestHash().equals(intent.hash())) throw ReturnIntent.conflict("RETURN_INTENT_CONFLICT");
        return ReturnResponse.from(old);
    }
    @Transactional public ReturnResponse create(CreateReturnRequest input,ReturnIntent intent,ReturnOrderClient.Snapshot order,String actor) {
        var previous=replay(input,intent);if(previous!=null) return previous;
        var request=new ReturnRequest(input.orderId(),input.customerId(),order.customerEmail(),input.requestType(),input.reason(),input.description());
        request.verify(intent,orders.lines(request,order,intent));requests.saveAndFlush(request);
        events.saveAndFlush(new ReturnEvent(request,"SUBMITTED",null,null,null,null,actor,order));return ReturnResponse.from(request);
    }
    @Transactional(readOnly=true) public ReturnResponse operation(Long id,String action,ReturnStatus target,Long version,UUID operation,String note) {
        requireMutable(require(id));var event=events.findByReturnIdAndOperationId(id,operation).orElse(null);if(event==null) return null;
        event.requireSame(action,target,version,note);return ReturnResponse.from(require(id));
    }
    @Transactional public ReturnResponse change(Long id,ChangeReturnStatusRequest command,ReturnOrderClient.Snapshot order,String actor) {
        var request=requests.lockId(id).orElseThrow(() -> notFound());requireMutable(request);
        var replay=operation(id,"STATUS_CHANGED",command.status(),command.expectedVersion(),command.operationId(),command.note().trim());if(replay!=null) return replay;
        checkVersion(request,command.expectedVersion());
        if(request.getOrderVerifiedAt()==null) throw ReturnIntent.conflict("RETURN_LEGACY_REVIEW_REQUIRED");
        if(command.status()==ReturnStatus.COMPLETED) throw ReturnIntent.conflict("RETURN_DOMAIN_EFFECTS_NOT_RECONCILED");
        var before=request.getStatus();
        boolean allowed=before==ReturnStatus.REQUESTED && command.status()==ReturnStatus.UNDER_REVIEW
                || before==ReturnStatus.UNDER_REVIEW && Set.of(ReturnStatus.APPROVED,ReturnStatus.REJECTED).contains(command.status());
        if(!allowed) throw ReturnIntent.conflict("RETURN_INVALID_TRANSITION");
        if(command.status()==ReturnStatus.APPROVED) orders.eligible(order,request.getRequestType());
        request.changeStatus(command.status(),command.note());requests.saveAndFlush(request);
        events.saveAndFlush(new ReturnEvent(request,"STATUS_CHANGED",before,command.expectedVersion(),command.operationId(),command.note().trim(),actor,order));return ReturnResponse.from(request);
    }
    @Transactional public ReturnResponse completeWithEffects(Long id,ReconcileReturnRequest command,ReturnOrderClient.Snapshot order,String actor,String effectsSummary) {
        var request=requests.lockId(id).orElseThrow(() -> notFound());requireMutable(request);
        var replay=operation(id,"DOMAIN_EFFECTS_RECONCILED",ReturnStatus.COMPLETED,command.expectedVersion(),command.operationId(),command.note().trim());if(replay!=null) return replay;
        checkVersion(request,command.expectedVersion());
        if(request.getStatus()!=ReturnStatus.APPROVED) throw ReturnIntent.conflict("RETURN_STATUS_NOT_APPROVED");
        orders.eligible(order,request.getRequestType());
        var note = "[RECONCILED] " + command.note().trim() + " | " + effectsSummary;
        request.changeStatus(ReturnStatus.COMPLETED,note);requests.saveAndFlush(request);
        events.saveAndFlush(new ReturnEvent(request,"DOMAIN_EFFECTS_RECONCILED",ReturnStatus.APPROVED,command.expectedVersion(),command.operationId(),command.note().trim(),actor,order));
        return ReturnResponse.from(request);
    }
    @Transactional public ReturnResponse reconcile(Long id,ReconcileReturnRequest command,ReturnOrderClient.Snapshot order,String actor) {
        var request=requests.lockId(id).orElseThrow(() -> notFound());requireMutable(request);
        var replay=operation(id,"LEGACY_VERIFIED",null,command.expectedVersion(),command.operationId(),command.note().trim());if(replay!=null) return replay;
        checkVersion(request,command.expectedVersion());
        if(request.getOrderVerifiedAt()!=null || Set.of(ReturnStatus.REJECTED,ReturnStatus.COMPLETED).contains(request.getStatus())) throw ReturnIntent.conflict("RETURN_LEGACY_RECONCILIATION_NOT_APPLICABLE");
        orders.eligible(order,request.getRequestType());
        var input=new CreateReturnRequest(request.getOrderId(),request.getCustomerId(),request.getCustomerEmail(),request.getRequestType(),request.getReason(),request.getDescription());
        var intent=ReturnIntent.from(input);request.verify(intent,orders.lines(request,order,intent));requests.saveAndFlush(request);
        events.saveAndFlush(new ReturnEvent(request,"LEGACY_VERIFIED",request.getStatus(),command.expectedVersion(),command.operationId(),command.note().trim(),actor,order));return ReturnResponse.from(request);
    }
    @Transactional(readOnly=true) public ReturnResponse get(Long id) { return ReturnResponse.from(require(id)); }
    private void requireMutable(ReturnRequest request){if(request.isPrivacyRedacted())throw ReturnIntent.conflict("RETURN_PRIVACY_REDACTED_READONLY");}
    private ReturnRequest require(Long id) { return requests.findById(id).orElseThrow(() -> notFound()); }
    private void checkVersion(ReturnRequest request,Long expected) {
        if(expected==null || expected!=request.getVersion()) throw ReturnIntent.conflict("RETURN_VERSION_CONFLICT");
    }
    private org.springframework.web.server.ResponseStatusException notFound() { return new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"RETURN_NOT_FOUND"); }
}
