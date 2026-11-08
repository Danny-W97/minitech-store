package org.demo.paymentservice.payment;

import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(name="payment.stripe.recovery-enabled",havingValue="true",matchIfMissing=true)
public class StripeRecoveryScheduler {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(StripeRecoveryScheduler.class);
    private final PaymentRepository payments;
    private final PaymentRefundStateStore refundStates;
    private final StripeCheckoutService checkouts;
    private final PaymentRefundService refunds;
    private final PaymentGateway gateway;
    private final org.demo.platformsecurity.BoundedJobs checkoutJobs=new org.demo.platformsecurity.BoundedJobs("stripe-checkout",2,40,45000);
    private final org.demo.platformsecurity.BoundedJobs refundJobs=new org.demo.platformsecurity.BoundedJobs("stripe-refund",2,40,45000);
    public StripeRecoveryScheduler(PaymentRepository payments,PaymentRefundStateStore refundStates,StripeCheckoutService checkouts,PaymentRefundService refunds,PaymentGateway gateway) {
        this.payments=payments;this.refundStates=refundStates;this.checkouts=checkouts;this.refunds=refunds;this.gateway=gateway;
    }
    @Scheduled(fixedDelayString="${payment.stripe.recovery-delay-ms:5000}")
    public void recover() {
        if(!(gateway instanceof StripePaymentGateway)) return;
        for(Long id:refundStates.due()) {
            refundJobs.submit(id,() -> { try { refunds.process(id); } catch(RuntimeException failure) { log.warn("Stripe refund {} recovery deferred",id); } });
        }
        for(Long id:payments.stripeDue(java.time.Instant.now(),org.springframework.data.domain.PageRequest.of(0,20))) {
            checkoutJobs.submit(id,() -> { try { checkouts.reconcile(id); } catch(RuntimeException failure) { log.warn("Stripe payment {} recovery deferred",id); } });
        }
    }
    @jakarta.annotation.PreDestroy void close() { checkoutJobs.close();refundJobs.close(); }
}
