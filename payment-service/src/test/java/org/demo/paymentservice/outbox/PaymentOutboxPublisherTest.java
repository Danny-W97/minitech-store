package org.demo.paymentservice.outbox;

import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import static org.mockito.Mockito.*;
class PaymentOutboxPublisherTest {
    private final PaymentOutboxStateStore states=mock(PaymentOutboxStateStore.class);
    private final PaymentEventPublisher sender=mock(PaymentEventPublisher.class);
    private final PaymentOutboxPublisher publisher=new PaymentOutboxPublisher(states,sender);
    @AfterEach void close() { publisher.close(); }
    @Test void publicationIsRecordedOnlyForTheClaimedTicket() {
        var ticket=new PaymentOutboxStateStore.Ticket(new PaymentOutboxEvent("PAYMENT_SUCCEEDED",11L,BigDecimal.TEN,"owner@example.test"),"token");
        when(states.claim(1L)).thenReturn(ticket);when(sender.publish(ticket.event())).thenReturn(true);
        publisher.publishOne(1L);verify(states).complete(ticket,true);
    }
    @Test void brokerFailureIsDurablyScheduledAndAnUnclaimedRecordIsNeverPublished() {
        var ticket=new PaymentOutboxStateStore.Ticket(new PaymentOutboxEvent("PAYMENT_FAILED",11L,BigDecimal.TEN,"owner@example.test"),"token");
        when(states.claim(1L)).thenReturn(ticket);when(sender.publish(ticket.event())).thenThrow(new IllegalStateException("Broker unavailable"));
        publisher.publishOne(1L);verify(states).complete(ticket,false);
        publisher.publishOne(2L);verify(sender,times(1)).publish(any());
    }
}
