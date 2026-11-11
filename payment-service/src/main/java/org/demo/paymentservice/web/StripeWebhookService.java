package org.demo.paymentservice.web;

import org.demo.paymentservice.payment.PaymentGateway;
import org.demo.paymentservice.payment.StripeCheckoutService;
import org.demo.paymentservice.payment.PaymentRefundRepository;
import org.demo.paymentservice.payment.PaymentRefundService;
import org.demo.paymentservice.payment.PaymentStateStore;
import org.demo.paymentservice.payment.StripePaymentGateway;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class StripeWebhookService {
    private final StripeWebhookVerifier verifier;
    private final PaymentGateway gateway;
    private final PaymentStateStore states;
    private final StripeCheckoutService payments;
    private final PaymentRefundRepository refundRows;
    private final PaymentRefundService refunds;
    private final org.demo.paymentservice.payment.StripeExternalRefundService externalRefunds;
    private final ObjectMapper json;

    public StripeWebhookService(StripeWebhookVerifier verifier,PaymentGateway gateway,PaymentStateStore states,
            StripeCheckoutService payments,ObjectMapper json,PaymentRefundRepository refundRows,PaymentRefundService refunds,
            org.demo.paymentservice.payment.StripeExternalRefundService externalRefunds) {
        this.verifier=verifier;this.gateway=gateway;this.states=states;this.payments=payments;this.json=json;
        this.refundRows=refundRows;this.refunds=refunds;
        this.externalRefunds=externalRefunds;
    }

    public void receive(byte[] rawBody,String signature) {
        if(!(gateway instanceof StripePaymentGateway stripe))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Stripe is not the active payment provider");
        String payload=verifier.verify(rawBody,signature);
        JsonNode event;
        try { event=json.readTree(payload); }
        catch(RuntimeException malformed) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid Stripe event payload"); }
        if(event==null || !event.isObject()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid Stripe event payload");
        String eventId=text(event,"id"),type=text(event,"type");
        if(eventId==null || !eventId.matches("evt_[A-Za-z0-9]{8,240}") || type==null || type.length()>120)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid Stripe event identity");
        JsonNode mode=event.get("livemode");
        if(mode==null || !mode.isBoolean() || mode.booleanValue()!=stripe.liveMode())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Stripe event mode does not match Payment configuration");
        if(states.stripeEventReceived(eventId)) return;

        if("refund.created".equals(type) || "refund.updated".equals(type) || "refund.failed".equals(type)) {
            var data=event.path("data").path("object");
            if(!"refund".equals(text(data,"object"))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unexpected refund event");
            var operation=text(data.path("metadata"),"operation_id");
            var local=operation==null ? java.util.Optional.<org.demo.paymentservice.payment.PaymentRefund>empty() : refundRows.findByOperationId(operation);
            if(local.isEmpty()) {
                Long paymentId=externalRefunds.observe(text(data,"id"));
                if(paymentId==null) states.recordIgnoredStripeEvent(eventId,type);
                else states.recordCompletedStripeEvent(eventId,type,paymentId);
                return;
            }
            refunds.reconcile(local.get().getId());
            states.recordCompletedStripeEvent(eventId,type,local.get().getPaymentId());return;
        }

        boolean succeeded="checkout.session.completed".equals(type) || "checkout.session.async_payment_succeeded".equals(type);
        boolean expired="checkout.session.expired".equals(type);
        if(!succeeded && !expired) {
            states.recordIgnoredStripeEvent(eventId,type);
            return;
        }
        JsonNode session=event.path("data").path("object");
        if(!"checkout.session".equals(text(session,"object"))) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unexpected Stripe webhook object");
        String paymentStatus=text(session,"payment_status");
        if(succeeded && !"paid".equals(paymentStatus)) {
            states.recordIgnoredStripeEvent(eventId,type);
            return;
        }
        if(expired && (!"expired".equals(text(session,"status")) || "paid".equals(paymentStatus)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Stripe Checkout expiry does not match its payment state");
        String sessionId=text(session,"id");
        String currency=text(session,"currency");
        String intent=text(session,"payment_intent");
        Long orderId=longValue(session.path("metadata").get("order_id"));
        Long clientOrder=longValue(session.get("client_reference_id"));
        JsonNode minor=session.get("amount_total");
        if(sessionId==null || !sessionId.startsWith("cs_") || orderId==null || clientOrder==null || !orderId.equals(clientOrder)
                || currency==null || minor==null || !minor.isIntegralNumber() || minor.longValue()<1
                || (intent!=null && !intent.startsWith("pi_")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Stripe Checkout fields are incomplete");
        payments.event(eventId,type,mode.booleanValue(),sessionId,orderId,minor.longValue(),currency);
    }

    private static String text(JsonNode node,String field) {
        JsonNode value=node==null ? null : node.get(field);
        return value==null || !value.isString() ? null : value.asText();
    }
    private static Long longValue(JsonNode node) {
        if(node==null || node.isNull()) return null;
        try { return node.isIntegralNumber() ? node.longValue() : Long.valueOf(node.asText()); }
        catch(NumberFormatException malformed) { return null; }
    }
}
