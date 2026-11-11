package org.demo.paymentservice.payment;

import org.demo.paymentservice.outbox.*;
import org.demo.paymentservice.followup.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:a11payment;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.hikari.maximum-pool-size=3","spring.datasource.hikari.minimum-idle=0"})
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class CancellationFenceTest {
    @Autowired PaymentStateStore states;
    @Autowired PaymentRepository payments;
    @Autowired PaymentOrderGateRepository gates;
    @Autowired PaymentOutboxRepository events;
    @Autowired PaymentFollowUpRepository followUps;
    @BeforeEach void clear() { followUps.deleteAll();events.deleteAll();payments.deleteAll();gates.deleteAll(); }
    private Payment candidate(long id) { var p=new Payment(id,BigDecimal.TEN,"CARD");p.bindRequest("test-fingerprint");return p; }
    @Test void cancellationBeforeFirstPaymentPermanentlyFencesAllReplicas() {
        states.fenceCancellation(101L);states.fenceCancellation(101L);
        assertThatThrownBy(() -> states.claim(candidate(101))).hasMessageContaining("409");
        assertThat(payments.count()).isZero();
    }
    @Test void pendingExpiredAuthorizedSucceededAndLegacyFailuresCannotBeCancelled() {
        for(long id=1;id<=5;id++) {
            var p=candidate(id);
            if(id==2) p.claim("expired",Instant.now().minusSeconds(60));
            if(id==3) p.recordAuthorization("REAL-AUTH");
            if(id==4) p.markSucceeded("REAL-AUTH");
            if(id==5) { p.markFailed("Unknown historic failure");p.recordResult(); }
            payments.saveAndFlush(p);long orderId=id;
            assertThatThrownBy(() -> states.fenceCancellation(orderId)).hasMessageContaining("409");
        }
        assertThat(gates.count()).isZero();
    }
    @Test void onlyAnExplicitlyRecordedProviderDeclineAllowsCancellation() {
        var ticket=states.claim(candidate(11));ticket.payment().markDeclined("Rejected by provider");
        states.finish(ticket,null);states.fenceCancellation(11L);
        assertThat(gates.findById(11L).orElseThrow().isCancelled()).isTrue();
        assertThatThrownBy(() -> states.claim(candidate(11))).hasMessageContaining("409");
    }
    @Test void racingClaimAndCancellationCannotBothSucceed() throws Exception {
        // Persist the common lock first; first-insert races are covered by the native test.
        gates.saveAndFlush(new PaymentOrderGate(201L));
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var payment=pool.submit(() -> { start.await();try { states.claim(candidate(201));return 200; } catch(org.springframework.web.server.ResponseStatusException expected) {return 409;} });
            var cancel=pool.submit(() -> { start.await();try { states.fenceCancellation(201L);return 200; } catch(org.springframework.web.server.ResponseStatusException expected) {return 409;} });
            start.countDown();
            assertThat(java.util.List.of(payment.get(10,TimeUnit.SECONDS),cancel.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
    }
}
