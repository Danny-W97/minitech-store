package org.demo.paymentservice.payment;

import org.demo.paymentservice.delivery.DeliveryClient;
import org.demo.paymentservice.followup.FollowUpRunner;
import org.demo.paymentservice.order.OrderClient;
import org.demo.paymentservice.web.*;
import org.demo.platformsecurity.RequestBudget;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class PaymentCollectionService {
    private final PaymentRepository payments;
    private final PaymentStateStore states;
    private final OrderClient orders;
    private final DeliveryClient delivery;
    private final FollowUpRunner runner;
    public PaymentCollectionService(PaymentRepository payments,PaymentStateStore states,OrderClient orders,DeliveryClient delivery,FollowUpRunner runner) {
        this.payments=payments;this.states=states;this.orders=orders;this.delivery=delivery;this.runner=runner;
    }
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public PaymentResponse collect(Long id,CollectPaymentRequest receipt) {
        try(var budget=RequestBudget.open(30000)) {
            if(receipt.receiptReference()==null || !receipt.receiptReference().matches("[A-Za-z0-9][A-Za-z0-9._/-]{2,79}"))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Provide a valid cash receipt reference");
            var payment=payments.findById(id).orElseThrow(() -> new PaymentNotFoundException(id));
            if(receipt.amount()==null || payment.getAmount().compareTo(receipt.amount())!=0 || !payment.getCurrency().equals(receipt.currency()))
                throw conflict("Collection amount or currency does not match payment");
            if(payment.getCollectionReference()==null) {
                if(!PaymentMethod.parse(payment.getPaymentMethod()).deferred() || payment.getStatus()!=PaymentStatus.PENDING)
                    throw conflict("Only an accepted deferred payment can be collected");
                var order=orders.getOrder(payment.getOrderId());
                if(order.lifecycleReviewRequired()) throw conflict("Order lifecycle requires reconciliation before collection");
                if(!"COLLECTION_DUE".equals(order.paymentState()) || !id.equals(order.deferredPaymentId())
                        || !payment.getPaymentMethod().equals(order.deferredPaymentMethod())) {
                    // Another replica may have recorded this receipt after our first read.
                    if(payments.findById(id).orElseThrow().getCollectionReference()==null) throw conflict("Order is not awaiting this collection");
                } else {
                    var tracking=delivery.tracking(order.id());
                    boolean pickup=PaymentMethod.CASH_AT_PICKUP.name().equals(payment.getPaymentMethod());
                    if(!java.util.Objects.equals(order.deliveryMethod(),tracking.methodCode())
                            || !(pickup ? "PICKED_UP" : "DELIVERED").equals(tracking.status())) throw conflict("Order handover has not been confirmed");
                }
            }
            Payment recorded;
            try { recorded=states.collect(id,receipt); }
            catch(org.springframework.dao.DataIntegrityViolationException duplicateReceipt) {
                var other=payments.findByCollectionReference(receipt.receiptReference());
                if(other.isPresent() && !other.get().getId().equals(id)) throw conflict("Receipt reference already belongs to another payment");
                throw duplicateReceipt;
            }
            runner.runOrder(recorded.getOrderId());
            return PaymentResponse.from(recorded);
        }
    }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
}
